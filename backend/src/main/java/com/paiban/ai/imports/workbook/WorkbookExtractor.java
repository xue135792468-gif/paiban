package com.paiban.ai.imports.workbook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paiban.ai.AiGateway;
import com.paiban.ai.AiMessage;
import com.paiban.ai.AiOptions;
import com.paiban.ai.AiRequest;
import com.paiban.ai.AiResult;
import com.paiban.ai.AiTaskType;
import com.paiban.ai.imports.AiImportParser;
import com.paiban.ai.imports.ImportDtos.RowDto;
import com.paiban.ai.imports.ImportTarget;
import com.paiban.ai.imports.SlotText;
import com.paiban.ai.imports.workbook.WorkbookPlan.SheetCategory;
import com.paiban.ai.imports.workbook.WorkbookPlan.SheetPlan;
import com.paiban.common.settings.SettingsService;
import com.paiban.common.web.ApiException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Copies values out of each sheet once the structure is confirmed. Every batch travels with
 * the same column mapping, so a file cannot produce two different readings of the same
 * column; the model only reads cells. Rows are batched (40 by default, configurable) so a
 * 560-row sheet needs 14 calls instead of one answer that would be truncated.
 *
 * <p>The plan is deterministic and separate from the calls: {@link #planBatches} says what
 * will be asked (and is persisted before the first call), {@link #extractBatch} asks it.
 * A retry replays a stored batch without re-reading the workbook or re-planning it.
 */
@Component
public class WorkbookExtractor {

    private static final int MAX_TOKENS = 8192;
    private static final int MAX_BATCH = 200;

    private final AiGateway gateway;
    private final ObjectMapper objectMapper;
    private final AiImportParser parser;
    private final SettingsService settings;
    private final int batchSize;

    public WorkbookExtractor(
            AiGateway gateway,
            ObjectMapper objectMapper,
            AiImportParser parser,
            SettingsService settings,
            @Value("${paiban.ai.import.batch-size:40}") int batchSize) {
        this.gateway = gateway;
        this.objectMapper = objectMapper;
        this.parser = parser;
        this.settings = settings;
        this.batchSize = Math.max(1, Math.min(MAX_BATCH, batchSize));
    }

    /**
     * One extraction batch. {@code key} is stable for the same sheet, data range and plan
     * version; {@code columns} maps a system field to the sheet's column label and is stored
     * with the batch so a retry needs neither the workbook nor the planning step.
     */
    public record BatchPlan(
            String key,
            int index,
            String sheet,
            SheetCategory category,
            Map<String, String> columns,
            int startRow,
            int planVersion,
            /** Total non-empty data rows in the sheet; the prompt keeps saying "第 x/y 行". */
            int totalRows,
            List<List<String>> rows) {

        public int rowCount() {
            return rows.size();
        }

        public String label() {
            int end = startRow + Math.max(0, rowCount() - 1);
            String range = startRow == end ? "第 " + startRow + " 行" : "第 " + startRow + "-" + end + " 行";
            return "工作表「" + sheet + "」" + range;
        }
    }

    /** What the deterministic plan produced: batches plus sheets that cannot be read. */
    public record Planning(List<BatchPlan> batches, List<String> warnings) {}

    /** One batch's answer. An empty row list means the model copied nothing. */
    public record BatchResult(List<RowDto> rows, String rawResponse) {}

    /**
     * Splits every contributing sheet into batches without calling the model. The batch
     * key includes the plan version, so batches from an older plan can never be mistaken
     * for this one's.
     */
    public Planning planBatches(List<ProfiledSheet> profiles, WorkbookPlan plan, int planVersion) {
        List<BatchPlan> batches = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (SheetPlan sheetPlan : plan.dataSheets()) {
            ProfiledSheet sheet = find(profiles, sheetPlan.sheet());
            if (sheet == null || sheet.skipped()) {
                continue;
            }
            List<List<String>> dataRows = new ArrayList<>();
            for (List<String> row : sheet.dataRows()) {
                if (HeaderDetection.nonEmpty(row) > 0) {
                    dataRows.add(row);
                }
            }
            if (dataRows.isEmpty()) {
                continue;
            }
            Map<String, Integer> columns = columns(sheet, sheetPlan);
            if (columns.isEmpty()) {
                warnings.add("工作表「" + sheet.name() + "」没有可用的列映射，已跳过");
                continue;
            }
            Map<String, String> labels = new LinkedHashMap<>();
            List<Integer> indices = new ArrayList<>();
            for (Map.Entry<String, Integer> entry : columns.entrySet()) {
                labels.put(entry.getKey(), sheet.labels().get(entry.getValue() - 1));
                indices.add(entry.getValue() - 1);
            }
            for (int offset = 0; offset < dataRows.size(); offset += batchSize) {
                List<List<String>> chunk = dataRows.subList(
                        offset, Math.min(offset + batchSize, dataRows.size()));
                List<List<String>> projected = new ArrayList<>();
                for (List<String> row : chunk) {
                    List<String> cells = new ArrayList<>();
                    for (int index : indices) {
                        cells.add(index < row.size() && row.get(index) != null ? row.get(index) : "");
                    }
                    projected.add(List.copyOf(cells));
                }
                int startRow = offset + 1;
                int endRow = startRow + chunk.size() - 1;
                batches.add(new BatchPlan(
                        sheet.name() + "#" + startRow + "-" + endRow + "@" + planVersion,
                        batches.size() + 1,
                        sheet.name(),
                        sheetPlan.category(),
                        java.util.Collections.unmodifiableMap(labels),
                        startRow,
                        planVersion,
                        dataRows.size(),
                        List.copyOf(projected)));
            }
        }
        return new Planning(List.copyOf(batches), List.copyOf(warnings));
    }

    /** One AI call for one stored batch. A failure belongs to this batch, not the session. */
    public BatchResult extractBatch(BatchPlan batch) {
        AiResult result = gateway.chat(request(prompt(batch)));
        if (!result.success()) {
            throw ApiException.badRequest("AI 解析失败：" + (result.errorMessage() == null
                    ? "请检查 AI 供应商配置" : result.errorMessage()));
        }
        String raw = result.rawResponse() == null ? result.content() : result.rawResponse();
        return new BatchResult(parse(result.content(), batch.category()), raw);
    }

    private AiRequest request(String prompt) {
        return new AiRequest(
                AiTaskType.DATA_IMPORT,
                List.of(AiMessage.system(system()), AiMessage.user(prompt)),
                List.of(),
                new AiOptions(null, MAX_TOKENS, true));
    }

    private String system() {
        return """
                你是排课系统的数据导入助手，正在按已经确认的结构抄写表格数据。
                只输出一个 JSON 对象，不要 Markdown，不要额外解释，结构为：
                {"rows":[{"values":{"字段名":"单元格值"},"confidence":{"字段名":0.99}}],"explanation":""}
                绝不编造原始数据中不存在的内容；缺的单元格留空字符串。
                不要输出表头，不要合并行，不要漏行。
                """;
    }

    private String prompt(BatchPlan batch) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("target", parserTarget(batch.category()).code());
        payload.put("sheet", batch.sheet());
        payload.put("columns", batch.columns());
        payload.put("firstRow", batch.startRow());
        payload.put("rowCount", Math.max(batch.totalRows(), batch.rowCount()));
        payload.put("rows", batch.rows());
        return "结构已确认，请只按 columns 的映射把 rows 抄成记录，每行一条。\n"
                + "columns 的键是系统字段，值是该字段所在列的列名；rows 的每一列依次对应 columns 的字段顺序。\n"
                + fieldHint(batch.category())
                + "数据（JSON）：\n" + json(payload);
    }

    private String fieldHint(SheetCategory category) {
        return switch (category) {
            case TIMETABLE -> "课表字段：className（班级）、dayOfWeek（星期）、periodNo（节次）、"
                    + "courseName（课程）、teacherName（教师）、roomName（教室，可空）。\n";
            case TEACHER -> "教师字段：name（姓名）、employeeNo（工号）、phone（电话）、"
                    + "email（邮箱）、subject（学科）。只有姓名必填。\n";
            case COURSE -> "课程字段：name（课程名）、subject（学科，可空）。\n";
            default -> "";
        };
    }

    private List<RowDto> parse(String content, SheetCategory category) {
        try {
            return parser.parse(
                    content, parserTarget(category), settings.aiImportConfidenceThreshold(), true)
                    .rows();
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest("AI 返回内容无法解析：" + ex.getMessage());
        }
    }

    private Map<String, Integer> columns(ProfiledSheet sheet, SheetPlan plan) {
        Map<String, Integer> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : plan.columns().entrySet()) {
            int column = sheet.columnOf(entry.getValue());
            if (column > 0) {
                resolved.put(entry.getKey(), column);
            }
        }
        if (resolved.isEmpty()) {
            Map<String, String> fallback = WorkbookPlan.columns(sheet, plan.category());
            for (Map.Entry<String, String> entry : fallback.entrySet()) {
                int column = sheet.columnOf(entry.getValue());
                if (column > 0) {
                    resolved.put(entry.getKey(), column);
                }
            }
        }
        return resolved;
    }

    /** Course rows share the teacher shape: {@code name} plus {@code subject}. */
    private ImportTarget parserTarget(SheetCategory category) {
        return category == SheetCategory.TIMETABLE ? ImportTarget.TIMETABLE : ImportTarget.TEACHER;
    }

    private ProfiledSheet find(List<ProfiledSheet> profiles, String name) {
        String needle = SlotText.normalizeName(name);
        for (ProfiledSheet sheet : profiles) {
            if (SlotText.normalizeName(sheet.name()).equals(needle)) {
                return sheet;
            }
        }
        return null;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw ApiException.badRequest("无法构造 AI 请求数据");
        }
    }
}
