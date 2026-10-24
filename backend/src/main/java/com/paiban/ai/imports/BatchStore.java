package com.paiban.ai.imports;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paiban.ai.imports.ImportDtos.MappingDto;
import com.paiban.ai.imports.ImportDtos.RowDto;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The per-batch record of a background parse: what is in {@code batches_json}, how the
 * finished batches merge into one preview, and the failure wording that names a class.
 * Kept apart from the import flow so a stop, a finished batch and the startup recovery
 * all fold a batch the same way instead of each carrying its own copy of "read, merge,
 * count, write".
 */
final class BatchStore {

    static final String PENDING = "PENDING";
    static final String DONE = "DONE";
    static final String FAILED = "FAILED";

    private final ObjectMapper objectMapper;

    BatchStore(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** One planned batch: the grid rows it covers and its 1-based position. */
    record Work(int index, String label, List<List<String>> sourceRows) {}

    /** What one batch produced: a parsed answer, or the reason it could not parse. */
    record Outcome(AiImportParser.Parsed parsed, String reason) {

        static Outcome parsed(AiImportParser.Parsed parsed) {
            return new Outcome(parsed, null);
        }

        static Outcome failed(String reason) {
            return new Outcome(null, reason);
        }
    }

    /** Every {@code DONE} batch folded into one de-duplicated preview payload. */
    record Merged(
            List<MappingDto> mappings, List<RowDto> rows, String explanation, String suggestedTarget) {}

    /** One batch's persisted state, read back from and written to {@code batches_json}. */
    static final class State {
        int index;
        String label;
        String status;
        String reason;
        List<List<String>> sourceRows = new ArrayList<>();
        List<RowDto> rows = new ArrayList<>();
        List<MappingDto> mappings = new ArrayList<>();
        String suggestedTarget;
        String explanation;
        /** This batch's own AI answer, so a retry cannot overwrite the first pass's audit. */
        String rawResponse;

        static State pending(Work work) {
            State batch = new State();
            batch.index = work.index();
            batch.label = work.label();
            batch.status = PENDING;
            batch.sourceRows = new ArrayList<>(work.sourceRows());
            return batch;
        }

        boolean done() {
            return DONE.equals(status);
        }

        boolean failed() {
            return FAILED.equals(status);
        }
    }

    List<State> read(String json) {
        List<State> batches = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return batches;
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            if (!root.isArray()) {
                return batches;
            }
            for (JsonNode node : root) {
                State batch = new State();
                batch.index = node.path("index").asInt();
                batch.label = node.path("label").asText("");
                batch.status = node.path("status").asText(PENDING);
                batch.reason = node.hasNonNull("reason") ? node.get("reason").asText("") : null;
                for (JsonNode row : node.path("sourceRows")) {
                    List<String> cells = new ArrayList<>();
                    for (JsonNode cell : row) {
                        cells.add(cell.isNull() ? "" : cell.asText(""));
                    }
                    batch.sourceRows.add(cells);
                }
                for (JsonNode row : node.path("rows")) {
                    batch.rows.add(objectMapper.convertValue(row, RowDto.class));
                }
                for (JsonNode mapping : node.path("mappings")) {
                    batch.mappings.add(new MappingDto(
                            mapping.path("source").asText(""),
                            mapping.path("target").asText(""),
                            mapping.path("note").asText("")));
                }
                batch.suggestedTarget =
                        node.hasNonNull("suggestedTarget") ? node.get("suggestedTarget").asText("") : null;
                batch.explanation =
                        node.hasNonNull("explanation") ? node.get("explanation").asText("") : null;
                batch.rawResponse =
                        node.hasNonNull("rawResponse") ? node.get("rawResponse").asText("") : null;
                batches.add(batch);
            }
        } catch (Exception ex) {
            throw new IllegalStateException("读取导入批次记录失败", ex);
        }
        return batches;
    }

