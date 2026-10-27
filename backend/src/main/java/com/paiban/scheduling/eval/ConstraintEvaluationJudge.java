package com.paiban.scheduling.eval;

import com.paiban.scheduling.eval.ConstraintEvaluationCorpus.Boundary;
import com.paiban.scheduling.eval.ConstraintEvaluationCorpus.Expected;
import com.paiban.scheduling.eval.ConstraintEvaluationCorpus.Sentence;
import com.paiban.scheduling.eval.ConstraintEvaluationCorpus.Slot;
import com.paiban.scheduling.eval.ConstraintEvaluationCorpus.SlotMode;
import com.paiban.scheduling.web.ConstraintProposalDtos.ConstraintProposalDto;
import com.paiban.scheduling.web.ConstraintProposalDtos.ProposalCandidateDto;
import com.paiban.scheduling.web.ConstraintProposalDtos.ProposalEntryDto;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * The one judge for the fixed corpus (ticket 14/09).
 *
 * <p>A supported sentence passes only when every independent requirement is present
 * with the exact type, object, slots/values and strength, and no extra saveable
 * proposal was invented. A boundary sentence passes only when it produced no saveable
 * mistranslation and kept the clear part (C16). Field-by-field partial credit does not
 * exist: the unit is the whole sentence.
 */
public final class ConstraintEvaluationJudge {

    public record Verdict(boolean passed, String reason) {
        static Verdict pass() {
            return new Verdict(true, "");
        }

        static Verdict fail(String reason) {
            return new Verdict(false, reason);
        }
    }

    private ConstraintEvaluationJudge() {}

    public static Verdict judge(Sentence sentence, List<ConstraintProposalDto> proposals) {
        return sentence.supported()
                ? judgeSupported(sentence, proposals)
                : judgeBoundary(sentence, proposals);
    }

    // ------------------------------------------------------------------ supported

    private static Verdict judgeSupported(
            Sentence sentence, List<ConstraintProposalDto> proposals) {
        List<ConstraintProposalDto> ready = proposals.stream()
                .filter(proposal -> "READY".equals(proposal.status()))
                .toList();
        Set<Integer> used = new java.util.HashSet<>();
        for (Expected expected : sentence.expected()) {
            Map<String, Object> resolved = resolve(expected.params());
            if (resolved == null) {
                return Verdict.fail("语料夹具缺少对象: " + expected.params());
            }
            List<Map<String, Object>> wanted = entries(expected, resolved);
            int match = -1;
            for (int index = 0; index < ready.size(); index++) {
                if (used.contains(index)) {
                    continue;
                }
                if (matches(ready.get(index), expected, wanted)) {
                    match = index;
                    break;
                }
            }
            if (match < 0) {
                return Verdict.fail("缺少或错误的要求: " + describe(expected));
            }
            used.add(match);
        }
        if (used.size() != ready.size()) {
            List<String> extra = new ArrayList<>();
            for (int index = 0; index < ready.size(); index++) {
                if (!used.contains(index)) {
                    extra.add(ready.get(index).type() + " " + ready.get(index).entries());
                }
            }
            return Verdict.fail("多出可加入的建议（扩大范围或重复）: " + String.join("; ", extra));
        }
        return Verdict.pass();
    }

    private static boolean matches(
            ConstraintProposalDto proposal, Expected expected,
            List<Map<String, Object>> wantedEntries) {
        if (!expected.type().equals(proposal.type())
                || !expected.strength().equals(proposal.strength())) {
            return false;
        }
        List<Map<String, Object>> actual = proposal.entries().stream()
                .map(ProposalEntryDto::params)
                .map(ConstraintEvaluationJudge::canonical)
                .toList();
        List<Map<String, Object>> wanted = wantedEntries.stream()
                .map(ConstraintEvaluationJudge::canonical)
                .toList();
        return actual.size() == wanted.size()
                && actual.containsAll(wanted)
                && wanted.containsAll(actual);
    }

    private static List<Map<String, Object>> entries(
            Expected expected, Map<String, Object> resolved) {
        List<Map<String, Object>> entries = new ArrayList<>();
        if (expected.mode() == SlotMode.NONE) {
            entries.add(new LinkedHashMap<>(resolved));
            return entries;
        }
        for (Slot slot : expected.slots()) {
            Map<String, Object> params = new LinkedHashMap<>(resolved);
            params.put("dayOfWeek", slot.day());
            if (slot.periodNo() != null) {
                params.put("periodNo", slot.periodNo());
            }
            entries.add(params);
        }
        return entries;
    }

