package com.paiban.ai.imports;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AiImportPromptsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void gridPromptSendsTheSheetNameAndEveryGridCell() {
        List<List<String>> grid = List.of(
                List.of("2026 春季课表"),
                List.of("班级", "星期一", "星期二"),
                List.of("一年级1班", "语文", "数学"));

        String prompt = AiImportPrompts.gridPrompt(ImportTarget.TEACHER, "教师名单", grid, objectMapper);

        assertThat(prompt)
                .contains("\"target\":\"teacher\"")
                .contains("\"sheetName\":\"教师名单\"")
                .contains("2026 春季课表")
                .contains("一年级1班")
                .contains("语文")
                .contains("原始网格")
                .contains("合并单元格")
                .contains("前几行可能是标题");
    }

    @Test
    void timetableGridPromptDescribesOneLessonPerRow() {
        String prompt = AiImportPrompts.gridPrompt(
                ImportTarget.TIMETABLE, "一年级课表", List.of(List.of("一年级1班", "周一")), objectMapper);

        assertThat(prompt)
                .contains("\"target\":\"timetable\"")
                .contains("每一行代表一节课")
                .contains("className")
                .contains("dayOfWeek")
                .contains("periodNo")
                .contains("courseName")
                .contains("teacherName")
                .contains("roomName")
                .doesNotContain("姓名");
    }

    @Test
    void entityGridPromptDescribesOneObjectPerRow() {
        String prompt = AiImportPrompts.gridPrompt(
                ImportTarget.TEACHER, "教师名单", List.of(List.of("张三")), objectMapper);

        assertThat(prompt)
                .contains("每一行代表一个对象")
                .doesNotContain("每一行代表一节课");
    }

    @Test
    void textPromptIsGeneratedFromTheTargetFields() {
        String teacherPrompt = AiImportPrompts.textPrompt(ImportTarget.TEACHER, "张三教语文");
        String timetablePrompt = AiImportPrompts.textPrompt(ImportTarget.TIMETABLE, "一年级1班周一第一节语文");

        assertThat(teacherPrompt)
                .contains("name（姓名，必填）")
                .contains("subject（学科，可空）")
                .doesNotContain("教师名单");
        assertThat(timetablePrompt)
                .contains("className（班级，必填）")
                .contains("periodNo（节次，必填）")
                .doesNotContain("教师名单")
                .doesNotContain("姓名");
    }

    @Test
    void imagePromptIsGeneratedFromTheTargetFields() {
        String prompt = AiImportPrompts.imagePrompt(ImportTarget.TIMETABLE);

        assertThat(prompt)
                .contains("目标对象：timetable")
                .contains("className（班级，必填）")
                .contains("roomName（教室，可空）")
                .doesNotContain("教师名单")
                .doesNotContain("姓名");
    }

    @Test
    void systemPromptListsTheTargetFields() {
        String prompt = AiImportPrompts.system(ImportTarget.TIMETABLE);

        assertThat(prompt)
                .contains("目标对象：timetable")
                .contains("className（班级，必填）")
                .contains("dayOfWeek（星期，必填）")
                .contains("periodNo（节次，必填）")
                .contains("courseName（课程，必填）")
                .contains("teacherName（教师，必填）")
                .contains("roomName（教室，可空）")
                .doesNotContain("employeeNo");
    }

    @Test
    void systemPromptOffersAnOptionalSuggestedTarget() {
        String prompt = AiImportPrompts.system(ImportTarget.TEACHER);

        assertThat(prompt)
                .contains("\"suggestedTarget\":\"teacher|timetable\"")
                .contains("只能填 teacher 或 timetable")
                .contains("不确定就留空");
    }

    @Test
    void gridPromptStopsAtFiveHundredRows() {
        List<List<String>> grid = new ArrayList<>();
        for (int r = 1; r <= 505; r++) {
            grid.add(List.of("R" + r));
        }

        String prompt = AiImportPrompts.gridPrompt(ImportTarget.TEACHER, "大表", grid, objectMapper);

        assertThat(prompt).contains("R500").doesNotContain("R501");
    }

    @Test
    void gridPromptStopsAtSixtyColumns() {
        List<String> row = new ArrayList<>();
        for (int c = 1; c <= 65; c++) {
            row.add("C" + c);
        }

        String prompt = AiImportPrompts.gridPrompt(ImportTarget.TEACHER, "宽表", List.of(row), objectMapper);

        assertThat(prompt).contains("C60").doesNotContain("C61");
    }

    @Test
    void chunkPromptKeepsTheHeaderContextAndAsksOnlyForTheChunkRows() {
        String prompt = AiImportPrompts.chunkPrompt(
                ImportTarget.TIMETABLE,
                "一年级课表",
                List.of(List.of("2026 春季课表"), List.of("班级", "星期一")),
                List.of(List.of("一年级1班", "语文")),
                0,
                14,
                objectMapper);

        assertThat(prompt)
                .contains("\"chunk\":\"1/14\"")
                .contains("\"headerContext\"")
                .contains("\"rowsToParse\"")
                .contains("不要输出其中的内容")
                .contains("每行一节课")
                .contains("一年级1班");
    }

    @Test
    void gridPromptSerializesTheMaximumGridQuickly() {
        List<List<String>> grid = new ArrayList<>();
        for (int r = 0; r < 500; r++) {
            List<String> row = new ArrayList<>();
            for (int c = 0; c < 60; c++) {
                row.add("R" + r + "C" + c);
            }
            grid.add(row);
        }

        long started = System.nanoTime();
        String prompt = AiImportPrompts.gridPrompt(ImportTarget.TIMETABLE, "大表", grid, objectMapper);
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

        assertThat(prompt).contains("R0C0").contains("R499C59");
        // Generous budget: the point is that a maximum-size sheet is serialized at all,
        // not that a build machine's timing is pinned.
        assertThat(elapsedMillis).isLessThan(5_000L);
    }
}
