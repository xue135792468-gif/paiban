package com.paiban.ai.imports.workbook;

import static org.assertj.core.api.Assertions.assertThat;

import com.paiban.ai.imports.workbook.WorkbookPlan.SheetCategory;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The deterministic fallback mapping: a packed column supplies class/day/period, the next
 * two columns course and teacher, and nothing is ever guessed as a room - a real export has
 * no reliable room column, and taking the next free one would match a teacher name as a room.
 */
class WorkbookPlanTest {

    @Test
    void headerlessTimetableMapsCourseAndTeacherButNeverARoom() {
        ProfiledSheet sheet = SheetProfiler.profile("数据源", List.of(
                List.of("八（1）|星期一|1", "语文", "金林墨", "语文  金林墨"),
                List.of("八（1）|星期一|2", "数学", "周小媚", "数学  周小媚"),
                List.of("八（1）|星期一|3", "英语", "虞文茜", "英语  虞文茜"),
                List.of("八（2）|星期二|1", "科学", "徐良贵", "科学  徐良贵")));

        Map<String, String> columns = WorkbookPlan.columns(sheet, SheetCategory.TIMETABLE);

        assertThat(columns).containsEntry("className", "列1·班级");
        assertThat(columns).containsEntry("dayOfWeek", "列1·星期");
        assertThat(columns).containsEntry("periodNo", "列1·节次");
        assertThat(columns).containsEntry("courseName", "列2");
        assertThat(columns).containsEntry("teacherName", "列3");
        assertThat(columns).doesNotContainKey("roomName");
    }
}
