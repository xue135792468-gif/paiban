package com.paiban.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

/**
 * V60 → V61 的旧日志保全边界：升级只给账号事件补分类与新增列，绝不改写历史行。
 *
 * <p>旧库里的账号事件用旧动作码（DISABLE_USER / ENABLE_USER / users 的 IMPORT），没有目标账号 ID
 * 与快照；升级后这些原值、原文、成功 / 失败行数必须一字不差，只有 {@code category} 被设为
 * {@code ACCOUNT}。其他分类的 IMPORT（如教师导入）不受影响，保持 {@code category} 为空。
 */
class AccountAuditUpgradeDataPreservationTest {

    @Test
    void upgradingToV61ClassifiesAccountEventsWithoutRewritingHistory() throws Exception {
        String url = "jdbc:h2:mem:account_audit_upgrade_"
                + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        migrate(url, "60");
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
                Statement sql = connection.createStatement()) {
            seedLegacyAuditRows(sql);
            Map<Integer, List<String>> before = auditRows(connection);
            migrate(url, "61");

            assertThat(auditRows(connection))
                    .as("历史行的操作者、动作、实体、原文与成功/失败数都不得改写")
                    .isEqualTo(before);
            assertThat(strings(sql, "select category from audit_log order by id"))
                    .containsExactly("ACCOUNT", "ACCOUNT", "ACCOUNT", null, null);
            assertThat(strings(sql, "select target_user_id from audit_log order by id"))
                    .as("旧记录本来就没有目标账号 ID，升级不能补造")
                    .containsOnlyNulls();
            assertThat(strings(sql, "select before_json from audit_log order by id"))
                    .containsOnlyNulls();
            assertThat(strings(sql, "select after_json from audit_log order by id"))
                    .containsOnlyNulls();
            assertThat(strings(sql, "select action from audit_log order by id"))
                    .as("旧动作码原样保留，只读别名发生在读取时")
                    .containsExactly("DISABLE_USER", "ENABLE_USER", "IMPORT", "IMPORT", "CREATE");
        }
    }

    private static void migrate(String url, String version) {
        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration")
                .target(version).load().migrate();
    }

    private static Map<Integer, List<String>> auditRows(Connection connection) throws Exception {
        Map<Integer, List<String>> rows = new LinkedHashMap<>();
        try (Statement sql = connection.createStatement();
                ResultSet result = sql.executeQuery(
                        "select id, actor, action, entity_type, detail, success_rows, failed_rows,"
                                + " created_at from audit_log order by id")) {
            while (result.next()) {
                List<String> row = new ArrayList<>();
                for (int column = 1; column <= result.getMetaData().getColumnCount(); column++) {
                    row.add(result.getString(column));
                }
                rows.put(result.getInt(1), row);
            }
        }
        return rows;
    }

    private static List<String> strings(Statement sql, String query) throws Exception {
        List<String> values = new ArrayList<>();
        try (ResultSet result = sql.executeQuery(query)) {
            while (result.next()) values.add(result.getString(1));
        }
        return values;
    }

    private static void seedLegacyAuditRows(Statement sql) throws Exception {
        for (String statement : List.of(
                "insert into audit_log(actor, action, entity_type, detail, success_rows, failed_rows, created_at)"
                        + " values ('admin','DISABLE_USER','APP_USER','停用用户「旧存档」(legacy_zhang)',1,0,TIMESTAMP '2030-09-01 08:00:00')",
                "insert into audit_log(actor, action, entity_type, detail, success_rows, failed_rows, created_at)"
                        + " values ('admin','ENABLE_USER','APP_USER','启用用户「旧存档」(legacy_zhang)',1,0,TIMESTAMP '2030-09-01 09:00:00')",
                "insert into audit_log(actor, action, entity_type, detail, success_rows, failed_rows, created_at)"
                        + " values ('admin','IMPORT','users','文件: users.xlsx, 成功 2 行',2,0,TIMESTAMP '2030-09-01 10:00:00')",
                "insert into audit_log(actor, action, entity_type, detail, success_rows, failed_rows, created_at)"
                        + " values ('admin','IMPORT','TEACHER','教师导入',3,1,TIMESTAMP '2030-09-01 11:00:00')",
                "insert into audit_log(actor, action, entity_type, detail, success_rows, failed_rows, created_at)"
                        + " values ('system','CREATE','SEMESTER','新建学期',1,0,TIMESTAMP '2030-09-01 12:00:00')")) {
            sql.execute(statement);
        }
    }
}
