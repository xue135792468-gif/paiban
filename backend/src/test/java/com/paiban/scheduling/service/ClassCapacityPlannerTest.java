package com.paiban.scheduling.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paiban.scheduling.domain.ConstraintConfig;
import com.paiban.scheduling.domain.ConstraintEntry;
import com.paiban.scheduling.domain.ConstraintType;
import com.paiban.scheduling.service.ClassCapacityPlanner.Load;
import com.paiban.scheduling.service.ClassCapacityPlanner.Slot;
import com.paiban.scheduling.solver.ConstraintSettings;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 容量只是必要条件：真实作息去重后逐班比较周课时与可用位置。 */
class ClassCapacityPlannerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long CLASS_ID = 21L;

    @Test
    void fortySlotsWithTwoBlockedAndThirtyNineLessonsIsShortByOne() {
        List<Slot> slots = fortySlots();
        ConstraintSettings settings = settings(
                group("a", null, 1, 1),
                group("b", null, 1, 2));

        List<Load> shortages = ClassCapacityPlanner.shortages(
                slots, Map.of(CLASS_ID, 39), Map.of(CLASS_ID, "高二1班"), settings);

        assertThat(shortages).hasSize(1);
        Load shortage = shortages.get(0);
        assertThat(shortage.className()).isEqualTo("高二1班");
        assertThat(shortage.required()).isEqualTo(39);
        assertThat(shortage.available()).isEqualTo(38);
        assertThat(shortage.shortage()).isEqualTo(1);
        assertThat(ClassCapacityPlanner.message(shortages))
                .contains("高二1班").contains("39").contains("38").contains("1");
    }

    @Test
    void overlappingRequirementsDeductTheSameSlotOnce() {
        List<Slot> slots = fortySlots();
        ConstraintSettings settings = settings(
                group("a", null, 1, 1),
                group("b", null, 1, 1),
                group("c", null, 1, 2));

        List<Load> loads = ClassCapacityPlanner.analyse(
                slots, Map.of(CLASS_ID, 39), Map.of(CLASS_ID, "高二1班"), settings);

        assertThat(loads).singleElement().satisfies(load -> {
            assertThat(load.available()).isEqualTo(38);
            assertThat(load.shortage()).isEqualTo(1);
        });
    }

    @Test
    void aClassThatFitsProducesNoShortage() {
        // Two overlapping requirements that hit the same slot leave 39 of 40 positions.
        List<Load> shortages = ClassCapacityPlanner.shortages(
                fortySlots(), Map.of(CLASS_ID, 39), Map.of(CLASS_ID, "高二1班"),
                settings(group("a", null, 1, 1), group("b", null, 1, 1)));

        assertThat(shortages).isEmpty();
    }

    @Test
    void selectedClassBanOnlyReducesThatClass() {
        ConstraintSettings settings = settings(group("a", CLASS_ID, 1, 1));
        List<Slot> slots = fortySlots();

        List<Load> loads = ClassCapacityPlanner.analyse(
                slots, Map.of(CLASS_ID, 40, 22L, 40),
                Map.of(CLASS_ID, "高二1班", 22L, "高二2班"), settings);

        assertThat(loads).extracting(Load::classId).containsExactly(CLASS_ID, 22L);
        assertThat(loads).extracting(Load::available).containsExactly(39, 40);
    }

    @Test
    void aSemesterWithoutPeriodsHasZeroCapacity() {
        List<Load> shortages = ClassCapacityPlanner.shortages(
                List.of(), Map.of(CLASS_ID, 5), Map.of(CLASS_ID, "高二1班"),
                settings(group("a", null, 1, 1)));

        assertThat(shortages).singleElement().satisfies(load -> {
            assertThat(load.required()).isEqualTo(5);
            assertThat(load.available()).isZero();
            assertThat(load.shortage()).isEqualTo(5);
        });
    }

    private static List<Slot> fortySlots() {
        List<Slot> slots = new ArrayList<>();
        for (int day = 1; day <= 5; day++) {
            for (int periodNo = 1; periodNo <= 8; periodNo++) {
                slots.add(new Slot(day, periodNo));
            }
        }
        return slots;
    }

    private static ConstraintEntry group(
            String groupKey, Long classId, int dayOfWeek, int periodNo) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (classId != null) {
            params.put("classId", classId);
        }
        params.put("dayOfWeek", dayOfWeek);
        params.put("periodNo", periodNo);

        ConstraintEntry entry = new ConstraintEntry();
        entry.setType(ConstraintType.CLASS_UNAVAILABLE.name());
        entry.setEnabled(true);
        entry.setGroupKey(groupKey);
        entry.setParamsJson(write(params));
        return entry;
    }

    private static ConstraintSettings settings(ConstraintEntry... entries) {
        ConstraintConfig config = new ConstraintConfig();
        config.setType(ConstraintType.CLASS_UNAVAILABLE.name());
        config.setEnabled(true);
        config.setHard(true);
        config.setWeight(100);
        config.setParamsJson("{}");
        List<ConstraintEntry> rows = new ArrayList<>();
        long id = 1;
        for (ConstraintEntry entry : entries) {
            entry.setId(id++);
            rows.add(entry);
        }
        return ConstraintSettings.from(List.of(config), rows, MAPPER);
    }

    private static String write(Map<String, Object> params) {
        try {
            return MAPPER.writeValueAsString(params);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
