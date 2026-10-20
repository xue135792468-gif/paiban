package com.paiban.integration.feishu;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns a {@link FeishuMessage} into the JSON string Feishu expects in the
 * {@code content} field of {@code POST /open-apis/im/v1/messages}.
 *
 * <p>Pure and deterministic: the only outside input is the public base URL used
 * to make relative links absolute, so the payload can be unit-tested without a
 * Feishu tenant.
 */
public class FeishuCardBuilder {

    static final String DETAIL_BUTTON = "查看详情";
    private static final String DEFAULT_TEMPLATE = "blue";

    private final ObjectMapper objectMapper;
    private final String publicBaseUrl;

    public FeishuCardBuilder(String publicBaseUrl) {
        this(new ObjectMapper(), publicBaseUrl);
    }

    public FeishuCardBuilder(ObjectMapper objectMapper, String publicBaseUrl) {
        this.objectMapper = objectMapper;
        this.publicBaseUrl = publicBaseUrl == null ? "" : publicBaseUrl;
    }

    /**
     * @throws IllegalStateException if the payload cannot be serialized; callers
     *                               report that as a failed send instead of
     *                               propagating it into business code
     */
    public String build(FeishuMessage message) {
        try {
            return message.card() ? card(message) : text(message);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("飞书消息序列化失败", ex);
        }
    }

    private String card(FeishuMessage message) throws JsonProcessingException {
        List<Object> elements = new ArrayList<>();
        elements.add(Map.of(
                "tag", "div",
                "text", Map.of("tag", "lark_md", "content", nullToEmpty(message.content()))));
        String url = absoluteLink(message.link());
        if (url != null) {
            elements.add(Map.of(
                    "tag", "action",
                    "actions", List.of(Map.of(
                            "tag", "button",
                            "text", Map.of("tag", "plain_text", "content", DETAIL_BUTTON),
                            "type", "primary",
                            "url", url))));
        }
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("config", Map.of("wide_screen_mode", true));
        card.put("header", Map.of(
                "title", Map.of("tag", "plain_text", "content", nullToEmpty(message.title())),
                "template", templateColor(message.templateCode())));
        card.put("elements", elements);
        return objectMapper.writeValueAsString(card);
    }

    private String text(FeishuMessage message) throws JsonProcessingException {
        String text = nullToEmpty(message.content());
        String url = absoluteLink(message.link());
        if (url != null) {
            text = text + "\n" + url;
        }
        return objectMapper.writeValueAsString(Map.of("text", text));
    }

    /** Approved/effective cards are green, rejected red, published (and unknown) blue. */
    String templateColor(String templateCode) {
        if (templateCode == null) {
            return DEFAULT_TEMPLATE;
        }
        String code = templateCode.toUpperCase(Locale.ROOT);
        if (code.contains("REJECTED")) {
            return "red";
        }
        if (code.contains("EFFECTIVE") || code.contains("APPROVED")) {
            return "green";
        }
        return DEFAULT_TEMPLATE;
    }

    private String absoluteLink(String link) {
        if (link == null || link.isBlank()) {
            return null;
        }
        return link.startsWith("http") ? link : publicBaseUrl + link;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
