package com.paiban.scheduling.solver;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paiban.scheduling.domain.ConstraintConfig;
import com.paiban.scheduling.domain.ConstraintEntry;
import com.paiban.scheduling.domain.ConstraintType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The rule fingerprint must be a deterministic function of the effective rules only:
 * the same configuration built in a different order is the same fingerprint, while any
 * change to a hard/soft choice, a weight or an entry parameter is a different one.
 */
class ConstraintSettingsFingerprintTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void sameConfigurationInADifferentOrderSharesAFingerprint() throws Exception {
        List<ConstraintConfig> configs = List.of(
                config(ConstraintType.TEACHER_UNAVAILABLE, true, 100),
                config(ConstraintType.CLASS_MAX_PER_DAY, true, 50));
        List<ConstraintEntry> entries = new ArrayList<>(List.of(
                entry(1, ConstraintType.TEACHER_UNAVAILABLE, params("teacherId", 11, "dayOfWeek", 1)),
                entry(2, ConstraintType.TEACHER_UNAVAILABLE, params("teacherId", 12, "dayOfWeek", 2)),
                entry(3, ConstraintType.CLASS_MAX_PER_DAY, params("maxPerDay", 8))));

        ConstraintSettings straight = ConstraintSettings.from(configs, entries, MAPPER);
        List<ConstraintConfig> reversedConfigs = new ArrayList<>(configs);
        java.util.Collections.reverse(reversedConfigs);
        List<ConstraintEntry> reversedEntries = new ArrayList<>(entries);
        java.util.Collections.reverse(reversedEntries);
        ConstraintSettings reversed = ConstraintSettings.from(reversedConfigs, reversedEntries, MAPPER);

        assertThat(straight.fingerprint())
                .isNotEmpty()
                .hasSize(64)
                .isEqualTo(reversed.fingerprint());
    }

    @Test
    void hardSoftAndWeightChangesChangeTheFingerprint() throws Exception {
        ConstraintSettings hard = ConstraintSettings.from(
                List.of(config(ConstraintType.TEACHER_UNAVAILABLE, true, 100)),
                List.of(entry(1, ConstraintType.TEACHER_UNAVAILABLE, params("teacherId", 11))),
                MAPPER);
        ConstraintSettings soft = ConstraintSettings.from(
                List.of(config(ConstraintType.TEACHER_UNAVAILABLE, false, 100)),
                List.of(entry(1, ConstraintType.TEACHER_UNAVAILABLE, params("teacherId", 11))),
                MAPPER);
        ConstraintSettings lighter = ConstraintSettings.from(
                List.of(config(ConstraintType.TEACHER_UNAVAILABLE, true, 60)),
                List.of(entry(1, ConstraintType.TEACHER_UNAVAILABLE, params("teacherId", 11))),
                MAPPER);

        assertThat(hard.fingerprint()).isNotEqualTo(soft.fingerprint());
        assertThat(hard.fingerprint()).isNotEqualTo(lighter.fingerprint());
    }

    @Test
    void parameterChangeChangesTheFingerprint() throws Exception {
        ConstraintSettings monday = ConstraintSettings.from(
                List.of(config(ConstraintType.TEACHER_UNAVAILABLE, true, 100)),
                List.of(entry(1, ConstraintType.TEACHER_UNAVAILABLE,
                        params("teacherId", 11, "dayOfWeek", 1))),
                MAPPER);
        ConstraintSettings tuesday = ConstraintSettings.from(
                List.of(config(ConstraintType.TEACHER_UNAVAILABLE, true, 100)),
                List.of(entry(1, ConstraintType.TEACHER_UNAVAILABLE,
                        params("teacherId", 11, "dayOfWeek", 2))),
                MAPPER);

        assertThat(monday.fingerprint()).isNotEqualTo(tuesday.fingerprint());
    }

    @Test
    void defaultConfigurationFingerprintIsStable() {
        String first = ConstraintSettings.defaults().fingerprint();
        String second = ConstraintSettings.defaults().fingerprint();

        assertThat(first).hasSize(64).isEqualTo(second);
    }

    private static ConstraintConfig config(ConstraintType type, boolean hard, int weight) {
        ConstraintConfig config = new ConstraintConfig();
        config.setType(type.name());
        config.setHard(hard);
        config.setWeight(weight);
        config.setEnabled(true);
        config.setParamsJson("{}");
        return config;
    }

    private static ConstraintEntry entry(long id, ConstraintType type, Map<String, Object> params)
            throws Exception {
        ConstraintEntry entry = new ConstraintEntry();
        entry.setId(id);
        entry.setType(type.name());
        entry.setEnabled(true);
        entry.setParamsJson(MAPPER.writeValueAsString(params));
        return entry;
    }

    private static Map<String, Object> params(Object... pairs) {
        Map<String, Object> params = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            params.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return params;
    }
}
