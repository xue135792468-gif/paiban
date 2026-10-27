package com.paiban.scheduling.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Ticket 14/08: the envelope is lenient, the content is not. */
class ConstraintProposalParserTest {

    private final ConstraintProposalParser parser =
            new ConstraintProposalParser(new ObjectMapper());

    @Test
    void readsThePlainEnvelope() {
        List<JsonNode> rows = parser.parse("""
                {"proposals":[{"type":"COURSE_NOT_IN_EVENING"},{"type":"COURSE_SPREAD"}]}""");
        assertThat(rows).hasSize(2);
    }

    @Test
    void stripsMarkdownFencesAndSurroundingProse() {
        List<JsonNode> rows = parser.parse("""
                这是结果：
                ```json
                {"proposals":[{"type":"COURSE_SPREAD"}]}
                ```
                以上。""");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).path("type").asText()).isEqualTo("COURSE_SPREAD");
    }

    @Test
    void acceptsABareArray() {
        assertThat(parser.parse("[{\"type\":\"COURSE_SPREAD\"}]")).hasSize(1);
    }

    @Test
    void acceptsOneObjectPerLine() {
        List<JsonNode> rows = parser.parse("""
                {"type":"COURSE_SPREAD"}
                {"type":"COURSE_NOT_IN_EVENING"}""");
        assertThat(rows).hasSize(2);
    }

    @Test
    void refusesAnAnswerWithoutAProposalList() {
        assertThatThrownBy(() -> parser.parse("{\"answer\":\"nope\"}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("proposals");
        assertThatThrownBy(() -> parser.parse("完全不是 JSON"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
