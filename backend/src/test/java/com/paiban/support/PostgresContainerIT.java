package com.paiban.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real-PostgreSQL integration check required by the spec. It only runs when a
 * Docker daemon is available: {@code ./mvnw verify -Dtestcontainers=true}.
 * The default suite uses H2 in PostgreSQL mode because CI-less environments in
 * this workspace have no Docker.
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("postgres")
@EnabledIfSystemProperty(named = "testcontainers", matches = "true")
class PostgresContainerIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void flywayMigrationsApplyOnPostgres() {
        Integer tables = jdbcTemplate.queryForObject(
                "select count(*) from information_schema.tables where table_schema = 'public'", Integer.class);
        assertThat(tables).isNotNull().isGreaterThan(20);
        Integer semesters = jdbcTemplate.queryForObject("select count(*) from semester", Integer.class);
        assertThat(semesters).isNotNull();
    }
}
