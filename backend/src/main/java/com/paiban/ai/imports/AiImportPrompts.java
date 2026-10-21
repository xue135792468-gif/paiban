package com.paiban.ai.imports;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paiban.ai.imports.ImportDtos.RowDto;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Prompt templates for the import pipeline. Kept in one place so the JSON contract is obvious. */
final class AiImportPrompts {

    private static final int MAX_CELL = 500;
    private static final int MAX_ROWS = 500;
    private static final int MAX_COLUMNS = 60;

    private AiImportPrompts() {}

    static String system(ImportTarget target) {
        StringBuilder fields = new StringBuilder();
        for (ImportTarget.Field field : target.fields()) {
            fields.append("- ")
                    .append(field.field())
                    .append("（")
                    .append(field.label())
                    .append(field.required() ? "，必填" : "，可空")
                    .append("）\n");
        }
        return """
                你是排课系统的数据导入助手，负责把用户提供的原始数据解析为系统字段。
                目标对象：%s
                字段：
                %s
                只输出一个 JSON 对象，不要 Markdown 代码块，不要额外解释。结构必须是：
                {"suggestedTarget":"teacher|timetable",
                 "mappings":[{"source":"原始列或片段","target":"系统字段名","note":"映射说明"}],
                 "rows":[{"values":%s,
                          "confidence":%s}],
                 "explanation":"一到三句话说明你如何完成映射，以及哪些地方不确定。"}
                suggestedTarget 表示你判断这份数据更像哪种导入目标，只能填 teacher 或 timetable；
                当前请求的目标是 %s，只有在你认为明显更合适时才填写不同的值，不确定就留空。
                confidence 取 0 到 1 之间的小数，表示该单元格的把握程度；不确定就留空值并给低置信度。
                绝不编造原始数据中不存在的信息。最多返回 %d 行。
                """
                .formatted(
                        target.code(),
                        fields,
                        valuesExample(target),
                        confidenceExample(target),
                        target.code(),
                        MAX_ROWS);
    }

    static String textPrompt(ImportTarget target, String text) {
        return "目标对象：" + target.code()
                + "\n请解析下面的文本，按目标字段抽取数据：" + fieldHint(target) + "。\n"
                + truncate(text, 200_000);
    }

    static String imagePrompt(ImportTarget target) {
        return "目标对象：" + target.code()
                + "\n请识别图片中的表格或名单（可能是纸质表格、截图或手写表），按目标字段抽取数据："
                + fieldHint(target) + "。看不清的单元格留空并给低置信度。";
    }

    /**
     * Sends the sheet as a raw grid. The AI has to find the header and the layout
     * itself: title rows, merged cells and extra columns are all still present.
     */
    static String gridPrompt(
            ImportTarget target, String sheetName, List<List<String>> grid, ObjectMapper objectMapper) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("target", target.code());
        payload.put("sheetName", sheetName == null ? "" : sheetName);
        payload.put("grid", truncateGrid(grid));
        String shape = target.kind() == ImportTarget.Kind.GRID
                ? "每一行代表一节课，按目标字段逐行输出。"
                : "每一行代表一个对象，按目标字段逐行输出。";
        return "下面是工作表「" + (sheetName == null ? "" : sheetName) + "」的原始网格（二维数组）：\n"
                + "前几行可能是标题、说明或表头，合并单元格已经展开到每个被覆盖的单元格，列的顺序和数量都不固定。\n"
                + "请先识别表格结构，再按目标字段输出。" + shape + "\n"
                + "目标字段：" + fieldHint(target) + "\n"
                + "请解析下面的表格数据：\n" + json(payload, objectMapper);
    }

    static String continuePrompt(
            ImportTarget target,
            String originalPrompt,
            List<RowDto> confirmed,
            ObjectMapper objectMapper) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("target", target.code());
        payload.put("userConfirmedRows", confirmed);
        return "用户在上一轮预览中手工修正了部分单元格。请保留这些行中所有非空值，"
                + "基于原始数据补全其余空白字段，并补全尚未解析的剩余行。\n"
                + "原始数据：\n" + originalPrompt + "\n"
                + "用户确认的行：\n" + json(payload, objectMapper);
    }

    private static String valuesExample(ImportTarget target) {
        StringBuilder example = new StringBuilder("{");
        for (ImportTarget.Field field : target.fields()) {
            if (example.length() > 1) {
                example.append(",");
            }
            example.append("\"").append(field.field()).append("\":\"").append(field.label()).append("\"");
        }
        return example.append("}").toString();
    }

    private static String confidenceExample(ImportTarget target) {
        StringBuilder example = new StringBuilder("{");
        for (ImportTarget.Field field : target.fields()) {
            if (example.length() > 1) {
                example.append(",");
            }
            example.append("\"")
                    .append(field.field())
                    .append("\":")
                    .append(field.required() ? "0.99" : "0.9");
        }
        return example.append("}").toString();
    }

    private static String fieldHint(ImportTarget target) {
        StringBuilder hint = new StringBuilder();
        for (ImportTarget.Field field : target.fields()) {
            if (hint.length() > 0) {
                hint.append("、");
            }
            hint.append(field.field())
                    .append("（")
                    .append(field.label())
                    .append(field.required() ? "，必填" : "，可空")
                    .append("）");
        }
        return hint.toString();
    }

    private static List<List<String>> truncateGrid(List<List<String>> grid) {
        if (grid == null) {
            return List.of();
        }
        return grid.stream()
                .limit(MAX_ROWS)
                .map(row -> row.stream()
                        .limit(MAX_COLUMNS)
                        .map(cell -> truncate(cell, MAX_CELL))
                        .toList())
                .toList();
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String json(Object value, ObjectMapper objectMapper) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("无法构造 AI 请求数据", ex);
        }
    }
}
