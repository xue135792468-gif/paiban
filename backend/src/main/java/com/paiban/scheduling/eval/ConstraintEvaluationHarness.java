package com.paiban.scheduling.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paiban.ai.AiResult;
import com.paiban.foundation.domain.PeriodSection;
import com.paiban.scheduling.eval.ConstraintEvaluationCorpus.Sentence;
import com.paiban.scheduling.service.ConstraintProposalNormalizer;
import com.paiban.scheduling.service.ConstraintProposalNormalizer.ClassRef;
import com.paiban.scheduling.service.ConstraintProposalNormalizer.Context;
import com.paiban.scheduling.service.ConstraintProposalNormalizer.CourseRef;
import com.paiban.scheduling.service.ConstraintProposalNormalizer.TeacherRef;
import com.paiban.scheduling.service.ConstraintProposalParser;
import com.paiban.scheduling.service.ConstraintProposalPrompt;
import com.paiban.scheduling.web.ConstraintProposalDtos.ConstraintProposalDto;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/**
 * The reusable evaluation seam (ticket 14/09): the same prompt, gateway answer,
 * parser and normalizer the live feature uses, applied to the fixed synthetic school.
 *
 * <p>It never touches the database, so the corpus and the judge are reproducible and
 * the CI test can exercise them with a stub caller.
 */
@Component
public class ConstraintEvaluationHarness {

    public record PromptText(String system, String user) {}

    public record Attempt(
            String content,
            String rawResponse,
            List<ConstraintProposalDto> proposals,
            ConstraintEvaluationJudge.Verdict verdict,
            Integer inputTokens,
            Integer outputTokens,
            long latencyMs) {}

    private final ConstraintProposalPrompt prompt;
    private final ConstraintProposalParser parser;
    private final ObjectMapper mapper;

    public ConstraintEvaluationHarness(
            ConstraintProposalPrompt prompt,
            ConstraintProposalParser parser,
            ObjectMapper mapper) {
        this.prompt = prompt;
        this.parser = parser;
        this.mapper = mapper;
    }

    public PromptText prompt(Sentence sentence) {
        return new PromptText(prompt.system(), prompt.user(contextJson(), sentence.text()));
    }

    /** The fixed C01–C22 school: 5 days, 4+4+2 periods, five teachers/courses. */
    public static Context context() {
        List<TeacherRef> teachers = new ArrayList<>();
        for (Map.Entry<String, Long> entry : ConstraintEvaluationCorpus.TEACHERS.entrySet()) {
            String subject = entry.getKey().startsWith("张") ? "数学" : "语文";
            teachers.add(new TeacherRef(entry.getValue(), entry.getKey(), subject));
        }
        List<CourseRef> courses = new ArrayList<>();
        for (Map.Entry<String, Long> entry : ConstraintEvaluationCorpus.COURSES.entrySet()) {
            String roomType = switch (entry.getKey()) {
                case "体育" -> "PLAYGROUND";
                case "化学" -> "LAB";
                default -> null;
            };
            courses.add(new CourseRef(entry.getValue(), entry.getKey(), entry.getKey(), roomType));
        }
        List<ClassRef> classes = new ArrayList<>();
        ConstraintEvaluationCorpus.CLASSES.forEach(
                (name, id) -> classes.add(new ClassRef(id, name)));
        Map<Integer, Map<PeriodSection, List<Integer>>> timetable = new TreeMap<>();
        for (int day = 1; day <= 5; day++) {
            Map<PeriodSection, List<Integer>> sections = new LinkedHashMap<>();
            sections.put(PeriodSection.MORNING, List.of(1, 2, 3, 4));
            sections.put(PeriodSection.AFTERNOON, List.of(5, 6, 7, 8));
            sections.put(PeriodSection.EVENING, List.of(9, 10));
            timetable.put(day, sections);
        }
        return new Context(teachers, courses, classes, timetable);
    }

    /** What the model sees: names and the real timetable, never internal ids. */
    public String contextJson() {
        Context context = context();
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("teachers", context.teachers().stream()
                .map(teacher -> Map.of("name", teacher.name(),
                        "subject", teacher.subject() == null ? "" : teacher.subject()))
                .toList());
        json.put("courses", context.courses().stream()
                .map(course -> Map.of("name", course.name(),
                        "subject", course.subject() == null ? "" : course.subject(),
                        "requiredRoomType",
                        course.requiredRoomType() == null ? "" : course.requiredRoomType()))
                .toList());
        json.put("classes", context.classes().stream()
                .map(adminClass -> Map.of("name", adminClass.name()))
                .toList());
        Map<String, Object> timetable = new LinkedHashMap<>();
        context.timetable().forEach((day, sections) -> {
            Map<String, List<Integer>> bySection = new LinkedHashMap<>();
            sections.forEach((section, numbers) -> bySection.put(section.getLabel(), numbers));
            timetable.put(String.valueOf(day), bySection);
        });
        json.put("timetable", timetable);
        try {
            return mapper.writeValueAsString(json);
        } catch (Exception ex) {
            throw new IllegalStateException("序列化评测上下文失败", ex);
        }
    }

    public Attempt evaluate(Sentence sentence, AiResult result) {
        if (!result.success()) {
            return new Attempt(null, result.rawResponse(), List.of(),
                    ConstraintEvaluationJudge.Verdict.fail(
                            "调用失败: " + result.errorCode() + " " + result.errorMessage()),
                    result.inputTokens(), result.outputTokens(), result.latencyMs());
        }
        try {
            List<JsonNode> nodes = parser.parse(result.content());
            List<ConstraintProposalDto> proposals = ConstraintProposalNormalizer.normalize(
                    nodes, context(), "eval-" + sentence.code());
            return new Attempt(result.content(), result.rawResponse(), proposals,
                    ConstraintEvaluationJudge.judge(sentence, proposals),
                    result.inputTokens(), result.outputTokens(), result.latencyMs());
        } catch (IllegalArgumentException ex) {
            return new Attempt(result.content(), result.rawResponse(), List.of(),
                    ConstraintEvaluationJudge.Verdict.fail("输出无法解析: " + ex.getMessage()),
                    result.inputTokens(), result.outputTokens(), result.latencyMs());
        }
    }
}
