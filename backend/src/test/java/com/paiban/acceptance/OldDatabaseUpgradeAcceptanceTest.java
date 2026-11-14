package com.paiban.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 票 08 的存量升级验收：一台停在 V59 的旧库，带满业务数据，一次升到 classpath 上最新的迁移。
 *
 * <p>与 {@code RoleUpgradeDataPreservationTest}（只到 V60）和 {@code LoginTypeMigrationTest}（只到
 * V62）不同，这里一次跑完当前 classpath 的<b>全部</b>迁移，并用「V59 时抓到的旧列名 + 按 id 的
 * 逐列取值」做投影对照：升级后按同一批旧列重查，每一行每一个旧值都必须逐字相等。新列不进入该
 * 投影，它们的默认值与约束在同一测试类的独立用例里单独断言，避免「多出来的列恰好相等」掩盖
 * 旧值改写，也避免「验证约束用的插入」污染保全投影。
 *
 * <p>{@code role} / {@code permission} / {@code user_role} / {@code role_permission} 是 V60 有意
 * 调整的授权数据，不在这张「全部旧列原样保留」的名单里，单独用例断两个旧管理员转超级管理员、
 * 班主任转老师、自定义角色与其授权原样保留。
 *
 * <p><b>边界</b>：本类跑在 H2 的 PostgreSQL 兼容模式上，只证明迁移 SQL 在旧数据上可执行、
 * 旧值不被改写；它<b>不证明</b>真实 PostgreSQL 上的行为。真实 PG 的可执行性由 CI 的
 * {@code PostgresContainerIT} 覆盖。
 */
class OldDatabaseUpgradeAcceptanceTest {

    /** V59 旧库里被逐值对照的业务表（授权表见类注释，不在其中）。 */
    private static final List<String> PRESERVED_TABLES = List.of(
            "app_user",
            "teacher",
            "semester",
            "period",
            "grade",
            "admin_class",
            "teaching_class",
            "course",
            "assignment",
            "timetable_draft",
            "draft_lesson",
            "timetable_entry",
            "timetable_publish",
            "scheduling_job",
            "substitution_request",
            "substitution_history",
            "substitution",
            "constraint_config",
            "constraint_entry",
            "ai_import_log",
            "audit_log");

    /**
     * 合成凭据：V62 只给账号加登录类型，绝不改写哈希。使用明显的合成字面量，断言「逐字保留」。
     * （原任务说明里的密码占位符不是可用值，这里用等长的合成串代替，语义是同一个：旧哈希不许变。）
     */
    private static final String ADMIN_HASH = "$2a$10$v59-upgrade-legacy-hash-admin-kept-verbatim";
    private static final String SECOND_ADMIN_HASH = "$2a$10$v59-upgrade-legacy-hash-second-admin";
    private static final String HEAD_TEACHER_HASH = "$2a$10$v59-upgrade-legacy-hash-head-teacher";
    private static final String TEACHER_HASH = "$2a$10$v59-upgrade-legacy-hash-teacher";
    private static final String DISABLED_HASH = "$2a$10$v59-upgrade-legacy-hash-disabled";
    private static final String AUDITOR_HASH = "$2a$10$v59-upgrade-legacy-hash-auditor";

    private String url;

    @BeforeEach
    void freshDatabase() {
        url = "jdbc:h2:mem:old_database_upgrade_"
                + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
    }

