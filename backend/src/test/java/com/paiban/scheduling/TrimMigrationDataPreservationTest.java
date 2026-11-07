package com.paiban.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 票 16 的迁移数据保全验收：V55 只 DROP 17 个旧列、3 张旧表、4 个索引与 2 个约束，
 * 不 DELETE 任何数据行，因此升级后的验收口径是「行数与主体不丢」，而不是「被删的旧列还在」。
 * 本测试在 V54 结构上写入旧数据，再迁到 V55，然后用 JDBC 直查行数与主体值。
 *
 * <p>这里停在 V55 是刻意的：票 03 的 V56 会按产品所有者确认清空非系统规则、
 * 工作区与最近删除，这些保全断言只描述 V55 的行为，不能跨到 V56 之后。
 *
 * <p>迁移在独立 UUID 的 H2 内存库上跑全量（{@code MODE=PostgreSQL}），不启动 Spring、
 * 不连 Docker，所以本地默认就跑。真实 PostgreSQL 的<b>迁移可执行性</b>由 CI
 * {@code -Dtestcontainers=true} 的 {@code PostgresContainerIT} 覆盖（空库跑全量迁移并断言表存在）；
 * 本测试的旧数据保全断言只跑在 H2(PostgreSQL 模式) 上。
 */
class TrimMigrationDataPreservationTest {

    private String url;

    @BeforeEach
    void freshDatabase() {
        // 每个测试一个独立内存库：Flyway 在各自的库上完整跑一次迁移。
        url = "jdbc:h2:mem:trim_migration_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
    }

    @Test
    void v55KeepsPublishedTimetableSubstitutionRuleAndImportRows() throws Exception {
        migrateTo("54");
        seedLegacySchedulingData();
        migrateTo("55");

        try (Connection connection = DriverManager.getConnection(url, "sa", "");
                Statement statement = connection.createStatement()) {
            // 票 16 第 1 项：已发布课表主体不丢。两行都还在，且按班级/星期/节次仍能回查到课程与教师。
            assertThat(count(connection, "timetable_entry")).isEqualTo(2);
            try (ResultSet row = statement.executeQuery(
                    "select course_id, teacher_id, period_no from timetable_entry"
                            + " where admin_class_id = 1 and day_of_week = 1 and period_id = 1")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getLong("course_id")).isEqualTo(1L);
                assertThat(row.getLong("teacher_id")).isEqualTo(1L);
                assertThat(row.getInt("period_no")).isEqualTo(1);
            }
            try (ResultSet row = statement.executeQuery(
                    "select course_id, teacher_id, period_no from timetable_entry"
                            + " where admin_class_id = 1 and day_of_week = 1 and period_id = 2")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getLong("course_id")).isEqualTo(1L);
                assertThat(row.getLong("teacher_id")).isEqualTo(1L);
                assertThat(row.getInt("period_no")).isEqualTo(2);
            }

