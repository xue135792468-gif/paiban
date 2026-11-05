package com.paiban.imports.recipe;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paiban.ai.AiGateway;
import com.paiban.ai.AiMessage;
import com.paiban.ai.AiOptions;
import com.paiban.ai.AiRequest;
import com.paiban.ai.AiResult;
import com.paiban.ai.AiTaskType;
import com.paiban.ai.imports.workbook.HeaderDetection;
import com.paiban.imports.ImportDtos.AiRecipeSuggestion;
import com.paiban.imports.ImportDtos.AiRecipeSuggestionDto;
import com.paiban.imports.ImportDtos.ManualMapping;
import com.paiban.imports.ImportDtos.ManualPreviewDto;
import com.paiban.imports.ImportDtos.RecipeCounts;
import com.paiban.imports.ImportDtos.RecipeMatchDto;
import com.paiban.imports.manual.ManualImportService;
import com.paiban.imports.manual.ManualSuggestion;
import com.paiban.imports.manual.SourceSheets;
import com.paiban.imports.manual.SourceSheets.Sheet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Ticket 07: when no recipe matches, ask the model to propose the column mapping for
 * this sheet. The answer is never trusted blindly — it is translated into the manual
 * path's own {@link ManualMapping} and re-parsed with the production preview pipeline
 * before it is returned.
 *
 * <p>AI availability is a soft dependency: no configured provider is a normal
 * {@code available=false} answer (HTTP 200), and every provider/parse failure is a
 * structured {@code success=false} plus the raw answer. The manual page keeps working
 * because this service never touches {@code /api/ai/import} or the session state
 * machine. Each call is audited by the gateway's existing {@code ai_call_log}.
 */
@Service
public class AiRecipeSuggestionService {

    /** Bounded prompt: ten data rows is enough to see the shape of a timetable. */
    private static final int MAX_SAMPLE_ROWS = 10;
    private static final int MAX_CELL_LENGTH = 40;
    private static final int MAX_RAW_LENGTH = 20_000;
    private static final int TASK_TIMEOUT_SECONDS = 60;
    private static final int MAX_OUTPUT_TOKENS = 2048;

    private static final List<String> KEY_PARTS =
            List.of("className", "dayOfWeek", "periodNo");

    private final AiGateway gateway;
    private final AiRecipePrompt prompt;
    private final AiRecipeSuggestionParser parser;
    private final ManualImportService manualImports;
    private final ObjectMapper objectMapper;

    public AiRecipeSuggestionService(
            AiGateway gateway,
            AiRecipePrompt prompt,
            AiRecipeSuggestionParser parser,
            ManualImportService manualImports,
            ObjectMapper objectMapper) {
        this.gateway = gateway;
        this.prompt = prompt;
        this.parser = parser;
        this.manualImports = manualImports;
        this.objectMapper = objectMapper;
    }

    public AiRecipeSuggestionDto suggest(
            byte[] file, String fileName, String sheetName, Integer headerRow) {
        if (!gateway.configured(AiTaskType.DATA_IMPORT)) {
            return AiRecipeSuggestionDto.unavailable("AI 未配置，可手动选列");
        }
        List<Sheet> sheets = SourceSheets.read(file, fileName);
        Sheet sheet = SourceSheets.select(sheets, sheetName);
        HeaderDetection.Header header = HeaderDetection.detect(sheet.rows());
        ManualMapping guess = ManualSuggestion.suggest(sheet);
        String context = contextJson(fileName, sheet, header, guess, headerRow);

        AiResult result;
        try {
            result = gateway.chat(new AiRequest(
                    AiTaskType.DATA_IMPORT,
                    List.of(AiMessage.system(prompt.system()), AiMessage.user(prompt.user(context))),
                    List.of(),
                    new AiOptions(null, MAX_OUTPUT_TOKENS, true)
                            .withLimits(TASK_TIMEOUT_SECONDS, 1))
                    .withSubject("MANUAL_IMPORT", null));
        } catch (RuntimeException ex) {
            // The gateway contract already turns provider failures into results; this is
            // a last guard so a bug cannot surface as a 500 on the manual page.
            return failure("AI 调用失败：" + ex.getClass().getSimpleName(), null, null, 0L);
        }

        String raw = rawOf(result);
        if (result == null || !result.success()) {
            String message = result == null || !StringUtils.hasText(result.errorMessage())
                    ? "AI 调用失败，请手动选列" : result.errorMessage();
            return failure(message, raw, result == null ? null : result.modelName(),
                    result == null ? 0L : result.latencyMs());
        }

        AiRecipeSuggestion proposal;
        ManualMapping mapping;
        try {
            proposal = parser.parse(result.content());
            mapping = toMapping(sheet, header, proposal, headerRow);
        } catch (IllegalArgumentException ex) {
            return failure("AI 返回的列映射无法使用：" + ex.getMessage(),
                    raw, result.modelName(), result.latencyMs());
        }

        ManualPreviewDto preview;
        try {
            preview = manualImports.previewWithMapping(sheet, mapping, RecipeMatchDto.none(), null);
        } catch (RuntimeException ex) {
            return failure("AI 建议的列映射预览失败：" + ex.getMessage(),
                    raw, result.modelName(), result.latencyMs());
        }
        if (preview.slotCount() == 0) {
            return failure("AI 建议的列映射没有解析出任何数据行",
                    raw, result.modelName(), result.latencyMs());
        }

        AiRecipeSuggestion accepted = new AiRecipeSuggestion(
                mapping.keyColumn() != null ? ImportFingerprint.LAYOUT_LONG
                        : ImportFingerprint.LAYOUT_GRID,
                sheet.name(),
                mapping.resolvedHeaderRow(),
                mapping.resolvedDelimiter(),
                proposal.compositeColumn(),
                proposal.classColumn(),
                proposal.dayColumn(),
                proposal.periodColumn(),
                proposal.courseColumn(),
                proposal.teacherColumn(),
                proposal.ignoreColumns() == null ? List.of() : proposal.ignoreColumns(),
                proposal.reasoning());
        return new AiRecipeSuggestionDto(
                true,
                true,
                accepted,
                new RecipeCounts(
                        preview.slotCount(), preview.lessonCount(), preview.emptySlotCount(),
                        preview.teacherCount(), preview.courseCount()),
                raw,
                proposal.reasoning(),
                result.modelName(),
                result.latencyMs(),
                null);
    }