    @Test
    void everyOldColumnValueSurvivesTheWholeMigrationChain() throws Exception {
        migrateTo("59");
        Map<String, List<String>> oldColumns;
        Map<String, Map<Long, List<String>>> before;
        try (Connection connection = open(); Statement sql = connection.createStatement()) {
            seedOldSchool(sql);
            oldColumns = new LinkedHashMap<>();
            for (String table : PRESERVED_TABLES) {
                oldColumns.put(table, columnsOf(connection, table));
            }
            before = readAll(connection, oldColumns);
        }

        String current = migrateToNewestOnClasspath();

        try (Connection connection = open(); Statement sql = connection.createStatement()) {
            assertThat(current)
                    .as("必须升到 classpath 上最新的迁移，而不是某个固定版本快照")
                    .isEqualTo(newestMigrationOnClasspath());

            for (String table : PRESERVED_TABLES) {
                assertThat(columnsOf(connection, table))
                        .as("%s 的 V59 旧列必须都还在（新列只能追加）", table)
                        .containsAll(oldColumns.get(table));
                Map<Long, List<String>> expected = before.get(table);
                assertThat(expected)
                        .as("%s 在 V59 种下的数据不能是空的，否则这条对照等于没断", table)
                        .isNotEmpty();
                assertThat(readRows(connection, table, oldColumns.get(table)))
                        .as("%s：按 id 的每一行、每一个旧列值升级后都不得改变", table)
                        .isEqualTo(expected);
            }

            // 投影相等之外，再按业务语义点名几处最容易「悄悄变好」的旧值。
            assertThat(scalar(sql, "select password_hash from app_user where username='admin'"))
                    .isEqualTo(ADMIN_HASH);
            assertThat(scalar(sql, "select enabled from app_user where username='disabled_teacher'"))
                    .isEqualTo("FALSE");
            assertThat(scalar(sql, "select cast(teacher_id as varchar) from app_user"
                    + " where username='old_head'")).isEqualTo("2");
            assertThat(scalar(sql, "select cast(count(*) as varchar) from substitution_history"))
                    .isEqualTo("2");
            assertThat(scalar(sql, "select reason from substitution_request where id=1"))
                    .isEqualTo("外出培训");
            assertThat(scalar(sql, "select status from timetable_draft where id=2"))
                    .isEqualTo("PUBLISHED");

            // 自检：证明上面的投影对照确实在读库（不是空集合等于空集合）——全部断言之后再改一个
            // 旧值，同一投影必须立刻看出差异。这一步只在本用例末尾做，不进入任何保全投影。
            execute(connection, "update app_user set display_name = '改名后' where id = 6");
            assertThat(readRows(connection, "app_user", oldColumns.get("app_user")))
                    .as("投影对照的灵敏度自检：旧值被改写后必须能报出差异")
                    .isNotEqualTo(before.get("app_user"));
        }
    }

    @Test
    void v60TurnsOldAuthorityIntoTheThreePresetRolesAndKeepsCustomRoles() throws Exception {
        migrateTo("59");
        seedOldSchool();
        migrateToNewestOnClasspath();

        try (Connection connection = open(); Statement sql = connection.createStatement()) {
            assertThat(roleCodesOf(sql, "admin"))
                    .as("旧 admin 持有者升级后只有超级管理员")
                    .containsExactly("super_admin");
            assertThat(roleCodesOf(sql, "second_old_admin"))
                    .as("第二个旧 admin 不能漏掉：V60 按持有关系迁移，不按用户名")
                    .containsExactly("super_admin");
            assertThat(roleCodesOf(sql, "old_head"))
                    .as("旧班主任转老师")
                    .containsExactly("teacher");
            assertThat(roleCodesOf(sql, "old_teacher")).containsExactly("teacher");
            assertThat(roleCodesOf(sql, "disabled_teacher")).containsExactly("teacher");
            assertThat(roleCodesOf(sql, "legacy_auditor"))
                    .as("自定义角色与其持有关系不在 V60 的改动范围内")
                    .containsExactly("audit_jw");

            assertThat(strings(sql, "select code from role order by code"))
                    .as("班主任角色下线，自定义角色保留")
                    .containsExactly("admin", "audit_jw", "super_admin", "teacher");
            assertThat(strings(sql, "select name from role where code='admin'"))
                    .isEqualTo(List.of("教务管理员"));
            assertThat(strings(sql, "select name from role where code='super_admin'"))
                    .isEqualTo(List.of("超级管理员"));
            assertThat(strings(sql, "select name from role where code='teacher'"))
                    .isEqualTo(List.of("老师"));

            assertThat(permissionsOf(sql, "admin"))
                    .as("教务管理员保留旧授权，但让出用户管理、集成配置、账号审计")
                    .containsExactlyInAnyOrder("timetable:view", "timetable:edit", "semester:manage");
            assertThat(permissionsOf(sql, "teacher"))
                    .as("老师新增全校只读，本班范围权限点不再分配")
                    .containsExactlyInAnyOrder("timetable:view", "timetable:edit");
            assertThat(permissionsOf(sql, "super_admin"))
                    .as("超级管理员持有权限目录里的全部权限点，但随班主任下线退休的本班范围权限不再分配")
                    .containsExactlyInAnyOrder("user:manage", "integration:manage",
                            "audit:view:account", "timetable:view", "timetable:edit",
                            "semester:manage");
            assertThat(permissionsOf(sql, "audit_jw"))
                    .as("自定义角色的授权原样保留，包括预置角色不再分配的本班权限点")
                    .containsExactlyInAnyOrder("audit:view:account", "timetable:view:class");
            assertThat(strings(sql, "select code from permission order by code"))
                    .as("退休权限点仍留在权限目录里（历史行保留），只是不再分配给预置角色")
                    .contains("timetable:view:class", "substitution:view:class");
            assertThat(strings(sql, "select distinct p.code from role_permission rp"
                    + " join role r on r.id = rp.role_id join permission p on p.id = rp.permission_id"
                    + " where r.code in ('super_admin','admin','teacher')"
                    + " and p.code in ('timetable:view:class','substitution:view:class')"))
                    .as("三个预置角色都不再持有退休的本班范围权限点（超级管理员也不例外）")
                    .isEmpty();

            assertThat(strings(sql, "select cast(count(*) as varchar) from user_role ur"
                    + " join role r on r.id = ur.role_id where r.code = 'head_teacher'"))
                    .isEqualTo(List.of("0"));
            assertThat(strings(sql, "select cast(count(*) as varchar) from user_role ur"
                    + " join role r on r.id = ur.role_id where r.code = 'admin'"))
                    .as("旧 admin 角色的持有关系已全部迁走，不留空挂")
                    .isEqualTo(List.of("0"));
        }
    }

