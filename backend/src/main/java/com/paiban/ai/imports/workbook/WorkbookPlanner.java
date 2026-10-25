package com.paiban.ai.imports.workbook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paiban.ai.AiGateway;
import com.paiban.ai.AiMessage;
import com.paiban.ai.AiOptions;
import com.paiban.ai.AiRequest;
import com.paiban.ai.AiResult;
import com.paiban.ai.AiTaskType;
import com.paiban.ai.imports.ImportMappingService;
import com.paiban.common.web.ApiException;
import com.paiban.ai.imports.workbook.WorkbookPlan.SheetCategory;
import com.paiban.ai.imports.workbook.WorkbookPlan.SheetPlan;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The tool loop the model runs before a workbook is imported. The model browses the file
 * with read-only tools (list sheets, page through one, look names up) and then declares each
 * sheet's category and column mapping. The loop is plain JSON over the existing gateway: no
 * provider adapter learns a tool protocol, and the model can never write anything.
 *
 * <p>Six rounds is the ceiling. Running out is not a failure: the deterministic guess
 * (headers and sheet names) produces a plan flagged "unconfirmed" instead.
 */
@Component
public class WorkbookPlanner {

    static final int MAX_ROUNDS = 6;
    /** Explains why a sheet that calls itself a timetable contributed no rows. */
    static final String WIDE_SHEET = "宽表（星期为列）或缺少班级/星期/节次列，已跳过";
    static final int MAX_PAGE = 200;
    private static final int DEFAULT_PAGE = 120;
    private static final int PREVIEW_ROWS = 3;
    private static final int MAX_TOKENS = 4096;

    private final AiGateway gateway;
    private final ObjectMapper objectMapper;
    private final ImportMappingService mappings;

    public WorkbookPlanner(AiGateway gateway, ObjectMapper objectMapper, ImportMappingService mappings) {
        this.gateway = gateway;
        this.objectMapper = objectMapper;
        this.mappings = mappings;
    }

    /**
     * One round of the loop: the tool the model asked for, its arguments, the model's own
     * reply and a readable summary of what the tool returned. Kept on the session so a
     * wrong structural judgement can be replayed.
     */
    public record Trace(int round, String tool, String args, String reply, String result) {}

    public record Outcome(WorkbookPlan plan, List<Trace> trace) {}

    public Outcome plan(List<ProfiledSheet> profiles) {
        List<Trace> trace = new ArrayList<>();
        List<AiMessage> messages = new ArrayList<>();
        messages.add(AiMessage.system(systemPrompt()));
        messages.add(AiMessage.user(initialPrompt(profiles)));
        for (int round = 1; round <= MAX_ROUNDS; round++) {
            AiResult result = gateway.chat(new AiRequest(
                    AiTaskType.DATA_IMPORT, List.copyOf(messages), List.of(),
                    new AiOptions(null, MAX_TOKENS, true)));
            if (!result.success()) {
                throw ApiException.badRequest("AI 解析失败：" + (result.errorMessage() == null
                        ? "请检查 AI 供应商配置" : result.errorMessage()));
            }
            String content = result.content();
            Action action = parseAction(content);
            if (action == null) {
                trace.add(new Trace(round, "invalid", "", summarize(content), ""));
                messages.add(AiMessage.assistant(content == null ? "" : content));
                messages.add(AiMessage.user(
                        "上一轮回复不是可解析的动作对象。请只输出一个 JSON 对象，字段为 tool 和 args。"));
                continue;
            }
            String toolResult = execute(action, profiles);
            trace.add(new Trace(
                    round, action.tool(), json(action.args()), summarize(content),
                    summarize(toolResult)));
            if ("finish".equals(action.tool())) {
                WorkbookPlan plan = finishPlan(action.args(), profiles);
                if (plan != null) {
                    return new Outcome(plan, List.copyOf(trace));
                }
                messages.add(AiMessage.assistant(content == null ? "" : content));
                messages.add(AiMessage.user(
                        "finish 的内容缺少可用的工作表类别或列映射，请补齐后再调用 finish。"));
                continue;
            }
            messages.add(AiMessage.assistant(content == null ? "" : content));
            messages.add(AiMessage.user("工具结果：" + toolResult));
        }
        return new Outcome(
                WorkbookPlan.fallback(profiles, "结构未确认，请人工核对"), List.copyOf(trace));
    }

    // ------------------------------------------------------------------ prompts

