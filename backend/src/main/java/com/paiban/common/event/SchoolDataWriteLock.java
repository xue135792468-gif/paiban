package com.paiban.common.event;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Serializes roster and derived teacher-group writes across application instances. */
@Component
public class SchoolDataWriteLock {

    private final JdbcTemplate jdbc;

    public SchoolDataWriteLock(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void acquire() {
        jdbc.queryForObject(
                "select id from school_data_write_lock where id = 1 for update",
                Integer.class);
    }
}
