package com.paiban.ai.imports;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class AiImportParserTest {

    private final AiImportParser parser = new AiImportParser(new ObjectMapper());

    private String answer(String suggestedTarget) {
        String suggestion = suggestedTarget == null
                ? ""
                : "\"suggestedTarget\":\"" + suggestedTarget + "\",";
        return "{" + suggestion
                + "\"mappings\":[],\"rows\":[{\"values\":{\"name\":\"张三\"},\"confidence\":{\"name\":0.9}}],"
                + "\"explanation\":\"看起来像教师名单\"}";
    }

    @Test
    void readsTheSuggestedTargetTheAiReports() {
        AiImportParser.Parsed parsed = parser.parse(answer("timetable"), ImportTarget.TEACHER, 0.7);

        assertThat(parsed.suggestedTarget()).isEqualTo("timetable");
        assertThat(parsed.rows()).hasSize(1);
    }

    @Test
    void anUnknownSuggestedTargetIsIgnoredInsteadOfFailingTheParse() {
        AiImportParser.Parsed parsed = parser.parse(answer("students"), ImportTarget.TEACHER, 0.7);

        assertThat(parsed.suggestedTarget()).isEmpty();
        assertThat(parsed.rows()).hasSize(1);
    }

    @Test
    void readsOneRowPerLineWhenTheModelAnswersThatWay() {
        String ndjson = "{\"className\":\"八（1）\",\"dayOfWeek\":\"星期一\",\"periodNo\":\"1\",\"courseName\":\"语文\",\"teacherName\":\"金林墨\"}"
                + "\n"
                + "{\"className\":\"八（1）\",\"dayOfWeek\":\"星期一\",\"periodNo\":\"2\",\"courseName\":\"数学\",\"teacherName\":\"周小媚\"}";

        AiImportParser.Parsed parsed = parser.parse(ndjson, ImportTarget.TIMETABLE, 0.7, true);

        assertThat(parsed.rows()).hasSize(2);
        assertThat(parsed.rows().get(1).values()).containsEntry("teacherName", "周小媚");
    }

    @Test
    void readsABareArrayOfRows() {
        String array = "[{\"className\":\"八（1）\",\"dayOfWeek\":\"星期一\",\"periodNo\":\"1\"}]";

        AiImportParser.Parsed parsed = parser.parse(array, ImportTarget.TIMETABLE, 0.7, true);

        assertThat(parsed.rows()).hasSize(1);
        assertThat(parsed.rows().get(0).values()).containsEntry("periodNo", "1");
    }

    @Test
    void readsASingleRowObject() {
        String single = "{\"name\":\"张三\",\"subject\":\"语文\"}";

        AiImportParser.Parsed parsed = parser.parse(single, ImportTarget.TEACHER, 0.7, true);

        assertThat(parsed.rows()).hasSize(1);
        assertThat(parsed.rows().get(0).values()).containsEntry("name", "张三");
    }

    @Test
    void aMissingSuggestedTargetStaysEmpty() {
        AiImportParser.Parsed parsed = parser.parse(answer(null), ImportTarget.TEACHER, 0.7);

        assertThat(parsed.suggestedTarget()).isEmpty();
    }
}
