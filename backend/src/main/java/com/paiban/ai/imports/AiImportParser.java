package com.paiban.ai.imports;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paiban.ai.imports.ImportDtos.MappingDto;
import com.paiban.ai.imports.ImportDtos.RowDto;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Parses the model's structured answer. Tolerates Markdown fences and both the
 * documented {@code rows[].values} shape and a flat object-per-row shape.
 */
@Component
public class AiImportParser {

    private static final int MAX_ROWS = 5000;
    private static final int MAX_VALUE = 2000;

    private final ObjectMapper objectMapper;

    public AiImportParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public record Parsed(
            List<MappingDto> mappings, List<RowDto> rows, String explanation, String suggestedTarget) {}

    public Parsed parse(String content, ImportTarget target, double threshold) {
        return parse(content, target, threshold, false);
    }

    /**
     * {@code allowEmpty} is for one batch of a larger table: a sheet can end on rows the
     * model has nothing to say about, and an empty answer there is not a broken answer.
     * A whole-table parse keeps requiring at least one row.
     */
    public Parsed parse(String content, ImportTarget target, double threshold, boolean allowEmpty) {
        JsonNode root = readRoot(content);
        List<MappingDto> mappings = parseMappings(root.path("mappings"));
        List<RowDto> rows = parseRows(root.path("rows"), target, threshold);
        if (rows.isEmpty() && !allowEmpty) {
            throw new IllegalArgumentException("AI 未返回任何数据行，请打回重析或检查原始数据");
        }
        String explanation = root.path("explanation").asText("");
        String suggestedTarget = ImportTarget.codeOrEmpty(root.path("suggestedTarget").asText(""));
        return new Parsed(mappings, rows, explanation, suggestedTarget);
    }

    private JsonNode readRoot(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("AI 返回内容为空");
        }
        String cleaned = content.trim();
        if (cleaned.startsWith("```")) {
            int firstNewline = cleaned.indexOf('\n');
            int lastFence = cleaned.lastIndexOf("```");
            if (firstNewline >= 0 && lastFence > firstNewline) {
                cleaned = cleaned.substring(firstNewline + 1, lastFence).trim();
            }
        }
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("AI 返回的不是 JSON 对象");
        }
        try {
            JsonNode node = objectMapper.readTree(cleaned.substring(start, end + 1));
            if (node == null || !node.isObject()) {
                throw new IllegalArgumentException("AI 返回的 JSON 结构不正确");
            }
            return node;
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("无法解析 AI 返回的 JSON");
        }
    }

    private List<MappingDto> parseMappings(JsonNode node) {
        List<MappingDto> mappings = new ArrayList<>();
        if (!node.isArray()) {
            return mappings;
        }
        for (JsonNode item : node) {
            String source = item.path("source").asText("");
            String target = item.path("target").asText("");
            String note = item.path("note").asText("");
            if (!source.isBlank() || !target.isBlank()) {
                mappings.add(new MappingDto(source, target, note));
            }
        }
        return mappings;
    }

    private List<RowDto> parseRows(JsonNode node, ImportTarget target, double threshold) {
        List<RowDto> rows = new ArrayList<>();
        if (!node.isArray()) {
            return rows;
        }
        for (JsonNode item : node) {
            if (rows.size() >= MAX_ROWS) {
                break;
            }
            JsonNode valuesNode = item.path("values");
            if (!valuesNode.isObject()) {
                valuesNode = item;
            }
            JsonNode confidenceNode = item.path("confidence");
            Map<String, String> values = new LinkedHashMap<>();
            Map<String, Double> confidence = new LinkedHashMap<>();
            List<String> lowConfidence = new ArrayList<>();
            for (String field : target.fieldNames()) {
                String value = text(valuesNode.path(field));
                double score = score(confidenceNode.path(field).isNumber() ? confidenceNode.path(field).asDouble() : 1.0);
                values.put(field, value);
                confidence.put(field, score);
                if (score < threshold) {
                    lowConfidence.add(field);
                }
            }
            if (values.values().stream().allMatch(String::isBlank)) {
                continue;
            }
            rows.add(new RowDto(values, confidence, lowConfidence));
        }
        return rows;
    }

    private String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        String value = node.asText();
        value = value == null ? "" : value.trim();
        return value.length() <= MAX_VALUE ? value : value.substring(0, MAX_VALUE);
    }

    private double score(double value) {
        return Math.max(0, Math.min(1, value));
    }
}