    private String systemPrompt() {
        return """
                你是排课系统的数据导入助手。用户上传的是一份真实教务工作簿，里面可能有多张工作表，\
                结构并不统一。你必须先用只读工具浏览文件，再宣告每张表的类别与列映射。

                每一步只输出一个 JSON 对象，不要 Markdown，不要额外解释。可用动作：
                {"tool":"list_sheets"}
                {"tool":"read_sheet","args":{"sheet":"工作表名","offset":0,"limit":100}}
                {"tool":"lookup","args":{"type":"teacher|course|class|room","names":["张三"]}}
                {"tool":"finish","args":{"sheets":[{"sheet":"工作表名","category":"timetable","columns":{"className":"班级"},"note":"判断依据"}],"explanation":"整体说明"}}

                类别 category 只能是：
                - timetable：一行一节课（含班级、星期、节次）
                - teacher：教师名单
                - course：课程清单
                - explanation：使用说明、目录、备注等没有可导入数据的表
                - unknown：看不出用途

                列映射 columns 的键：
                - 课表：className、dayOfWeek、periodNo、courseName、teacherName、roomName
                - 教师：name、employeeNo、phone、email、subject
                - 课程：name、subject
                值写工作表的列名；没有表头时写列序号（从 1 开始，例如 "2"）。
                复合列（例如 八（1）|星期一|1）在读过的工作表里已经拆成派生列，直接用派生列名，不要再自己拆。
                不要编造单元格里不存在的内容。最多 %d 轮，请尽快 finish。
                """
                .formatted(MAX_ROUNDS);
    }

    private String initialPrompt(List<ProfiledSheet> profiles) {
        StringBuilder text = new StringBuilder("工作簿内容概览（每张表的前几行已附上）：\n");
        for (ProfiledSheet sheet : profiles) {
            if (sheet.skipped()) {
                text.append("- 「").append(sheet.name()).append("」已跳过：")
                        .append(sheet.skippedReason()).append("\n");
                continue;
            }
            text.append("- 「").append(sheet.name()).append("」：")
                    .append(sheet.dataRows().size()).append(" 行 × ")
                    .append(sheet.labels().size()).append(" 列；")
                    .append(sheet.headerRowIndex() < 0 ? "无表头" : "表头在第 " + (sheet.headerRowIndex() + 1) + " 行")
                    .append("\n");
            if (!sheet.derived().isEmpty()) {
                text.append("  已拆分的复合列：")
                        .append(String.join("、", sheet.derived().stream()
                                .map(CompositeColumns.Derived::label).toList()))
                        .append("\n");
            }
            text.append("  预览：").append(json(head(sheet.grid()))).append("\n");
            List<String> composite = unSplitComposite(sheet);
            if (!composite.isEmpty()) {
                text.append("  可能未拆分的复合单元格：")
                        .append(String.join("、", composite))
                        .append("（如果一个单元格里挤着多个字段，请在 columns 里按列名指定）\n");
            }
        }
        text.append("请调用工具确认结构，最后调用 finish 宣告每张有数据的工作表。");
        return text.toString();
    }

    /** The first few normalized rows, header included: enough to judge the layout. */
    private List<List<String>> head(List<List<String>> rows) {
        return rows.subList(0, Math.min(PREVIEW_ROWS, rows.size()));
    }

    /**
     * Columns that still contain a separator but were not split. The model is told about
     * them so it can map them deliberately instead of guessing at the parts.
     */
    private List<String> unSplitComposite(ProfiledSheet sheet) {
        java.util.Set<Integer> splitSources = new java.util.HashSet<>();
        for (CompositeColumns.Derived derived : sheet.derived()) {
            splitSources.add(derived.sourceColumn());
        }
        List<String> candidates = new ArrayList<>();
        for (int column = 0; column < sheet.labels().size() - sheet.derived().size(); column++) {
            if (splitSources.contains(column)) {
                continue;
            }
            int hits = 0;
            for (List<String> row : sheet.dataRows()) {
                String value = column < row.size() && row.get(column) != null ? row.get(column) : "";
                if (value.contains("|") || value.contains("｜") || value.contains("·")
                        || value.contains("—")) {
                    hits++;
                }
            }
            if (hits >= 3) {
                candidates.add(sheet.labels().get(column));
            }
            if (candidates.size() >= 3) {
                break;
            }
        }
        return candidates;
    }

    // ------------------------------------------------------------------- tools

    private record Action(String tool, JsonNode args) {}

    private Action parseAction(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        JsonNode root = readJson(content);
        if (root == null || !root.isObject()) {
            return null;
        }
        String tool = root.path("tool").asText(root.path("action").asText("")).trim();
        if (tool.isEmpty()) {
            return null;
        }
        JsonNode args = root.path("args");
        if (!args.isObject()) {
            args = root;
        }
        return new Action(tool, args);
    }

