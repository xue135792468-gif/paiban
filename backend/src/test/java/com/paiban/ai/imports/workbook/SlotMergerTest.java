package com.paiban.ai.imports.workbook;

import static org.assertj.core.api.Assertions.assertThat;

import com.paiban.ai.imports.ImportDtos.RowDto;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SlotMergerTest {

    @Test
    void completeRowWinsOverIncompleteDuplicate() {
        SlotMerger.Merge merge = SlotMerger.merge(List.of(
                kept("数据源", 2, "八（1）", "星期一", "1", "语文", ""),
                kept("班级课表", 8, "八（1）", "星期一", "1", "语文", "张三")));

        assertThat(merge.kept()).hasSize(1);
        assertThat(merge.kept().get(0).row().values()).containsEntry("courseName", "语文");
        assertThat(merge.droppedIncomplete()).isEqualTo(1);
        assertThat(merge.errors()).isEmpty();
    }

    @Test
    void freePeriodIsSkippedAndCounted() {
        SlotMerger.Merge merge = SlotMerger.merge(List.of(
                kept("数据源", 20, "八（1）", "星期五", "7", "", "")));

        assertThat(merge.kept()).isEmpty();
        assertThat(merge.freeSlots()).isEqualTo(1);
        assertThat(merge.errors()).isEmpty();
        assertThat(merge.perSheet().get("数据源").freeSlots()).isEqualTo(1);
    }

    @Test
    void aLoneIncompleteRowBlocks() {
        SlotMerger.Merge merge = SlotMerger.merge(List.of(
                kept("数据源", 3, "八（1）", "星期一", "1", "语文", "")));

        assertThat(merge.kept()).isEmpty();
        assertThat(merge.errors()).hasSize(1);
        assertThat(merge.errors().get(0).message()).contains("八（1）").contains("第 3 行");
    }

    @Test
    void duplicateCompleteRowsCollapseToOne() {
        SlotMerger.Merge merge = SlotMerger.merge(List.of(
                kept("数据源", 2, "八（1）", "星期一", "1", "语文", "张三"),
                kept("教师课表", 5, "八（1）", "周一", "第1节", "语文", "张三")));

        assertThat(merge.kept()).hasSize(1);
        assertThat(merge.duplicates()).isEqualTo(1);
    }

    @Test
    void anEmptyDuplicateOfALessonSlotStillCountsAsAFreePeriod() {
        SlotMerger.Merge merge = SlotMerger.merge(List.of(
                kept("班级课表", 2, "八（1）", "星期一", "1", "语文", "张三"),
                kept("数据源", 30, "八（1）", "星期一", "1", "", "")));

        assertThat(merge.kept()).hasSize(1);
        assertThat(merge.freeSlots()).isEqualTo(1);
        assertThat(merge.perSheet().get("数据源").freeSlots()).isEqualTo(1);
    }

    @Test
    void sameClassDifferentDayStaysSeparate() {
        SlotMerger.Merge merge = SlotMerger.merge(List.of(
                kept("数据源", 2, "八（1）", "星期一", "1", "语文", "张三"),
                kept("数据源", 3, "八（1）", "星期二", "1", "语文", "张三")));

        assertThat(merge.kept()).hasSize(2);
    }

    private static SlotMerger.Kept kept(
            String sheet, int rowNumber, String className, String day, String period,
            String course, String teacher) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("className", className);
        values.put("dayOfWeek", day);
        values.put("periodNo", period);
        values.put("courseName", course);
        values.put("teacherName", teacher);
        values.put("roomName", "");
        return new SlotMerger.Kept(sheet, rowNumber, new RowDto(values, Map.of(), List.of()));
    }
}
