package com.paiban.integration.feishu;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** The card payload is pure data: no Spring, no network, no clock. */
class FeishuCardBuilderTest {

    private static final String BASE_URL = "https://paiban.example.com";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final FeishuCardBuilder builder = new FeishuCardBuilder(JSON, BASE_URL);

    private JsonNode buildCard(String templateCode, String link) throws Exception {
        FeishuMessage message = new FeishuMessage(
                "ou-001", "fs-001", "调代申请已批准", "**课程**：数学", link, true, templateCode);
        return JSON.readTree(builder.build(message));
    }

    @Test
    void buildsInteractiveCardWithTitleMarkdownBodyAndButton() throws Exception {
        JsonNode card = buildCard("SUBSTITUTION_EFFECTIVE_ORIGINAL", "/substitutions?requestId=7");

        assertThat(card.at("/header/title/content").asText()).isEqualTo("调代申请已批准");
        assertThat(card.at("/header/template").asText()).isEqualTo("green");
        assertThat(card.at("/elements/0/text/tag").asText()).isEqualTo("lark_md");
        assertThat(card.at("/elements/0/text/content").asText()).isEqualTo("**课程**：数学");
        JsonNode button = card.at("/elements/1/actions/0");
        assertThat(button.at("/tag").asText()).isEqualTo("button");
        assertThat(button.at("/text/content").asText()).isEqualTo("查看详情");
        assertThat(button.at("/url").asText()).isEqualTo(BASE_URL + "/substitutions?requestId=7");
    }

    @Test
    void colorsHeaderByTemplateCode() throws Exception {
        assertThat(buildCard("SUBSTITUTION_EFFECTIVE_SUBSTITUTE", null).at("/header/template").asText())
                .isEqualTo("green");
        assertThat(buildCard("SUBSTITUTION_REJECTED", null).at("/header/template").asText())
                .isEqualTo("red");
        assertThat(buildCard("TIMETABLE_PUBLISHED", null).at("/header/template").asText())
                .isEqualTo("blue");
        assertThat(buildCard("SOMETHING_ELSE", null).at("/header/template").asText())
                .isEqualTo("blue");
        assertThat(buildCard(null, null).at("/header/template").asText())
                .isEqualTo("blue");
    }

    @Test
    void omitsButtonWhenThereIsNoLink() throws Exception {
        JsonNode card = buildCard("SUBSTITUTION_EFFECTIVE_ORIGINAL", null);

        assertThat(card.at("/elements/0/text/tag").asText()).isEqualTo("lark_md");
        assertThat(card.at("/elements/1").isMissingNode()).isTrue();
    }

    @Test
    void textMessageAppendsTheAbsoluteLink() throws Exception {
        FeishuMessage message = new FeishuMessage(
                "ou-001", "fs-001", "课表已发布", "新学期课表已发布", "/timetable", false, "TIMETABLE_PUBLISHED");

        JsonNode content = JSON.readTree(builder.build(message));

        assertThat(content.at("/text").asText())
                .isEqualTo("新学期课表已发布\n" + BASE_URL + "/timetable");
    }

    @Test
    void absoluteLinkIsNotPrefixedTwice() throws Exception {
        JsonNode card = buildCard("SUBSTITUTION_EFFECTIVE_ORIGINAL", "https://other.example.com/x");

        assertThat(card.at("/elements/1/actions/0/url").asText()).isEqualTo("https://other.example.com/x");
    }
}