    private String execute(Action action, List<ProfiledSheet> profiles) {
        return switch (action.tool()) {
            case "list_sheets" -> listSheets(profiles);
            case "read_sheet" -> readSheet(action.args(), profiles);
            case "lookup" -> lookup(action.args());
            case "finish" -> "结构已收到，正在生成预览。";
            default -> "未知工具：" + action.tool() + "。可用工具：list_sheets、read_sheet、lookup、finish。";
        };
    }

    private String listSheets(List<ProfiledSheet> profiles) {
        List<Map<String, Object>> sheets = new ArrayList<>();
        for (ProfiledSheet sheet : profiles) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("sheet", sheet.name());
            if (sheet.skipped()) {
                item.put("rows", 0);
                item.put("columns", 0);
                item.put("skipped", sheet.skippedReason());
            } else {
                item.put("rows", sheet.dataRows().size());
                item.put("columns", sheet.labels().size());
                item.put("headerRow", sheet.headerRowIndex() < 0 ? null : sheet.headerRowIndex() + 1);
                item.put("preview", head(sheet.grid()));
            }
            sheets.add(item);
        }
        return json(Map.of("sheets", sheets));
    }

    private String readSheet(JsonNode args, List<ProfiledSheet> profiles) {
        String name = args.path("sheet").asText("");
        ProfiledSheet sheet = find(profiles, name);
        if (sheet == null) {
            return "找不到工作表「" + name + "」，可用工作表：" + names(profiles);
        }
        if (sheet.skipped()) {
            return "工作表「" + sheet.name() + "」已按说明类跳过：" + sheet.skippedReason();
        }
        int offset = Math.max(0, args.path("offset").asInt(0));
        int limit = args.path("limit").asInt(DEFAULT_PAGE);
        limit = Math.max(1, Math.min(MAX_PAGE, limit));
        List<List<String>> rows = sheet.grid();
        int end = Math.min(rows.size(), offset + limit);
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("sheet", sheet.name());
        page.put("total", rows.size());
        page.put("offset", offset);
        page.put("limit", limit);
        page.put("rows", offset >= rows.size() ? List.of() : rows.subList(offset, end));
        return json(page);
    }

    private String lookup(JsonNode args) {
        String type = args.path("type").asText("");
        List<String> names = new ArrayList<>();
        JsonNode array = args.path("names");
        if (array.isArray()) {
            for (JsonNode item : array) {
                if (!item.asText("").isBlank()) {
                    names.add(item.asText(""));
                }
            }
        }
        if (names.isEmpty()) {
            String single = args.path("name").asText("");
            if (!single.isBlank()) {
                names.add(single);
            }
        }
        try {
            ImportMappingService.NameLookup lookup = mappings.lookupNames(type, names);
            Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("type", lookup.type());
            answer.put("found", lookup.found());
            answer.put("missing", lookup.missing());
            answer.put("total", lookup.total());
            return json(answer);
        } catch (RuntimeException ex) {
            return "查询失败：" + ex.getMessage();
        }
    }

    // -------------------------------------------------------------------- plan

    private WorkbookPlan finishPlan(JsonNode args, List<ProfiledSheet> profiles) {
        JsonNode sheets = args.path("sheets");
        if (!sheets.isArray()) {
            return null;
        }
        Map<String, SheetPlan> declared = new LinkedHashMap<>();
        Map<String, SheetPlan> planned = new LinkedHashMap<>();
        boolean guessed = false;
        for (JsonNode item : sheets) {
            String name = item.path("sheet").asText(item.path("name").asText("")).trim();
            ProfiledSheet sheet = find(profiles, name);
            if (sheet == null || sheet.skipped()) {
                continue;
            }
            SheetCategory category = category(item.path("category").asText(""));
            if (category == SheetCategory.UNKNOWN) {
                // The model named the sheet but did not judge it (a weak model often just
                // lists names). Guess instead of dropping the sheet: a preview marked
                // "unconfirmed" is useful, an all-unknown preview is not.
                category = WorkbookPlan.guess(sheet);
                guessed = true;
            }
            Map<String, String> columns = resolveColumns(item.path("columns"), sheet, category);
            if (columns.isEmpty() && category != SheetCategory.EXPLANATION
                    && category != SheetCategory.UNKNOWN) {
                columns = WorkbookPlan.columns(sheet, category);
            }
            SheetPlan plan = new SheetPlan(
                    sheet.name(), category, columns, item.path("note").asText(""), !guessed);
            declared.put(sheet.name(), plan);
            planned.put(sheet.name(), WorkbookPlan.normalize(plan));
        }
        if (planned.isEmpty()) {
            return null;
        }
        int plannedFromModel = declared.size();
        // A sheet the model left out is still in the workbook: guess it so no data is silently lost.
        for (ProfiledSheet sheet : profiles) {
            if (sheet.skipped() || planned.containsKey(sheet.name())) {
                continue;
            }
            SheetCategory category = WorkbookPlan.guess(sheet);
            planned.put(sheet.name(), WorkbookPlan.normalize(new SheetPlan(
                    sheet.name(), category, WorkbookPlan.columns(sheet, category), "", false)));
        }
        String explanation = args.path("explanation").asText("").trim();
        // Only a plan that covers every non-skipped sheet with a known category counts as
        // confirmed; a guessed sheet (or an "unknown" one) leaves the marker on the preview.
        // A sheet this code later downgrades to a wide pivot still counts as declared.
        int expected = (int) profiles.stream().filter(sheet -> !sheet.skipped()).count();
        boolean complete = plannedFromModel == expected
                && !guessed
                && declared.values().stream().noneMatch(plan -> plan.category() == SheetCategory.UNKNOWN);
        return new WorkbookPlan(
                List.copyOf(planned.values()),
                complete,
                explanation.isEmpty() ? "模型已确认工作表结构" : explanation);
    }

    /** Chinese category names a model may write instead of the enum codes. */
    private SheetCategory category(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (value.isEmpty()) {
            return SheetCategory.UNKNOWN;
        }
        if (value.contains("说明") || value.contains("备注") || value.contains("解释")) {
            return SheetCategory.EXPLANATION;
        }
        if (value.contains("课表") || value.contains("考勤") || value.contains("数据源")
                || value.contains("原始数据") || value.contains("排课")) {
            return SheetCategory.TIMETABLE;
        }
        if (value.contains("教师") || value.contains("老师")) {
            return SheetCategory.TEACHER;
        }
        if (value.contains("课程") || value.contains("科目")) {
            return SheetCategory.COURSE;
        }
        return SheetCategory.from(raw);
    }

    private Map<String, String> resolveColumns(JsonNode node, ProfiledSheet sheet, SheetCategory category) {
        Map<String, String> columns = new LinkedHashMap<>();
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        node.fieldNames().forEachRemaining(field -> {
            JsonNode value = node.path(field);
            // A model may describe a column as an object ({"split_into":[...]}) instead of
            // naming it; that is not a mapping, so the deterministic columns are used.
            if (!value.isValueNode()) {
                return;
            }
            String label = columnLabel(value.asText(""), sheet);
            if (label != null && relevant(field, category)) {
                columns.put(field, label);
            }
        });
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(columns));
    }

    /** Accepts a label, a 1-based number or a spreadsheet letter. Null when unusable. */
    private String columnLabel(String raw, ProfiledSheet sheet) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        if (value.matches("\\d+")) {
            int index = Integer.parseInt(value);
            return index >= 1 && index <= sheet.labels().size() ? sheet.labels().get(index - 1) : null;
        }
        if (value.matches("(?i)[a-z]{1,3}")) {
            int index = 0;
            for (char c : value.toUpperCase(Locale.ROOT).toCharArray()) {
                index = index * 26 + (c - 'A' + 1);
            }
            return index >= 1 && index <= sheet.labels().size() ? sheet.labels().get(index - 1) : null;
        }
        for (String label : sheet.labels()) {
            if (label.equals(value) || label.replaceAll("\\s+", "").equals(value.replaceAll("\\s+", ""))) {
                return label;
            }
        }
        return null;
    }

    private boolean relevant(String field, SheetCategory category) {
        return switch (category) {
            case TIMETABLE -> List.of(
                    "className", "dayOfWeek", "periodNo", "courseName", "teacherName", "roomName")
                    .contains(field);
            case TEACHER -> List.of("name", "employeeNo", "phone", "email", "subject").contains(field);
            case COURSE -> List.of("name", "subject").contains(field);
            default -> false;
        };
    }

    private ProfiledSheet find(List<ProfiledSheet> profiles, String name) {
        String needle = normalize(name);
        for (ProfiledSheet sheet : profiles) {
            if (normalize(sheet.name()).equals(needle)) {
                return sheet;
            }
        }
        return null;
    }

    private String names(List<ProfiledSheet> profiles) {
        return String.join("、", profiles.stream().map(ProfiledSheet::name).toList());
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", "");
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode readJson(String content) {
        if (content == null) {
            return null;
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
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(cleaned.substring(start, end + 1));
            return node != null && node.isObject() ? node : null;
        } catch (Exception ex) {
            return null;
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return "{}";
        }
    }

    /** Tool results can be a whole page of rows; the trace keeps only a readable head. */
    private String summarize(String value) {
        if (value == null) {
            return "";
        }
        String compact = value.replaceAll("\\s+", " ").trim();
        return compact.length() <= 500 ? compact : compact.substring(0, 500) + "…";
    }
}
