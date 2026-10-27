package com.paiban.scheduling.eval;

import com.paiban.scheduling.eval.ConstraintEvaluationCorpus.Boundary;
import com.paiban.scheduling.eval.ConstraintEvaluationCorpus.Expected;
import com.paiban.scheduling.eval.ConstraintEvaluationCorpus.Sentence;
import com.paiban.scheduling.eval.ConstraintEvaluationCorpus.Slot;
import com.paiban.scheduling.eval.ConstraintEvaluationCorpus.SlotMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A deterministic "model" that answers the corpus correctly (ticket 14/09).
 *
 * <p>It exists so the harness, the judge and the budget guard can be rehearsed without
 * a real provider, and so CI can verify the plumbing without spending money. It is
 * never evidence that a real model understands the sentences.
 */
public final class ConstraintEvaluationStub {

    private ConstraintEvaluationStub() {}

    public static String answer(Sentence sentence) {
        List<Map<String, Object>> proposals = sentence.supported()
                ? supported(sentence)
                : boundary(sentence);
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(Map.of("proposals", proposals));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static List<Map<String, Object>> supported(Sentence sentence) {
        List<Map<String, Object>> proposals = new ArrayList<>();
        for (Expected expected : sentence.expected()) {
            Map<String, Object> proposal = new LinkedHashMap<>();
            proposal.put("sourceText", sentence.text());
            proposal.put("type", expected.type());
            proposal.put("strength", expected.strength());
            proposal.put("status", "READY");
            proposal.put("explanation", "评测替身按预期转换");
            for (Map.Entry<String, Object> entry : expected.params().entrySet()) {
                switch (entry.getKey()) {
                    case "teacher" -> proposal.put("teacherName", entry.getValue());
                    case "course" -> proposal.put("courseName", entry.getValue());
                    case "class" -> proposal.put("className", entry.getValue());
                    default -> proposal.put(entry.getKey(), entry.getValue());
                }
            }
            if (expected.mode() != SlotMode.NONE) {
                Set<Integer> days = new LinkedHashSet<>();
                Set<Integer> periods = new LinkedHashSet<>();
                for (Slot slot : expected.slots()) {
                    days.add(slot.day());
                    if (slot.periodNo() != null) {
                        periods.add(slot.periodNo());
                    }
                }
                proposal.put("days", List.copyOf(days));
                if (!periods.isEmpty()) {
                    proposal.put("periods", List.copyOf(periods));
                }
            }
            proposals.add(proposal);
        }
        return proposals;
    }

    private static List<Map<String, Object>> boundary(Sentence sentence) {
        return switch (sentence.boundary()) {
            case HOMONYM -> List.of(
                    Map.of("sourceText", sentence.text(), "type", "TEACHER_UNAVAILABLE",
                            "strength", "HARD", "status", "NEEDS_INPUT",
                            "teacherName", "张老师",
                            "reason", "有两位张老师，请选择"),
                    Map.of("sourceText", sentence.text(), "type", "COURSE_NOT_IN_EVENING",
                            "strength", "HARD", "status", "READY",
                            "courseName", "体育", "explanation", "体育不排晚自习"));
            case VAGUE_AMOUNT -> List.of(
                    Map.of("sourceText", sentence.text(), "type", "TEACHER_UNAVAILABLE",
                            "strength", "INHERIT", "status", "NEEDS_INPUT",
                            "reason", "请补充具体数量和课节"));
            case CLASS_SCOPE_UNSUPPORTED -> List.of(
                    Map.of("sourceText", sentence.text(), "type", "MAIN_SUBJECT_MORNING",
                            "strength", "SOFT", "status", "UNSUPPORTED",
                            "reason", "现有类型只能作用于课程，不能限定毕业班"));
            case ILLEGAL_PERIOD -> List.of(
                    Map.of("sourceText", sentence.text(), "type", "TEACHER_UNAVAILABLE",
                            "strength", "HARD", "status", "INVALID",
                            "reason", "第99节不在本学期作息内"));
        };
    }
}
