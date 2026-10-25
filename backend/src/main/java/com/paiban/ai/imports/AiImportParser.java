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
        // A model that answers one row per line, or a single row object, is understood as
        // the rows it clearly meant - the contract stays, the tolerance is in reading it.
        JsonNode rowsNode = root.path("rows");
        List<RowDto> rows = parseRows(rowsNode.isArray() ? rowsNode : root, target, threshold);
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
        String cleaned = stripFence(content.trim());
        JsonNode single = readObject(cleaned, true);
        if (single != null) {
            return single;
        }
        JsonNode array = readArray(cleaned);
        if (array != null) {
            return rowsRoot(array);
        }
        JsonNode many = readLineByLine(cleaned);
        if (many != null) {
            return rowsRoot(many);
        }
        // Last resort: one object with prose around it, the shape the old parser accepted.
        JsonNode lenient = readObject(cleaned, false);
        if (lenient != null) {
            return lenient;
        }
        throw new IllegalArgumentException("无法解析 AI 返回的 JSON");
    }

    private String stripFence(String cleaned) {
        if (!cleaned.startsWith("```")) {
            return cleaned;
        }
        int firstNewline = cleaned.indexOf('\n');
        int lastFence = cleaned.lastIndexOf("```");
        return firstNewline >= 0 && lastFence > firstNewline
                ? cleaned.substring(firstNewline + 1, lastFence).trim()
                : cleaned;
    }

    /**
     * {@code strict} rejects trailing content, so one JSON object per line is not silently
     * read as its first object only; the lenient pass (prose after the JSON) comes last.
     */
    private JsonNode readObject(String cleaned, boolean strict) {
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        String candidate = cleaned.substring(start, end + 1);
        try {
            com.fasterxml.jackson.databind.ObjectReader reader = strict
                    ? objectMapper.reader().with(
                            com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    : objectMapper.reader();
            JsonNode node = reader.readTree(candidate);
            return node != null && node.isObject() ? node : null;
        } catch (Exception ex) {
            return null;
        }
    }

    private JsonNode readArray(String cleaned) {
        int start = cleaned.indexOf('[');
        int end = cleaned.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(cleaned.substring(start, end + 1));
            return node != null && node.isArray() ? node : null;
        } catch (Exception ex) {
            return null;
        }
    }

    /** One JSON object per line, the shape weak models fall back to for tabular answers. */
    private JsonNode readLineByLine(String cleaned) {
        com.fasterxml.jackson.databind.node.ArrayNode rows = objectMapper.createArrayNode();
        for (String line : cleaned.split("\n")) {
            String candidate = line.trim();
            if (!candidate.startsWith("{")) {
                continue;
            }
            int end = candidate.lastIndexOf('}');
            if (end < 0) {
                continue;
            }
            try {
                JsonNode node = objectMapper.readTree(candidate.substring(0, end + 1));
                if (node != null && node.isObject()) {
                    rows.add(node);
                }
            } catch (Exception ignored) {
                // A line that is not JSON (prose around the data) is simply not a row.
            }
        }
        return rows.isEmpty() ? null : rows;
    }

    private JsonNode rowsRoot(JsonNode rows) {
        com.fasterxml.jackson.databind.node.ObjectNode root = objectMapper.createObjectNode();
        root.set("rows", rows);
        return root;
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
        List<JsonNode> items = new ArrayList<>();
        if (node.isArray()) {
            node.forEach(items::add);
        } else if (node.isObject()) {
            items.add(node);
        }
        for (JsonNode item : items) {
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
