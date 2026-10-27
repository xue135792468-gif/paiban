package com.paiban.scheduling.service;

import com.paiban.scheduling.domain.ConstraintType;
import com.paiban.scheduling.domain.ConstraintType.Param;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * The two messages of the natural-language constraint task (ticket 14/08).
 *
 * <p>The system message fixes the contract: only catalogue types, names instead of
 * ids, no guessing, and a JSON envelope. The user message carries the semester context
 * the server itself resolved (teachers/courses/classes and the real timetable), so the
 * model never sees identifiers it could invent and never receives unrelated data such
 * as phone numbers.
 */
@Component
public class ConstraintProposalPrompt {

    public String system() {
        String catalogue = List.of(ConstraintType.values()).stream()
                .map(this::describe)
                .collect(Collectors.joining("\n"));
        return """
                你是排课约束转换器。把用户提出的排课要求转换成系统约束目录内的建议，只输出 JSON。

                可用约束类型（只能使用这些 type，不能创造新类型）：
                %s

                输出格式：
                {"proposals":[{
                  "sourceText":"对应的原话片段",
                  "type":"目录中的 CODE",
                  "strength":"HARD|SOFT|INHERIT",
                  "status":"READY|NEEDS_INPUT|UNSUPPORTED|INVALID",
                  "explanation":"一句话解释这条建议的含义",
                  "reason":"当 status 不是 READY 时说明原因",
                  "teacherName":"教师完整姓名，没有则留空",
                  "courseName":"课程名称，没有则留空",
                  "className":"行政班名称，没有则留空",
                  "subject":"学科名称，没有则留空",
                  "roomType":"NORMAL|LAB|PLAYGROUND|MULTIMEDIA|OTHER",
                  "days":[1,3],
                  "periods":[1,6],
                  "section":"MORNING|AFTERNOON|EVENING",
                  "sectionOrdinals":[1,2],
                  "allDays":false,
                  "values":{"maxPerDay":4,"maxConsecutive":3,"minConsecutive":2,"beforePeriodNo":4}
                }]}

                规则：
                1. 一句话或一段话里的多项独立要求分别输出，不要合并成一条。
                2. 只使用目录内的类型。现有类型无法精确表达时 status=UNSUPPORTED 并说明原因；
                   不得用相近类型近似，也不得扩大或缩小作用范围。
                3. 缺少教师/课程/行政班/星期/课节/数量时 status=NEEDS_INPUT，保留已经明确的部分，
                   不得猜测数量或对象。
                4. 同名对象不要猜：如果上下文中有多个同名对象，status=NEEDS_INPUT 并在 reason 中说明，
                   不要任选一个。
                5. 明确禁令（不排、不能、必须、最多）用 strength=HARD；“尽量/优先/最好”用 SOFT；
                   没有明确强度用 INHERIT。
                6. 上午/下午/晚自习按上下文真实课节解释。periods 使用当天绝对节次；
                   也可以用 section + sectionOrdinals（如“下午第2节”= section=AFTERNOON, sectionOrdinals=[2]）。
                   allDays=true 表示每个实际上课日。
                7. 课节不在本学期作息内、数值超出范围时 status=INVALID 并说明，不要截断或自动创建课节。
                8. “每天/每个班/全部课程”等全体范围要明确表达；不要用空列表表示全体。
                9. 只输出 JSON，不要 Markdown、不要解释文字。
                """.formatted(catalogue);
    }

    public String user(String contextJson, String text) {
        return """
                当前学期上下文（教师、课程、行政班与作息；不包含任何联系方式）：
                %s

                用户要求原文：
                %s
                """.formatted(contextJson, text);
    }

    private String describe(ConstraintType type) {
        String params = type.getParams().isEmpty()
                ? "无参数"
                : type.getParams().stream().map(this::param).collect(Collectors.joining("；"));
        String defaults = "默认" + (type.isDefaultHard() ? "必须" : "尽量")
                + (type.isDefaultHard() ? "" : "，重要程度 " + type.getDefaultWeight());
        return "- " + type.name() + "（" + type.getDisplayName() + "）：" + params
                + "；" + defaults + "；" + type.getDescription();
    }

    private String param(Param param) {
        StringBuilder builder = new StringBuilder(param.name())
                .append("=").append(param.label())
                .append(param.required() ? "[必填]" : "[可空]");
        if (param.type() == ConstraintType.ParamType.ROOM_TYPE) {
            builder.append(" 可选值 ").append(String.join("/", param.options()));
        }
        if (param.min() != null || param.max() != null) {
            builder.append(" 范围 ").append(param.min()).append("-").append(param.max());
        }
        return builder.toString();
    }
}
