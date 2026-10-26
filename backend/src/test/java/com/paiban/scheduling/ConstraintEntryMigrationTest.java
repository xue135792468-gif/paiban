package com.paiban.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import java.util.UUID;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * V26 must turn every existing {@code constraint_config.params_json} into one
 * entry row, so a pre-existing single-params configuration keeps its meaning after
 * the upgrade. The check migrates to V25, seeds the old shape, then migrates to
 * the head and reads the entry rows back.
 */
class ConstraintEntryMigrationTest {

    private String url;

    @BeforeEach
    void freshDatabase() {
        // A private database per test: Flyway runs V1-V26 exactly once for each.
        url = "jdbc:h2:mem:constraint_entries_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
    }

    @Test
    void legacyParamsBecomeOneEntryPerType() throws Exception {
        migrateTo("25");
        long semesterId = seedLegacyConfiguration();
        migrateTo(null);

        try (Connection connection = DriverManager.getConnection(url, "sa", "");
                Statement statement = connection.createStatement()) {
            // V26 copies the real value; V27 adds the explicit "sport class" entry the
            // old blank course meant (see the next test).
            assertThat(entries(statement, semesterId))
                    .containsExactly(
                            "TEACHER_UNAVAILABLE|{\"teacherId\":7,\"dayOfWeek\":1,\"periodNo\":2}",
                            "SPORT_NOT_FIRST_AFTERNOON|{}");
            // The type-level row is untouched.
            try (ResultSet row = statement.executeQuery(
                    "select enabled, hard, weight from constraint_config"
                            + " where semester_id = " + semesterId + " and type = 'TEACHER_UNAVAILABLE'")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getBoolean("enabled")).isTrue();
                assertThat(row.getBoolean("hard")).isTrue();
                assertThat(row.getInt("weight")).isEqualTo(100);
            }
        }
    }

    @Test
    void emptyTargetOfAnOptionalTypeBecomesOneExplicitEntry() throws Exception {
        migrateTo("25");
        long semesterId = seedLegacyConfiguration();
        migrateTo(null);

        try (Connection connection = DriverManager.getConnection(url, "sa", "");
                Statement statement = connection.createStatement()) {
            // A blank target used to mean "every course / the sport class". Since
            // ticket 10/03 an empty list means "not configured", so V27 turns each of
            // those old rows into one explicit entry without an object condition.
            assertThat(entriesOfType(statement, semesterId, "SPORT_NOT_FIRST_AFTERNOON"))
                    .containsExactly("{}");
            // A switch type has no entries at all, before or after.
            assertThat(entriesOfType(statement, semesterId, "TEACHER_CONFLICT")).isEmpty();
            // Nothing is invented for a type nobody configured.
            assertThat(entriesOfType(statement, semesterId, "COURSE_SPREAD")).isEmpty();
        }
    }

    @Test
    void v27BackfillsEveryOptionalTargetTypeOnce() throws Exception {
        migrateTo("25");
        long semesterId;
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("insert into semester (name, start_date, end_date, is_current, created_at)"
                    + " values ('回填学期', DATE '2025-09-01', DATE '2026-01-20', false, CURRENT_TIMESTAMP)");
            try (ResultSet keys = statement.executeQuery("select max(id) from semester")) {
                keys.next();
                semesterId = keys.getLong(1);
            }
            for (String type : List.of("SAME_COURSE_ONCE_PER_DAY", "SPORT_NOT_FIRST_AFTERNOON",
                    "COURSE_SPREAD", "PE_LAST_PERIOD_AVOID")) {
                insertConfig(statement, semesterId, type, "{}", false, 50);
            }
        }
        migrateTo(null);

        try (Connection connection = DriverManager.getConnection(url, "sa", "");
                Statement statement = connection.createStatement()) {
            for (String type : List.of("SAME_COURSE_ONCE_PER_DAY", "SPORT_NOT_FIRST_AFTERNOON",
                    "COURSE_SPREAD", "PE_LAST_PERIOD_AVOID")) {
                assertThat(entriesOfType(statement, semesterId, type))
                        .as(type + " gets exactly one explicit all-target entry")
                        .containsExactly("{}");
            }
        }
    }

    private List<String> entriesOfType(Statement statement, long semesterId, String type)
            throws Exception {
        List<String> rows = new ArrayList<>();
        try (ResultSet result = statement.executeQuery(
                "select params_json from constraint_entry where semester_id = " + semesterId
                        + " and type = '" + type + "' order by id")) {
            while (result.next()) {
                rows.add(result.getString(1));
            }
        }
        return rows;
    }

    private void migrateTo(String version) {
        Flyway flyway = Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .target(version == null ? MigrationVersion.LATEST : MigrationVersion.fromVersion(version))
                .load();
        flyway.migrate();
    }

    private long seedLegacyConfiguration() throws Exception {
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("insert into semester (name, start_date, end_date, is_current, created_at)"
                    + " values ('历史学期', DATE '2025-09-01', DATE '2026-01-20', false, CURRENT_TIMESTAMP)");
            long semesterId;
            try (ResultSet keys = statement.executeQuery("select max(id) from semester")) {
                keys.next();
                semesterId = keys.getLong(1);
            }
            insertConfig(statement, semesterId, "TEACHER_UNAVAILABLE",
                    "{\"teacherId\":7,\"dayOfWeek\":1,\"periodNo\":2}", true, 100);
            insertConfig(statement, semesterId, "TEACHER_CONFLICT", "{}", true, 100);
            insertConfig(statement, semesterId, "SPORT_NOT_FIRST_AFTERNOON", "{}", false, 70);
            return semesterId;
        }
    }

    private void insertConfig(Statement statement, long semesterId, String type, String params,
                              boolean hard, int weight) throws Exception {
        statement.executeUpdate("insert into constraint_config"
                + " (semester_id, type, params_json, weight, hard, enabled) values ("
                + semesterId + ", '" + type + "', '" + params.replace("'", "''") + "', "
                + weight + ", " + hard + ", true)");
    }

    private List<String> entries(Statement statement, long semesterId) throws Exception {
        List<String> rows = new ArrayList<>();
        try (ResultSet result = statement.executeQuery(
                "select type, params_json, created_at from constraint_entry where semester_id = "
                        + semesterId + " order by id")) {
            while (result.next()) {
                assertThat(result.getTimestamp("created_at")).isNotNull()
                        .isAfter(Timestamp.from(Instant.EPOCH));
                rows.add(result.getString("type") + "|" + result.getString("params_json"));
            }
        }
        return rows;
    }
}
