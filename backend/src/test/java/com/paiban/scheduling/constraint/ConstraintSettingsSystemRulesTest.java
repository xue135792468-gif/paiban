package com.paiban.scheduling.constraint;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paiban.scheduling.domain.ConstraintConfig;
import com.paiban.scheduling.domain.ConstraintType;
import com.paiban.scheduling.solver.ConstraintSettings;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Ticket 03: the two system bottom lines are not ordinary user rules. A missing,
 * paused or soft-deleted configuration row must never turn teacher/class conflict
 * detection off, because the rule page only labels them "系统自带，不能关".
 */
class ConstraintSettingsSystemRulesTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void systemRulesAreHardEvenWhenNoRowsExist() {
        ConstraintSettings settings = ConstraintSettings.from(List.of(), List.of(), mapper);

        assertThat(settings.control(ConstraintType.TEACHER_CONFLICT).hard()).isTrue();
        assertThat(settings.control(ConstraintType.CLASS_CONFLICT).hard()).isTrue();
    }

    @Test
    void pausedRowsCannotDisableThem() {
        ConstraintConfig paused = new ConstraintConfig();
        paused.setType("TEACHER_CONFLICT");
        paused.setEnabled(false);
        paused.setHard(false);
        paused.setWeight(10);

        ConstraintConfig deleted = new ConstraintConfig();
        deleted.setType("CLASS_CONFLICT");
        deleted.setDeletedAt(Instant.now());
        deleted.setHard(false);
        deleted.setWeight(10);

        ConstraintSettings settings = ConstraintSettings.from(List.of(paused, deleted), List.of(), mapper);

        assertThat(settings.control(ConstraintType.TEACHER_CONFLICT).hard()).isTrue();
        assertThat(settings.control(ConstraintType.CLASS_CONFLICT).hard()).isTrue();
    }
}
