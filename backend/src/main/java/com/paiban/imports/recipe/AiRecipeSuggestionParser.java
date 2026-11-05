package com.paiban.imports.recipe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paiban.imports.ImportDtos.AiRecipeSuggestion;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Lenient reader for the model's column-mapping answer (ticket 07).
 *
 * <p>The prompt asks for one bare JSON object, but providers add markdown fences or a
 * sentence before it, so the envelope is softened here. Field values are not guessed:
 * a non-integer column index or a non-array {@code ignoreColumns} is rejected with an
 * explanation, and the caller turns that into {@code success=false} plus the raw
 * answer, never a 500.
 */
@Component
public class AiRecipeSuggestionParser {

    private final ObjectMapper mapper;

    public AiRecipeSuggestionParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public AiRecipeSuggestion parse(String content) {
        String text = content == null ? "" : content.trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("模型没有返回内容");
        }
        JsonNode root = readObject(extractJson(text));
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("模型输出不是 JSON 对象");
        }
        return new AiRecipeSuggestion(
                textOf(root, "layout"),
                textOf(root, "sheetName"),
                integerOf(root, "headerRow"),
                textOf(root, "delimiter"),
                integerOf(root, "compositeColumn"),
                integerOf(root, "classColumn"),
                integerOf(root, "dayColumn"),
                integerOf(root, "periodColumn"),
                integerOf(root, "courseColumn"),
                integerOf(root, "teacherColumn"),
                integersOf(root, "ignoreColumns"),
                textOf(root, "reasoning"));
    }

    private JsonNode readObject(String json) {
        if (json == null) {
            return null;
        }
        try {
            return mapper.readTree(json);
        } catch (Exception ex) {
            throw new IllegalArgumentException("模型输出不是合法 JSON", ex);
        }
    }

    /** Strips markdown fences and prose around the first complete JSON object. */
    static String extractJson(String text) {
        String stripped = text;
        int fence = text.indexOf("```");
        if (fence >= 0) {
            int start = text.indexOf('\n', fence);
            int end = text.indexOf("```", fence + 3);
            if (start >= 0 && end > start) {
                stripped = text.substring(start + 1, end).trim();
            }
        }
        int start = stripped.indexOf('{');
        if (start < 0) {
            return stripped;
        }
        int end = matchingBrace(stripped, start);
        return end > start ? stripped.substring(start, end + 1) : stripped.substring(start);
    }

    /** Tracks string literals so a brace inside a Chinese sentence does not end early. */
    private static int matchingBrace(String text, int start) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int index = start; index < text.length(); index++) {
            char current = text.charAt(index);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == '"') {
                    inString = false;
                }
                continue;
            }
            if (current == '"') {
                inString = true;
            } else if (current == '{') {
                depth++;
            } else if (current == '}') {
                depth--;
                if (depth == 0) {
                    return index;
                }
            }
        }
        return text.lastIndexOf('}');
    }

    private String textOf(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull() || node.isContainerNode()) {
            return null;
        }
        String value = node.asText("").trim();
        return value.isEmpty() ? null : value;
    }

    private Integer integerOf(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isIntegralNumber()) {
            return node.asInt();
        }
        if (node.isTextual()) {
            String value = node.asText("").trim();
            if (value.isEmpty()) {
                return null;
            }
            try {
                return Integer.valueOf(value);
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("字段 " + field + " 不是整数：" + value);
            }
        }
        throw new IllegalArgumentException("字段 " + field + " 不是整数");
    }

    private List<Integer> integersOf(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new IllegalArgumentException("字段 " + field + " 不是数组");
        }
        List<Integer> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (item.isIntegralNumber()) {
                values.add(item.asInt());
            } else if (item.isTextual()) {
                try {
                    values.add(Integer.valueOf(item.asText().trim()));
                } catch (NumberFormatException ex) {
                    throw new IllegalArgumentException("字段 " + field + " 含有非整数项");
                }
            } else {
                throw new IllegalArgumentException("字段 " + field + " 含有非整数项");
            }
        }
        return List.copyOf(values);
    }
}