    /** Turns corpus names into the ids the normalized entries carry. */
    private static Map<String, Object> resolve(Map<String, Object> params) {
        Map<String, Object> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            Object value = entry.getValue();
            switch (entry.getKey()) {
                case "teacher" -> {
                    Long id = ConstraintEvaluationCorpus.TEACHERS.get(String.valueOf(value));
                    if (id == null) {
                        return null;
                    }
                    resolved.put("teacherId", id);
                }
                case "course" -> {
                    Long id = ConstraintEvaluationCorpus.COURSES.get(String.valueOf(value));
                    if (id == null) {
                        return null;
                    }
                    resolved.put("courseId", id);
                }
                case "class" -> {
                    Long id = ConstraintEvaluationCorpus.CLASSES.get(String.valueOf(value));
                    if (id == null) {
                        return null;
                    }
                    resolved.put("classId", id);
                }
                default -> resolved.put(entry.getKey(), value);
            }
        }
        return resolved;
    }

    private static String describe(Expected expected) {
        return expected.type() + " " + expected.strength() + " " + expected.params()
                + (expected.slots().isEmpty() ? "" : " " + expected.slots());
    }

    // ------------------------------------------------------------------ boundary

    private static Verdict judgeBoundary(
            Sentence sentence, List<ConstraintProposalDto> proposals) {
        return switch (sentence.boundary()) {
            case HOMONYM -> judgeHomonym(proposals);
            case VAGUE_AMOUNT -> judgeVague(proposals);
            case CLASS_SCOPE_UNSUPPORTED -> judgeClassScope(proposals);
            case ILLEGAL_PERIOD -> judgeIllegalPeriod(proposals);
        };
    }

    /**
     * C16: the two 张伟 make "张老师" a choice (NEEDS_INPUT with both candidates);
     * the clear 体育晚自习 requirement stays READY. A READY teacher proposal would be
     * a guess and fails the whole run.
     */
    private static Verdict judgeHomonym(List<ConstraintProposalDto> proposals) {
        boolean readyTeacher = proposals.stream()
                .anyMatch(proposal -> "TEACHER_UNAVAILABLE".equals(proposal.type())
                        && "READY".equals(proposal.status()));
        if (readyTeacher) {
            return Verdict.fail("同名教师被直接猜测，生成了可保存条目");
        }
        boolean asked = proposals.stream()
                .filter(proposal -> "TEACHER_UNAVAILABLE".equals(proposal.type()))
                .anyMatch(proposal -> !"READY".equals(proposal.status())
                        && candidateCount(proposal) >= 2);
        if (!asked) {
            return Verdict.fail("同名教师没有作为候选请用户选择");
        }
        long sport = ConstraintEvaluationCorpus.COURSES.get("体育");
        boolean sportReady = proposals.stream()
                .filter(proposal -> "COURSE_NOT_IN_EVENING".equals(proposal.type())
                        && "READY".equals(proposal.status()))
                .anyMatch(proposal -> proposal.entries().stream()
                        .anyMatch(entry -> Objects.equals(
                                number(entry.params().get("courseId")), sport)));
        if (!sportReady) {
            return Verdict.fail("明确且可转换的体育晚自习要求被丢弃");
        }
        return Verdict.pass();
    }

    /** C17: an amount is missing, so nothing may become saveable. */
    private static Verdict judgeVague(List<ConstraintProposalDto> proposals) {
        boolean anyReady = proposals.stream()
                .anyMatch(proposal -> "READY".equals(proposal.status()));
        if (anyReady) {
            return Verdict.fail("含糊的数量被猜成了可保存要求");
        }
        boolean explained = proposals.stream()
                .anyMatch(proposal -> !"READY".equals(proposal.status())
                        && proposal.reason() != null && !proposal.reason().isBlank());
        return explained ? Verdict.pass() : Verdict.fail("含糊要求没有说明需要补充什么");
    }

    /** C19: a class-scoped subject rule must not become a school-wide math rule. */
    private static Verdict judgeClassScope(List<ConstraintProposalDto> proposals) {
        long math = ConstraintEvaluationCorpus.COURSES.get("数学");
        boolean wrong = proposals.stream()
                .filter(proposal -> "MAIN_SUBJECT_MORNING".equals(proposal.type())
                        && "READY".equals(proposal.status()))
                .anyMatch(proposal -> proposal.entries().stream()
                        .anyMatch(entry -> Objects.equals(
                                number(entry.params().get("courseId")), math)));
        if (wrong) {
            return Verdict.fail("不支持的范围被扩成了全校数学上午优先");
        }
        boolean explained = proposals.stream()
                .anyMatch(proposal -> !"READY".equals(proposal.status())
                        && proposal.reason() != null && !proposal.reason().isBlank());
        return explained ? Verdict.pass() : Verdict.fail("范围不支持却没有说明原因");
    }

    /** C20: period 99 is outside the timetable and must never become a saveable row. */
    private static Verdict judgeIllegalPeriod(List<ConstraintProposalDto> proposals) {
        boolean ready = proposals.stream()
                .anyMatch(proposal -> "READY".equals(proposal.status()));
        if (ready) {
            return Verdict.fail("非法课节被截断或忽略后仍生成了可保存条目");
        }
        boolean reported = proposals.stream()
                .anyMatch(proposal -> "INVALID".equals(proposal.status())
                        && proposal.reason() != null
                        && (proposal.reason().contains("99")
                        || proposal.reason().contains("作息")));
        return reported ? Verdict.pass() : Verdict.fail("非法课节没有指出 99 或作息范围");
    }

    private static int candidateCount(ConstraintProposalDto proposal) {
        return proposal.candidates().stream().mapToInt(
                candidate -> candidate.options() == null ? 0 : candidate.options().size()).sum();
    }

    private static Long number(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    /** Numbers become ints and keys are sorted, so JSON width never decides equality. */
    static Map<String, Object> canonical(Map<String, Object> params) {
        Map<String, Object> normalized = new TreeMap<>();
        params.forEach((key, value) -> normalized.put(key,
                value instanceof Number number ? (Object) number.intValue() : value));
        return normalized;
    }

    /** Candidate labels are part of the user-visible answer; unused but kept for tests. */
    public static List<String> candidateLabels(List<ProposalCandidateDto> candidates) {
        return candidates.stream()
                .map(candidate -> candidate.label() + ":" + candidate.options().stream()
                        .map(option -> option.name()).collect(Collectors.joining("/")))
                .toList();
    }
}
