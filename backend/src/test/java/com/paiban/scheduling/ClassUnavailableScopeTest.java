package com.paiban.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paiban.scheduling.domain.ConstraintConfig;
import com.paiban.scheduling.domain.ConstraintEntry;
import com.paiban.scheduling.domain.ConstraintType;
import com.paiban.scheduling.solver.ConstraintSettings;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 每周不上课时段的范围语义：省略或 {@code null} 的 classId 明确表示全校，
 * 覆盖当前及以后新增的行政班；任何非法或未知的非空 classId 都不能退化成全校。
 */
class ClassUnavailableScopeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void entryWithoutClassIdCoversEveryClass() {
        ConstraintSettings settings = settings(Map.of("dayOfWeek", 3, "periodNo", 2));

        assertThat(settings.classUnavailable(11L, 3, 2)).isTrue();
        assertThat(settings.classUnavailable(12L, 3, 2)).isTrue();
        assertThat(settings.classUnavailable(null, 3, 2)).isTrue();
        assertThat(settings.classUnavailable(11L, 3, 3)).isFalse();
    }

    @Test
    void explicitNullClassIdIsWholeSchool() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("classId", null);
        params.put("dayOfWeek", 1);
        params.put("periodNo", 1);

        assertThat(settings(params).classUnavailable(7L, 1, 1)).isTrue();
    }

    @Test
    void nonNumericClassIdMatchesNothing() {
        Map<String, Object> params = Map.of("classId", "abc", "dayOfWeek", 1, "periodNo", 1);
        ConstraintSettings settings = settings(params);

        assertThat(settings.classUnavailable(1L, 1, 1)).isFalse();
        assertThat(settings.classUnavailable(2L, 1, 1)).isFalse();
    }

    @Test
    void blankStringClassIdMatchesNothing() {
        // Only an absent or explicit null classId is whole school; a blank string is a
        // malformed reference (e.g. a hand-edited CLI payload) and must not widen.
        Map<String, Object> params = Map.of("classId", "   ", "dayOfWeek", 1, "periodNo", 1);
        ConstraintSettings settings = settings(params);

        assertThat(settings.classUnavailable(1L, 1, 1)).isFalse();
        assertThat(settings.classUnavailable(2L, 1, 1)).isFalse();
    }

    @Test
    void unknownButNumericClassIdMatchesNothing() {
        ConstraintSettings settings = settings(Map.of("classId", 999L, "dayOfWeek", 1, "periodNo", 1));

        assertThat(settings.classUnavailable(1L, 1, 1)).isFalse();
        assertThat(settings.classUnavailable(999L, 1, 1)).isTrue();
    }

    /** A paused whole-school requirement stops matching, the overlap union is unchanged. */
    @Test
    void pausedRequirementDropsOnlyItsOwnRows() {
        ConstraintSettings settings = settings(true, Map.of("dayOfWeek", 1, "periodNo", 1));

        assertThat(settings.classUnavailable(1L, 1, 1)).isFalse();
    }

    private static ConstraintSettings settings(Map<String, Object> params) {
        return settings(false, params);
    }

    private static ConstraintSettings settings(boolean entryDisabled, Map<String, Object> params) {
        ConstraintConfig config = new ConstraintConfig();
        config.setType(ConstraintType.CLASS_UNAVAILABLE.name());
        config.setEnabled(true);
        config.setHard(true);
        config.setWeight(100);
        config.setParamsJson("{}");

        ConstraintEntry entry = new ConstraintEntry();
        entry.setId(1L);
        entry.setType(ConstraintType.CLASS_UNAVAILABLE.name());
        entry.setEnabled(!entryDisabled);
        entry.setGroupKey("weekly-no-class");
        entry.setParamsJson(write(params));

        return ConstraintSettings.from(List.of(config), List.of(entry), MAPPER);
    }

    private static String write(Map<String, Object> params) {
        try {
            return MAPPER.writeValueAsString(params);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
