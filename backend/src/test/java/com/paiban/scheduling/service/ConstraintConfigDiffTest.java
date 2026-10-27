package com.paiban.scheduling.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.paiban.scheduling.service.ConstraintConfigDiff.Diff;
import com.paiban.scheduling.web.ConstraintDtos.ConstraintConfigDto;
import com.paiban.scheduling.web.ConstraintDtos.ConstraintEntryDto;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Ticket 14/10: the audit's diff proves the effective change, not the model's claim. */
class ConstraintConfigDiffTest {

    @Test
    void identicalConfigurationsProduceAnEmptyDiff() {
        Diff diff = ConstraintConfigDiff.diff(List.of(config("A", true, 100, entry(1))),
                List.of(config("A", true, 100, entry(1))));
        assertThat(diff.addedTypes()).isEmpty();
        assertThat(diff.removedTypes()).isEmpty();
        assertThat(diff.changedTypes()).isEmpty();
    }

    @Test
    void addedTypesAndEntriesAndControlChangesAreReported() {
        ConstraintConfigDto before = config("A", true, 100, entry(1));
        ConstraintConfigDto after = config("A", false, 40, entry(1), entry(2));
        Diff diff = ConstraintConfigDiff.diff(List.of(before),
                List.of(after, config("B", true, 100, entry(3))));

        assertThat(diff.addedTypes()).containsExactly("B");
        assertThat(diff.removedTypes()).isEmpty();
        assertThat(diff.changedTypes()).hasSize(1);
        ConstraintConfigDiff.Change change = diff.changedTypes().get(0);
        assertThat(change.type()).isEqualTo("A");
        assertThat(change.beforeControl()).containsEntry("hard", true);
        assertThat(change.afterControl()).containsEntry("hard", false);
        assertThat(change.addedEntries()).hasSize(1);
        assertThat(change.removedEntries()).isEmpty();
    }

    @Test
    void removedTypesAreReported() {
        Diff diff = ConstraintConfigDiff.diff(
                List.of(config("A", true, 100, entry(1)), config("B", true, 100, entry(2))),
                List.of(config("A", true, 100, entry(1))));
        assertThat(diff.removedTypes()).containsExactly("B");
        assertThat(diff.changedTypes()).isEmpty();
    }

    private ConstraintConfigDto config(
            String type, boolean hard, int weight, ConstraintEntryDto... entries) {
        return new ConstraintConfigDto(1L, type, type, "PEDAGOGY", "教学规律", "",
                Map.of(), weight, hard, true, List.of(entries));
    }

    private ConstraintEntryDto entry(int teacherId) {
        return new ConstraintEntryDto(1L, Map.of("teacherId", teacherId, "dayOfWeek", 1,
                "periodNo", 1), true, null, null, null);
    }
}
