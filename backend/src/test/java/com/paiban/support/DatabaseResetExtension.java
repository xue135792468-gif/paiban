package com.paiban.support;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * Every REST integration test starts from an empty business database.
 *
 * <p>All test classes share one Spring context and one H2 in-memory database, so a
 * test that changes the current semester or writes settings used to leak into the
 * next class. Identity seed rows (users, roles, permissions) stay because the
 * application only creates them at startup.
 */
public class DatabaseResetExtension implements BeforeEachCallback {

    private static final Set<String> KEEP = Set.of(
            "flyway_schema_history", "app_user", "role", "permission", "user_role", "role_permission");

    @Override
    public void beforeEach(ExtensionContext context) {
        ApplicationContext applicationContext = SpringExtension.getApplicationContext(context);
        awaitNotificationExecutor(applicationContext);
        DataSource dataSource = applicationContext.getBean(DataSource.class);
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("SET REFERENTIAL_INTEGRITY FALSE");
            for (String table : tables(statement)) {
                if (!KEEP.contains(table.toLowerCase(Locale.ROOT))) {
                    statement.execute("TRUNCATE TABLE \"" + table + "\"");
                }
            }
            statement.execute("SET REFERENTIAL_INTEGRITY TRUE");
        } catch (SQLException ex) {
            throw new IllegalStateException("重置测试数据库失败", ex);
        }
        // Truncation bypasses the services, so in-memory caches fed by them must be dropped too.
        try {
            applicationContext.publishEvent(new com.paiban.ai.event.AiConfigChangedEvent("test-database-reset"));
        } catch (RuntimeException ignored) {
            // The AI module is optional for tests that do not touch it.
        }
    }

    private List<String> tables(Statement statement) throws SQLException {
        List<String> tables = new ArrayList<>();
        try (ResultSet rows = statement.executeQuery(
                "select table_name from information_schema.tables where table_schema = 'public'")) {
            while (rows.next()) {
                tables.add(rows.getString(1));
            }
        }
        return tables;
    }

    private void awaitNotificationExecutor(ApplicationContext applicationContext) {
        ThreadPoolTaskExecutor executor = applicationContext.getBean(
                "notificationExecutor", ThreadPoolTaskExecutor.class);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (executor.getActiveCount() > 0
                || executor.getThreadPoolExecutor().getQueue().size() > 0) {
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException(
                        "通知 executor 未在 10 秒内空闲，拒绝重置测试数据库");
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待通知 executor 空闲时被中断", ex);
            }
        }
    }
}