    /**
     * The batch column is a reader for a later retry (it carries the source rows) and the
     * audit of what the AI answered for that batch ({@code rawResponse}), so it keeps its
     * documented shape: index, label, status, reason and the parsed result.
     */
    String write(List<State> batches) {
        List<Map<String, Object>> payload = new ArrayList<>();
        for (State batch : batches) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("index", batch.index);
            item.put("label", batch.label == null ? "" : batch.label);
            item.put("status", batch.status);
            if (batch.reason != null) {
                item.put("reason", batch.reason);
            }
            item.put("sourceRows", batch.sourceRows);
            if (!batch.rows.isEmpty()) {
                item.put("rows", batch.rows);
            }
            if (!batch.mappings.isEmpty()) {
                item.put("mappings", batch.mappings);
            }
            if (batch.explanation != null) {
                item.put("explanation", batch.explanation);
            }
            if (batch.rawResponse != null && !batch.rawResponse.isBlank()) {
                item.put("rawResponse", batch.rawResponse);
            }
            payload.add(item);
        }
        return writeJson(payload);
    }

    /** Merges every {@code DONE} batch into one de-duplicated preview payload. */
    Merged merge(List<State> batches) {
        List<RowDto> rows = new ArrayList<>();
        List<MappingDto> mappings = new ArrayList<>();
        Set<String> rowKeys = new HashSet<>();
        Set<String> mappingKeys = new HashSet<>();
        String explanation = "";
        String suggestedTarget = null;
        for (State batch : batches) {
            if (!batch.done()) {
                continue;
            }
            for (RowDto row : batch.rows) {
                if (rowKeys.add(rowKey(row))) {
                    rows.add(row);
                }
            }
            for (MappingDto mapping : batch.mappings) {
                if (mappingKeys.add(mapping.source() + "→" + mapping.target())) {
                    mappings.add(mapping);
                }
            }
            if (explanation.isBlank() && batch.explanation != null) {
                explanation = batch.explanation;
            }
            if (suggestedTarget == null && batch.suggestedTarget != null
                    && !batch.suggestedTarget.isBlank()) {
                suggestedTarget = batch.suggestedTarget;
            }
        }
        if (explanation.isBlank() && !batches.isEmpty()) {
            explanation = "分 " + batches.size() + " 批解析";
        }
        return new Merged(mappings, rows, explanation, suggestedTarget);
    }

    /** Records one finished batch's answer in place; a batch missing from the plan is added. */
    void replace(List<State> batches, Work work, Outcome outcome, String rawResponse) {
        State target = null;
        for (State batch : batches) {
            if (batch.index == work.index()) {
                target = batch;
                break;
            }
        }
        if (target == null) {
            target = State.pending(work);
            batches.add(target);
        }
        target.status = outcome.reason() == null ? DONE : FAILED;
        target.reason = outcome.reason();
        target.rows = outcome.parsed() == null ? List.of() : outcome.parsed().rows();
        target.mappings = outcome.parsed() == null ? List.of() : outcome.parsed().mappings();
        target.explanation = outcome.parsed() == null ? null : outcome.parsed().explanation();
        target.suggestedTarget = outcome.parsed() == null ? null : outcome.parsed().suggestedTarget();
        target.rawResponse = rawResponse;
    }

    /**
     * What the AI answered for this session, one entry per batch that got an answer, in the
     * file's batch order. The per-batch answers are stored in {@code batches_json} and this
     * column is rebuilt from them: a retry therefore adds its answer instead of replacing
     * the audit trail of the first pass.
     */
    List<String> rawAnswers(List<State> batches) {
        return batches.stream()
                .filter(batch -> batch.rawResponse != null && !batch.rawResponse.isBlank())
                .sorted(java.util.Comparator.comparingInt(batch -> batch.index))
                .map(batch -> batch.rawResponse)
                .toList();
    }

    int doneCount(List<State> batches) {
        return (int) batches.stream().filter(State::done).count();
    }

    int failedCount(List<State> batches) {
        return (int) batches.stream().filter(State::failed).count();
    }

    /** Every failed batch named the way a user reads it, oldest first. */
    String failureSummary(List<State> batches, int totalBatches) {
        List<String> parts = new ArrayList<>();
        for (State batch : batches) {
            if (batch.failed()) {
                parts.add(batchFailureText(batch, totalBatches));
            }
        }
        return String.join("；", parts);
    }

    /** {@code 第 2/14 批（八（1））解析失败：<原因>} */
    String batchFailureText(State batch, int totalBatches) {
        String label = batch.label == null || batch.label.isBlank() ? "" : "（" + batch.label + "）";
        String reason = batch.reason == null || batch.reason.isBlank() ? "未知错误" : batch.reason;
        return "第 " + batch.index + "/" + totalBatches + " 批" + label + "解析失败：" + reason;
    }

    /** The preview payload shape shared by the AI's own answer and the user's copy. */
    String payloadJson(
            List<MappingDto> mappings, List<RowDto> rows, String explanation, String suggestedTarget) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("suggestedTarget", suggestedTarget == null ? "" : suggestedTarget);
        payload.put("mappings", mappings);
        payload.put("rows", rows);
        payload.put("explanation", explanation == null ? "" : explanation);
        return writeJson(payload);
    }

    private String rowKey(RowDto row) {
        return String.join("\u0001",
                value(row, "className"), value(row, "dayOfWeek"), value(row, "periodNo"),
                value(row, "courseName"), value(row, "teacherName"), value(row, "roomName"));
    }

    private String value(RowDto row, String field) {
        if (row == null || row.values() == null) {
            return "";
        }
        String value = row.values().get(field);
        return value == null ? "" : value.trim();
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("保存导入批次失败", ex);
        }
    }
}