    @Test
    void v61ClassifiesLegacyAccountEventsAndLeavesSnapshotColumnsEmpty() throws Exception {
        migrateTo("59");
        seedOldSchool();
        migrateToNewestOnClasspath();

        try (Connection connection = open(); Statement sql = connection.createStatement()) {
            assertThat(strings(sql, "select category from audit_log order by id"))
                    .as("旧 APP_USER 与 users/IMPORT 事件归入账号审计，其他事件不分类")
                    .containsExactly("ACCOUNT", "ACCOUNT", null);
            assertThat(strings(sql, "select action from audit_log order by id"))
                    .as("旧动作码原样保留")
                    .containsExactly("DISABLE_USER", "IMPORT", "IMPORT");
            assertThat(strings(sql, "select target_user_id from audit_log order by id"))
                    .as("旧记录没有目标账号 ID，升级不许补造")
                    .containsOnlyNulls();
            assertThat(strings(sql, "select before_json from audit_log order by id"))
                    .containsOnlyNulls();
            assertThat(strings(sql, "select after_json from audit_log order by id"))
                    .containsOnlyNulls();
            assertThat(indexesOf(connection, "audit_log"))
                    .as("账号审计页的取数索引随迁移建好")
                    .contains("idx_audit_category_created_id");
        }
    }