            // 票 16 第 2 项：调代记录不丢。申请与生效行都还在，主体状态与调整后节次保持原值。
            assertThat(count(connection, "substitution_request")).isEqualTo(2);
            assertThat(count(connection, "substitution")).isEqualTo(1);
            try (ResultSet row = statement.executeQuery(
                    "select status, type from substitution_request where id = 2")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString("status")).isEqualTo("EFFECTIVE");
                assertThat(row.getString("type")).isEqualTo("COVER");
            }
            try (ResultSet row = statement.executeQuery(
                    "select status, new_period_id from substitution where id = 1")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString("status")).isEqualTo("EFFECTIVE");
                assertThat(row.getLong("new_period_id")).isEqualTo(2L);
            }

            // 票 16 第 3 项：规则不丢。配置与参数行都还在；已退役的 ROOM_CONFLICT 行按 Q6「留行可查」保留。
            assertThat(count(connection, "constraint_config")).isEqualTo(2);
            assertThat(count(connection, "constraint_entry")).isEqualTo(2);
            try (ResultSet row = statement.executeQuery(
                    "select enabled, hard, weight from constraint_config where type = 'ROOM_CONFLICT'")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getBoolean("enabled")).isTrue();
                assertThat(row.getBoolean("hard")).isFalse();
                assertThat(row.getInt("weight")).isEqualTo(60);
            }
            try (ResultSet row = statement.executeQuery(
                    "select params_json from constraint_entry where type = 'ROOM_CONFLICT'")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString("params_json")).isEqualTo("{\"roomId\":5}");
            }

            // 票 16 第 4 项：导入会话不丢。整本批次数据仍在同一行的 batches_json 里。
            assertThat(count(connection, "ai_import_log")).isEqualTo(1);
            try (ResultSet row = statement.executeQuery(
                    "select status, batches_json from ai_import_log where id = 1")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString("status")).isEqualTo("COMPLETED");
                assertThat(row.getString("batches_json")).isEqualTo("{\"batches\":[{\"index\":0,\"rows\":2}]}");
            }
        }
    }

    @Test
    void v55KeepsRedLineTablesAndTheirRows() throws Exception {
        migrateTo("54");
        seedRedLineData();
        migrateTo("55");

        try (Connection connection = DriverManager.getConnection(url, "sa", "");
                Statement statement = connection.createStatement()) {
            // 票 15 明确保留的 7 张表在升级后仍在 public schema 里，且已写入的行一条没少。
            for (String table : List.of("notification", "notification_log", "ai_call_log", "ai_import_log",
                    "recent_deletion", "import_recipe", "timetable_publish")) {
                assertThat(tableExists(connection, table)).as(table + " 仍在 public schema").isTrue();
                assertThat(count(connection, table)).as(table + " 的行不丢").isEqualTo(1);
            }

            // 主体值也保持原样，而不是只剩空壳行。
            assertThat(scalar(connection, "select title from notification where id = 1"))
                    .isEqualTo("调代通知");
            assertThat(scalar(connection, "select channel from notification_log where id = 1"))
                    .isEqualTo("dingtalk");
            assertThat(scalar(connection, "select task_type from ai_call_log where id = 1"))
                    .isEqualTo("IMPORT");
            assertThat(scalar(connection, "select batches_json from ai_import_log where id = 1"))
                    .isEqualTo("{\"batches\":[{\"index\":0,\"rows\":2}]}");
            assertThat(scalar(connection, "select batch_id from recent_deletion where id = 1"))
                    .isEqualTo("batch-1");
            assertThat(scalar(connection, "select name from import_recipe where id = 1"))
                    .isEqualTo("配方A");
            assertThat(scalar(connection, "select status from timetable_publish where id = 1"))
                    .isEqualTo("ACTIVE");

            // Q5：开课截止是直接生效的时间护栏，V55 不删这一列。
            assertThat(hasColumn(connection, "substitution_preview", "deadline_at")).isTrue();
            // 锁本身保留：只删了旧 room_id，locked 及其索引依据仍在。
            assertThat(hasColumn(connection, "draft_lesson", "locked")).isTrue();
            // 置换方案只删 room_id，其余业务列（目标节次/日期/顺序）仍在。
            assertThat(hasColumn(connection, "substitution_plan_move", "to_period_id")).isTrue();
            assertThat(hasColumn(connection, "substitution_plan_move", "from_date")).isTrue();
            assertThat(hasColumn(connection, "substitution_plan_move", "move_order")).isTrue();
        }
    }

    @Test
    void v55DropsOnlyLegacyColumnsAndTables() throws Exception {
        migrateTo("54");
        migrateTo("55");

        String[][] droppedColumns = {
                {"course", "required_room_type"},
                {"course", "require_outdoor"},
                {"draft_lesson", "room_id"},
                {"timetable_entry", "room_id"},
                {"substitution", "new_room_id"},
                {"substitution_plan_move", "room_id"},
                {"period", "section"},
                {"teacher", "feishu_user_id"},
                {"teacher", "feishu_open_id"},
                {"substitution_request", "approval_mode"},
                {"substitution_request", "approver_id"},
                {"substitution_request", "approved_at"},
                {"substitution_request", "approval_comment"},
                {"substitution_request", "revises_request_id"},
                {"substitution_request", "revision_kind"},
                {"substitution_preview", "revises_request_id"},
                {"substitution_preview", "revision_kind"},
        };

        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            // 票 15 的 3 张旧表（教室、历史转换批次与逐条留痕）已从 schema 消失。
            for (String table : List.of("room", "substitution_conversion", "substitution_conversion_item")) {
                assertThat(tableExists(connection, table)).as(table + " 已被删除").isFalse();
            }

            // 票 15 的 17 个旧列逐一确认不在 information_schema 里。
            for (String[] pair : droppedColumns) {
                assertThat(hasColumn(connection, pair[0], pair[1]))
                        .as(pair[0] + "." + pair[1] + " 已被删除").isFalse();
            }

            // 红线反证：保留的 7 张表与 Q5/锁相关列没有被误删。
            for (String table : List.of("notification", "notification_log", "ai_call_log", "ai_import_log",
                    "recent_deletion", "import_recipe", "timetable_publish")) {
                assertThat(tableExists(connection, table)).as(table + " 必须保留").isTrue();
            }
            assertThat(hasColumn(connection, "substitution_preview", "deadline_at")).isTrue();
            assertThat(hasColumn(connection, "draft_lesson", "locked")).isTrue();

            // 迁移本身确实执行成功：历史表里有成功的 V55 记录。
            assertThat(scalar(connection,
                    "select count(*) from flyway_schema_history where version = '55' and success = true"))
                    .isEqualTo("1");
        }
    }

    /**
     * 在 V54 结构上写入已发布课表、调代记录、规则与导入会话。显式 id 让外键顺序一目了然；
     * 修订链只让第二笔申请指向第一笔，避免 V55 删自引用外键时遇到非法引用。
     */
    private void seedLegacySchedulingData() throws Exception {
        exec(
                "insert into semester (id, name, start_date, end_date, is_current, created_at)"
                        + " values (1, '迁移学期', DATE '2025-09-01', DATE '2026-01-20', true, CURRENT_TIMESTAMP)",
                "insert into period (id, semester_id, day_of_week, period_no, label, section, start_time, end_time)"
                        + " values (1, 1, 1, 1, '第1节', 'MORNING', TIME '08:00:00', TIME '08:40:00')",
                "insert into period (id, semester_id, day_of_week, period_no, label, section, start_time, end_time)"
                        + " values (2, 1, 1, 2, '第2节', 'MORNING', TIME '08:50:00', TIME '09:30:00')",
                "insert into teacher (id, name, created_at) values (1, '张老师', CURRENT_TIMESTAMP)",
                "insert into course (id, name, created_at) values (1, '数学', CURRENT_TIMESTAMP)",
                "insert into grade (id, semester_id, name) values (1, 1, '高一')",
                "insert into admin_class (id, semester_id, grade_id, name, head_teacher_id)"
                        + " values (1, 1, 1, '高一1班', 1)",
                "insert into timetable_draft (id, semester_id, name, created_at)"
                        + " values (1, 1, '基线草稿', CURRENT_TIMESTAMP)",
                "insert into timetable_entry (id, semester_id, draft_id, admin_class_id, course_id,"
                        + " teacher_id, room_id, period_id, day_of_week, period_no, created_at)"
                        + " values (1, 1, 1, 1, 1, 1, 101, 1, 1, 1, CURRENT_TIMESTAMP)",
                "insert into timetable_entry (id, semester_id, draft_id, admin_class_id, course_id,"
                        + " teacher_id, room_id, period_id, day_of_week, period_no, created_at)"
                        + " values (2, 1, 1, 1, 1, 1, 102, 2, 1, 2, CURRENT_TIMESTAMP)",
                "insert into substitution_request (id, semester_id, entry_id, admin_class_id, course_id,"
                        + " original_teacher_id, requester_teacher_id, effective_date, period_id, day_of_week,"
                        + " period_no, reason, status, approval_mode, created_at, updated_at)"
                        + " values (1, 1, 1, 1, 1, 1, 1, DATE '2025-09-08', 1, 1, 1, '教师病假',"
                        + " 'EFFECTIVE', 'AUTO', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                "insert into substitution_request (id, semester_id, entry_id, admin_class_id, course_id,"
                        + " original_teacher_id, requester_teacher_id, effective_date, period_id, day_of_week,"
                        + " period_no, status, approval_mode, approver_id, approved_at, approval_comment,"
                        + " revises_request_id, revision_kind, created_at, updated_at)"
                        + " values (2, 1, 1, 1, 1, 1, 1, DATE '2025-09-08', 1, 1, 1, 'EFFECTIVE', 'MANUAL',"
                        + " 1, CURRENT_TIMESTAMP, '已批准', 1, 'SUBSTITUTE_CHANGE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                "insert into substitution (id, semester_id, request_id, entry_id, effective_date,"
                        + " original_teacher_id, substitute_teacher_id, new_room_id, new_period_id, status,"
                        + " created_at, lesson_date)"
                        + " values (1, 1, 2, 1, DATE '2025-09-08', 1, 1, 101, 2, 'EFFECTIVE',"
                        + " CURRENT_TIMESTAMP, DATE '2025-09-08')",
                "insert into constraint_config (id, semester_id, type, params_json, weight, hard, enabled)"
                        + " values (1, 1, 'ROOM_CONFLICT', '{\"maxPerDay\":1}', 60, false, true)",
                "insert into constraint_config (id, semester_id, type, params_json, weight, hard, enabled)"
                        + " values (2, 1, 'TEACHER_UNAVAILABLE',"
                        + " '{\"teacherId\":1,\"dayOfWeek\":1,\"periodNo\":1}', 100, true, true)",
                "insert into constraint_entry (id, semester_id, type, params_json, created_at)"
                        + " values (1, 1, 'ROOM_CONFLICT', '{\"roomId\":5}', CURRENT_TIMESTAMP)",
                "insert into constraint_entry (id, semester_id, type, params_json, created_at)"
                        + " values (2, 1, 'TEACHER_UNAVAILABLE',"
                        + " '{\"teacherId\":1,\"dayOfWeek\":1,\"periodNo\":1}', CURRENT_TIMESTAMP)",
                "insert into ai_import_log (id, user_id, username, import_type, target_type, file_name,"
                        + " status, row_count, imported_count, created_at, updated_at, batches_json)"
                        + " values (1, 1, 'admin', 'TIMETABLE', 'TIMETABLE', '课表.xlsx', 'COMPLETED',"
                        + " 2, 2, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '{\"batches\":[{\"index\":0,\"rows\":2}]}')");
    }

    /**
     * 往票 15 明确保留的 7 张表各写一行，升级后按行数与主体值回查。
     */
    private void seedRedLineData() throws Exception {
        exec(
                "insert into semester (id, name, start_date, end_date, is_current, created_at)"
                        + " values (1, '红线学期', DATE '2025-09-01', DATE '2026-01-20', true, CURRENT_TIMESTAMP)",
                "insert into timetable_draft (id, semester_id, name, created_at)"
                        + " values (1, 1, '发布草稿', CURRENT_TIMESTAMP)",
                "insert into notification (id, recipient_user_id, template_code, variables_json, title,"
                        + " content, is_read, created_at)"
                        + " values (1, 1, 'SUBSTITUTION_CREATED', '{\"a\":1}', '调代通知', '内容', false,"
                        + " CURRENT_TIMESTAMP)",
                "insert into notification_log (id, channel, recipient, template_code, content, success,"
                        + " created_at) values (1, 'dingtalk', '13800000000', 'SUBSTITUTION_CREATED',"
                        + " '内容', true, CURRENT_TIMESTAMP)",
                "insert into ai_call_log (id, task_type, provider_name, success, created_at)"
                        + " values (1, 'IMPORT', '测试供应商', true, CURRENT_TIMESTAMP)",
                "insert into ai_import_log (id, user_id, username, import_type, target_type, file_name,"
                        + " status, row_count, imported_count, created_at, updated_at, batches_json)"
                        + " values (1, 1, 'admin', 'TIMETABLE', 'TIMETABLE', '课表.xlsx', 'COMPLETED',"
                        + " 2, 2, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '{\"batches\":[{\"index\":0,\"rows\":2}]}')",
                "insert into recent_deletion (id, batch_id, entity_type, entity_id, entity_label,"
                        + " primary_flag, deleted_by, deleted_at, expires_at)"
                        + " values (1, 'batch-1', 'TEACHER', 1, '张老师', true, 'admin',"
                        + " CURRENT_TIMESTAMP, DATEADD('DAY', 7, CURRENT_TIMESTAMP))",
                "insert into import_recipe (id, name, layout, fingerprint_json, columns_json, options_json,"
                        + " created_by, created_at)"
                        + " values (1, '配方A', 'TIMETABLE', '{}', '[]', '{}', 'admin', CURRENT_TIMESTAMP)",
                "insert into timetable_publish (id, semester_id, draft_id, published_by, published_at)"
                        + " values (1, 1, 1, 'admin', CURRENT_TIMESTAMP)");
    }

    private void migrateTo(String version) {
        Flyway flyway = Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .target(version == null ? MigrationVersion.LATEST : MigrationVersion.fromVersion(version))
                .load();
        flyway.migrate();
    }

    private void exec(String... sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
                Statement statement = connection.createStatement()) {
            for (String one : sql) {
                statement.executeUpdate(one);
            }
        }
    }

    private long count(Connection connection, String table) throws Exception {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("select count(*) from " + table)) {
            result.next();
            return result.getLong(1);
        }
    }

    private String scalar(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            return result.next() ? result.getString(1) : null;
        }
    }

    private boolean tableExists(Connection connection, String table) throws Exception {
        return "1".equals(scalar(connection,
                "select count(*) from information_schema.tables where table_schema = 'public'"
                        + " and table_name = '" + table + "'"));
    }

    private boolean hasColumn(Connection connection, String table, String column) throws Exception {
        return "1".equals(scalar(connection,
                "select count(*) from information_schema.columns where table_schema = 'public'"
                        + " and table_name = '" + table + "' and column_name = '" + column + "'"));
    }
}
