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
    void aMissingSuggestedTargetStaysEmpty() {
        AiImportParser.Parsed parsed = parser.parse(answer(null), ImportTarget.TEACHER, 0.7);

        assertThat(parsed.suggestedTarget()).isEmpty();
    }
}