    @Test
    void v62MovesEveryLegacyAccountToPasswordAndEnforcesTheHashPairing() throws Exception {
        migrateTo("59");
        seedOldSchool();
        migrateToNewestOnClasspath();

        try (Connection connection = open(); Statement sql = connection.createStatement()) {
            assertThat(strings(sql, "select login_type from app_user order by id"))
                    .as("老账号一律 PASSWORD，逐行都不许漏")
                    .containsExactly("PASSWORD", "PASSWORD", "PASSWORD", "PASSWORD", "PASSWORD",
                            "PASSWORD");
            assertThat(strings(sql, "select password_hash from app_user order by id"))
                    .as("V62 只加类型，不改写任何旧凭据")
                    .containsExactly(ADMIN_HASH, SECOND_ADMIN_HASH, HEAD_TEACHER_HASH, TEACHER_HASH,
                            DISABLED_HASH, AUDITOR_HASH);

            execute(connection, "insert into app_user (id, username, password_hash, display_name,"
                    + " created_at) values (100, 'raw_insert', '" + TEACHER_HASH + "', '旧脚本',"
                    + " CURRENT_TIMESTAMP)");
            assertThat(scalar(sql, "select login_type from app_user where username='raw_insert'"))
                    .as("不带 login_type 的旧脚本插入回落到 PASSWORD 默认值")
                    .isEqualTo("PASSWORD");

            assertThatThrownBy(() -> execute(connection, "insert into app_user (id, username,"
                    + " password_hash, display_name, login_type, created_at) values (101, 'null_type',"
                    + " '" + TEACHER_HASH + "', '空类型', null, CURRENT_TIMESTAMP)"))
                    .as("login_type 是 NOT NULL")
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> execute(connection, "insert into app_user (id, username,"
                    + " password_hash, display_name, login_type, created_at) values (102, 'bad_type',"
                    + " '" + TEACHER_HASH + "', '错误类型', 'LDAP', CURRENT_TIMESTAMP)"))
                    .as("CHECK 只允许 PASSWORD / DINGTALK")
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> execute(connection, "insert into app_user (id, username,"
                    + " password_hash, display_name, login_type, created_at) values (103, 'ding_hash',"
                    + " '" + TEACHER_HASH + "', '钉钉带哈希', 'DINGTALK', CURRENT_TIMESTAMP)"))
                    .as("CHECK 拒绝钉钉账号带哈希")
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> execute(connection, "insert into app_user (id, username,"
                    + " password_hash, display_name, login_type, created_at) values (104, 'pwd_null',"
                    + " null, '密码无哈希', 'PASSWORD', CURRENT_TIMESTAMP)"))
                    .as("CHECK 拒绝密码账号没有哈希")
                    .isInstanceOf(SQLException.class);

            execute(connection, "insert into app_user (id, username, password_hash, display_name,"
                    + " login_type, created_at) values (105, 'ding_ok', null, '钉钉老师', 'DINGTALK',"
                    + " CURRENT_TIMESTAMP)");
            assertThat(scalar(sql, "select login_type from app_user where username='ding_ok'"))
                    .as("旧 NOT NULL 已放开，钉钉账号可以没有密码")
                    .isEqualTo("DINGTALK");
        }
    }

    @Test
    void v63AddsApprovalColumnsWithoutInventingLegacyApprovals() throws Exception {
        migrateTo("59");
        seedOldSchool();
        migrateToNewestOnClasspath();

        try (Connection connection = open(); Statement sql = connection.createStatement()) {
            assertThat(strings(sql, "select status from substitution_request order by id"))
                    .isEqualTo(List.of("EFFECTIVE", "CANCELLED"));
            assertThat(strings(sql, "select approver_user_id from substitution_request order by id"))
                    .as("旧申请没有审批人，升级不能猜造")
                    .containsOnlyNulls();
            assertThat(strings(sql, "select approved_at from substitution_request order by id"))
                    .containsOnlyNulls();
            assertThat(strings(sql, "select approval_comment from substitution_request order by id"))
                    .containsOnlyNulls();
            assertThat(indexesOf(connection, "substitution_request"))
                    .as("待审批列表的 学期+状态 索引随迁移建好")
                    .contains("idx_substitution_request_semester_status");

            assertThatThrownBy(() -> execute(connection, "update substitution_request"
                    + " set approver_user_id = 987654321 where id = 1"))
                    .as("新的 approver_user_id 必须是真实账号（外键兜底）")
                    .isInstanceOf(SQLException.class);
            execute(connection, "update substitution_request set approver_user_id = 1,"
                    + " approved_at = TIMESTAMP '2030-09-03 10:00:00',"
                    + " approval_comment = '同意代课' where id = 1");
            assertThat(scalar(sql, "select approval_comment from substitution_request where id = 1"))
                    .isEqualTo("同意代课");
        }
    }

    // ---------------------------------------------------------------- 迁移与读数

    private String migrateTo(String version) {
        Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion(version))
                .load()
                .migrate();
        return version;
    }

    private String migrateToNewestOnClasspath() {
        Flyway flyway = Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .load();
        flyway.migrate();
        return String.valueOf(flyway.info().current().getVersion());
    }

    private static String newestMigrationOnClasspath() throws IOException, URISyntaxException {
        Path directory = Path.of(OldDatabaseUpgradeAcceptanceTest.class.getClassLoader()
                .getResource("db/migration").toURI());
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith("V") && name.endsWith(".sql"))
                    .map(name -> name.substring(1, name.indexOf("__")))
                    .max(Comparator.comparingInt(Integer::parseInt))
                    .orElseThrow(() -> new IllegalStateException("classpath 上找不到迁移脚本"));
        }
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection(url, "sa", "");
    }

    private static List<String> columnsOf(Connection connection, String table) throws SQLException {
        List<String> columns = new ArrayList<>();
        try (Statement sql = connection.createStatement();
                ResultSet result = sql.executeQuery("select * from " + table + " where 1 = 0")) {
            ResultSetMetaData meta = result.getMetaData();
            for (int column = 1; column <= meta.getColumnCount(); column++) {
                columns.add(meta.getColumnName(column).toLowerCase(Locale.ROOT));
            }
        }
        return columns;
    }

    private static Map<String, Map<Long, List<String>>> readAll(
            Connection connection, Map<String, List<String>> tables) throws SQLException {
        Map<String, Map<Long, List<String>>> rows = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> table : tables.entrySet()) {
            rows.put(table.getKey(), readRows(connection, table.getKey(), table.getValue()));
        }
        return rows;
    }

    private static Map<Long, List<String>> readRows(
            Connection connection, String table, List<String> columns) throws SQLException {
        int idPosition = columns.indexOf("id");
        Map<Long, List<String>> rows = new LinkedHashMap<>();
        String query = "select " + String.join(", ", columns) + " from " + table + " order by id";
        try (Statement sql = connection.createStatement();
                ResultSet result = sql.executeQuery(query)) {
            while (result.next()) {
                List<String> values = new ArrayList<>();
                for (int column = 1; column <= columns.size(); column++) {
                    values.add(result.getString(column));
                }
                rows.put(result.getLong(idPosition + 1), values);
            }
        }
        return rows;
    }

    private static List<String> indexesOf(Connection connection, String table)
            throws SQLException {
        List<String> names = new ArrayList<>();
        try (Statement sql = connection.createStatement();
                ResultSet result = sql.executeQuery("select index_name from information_schema.indexes"
                        + " where lower(table_name) = '" + table.toLowerCase(Locale.ROOT) + "'")) {
            while (result.next()) {
                names.add(result.getString(1).toLowerCase(Locale.ROOT));
            }
        }
        return names;
    }

    private static List<String> roleCodesOf(Statement sql, String username) throws SQLException {
        return strings(sql, "select r.code from user_role ur join role r on r.id = ur.role_id"
                + " join app_user u on u.id = ur.user_id where u.username = '" + username + "'");
    }

    private static List<String> permissionsOf(Statement sql, String roleCode) throws SQLException {
        return strings(sql, "select p.code from role_permission rp join role r on r.id = rp.role_id"
                + " join permission p on p.id = rp.permission_id where r.code = '" + roleCode + "'");
    }

    private static List<String> strings(Statement sql, String query) throws SQLException {
        List<String> values = new ArrayList<>();
        try (ResultSet result = sql.executeQuery(query)) {
            while (result.next()) {
                values.add(result.getString(1));
            }
        }
        return values;
    }

    private static String scalar(Statement sql, String query) throws SQLException {
        try (ResultSet result = sql.executeQuery(query)) {
            return result.next() ? result.getString(1) : null;
        }
    }

    private static void execute(Connection connection, String statement) throws SQLException {
        try (Statement sql = connection.createStatement()) {
            sql.execute(statement);
        }
    }

    // ---------------------------------------------------------------- 旧库种子

    private void seedOldSchool() throws Exception {
        try (Connection connection = open(); Statement sql = connection.createStatement()) {
            seedOldSchool(sql);
        }
    }

    /**
     * V59 停机关机时的老学校：权限目录、三个预置角色 + 一个自定义角色、六个账号、
     * 两个学期与作息、年级/行政班/教学班/课程/授课任务、草稿与已发布基线、历史调代、
     * 约束、导入会话与审计。每张表都带数据，供上面的逐值投影对照使用。
     */
    private static void seedOldSchool(Statement sql) throws SQLException {
        for (String statement : List.of(
                // 权限目录与角色（授权表本身不进入保全投影，由 V60 用例单独断言）
                "insert into permission(id, code, name) values"
                        + " (1,'user:manage','账号与角色'),"
                        + " (2,'integration:manage','集成配置'),"
                        + " (3,'audit:view:account','账号审计查看'),"
                        + " (4,'timetable:view','全校课表'),"
                        + " (5,'timetable:view:class','本班课表'),"
                        + " (6,'substitution:view:class','本班调代'),"
                        + " (7,'timetable:edit','编辑课表'),"
                        + " (8,'semester:manage','学期与作息')",
                "insert into role(code, name) values"
                        + " ('admin','教务管理员'),('teacher','教师'),('head_teacher','班主任'),"
                        + " ('audit_jw','学籍审计岗')",
                "insert into role_permission(role_id, permission_id)"
                        + " select r.id, p.id from role r cross join permission p where r.code = 'admin'",
                "insert into role_permission(role_id, permission_id)"
                        + " select r.id, p.id from role r join permission p"
                        + " on p.code in ('timetable:view:class','timetable:edit','timetable:view')"
                        + " where r.code = 'teacher'",
                "insert into role_permission(role_id, permission_id)"
                        + " select r.id, p.id from role r join permission p"
                        + " on p.code in ('timetable:view:class','substitution:view:class','timetable:view')"
                        + " where r.code = 'head_teacher'",
                "insert into role_permission(role_id, permission_id)"
                        + " select r.id, p.id from role r join permission p"
                        + " on p.code in ('audit:view:account','timetable:view:class')"
                        + " where r.code = 'audit_jw'",
                // 教师与账号：启用/停用、教师绑定、旧哈希全部带值
                "insert into teacher(id, name, employee_no, phone, email, subject, dingtalk_user_id,"
                        + " active, created_at) values"
                        + " (1,'合成绑定教师','T001','13800000001','t1@example.test','数学','synthetic-dd-1',true,TIMESTAMP '2029-08-20 09:00:00'),"
                        + " (2,'合成班主任','T002','13800000002','t2@example.test','语文','synthetic-dd-2',true,TIMESTAMP '2029-08-20 09:05:00'),"
                        + " (3,'合成代课教师','T003',null,null,'物理','synthetic-dd-3',false,TIMESTAMP '2029-08-20 09:10:00')",
                "insert into app_user(id, username, password_hash, display_name, enabled, teacher_id,"
                        + " created_at) values"
                        + " (1,'admin','" + ADMIN_HASH + "','原管理员',true,null,TIMESTAMP '2029-08-21 08:00:00'),"
                        + " (2,'second_old_admin','" + SECOND_ADMIN_HASH + "','另一个原管理员',true,null,TIMESTAMP '2029-08-21 08:10:00'),"
                        + " (3,'old_head','" + HEAD_TEACHER_HASH + "','原班主任',true,2,TIMESTAMP '2029-08-21 08:20:00'),"
                        + " (4,'old_teacher','" + TEACHER_HASH + "','原任课教师',true,1,TIMESTAMP '2029-08-21 08:30:00'),"
                        + " (5,'disabled_teacher','" + DISABLED_HASH + "','停用账号',false,null,TIMESTAMP '2029-08-21 08:40:00'),"
                        + " (6,'legacy_auditor','" + AUDITOR_HASH + "','老审计岗',true,null,TIMESTAMP '2029-08-21 08:50:00')",
                "insert into user_role(user_id, role_id) select u.id, r.id from app_user u cross join role r"
                        + " where (u.username in ('admin','second_old_admin') and r.code = 'admin')"
                        + " or (u.username = 'old_head' and r.code = 'head_teacher')"
                        + " or (u.username in ('old_teacher','disabled_teacher') and r.code = 'teacher')"
                        + " or (u.username = 'legacy_auditor' and r.code = 'audit_jw')",
                // 学期与作息
                "insert into semester(id, name, start_date, end_date, is_current, created_at) values"
                        + " (1,'2029-2030 学年第一学期',DATE '2029-09-01',DATE '2030-01-20',false,TIMESTAMP '2029-08-15 10:00:00'),"
                        + " (2,'2030-2031 学年第一学期',DATE '2030-09-01',DATE '2031-01-20',true,TIMESTAMP '2030-08-15 10:00:00')",
                "insert into period(id, semester_id, day_of_week, period_no, label, start_time, end_time)"
                        + " values (1,2,1,1,'第1节',TIME '08:00:00',TIME '08:40:00'),"
                        + " (2,2,1,2,'第2节',TIME '08:50:00',TIME '09:30:00'),"
                        + " (3,2,2,1,'第1节',TIME '08:00:00',TIME '08:40:00'),"
                        + " (4,2,3,1,'第1节',TIME '08:00:00',TIME '08:40:00'),"
                        + " (5,1,1,1,'第1节',TIME '08:00:00',TIME '08:40:00')",
                // 年级 / 行政班 / 课程 / 教学班 / 授课任务
                "insert into grade(id, semester_id, name, sort_order) values"
                        + " (1,2,'高一',1),(2,2,'高二',2)",
                "insert into admin_class(id, semester_id, grade_id, name, head_teacher_id, student_count)"
                        + " values (1,2,1,'高一(1)班',2,48),(2,2,2,'高二(3)班',null,42)",
                "insert into course(id, name, subject, allow_consecutive, color, created_at) values"
                        + " (1,'数学','数学',true,'#3366cc',TIMESTAMP '2029-08-22 09:00:00'),"
                        + " (2,'物理','物理',false,null,TIMESTAMP '2029-08-22 09:10:00')",
                "insert into teaching_class(id, semester_id, admin_class_id, name) values"
                        + " (1,2,1,'高一(1)班·数学'),(2,2,2,'高二(3)班·物理')",
                "insert into assignment(id, semester_id, admin_class_id, teaching_class_id, course_id,"
                        + " teacher_id, weekly_lessons, created_at) values"
                        + " (1,2,1,1,1,1,5,TIMESTAMP '2029-08-23 09:00:00'),"
                        + " (2,2,2,2,2,3,3,TIMESTAMP '2029-08-23 09:10:00')",
                // 草稿 / 课节 / 已发布基线 / 排课任务
                "insert into timetable_draft(id, semester_id, name, status, score_hard, score_soft,"
                        + " created_by, created_at) values"
                        + " (1,2,'保全草稿','DRAFT',0,0,'admin',TIMESTAMP '2030-08-25 09:00:00'),"
                        + " (2,2,'升级前已发布','PUBLISHED',0,-120,'admin',TIMESTAMP '2030-08-24 09:00:00')",
                "insert into draft_lesson(id, draft_id, assignment_id, lesson_index, course_id,"
                        + " teacher_id, admin_class_id, student_count, period_id, locked) values"
                        + " (1,1,1,0,1,1,1,48,1,false),"
                        + " (2,1,1,1,1,1,1,48,2,true),"
                        + " (3,2,2,0,2,3,2,42,3,false)",
                "insert into timetable_entry(id, semester_id, draft_id, admin_class_id, course_id,"
                        + " teacher_id, period_id, day_of_week, period_no, created_at) values"
                        + " (1,2,2,1,1,1,1,1,1,TIMESTAMP '2030-08-24 10:00:00'),"
                        + " (2,2,2,2,2,3,3,2,1,TIMESTAMP '2030-08-24 10:05:00')",
                "insert into timetable_publish(id, semester_id, draft_id, published_by, published_at)"
                        + " values (1,2,2,'admin',TIMESTAMP '2030-08-24 10:30:00')",
                "insert into scheduling_job(id, semester_id, status, score, progress, elapsed_ms,"
                        + " draft_id, draft_name, solve_seconds, created_by, created_at, started_at,"
                        + " finished_at) values"
                        + " (1,2,'COMPLETED','0hard/-120soft',100,12345,2,'升级前已发布',60,'admin',"
                        + " TIMESTAMP '2030-08-24 09:30:00',TIMESTAMP '2030-08-24 09:30:10',"
                        + " TIMESTAMP '2030-08-24 09:32:00')",
                // 调代：申请、状态历史、已生效记录
                "insert into substitution_request(id, semester_id, entry_id, admin_class_id, course_id,"
                        + " original_teacher_id, requester_teacher_id, effective_date, period_id,"
                        + " day_of_week, period_no, reason, expected_substitute_teacher_id, status,"
                        + " operator_user_id, previous_teacher_id, final_teacher_id, target_date,"
                        + " created_at, updated_at) values"
                        + " (1,2,1,1,1,1,4,DATE '2030-09-02',1,1,1,'外出培训',3,'EFFECTIVE',1,1,3,NULL,"
                        + " TIMESTAMP '2030-09-01 15:00:00',TIMESTAMP '2030-09-01 15:30:00'),"
                        + " (2,2,2,2,2,3,4,DATE '2030-09-03',3,2,1,'临时请假',null,'CANCELLED',null,null,null,"
                        + " DATE '2030-09-03',TIMESTAMP '2030-09-02 08:00:00',TIMESTAMP '2030-09-02 08:20:00')",
                "insert into substitution_history(id, request_id, from_status, to_status, actor,"
                        + " comment, created_at) values"
                        + " (1,1,null,'SUBMITTED','old_teacher','老师自助提交',TIMESTAMP '2030-09-01 15:00:00'),"
                        + " (2,1,'SUBMITTED','EFFECTIVE','admin','同意代课',TIMESTAMP '2030-09-01 15:30:00')",
                "insert into substitution(id, semester_id, request_id, entry_id, effective_date,"
                        + " lesson_date, target_lesson_date, original_teacher_id, substitute_teacher_id,"
                        + " status, created_at) values"
                        + " (1,2,1,1,DATE '2030-09-02',DATE '2030-09-02',null,1,3,'EFFECTIVE',"
                        + " TIMESTAMP '2030-09-01 15:30:00')",
                // 约束（V59 之后不会再有 V56 那种清空动作；这里放一条系统底线与一条旧规则）
                "insert into constraint_config(id, semester_id, type, params_json, weight, hard,"
                        + " enabled, deleted_at) values"
                        + " (1,2,'TEACHER_CONFLICT','{}',100,true,true,null),"
                        + " (2,2,'TEACHER_UNAVAILABLE','{\"dayOfWeek\":1}',60,false,false,null)",
                "insert into constraint_entry(id, semester_id, type, params_json, created_at) values"
                        + " (1,2,'TEACHER_CONFLICT','{\"teacherId\":1,\"dayOfWeek\":1,\"periodNo\":1}',"
                        + " TIMESTAMP '2030-08-26 09:00:00'),"
                        + " (2,2,'TEACHER_UNAVAILABLE','{\"teacherId\":3,\"dayOfWeek\":2}',"
                        + " TIMESTAMP '2030-08-26 09:10:00')",
                // 导入会话与审计
                "insert into ai_import_log(id, user_id, username, import_type, target_type, file_name,"
                        + " file_size, file_path, source_text, preview_json, confirmed_json, result_json,"
                        + " ai_explanation, ai_raw_response, status, row_count, imported_count,"
                        + " total_batches, completed_batches, current_batch, batches_json, version,"
                        + " tool_trace_json, plan_json, plan_version, run_id, created_at, updated_at)"
                        + " values (1,1,'admin','TIMETABLE','TIMETABLE','课表.xlsx',20480,"
                        + " '/data/imports/课表.xlsx','第1节 数学 高一(1)班','{\"rows\":10}',"
                        + " '{\"confirmed\":10}','{\"imported\":10}','识别 10 行','{\"raw\":true}',"
                        + " 'COMPLETED',10,10,2,2,null,'[{\"index\":1}]',3,'[]','{}',1,'run-20300901',"
                        + " TIMESTAMP '2030-09-01 07:00:00',TIMESTAMP '2030-09-01 07:05:00')",
                "insert into audit_log(id, actor, action, entity_type, detail, success_rows,"
                        + " failed_rows, created_at) values"
                        + " (1,'admin','DISABLE_USER','APP_USER','停用用户「停用账号」(disabled_teacher)',1,0,"
                        + " TIMESTAMP '2030-08-30 09:00:00'),"
                        + " (2,'admin','IMPORT','users','文件: users.xlsx, 成功 6 行',6,0,"
                        + " TIMESTAMP '2030-08-30 10:00:00'),"
                        + " (3,'admin','IMPORT','TEACHER','教师导入',3,1,TIMESTAMP '2030-08-30 11:00:00')")) {
            sql.execute(statement);
        }
    }
}
