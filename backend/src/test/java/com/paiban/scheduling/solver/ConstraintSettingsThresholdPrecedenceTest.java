package com.paiban.scheduling.solver;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paiban.scheduling.domain.ConstraintConfig;
import com.paiban.scheduling.domain.ConstraintEntry;
import com.paiban.scheduling.domain.ConstraintType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Hark review P1-2: once a group is expanded, several threshold rows can target the
 * same object (a teacher's own cap plus a group cap, or two overlapping groups). The
 * winner must not depend on entry ids: a single-object rule beats a group rule, and
 * within one level the smallest upper bound wins. The value and its control always
 * come from the same row.
 */
class ConstraintSettingsThresholdPrecedenceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long TEACHER = 7L;

    @Test
    void singleObjectRuleBeatsAGroupRuleEvenWhenTheGroupIsTighter() {
        ConstraintSettings settings = settings(
                row(1, params(TEACHER, 5), false, 40),
                row(2, params(TEACHER, 4, true), true, 100));

        assertThat(settings.limitOrNull(ConstraintType.TEACHER_MAX_PER_DAY, TEACHER))
                .isEqualTo(5);
        // The number and the control must both come from the single-object row.
        assertThat(settings.thresholdControl(ConstraintType.TEACHER_MAX_PER_DAY, TEACHER))
                .isEqualTo(new ConstraintSettings.Control(false, 40));
    }

    @Test
    void twoGroupRulesTakeTheStricterUpperBound() {
        ConstraintSettings settings = settings(
                row(1, params(TEACHER, 5, true), true, 100),
                row(2, params(TEACHER, 4, true), false, 30));

        assertThat(settings.limitOrNull(ConstraintType.TEACHER_MAX_PER_DAY, TEACHER))
                .isEqualTo(4);
        assertThat(settings.thresholdControl(ConstraintType.TEACHER_MAX_PER_DAY, TEACHER))
                .isEqualTo(new ConstraintSettings.Control(false, 30));
    }

    @Test
    void aHardSingleObjectRuleBeatsASofterAndSmallerGroupRule() {
        ConstraintSettings settings = settings(
                row(1, params(TEACHER, 5), true, 100),
                row(2, params(TEACHER, 4, true), false, 30));

        assertThat(settings.limitOrNull(ConstraintType.TEACHER_MAX_PER_DAY, TEACHER))
                .isEqualTo(5);
        assertThat(settings.thresholdControl(ConstraintType.TEACHER_MAX_PER_DAY, TEACHER))
                .isEqualTo(new ConstraintSettings.Control(true, 0));
    }

    @Test
    void equalValuesPreferTheHardRuleWithinOneLevel() {
        ConstraintSettings settings = settings(
                row(1, params(TEACHER, 4, true), false, 30),
                row(2, params(TEACHER, 4, true), true, 100));

        assertThat(settings.limitOrNull(ConstraintType.TEACHER_MAX_PER_DAY, TEACHER))
                .isEqualTo(4);
        assertThat(settings.thresholdControl(ConstraintType.TEACHER_MAX_PER_DAY, TEACHER))
                .isEqualTo(new ConstraintSettings.Control(true, 0));
    }

    @Test
    void withoutAnObjectRuleTheGlobalDefaultTakesTheStricterUpperBound() {
        ConstraintSettings settings = settings(
                row(1, params(null, 6), true, 100),
                row(2, params(null, 4), false, 20));

        long other = 99L;
        assertThat(settings.limitOrNull(ConstraintType.TEACHER_MAX_PER_DAY, other))
                .isEqualTo(4);
        assertThat(settings.thresholdControl(ConstraintType.TEACHER_MAX_PER_DAY, other))
                .isEqualTo(new ConstraintSettings.Control(false, 20));
    }

    // ------------------------------------------------------------------ helpers

    private ConstraintSettings settings(ConstraintEntry... entries) {
        ConstraintConfig config = new ConstraintConfig();
        config.setType(ConstraintType.TEACHER_MAX_PER_DAY.name());
        config.setEnabled(true);
        config.setHard(true);
        config.setWeight(100);
        config.setParamsJson("{}");
        return ConstraintSettings.from(List.of(config), List.of(entries), MAPPER);
    }

    private Map<String, Object> params(Long teacherId, int maxPerDay) {
        return params(teacherId, maxPerDay, false);
    }

    private Map<String, Object> params(Long teacherId, int maxPerDay, boolean groupExpanded) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (teacherId != null) {
            params.put("teacherId", teacherId);
        }
        params.put("maxPerDay", maxPerDay);
        if (groupExpanded) {
            params.put(ConstraintSettings.GROUP_EXPANDED, true);
        }
        return params;
    }

    private ConstraintEntry row(
            long id, Map<String, Object> params, boolean hard, int weight) {
        ConstraintEntry entry = new ConstraintEntry();
        entry.setId(id);
        entry.setType(ConstraintType.TEACHER_MAX_PER_DAY.name());
        entry.setEnabled(true);
        entry.setHard(hard);
        entry.setWeight(weight);
        entry.setParamsJson(json(params));
        return entry;
    }

    private static String json(Map<String, Object> params) {
        try {
            return MAPPER.writeValueAsString(params);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