    // ---------------------------------------------------------------- mapping

    /**
     * Translates the proposal into the same mapping the manual selects produce. Every
     * index is bounds-checked against the sheet, and the role combination must be one
     * the parser understands (composite key or three separate columns).
     */
    private ManualMapping toMapping(
            Sheet sheet, HeaderDetection.Header header, AiRecipeSuggestion proposal,
            Integer headerRowHint) {
        int columnCount = sheet.columnCount();
        Integer headerRow = proposal.headerRow() != null
                ? proposal.headerRow() : validHint(headerRowHint, sheet);
        if (headerRow == null) {
            headerRow = header.rowIndex();
        }
        if (headerRow < -1 || headerRow >= sheet.rows().size()) {
            throw new IllegalArgumentException("表头行超出范围：" + headerRow);
        }
        Integer composite = column(proposal.compositeColumn(), columnCount, "compositeColumn");
        Integer classColumn = column(proposal.classColumn(), columnCount, "classColumn");
        Integer dayColumn = column(proposal.dayColumn(), columnCount, "dayColumn");
        Integer periodColumn = column(proposal.periodColumn(), columnCount, "periodColumn");
        Integer courseColumn = column(proposal.courseColumn(), columnCount, "courseColumn");
        Integer teacherColumn = column(proposal.teacherColumn(), columnCount, "teacherColumn");
        if (courseColumn == null) {
            throw new IllegalArgumentException("缺少课程列 courseColumn");
        }
        String delimiter = StringUtils.hasText(proposal.delimiter())
                ? proposal.delimiter().trim() : "|";
        if (composite != null) {
            return new ManualMapping(
                    sheet.name(), headerRow, delimiter, composite, KEY_PARTS,
                    null, null, null, courseColumn, teacherColumn, true);
        }
        if (classColumn == null || dayColumn == null || periodColumn == null) {
            throw new IllegalArgumentException("缺少班级/星期/节次列");
        }
        return new ManualMapping(
                sheet.name(), headerRow, null, null, KEY_PARTS,
                classColumn, dayColumn, periodColumn, courseColumn, teacherColumn, true);
    }

    private Integer column(Integer value, int columnCount, String field) {
        if (value == null) {
            return null;
        }
        if (value < 0 || value >= columnCount) {
            throw new IllegalArgumentException("字段 " + field + " 的列索引超出范围：" + value);
        }
        return value;
    }

    private Integer validHint(Integer headerRow, Sheet sheet) {
        if (headerRow == null) {
            return null;
        }
        return headerRow >= -1 && headerRow < sheet.rows().size() ? headerRow : null;
    }

    // ---------------------------------------------------------------- prompt context

    private String contextJson(
            String fileName, Sheet sheet, HeaderDetection.Header header, ManualMapping guess,
            Integer headerRowHint) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("fileName", fileName == null ? "" : fileName);
        context.put("sheetName", sheet.name());
        context.put("headerRow", header.rowIndex());
        context.put("headerless", header.headerless());
        context.put("headerRowHint", headerRowHint);
        context.put("columnCount", sheet.columnCount());
        context.put("headers", header.labels());
        context.put("sampleRows", sampleRows(sheet, header.dataStart()));
        context.put("deterministicGuess", guess);
        try {
            return objectMapper.writeValueAsString(context);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("工作表摘要无法序列化", ex);
        }
    }

    private List<List<String>> sampleRows(Sheet sheet, int dataStart) {
        List<List<String>> rows = new ArrayList<>();
        int start = Math.max(dataStart, 0);
        for (int index = start; index < Math.min(start + MAX_SAMPLE_ROWS, sheet.rows().size()); index++) {
            List<String> row = sheet.row(index);
            List<String> cells = new ArrayList<>();
            for (String cell : row) {
                String value = cell == null ? "" : cell.trim();
                cells.add(value.length() > MAX_CELL_LENGTH
                        ? value.substring(0, MAX_CELL_LENGTH) : value);
            }
            if (!cells.isEmpty()) {
                rows.add(List.copyOf(cells));
            }
        }
        return List.copyOf(rows);
    }

    // ---------------------------------------------------------------- result helpers

    private String rawOf(AiResult result) {
        if (result == null) {
            return null;
        }
        String raw = StringUtils.hasText(result.rawResponse())
                ? result.rawResponse() : result.content();
        if (raw == null) {
            return null;
        }
        return raw.length() > MAX_RAW_LENGTH ? raw.substring(0, MAX_RAW_LENGTH) : raw;
    }

    private AiRecipeSuggestionDto failure(
            String error, String raw, String modelName, long latencyMs) {
        return new AiRecipeSuggestionDto(
                true, false, null, null, raw, null, modelName, latencyMs, error);
    }
}
