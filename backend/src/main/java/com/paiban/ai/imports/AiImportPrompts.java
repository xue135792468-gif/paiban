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
                {"mappings":[{"source":"原始列或片段","target":"系统字段名","note":"映射说明"}],
                 "rows":[{"values":{"name":"张三","employeeNo":"T001","phone":"138...","email":"","subject":"语文"},
                          "confidence":{"name":0.99,"employeeNo":0.95,"phone":0.72,"email":1.0,"subject":0.9}}],
                 "explanation":"一到三句话说明你如何完成映射，以及哪些地方不确定。"}
                confidence 取 0 到 1 之间的小数，表示该单元格的把握程度；不确定就留空值并给低置信度。
                绝不编造原始数据中不存在的信息。最多返回 %d 行。
                """
                .formatted(target.code(), fields, MAX_ROWS);
    }

    static String tablePrompt(
            ImportTarget target, List<String> headers, List<Map<String, String>> rows, ObjectMapper objectMapper) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("target", target.code());
        payload.put("headers", headers);
        payload.put("rows", truncateRows(rows));
        return "请解析下面的表格数据：\n" + json(payload, objectMapper);
    }

    static String textPrompt(ImportTarget target, String text) {
        return "目标对象：" + target.code() + "\n请解析下面的文本，把人名、学科、电话、邮箱、工号等信息抽取成教师名单：\n"
                + truncate(text, 200_000);
    }

    static String imagePrompt(ImportTarget target) {
        return "目标对象：" + target.code()
                + "\n请识别图片中的教师名单（可能是纸质表格、截图或手写表），抽取姓名、工号、电话、邮箱、学科，"
                + "并按系统 JSON 结构返回。看不清的单元格留空并给低置信度。";
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

    private static List<Map<String, String>> truncateRows(List<Map<String, String>> rows) {
        return rows.stream()
                .limit(MAX_ROWS)
                .map(row -> {
                    Map<String, String> copy = new LinkedHashMap<>();
                    row.forEach((key, value) -> copy.put(key, truncate(value, MAX_CELL)));
                    return copy;
                })
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
