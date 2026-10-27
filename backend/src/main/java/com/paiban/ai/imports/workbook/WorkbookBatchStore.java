package com.paiban.ai.imports.workbook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paiban.ai.imports.ImportDtos.RowDto;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.util.StringUtils;

/**
 * The per-batch record of a workbook extraction, stored in {@code batches_json}.
 *
 * <p>A workbook batch is not a class row like the single-target planner's: it is a slice of
 * one sheet (a data range) read under one confirmed structure. Its identity therefore carries
 * the sheet, the 1-based first data row and the plan version - two runs of the same file are
 * only compatible when all three match. Legacy "one class per batch" records have none of
 * these fields and are read as unusable instead of being mistaken for workbook batches.
 */
final class WorkbookBatchStore {

    static final String PENDING = "PENDING";
    static final String DONE = "DONE";
    static final String FAILED = "FAILED";

    private final ObjectMapper objectMapper;

    WorkbookBatchStore(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** One planned slice: the projected cells the model will be asked to copy. */
    record Work(
            String key,
            int index,
            String sheet,
            String category,
            Map<String, String> columns,
            int startRow,
            int planVersion,
            int totalRows,
            List<List<String>> sourceRows) {}

    /** What one batch produced: copied rows, or the reason the model could not read it. */
    record Outcome(List<RowDto> rows, String reason) {

        static Outcome parsed(List<RowDto> rows) {
            return new Outcome(rows, null);
        }

        static Outcome failed(String reason) {
            return new Outcome(List.of(), reason);
        }
    }

    /** One batch's persisted state, read back from and written to {@code batches_json}. */
    static final class State {
        String key;
        int index;
        String sheet;
        String category;
        Map<String, String> columns = new LinkedHashMap<>();
        int startRow;
        int planVersion;
        int totalRows;
        String status;
        String reason;
        List<List<String>> sourceRows = new ArrayList<>();
        List<RowDto> rows = new ArrayList<>();
        /**
         * Every answer this batch ever received, including the current one and late answers
         * from a run that was stopped or superseded. Kept so retrying cannot erase the audit.
         */
        List<String> rawAnswers = new ArrayList<>();

        static State pending(Work work) {
            State batch = new State();
            batch.key = work.key();
            batch.index = work.index();
            batch.sheet = work.sheet();
            batch.category = work.category();
            batch.columns = new LinkedHashMap<>(work.columns());
            batch.startRow = work.startRow();
            batch.planVersion = work.planVersion();
            batch.totalRows = work.totalRows();
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

        int rowCount() {
            return sourceRows.size();
        }

        String endRow() {
            int end = startRow + Math.max(0, rowCount() - 1);
            return startRow == end ? "第 " + startRow + " 行" : "第 " + startRow + "-" + end + " 行";
        }

        String label() {
            return "工作表「" + (sheet == null ? "" : sheet) + "」" + endRow();
        }
    }

    List<State> read(String json) {
        List<State> batches = new ArrayList<>();
        if (!StringUtils.hasText(json)) {
            return batches;
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            if (!root.isArray()) {
                return batches;
            }
            for (JsonNode node : root) {
                State batch = new State();
                batch.key = node.path("key").asText("");
                batch.index = node.path("index").asInt();
                batch.sheet = node.path("sheet").asText("");
                batch.category = node.path("category").asText("");
                JsonNode columns = node.path("columns");
                if (columns.isObject()) {
                    columns.fieldNames().forEachRemaining(field ->
                            batch.columns.put(field, columns.path(field).asText("")));
                }
                batch.startRow = node.path("startRow").asInt();
                batch.planVersion = node.path("planVersion").asInt();
                batch.totalRows = node.path("totalRows").asInt();
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
                for (JsonNode raw : node.path("rawAnswers")) {
                    String answer = raw.asText("");
                    if (StringUtils.hasText(answer)) {
                        batch.rawAnswers.add(answer);
                    }
                }
                // A legacy "class per batch" record has no key/sheet/startRow. It cannot be
                // replayed under a workbook plan, so it is surfaced as unusable rather than
                // silently treated as batch number N of the new plan.
                if (!StringUtils.hasText(batch.key) || !StringUtils.hasText(batch.sheet)) {
                    batch.key = "legacy-" + batch.index;
                    batch.status = FAILED;
                    batch.reason = "旧格式批次记录，无法按工作簿计划重试，请重新规划";
                    batch.sourceRows = new ArrayList<>();
                }
                batches.add(batch);
            }
        } catch (Exception ex) {
            throw new IllegalStateException("读取工作簿批次记录失败", ex);
        }
        return batches;
    }

    String write(List<State> batches) {
        List<Map<String, Object>> payload = new ArrayList<>();
        for (State batch : batches) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("key", batch.key == null ? "" : batch.key);
            item.put("index", batch.index);
            item.put("sheet", batch.sheet == null ? "" : batch.sheet);
            item.put("category", batch.category == null ? "" : batch.category);
            item.put("columns", batch.columns);
            item.put("startRow", batch.startRow);
            item.put("planVersion", batch.planVersion);
            item.put("totalRows", batch.totalRows);
            item.put("status", batch.status);
            if (batch.reason != null) {
                item.put("reason", batch.reason);
            }
            item.put("sourceRows", batch.sourceRows);
            if (!batch.rows.isEmpty()) {
                item.put("rows", batch.rows);
            }
            if (!batch.rawAnswers.isEmpty()) {
                item.put("rawAnswers", batch.rawAnswers);
            }
            payload.add(item);
        }
        return writeJson(payload);
    }

    State find(List<State> batches, String key) {
        for (State batch : batches) {
            if (batch.key != null && batch.key.equals(key)) {
                return batch;
            }
        }
        return null;
    }

    /** Records one finished attempt; the batch keeps every answer it ever received. */
    void replace(State batch, Outcome outcome, String rawResponse) {
        batch.status = outcome.reason() == null ? DONE : FAILED;
        batch.reason = outcome.reason();
        batch.rows = outcome.rows() == null ? List.of() : List.copyOf(outcome.rows());
        if (StringUtils.hasText(rawResponse)) {
            batch.rawAnswers.add(rawResponse);
        }
    }

    /** A late answer is audit only: the batch status, rows and preview never change. */
    void recordLateAnswer(List<State> batches, String key, String rawResponse) {
        State batch = find(batches, key);
        if (batch == null || !StringUtils.hasText(rawResponse)) {
            return;
        }
        if (!batch.rawAnswers.contains(rawResponse)) {
            batch.rawAnswers.add(rawResponse);
        }
    }

    /** Puts a failed batch back into the queue of a retry, keeping its earlier audit. */
    void resetForRetry(State batch) {
        batch.status = PENDING;
        batch.reason = null;
        batch.rows = List.of();
    }

    int doneCount(List<State> batches) {
        return (int) batches.stream().filter(State::done).count();
    }

    int failedCount(List<State> batches) {
        return (int) batches.stream().filter(State::failed).count();
    }

    /** Every batch answer ever received, in the plan's sheet/range order. */
    List<String> rawAnswers(List<State> batches) {
        return batches.stream()
                .sorted(java.util.Comparator.comparingInt(batch -> batch.index))
                .flatMap(batch -> batch.rawAnswers.stream())
                .toList();
    }

    /** The failed ranges a user can retry, with the batch's 1-based position in the plan. */
    String failureSummary(List<State> batches, int totalBatches) {
        List<String> parts = new ArrayList<>();
        for (State batch : batches) {
            if (batch.failed()) {
                parts.add(failureText(batch, totalBatches));
            }
        }
        return String.join("；", parts);
    }

    String failureText(State batch, int totalBatches) {
        String reason = !StringUtils.hasText(batch.reason) ? "未知错误" : batch.reason;
        return "第 " + batch.index + "/" + totalBatches + " 批（" + batch.label() + "）解析失败：" + reason;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("保存工作簿批次失败", ex);
        }
    }
}
