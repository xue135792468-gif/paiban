package com.paiban.scheduling.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Lenient reader for the model's proposal answer (ticket 14/08).
 *
 * <p>The prompt asks for one JSON object with a {@code proposals} array, but real
 * providers add markdown fences or answer a bare array. Only the envelope is softened
 * here; every proposal's content is validated against the catalogue afterwards, so a
 * lenient read never turns a bad answer into a saveable suggestion.
 */
@Component
public class ConstraintProposalParser {

    private final ObjectMapper mapper;

    public ConstraintProposalParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public List<JsonNode> parse(String content) {
        String text = content == null ? "" : content.trim();
        JsonNode proposals = readProposals(text);
        List<JsonNode> rows = new ArrayList<>();
        proposals.forEach(rows::add);
        return rows;
    }

    private JsonNode readProposals(String text) {
        Exception failure = null;
        try {
            JsonNode root = mapper.readTree(extractJson(text));
            JsonNode proposals = root.isArray() ? root : root.path("proposals");
            if (proposals.isArray()) {
                return proposals;
            }
        } catch (Exception ex) {
            failure = ex;
        }
        // Some providers emit one JSON object per line (NDJSON). Wrapping the lines in
        // an array keeps that answer usable without guessing its meaning.
        List<String> lines = text.lines()
                .map(String::trim)
                .filter(line -> line.startsWith("{") && line.endsWith("}"))
                .toList();
        if (lines.size() > 1) {
            try {
                JsonNode array = mapper.readTree("[" + String.join(",", lines) + "]");
                if (array.isArray()) {
                    return array;
                }
            } catch (Exception ignored) {
                // fall through to the envelope error
            }
        }
        if (failure != null) {
            throw new IllegalArgumentException("模型输出不是合法 JSON", failure);
        }
        throw new IllegalArgumentException("模型输出缺少 proposals 数组");
    }

    /** Strips markdown fences and the prose some providers put around the JSON. */
    private String extractJson(String text) {
        String stripped = text;
        int fence = text.indexOf("```");
        if (fence >= 0) {
            int start = text.indexOf('\n', fence);
            int end = text.indexOf("```", fence + 3);
            if (start >= 0 && end > start) {
                stripped = text.substring(start + 1, end).trim();
            }
        }
        int object = stripped.indexOf('{');
        int array = stripped.indexOf('[');
        int start;
        if (object < 0) {
            start = array;
        } else if (array < 0) {
            start = object;
        } else {
            start = Math.min(object, array);
        }
        if (start < 0) {
            return stripped;
        }
        char open = stripped.charAt(start);
        char close = open == '{' ? '}' : ']';
        int end = stripped.lastIndexOf(close);
        return end > start ? stripped.substring(start, end + 1) : stripped.substring(start);
    }
}
