package com.paiban.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 票 03-A2 的迁移验收（V62）：老账号全部落在 PASSWORD 且哈希逐字保留，
 * DINGTALK 账号必须是空哈希；表结构在 H2(PostgreSQL 模式) 上可执行。
 *
 * <p>真实 PostgreSQL 的可执行性由 CI 的 {@code PostgresContainerIT} 覆盖；
 * Hibernate {@code ddl-auto=validate} 由同一模块的 HTTP 集成测试启动上下文时验证。
 */
class LoginTypeMigrationTest {

    private String url;

    @BeforeEach
    void freshDatabase() {
        url = "jdbc:h2:mem:login_type_migration_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
    }

    @Test
    void v62KeepsEveryExistingAccountOnPasswordAndPreservesItsHash() throws Exception {
        migrateTo("61");
        exec("insert into app_user (username, password_hash, display_name, created_at)"
                        + " values ('admin', '$2a$10$legacy-hash-kept-verbatim', '超级管理员', CURRENT_TIMESTAMP)",
                "insert into app_user (username, password_hash, display_name, created_at)"
                        + " values ('teacher_a', '$2a$10$another-legacy-hash', '张老师', CURRENT_TIMESTAMP)");

        migrateTo("62");

        try (Connection connection = driver()) {
            assertThat(scalar(connection, "select login_type from app_user where username = 'admin'"))
                    .isEqualTo("PASSWORD");
            assertThat(scalar(connection, "select login_type from app_user where username = 'teacher_a'"))
                    .isEqualTo("PASSWORD");
            // 哈希逐字保留：迁移只加类型，不改写旧凭据。
            assertThat(scalar(connection, "select password_hash from app_user where username = 'admin'"))
                    .isEqualTo("$2a$10$legacy-hash-kept-verbatim");
            assertThat(scalar(connection, "select password_hash from app_user where username = 'teacher_a'"))
                    .isEqualTo("$2a$10$another-legacy-hash");

            // 旧的 raw INSERT 不认识 login_type，仍要回落到默认 PASSWORD。
            execute(connection, "insert into app_user (username, password_hash, display_name, created_at)"
                    + " values ('raw_insert', '$2a$10$raw', '旧脚本', CURRENT_TIMESTAMP)");
            assertThat(scalar(connection, "select login_type from app_user where username = 'raw_insert'"))
                    .isEqualTo("PASSWORD");
        }
    }

    @Test
    void v62OnlyAllowsDingTalkAccountsWithoutAPassword() throws Exception {
        migrateTo("62");

        try (Connection connection = driver()) {
            execute(connection, "insert into app_user (username, password_hash, display_name,"
                    + " login_type, created_at) values ('ding_ok', null, '钉钉老师', 'DINGTALK', CURRENT_TIMESTAMP)");
            assertThat(scalar(connection, "select login_type from app_user where username = 'ding_ok'"))
                    .isEqualTo("DINGTALK");
            assertThat(scalar(connection, "select password_hash from app_user where username = 'ding_ok'"))
                    .isNull();

            // 类型与哈希的组合由数据库兜底：钉钉账号带哈希、密码账号无哈希都必须拒绝。
            assertThatThrownBy(() -> execute(connection,
                    "insert into app_user (username, password_hash, display_name, login_type, created_at)"
                            + " values ('ding_with_hash', '$2a$10$x', '钉钉老师2', 'DINGTALK', CURRENT_TIMESTAMP)"))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> execute(connection,
                    "insert into app_user (username, password_hash, display_name, login_type, created_at)"
                            + " values ('pwd_without_hash', null, '无密码管理员', 'PASSWORD', CURRENT_TIMESTAMP)"))
                    .isInstanceOf(SQLException.class);
        }
    }

    private void migrateTo(String version) {
        Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion(version))
                .load()
                .migrate();
    }

    private Connection driver() throws SQLException {
        return DriverManager.getConnection(url, "sa", "");
    }

    private void exec(String... sql) throws Exception {
        try (Connection connection = driver()) {
            for (String one : sql) {
                execute(connection, one);
            }
        }
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private String scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            return result.next() ? result.getString(1) : null;
        }
    }
}
