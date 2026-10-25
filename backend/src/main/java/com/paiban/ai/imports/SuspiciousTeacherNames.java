package com.paiban.ai.imports;

import com.paiban.ai.imports.ImportDtos.TeacherWarningDto;
import com.paiban.ai.imports.ImportDtos.TeacherWarningReasonDto;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

/**
 * Flags teacher values that are probably not a person's name: a course name, a fragment
 * of one, or a post/placeholder word. The rules are deterministic — the same workbook
 * always produces the same warnings, with no similarity score and no model call — because
 * the preview has to be explainable and testable.
 *
 * <p>What counts as "the same value" is {@link #normalize}: NFKC (full-width to
 * half-width), bracketed notes dropped ({@code 张三（代课）} → {@code 张三}), whitespace and
 * common punctuation removed, case folded. One warning per normalized value, with the
 * lesson count and the preview row numbers aggregated.
 */
public final class SuspiciousTeacherNames {

    /**
     * Post names, placeholders and room kinds that real workbooks put in the teacher
     * column. "信息技术" is here because the observed workbook used the subject as the
     * teacher of 14 信息科技 lessons; that pair is a lookalike, not a substring, so only an
     * explicit term catches it.
     */
    public static final List<String> PLACEHOLDER_WORDS =
            List.of("老师", "教师", "待定", "暂无", "机房", "自习", "信息技术", "未定");

    /** A value seen in the workbook; {@code rowNumber} is 1-based, 0 for a name sheet row. */
    public record Occurrence(String value, int rowNumber) {}

    /** The shorter side of a substring match must be at least this long. */
    private static final int MIN_OVERLAP = 2;

    private static final Pattern BRACKETED =
            Pattern.compile("[（(\\[【〔〖][^）)\\]】〕〗]*[）)\\]】〕〗]");
    private static final Pattern NOISE =
            Pattern.compile("[\\s\\u3000\\p{Punct}，。、；：？！·—…“”‘’「」『』《》〈〉【】〔〕〖〗]");

    private SuspiciousTeacherNames() {}

    /** NFKC, bracketed notes removed, no whitespace or punctuation, lower case. */
    public static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String text = Normalizer.normalize(value, Normalizer.Form.NFKC);
        text = BRACKETED.matcher(text).replaceAll("");
        text = NOISE.matcher(text).replaceAll("");
        return text.toLowerCase(Locale.ROOT);
    }

    /**
     * One warning per distinct normalized value that hits at least one rule. Values that
     * hit nothing are absent, so the response carries only what the user has to look at.
     */
    public static List<TeacherWarningDto> inspect(
            List<Occurrence> occurrences, Collection<String> courseNames) {
        Set<String> courses = new LinkedHashSet<>();
        if (courseNames != null) {
            for (String course : courseNames) {
                String normalized = normalize(course);
                if (StringUtils.hasText(normalized)) {
                    courses.add(normalized);
                }
            }
        }
        Map<String, Aggregate> aggregated = new LinkedHashMap<>();
        if (occurrences != null) {
            for (Occurrence occurrence : occurrences) {
                if (occurrence == null || occurrence.value() == null) {
                    continue;
                }
                String raw = occurrence.value().strip();
                String normalized = normalize(raw);
                if (!StringUtils.hasText(normalized)) {
                    continue;
                }
                Aggregate aggregate = aggregated.computeIfAbsent(
                        normalized, key -> new Aggregate(raw));
                if (occurrence.rowNumber() > 0) {
                    aggregate.lessonCount++;
                    aggregate.rowNumbers.add(occurrence.rowNumber());
                }
            }
        }
        List<TeacherWarningDto> warnings = new ArrayList<>();
        for (Map.Entry<String, Aggregate> entry : aggregated.entrySet()) {
            List<TeacherWarningReasonDto> reasons = reasonsOf(entry.getKey(), courses);
            if (reasons.isEmpty()) {
                continue;
            }
            Aggregate aggregate = entry.getValue();
            warnings.add(new TeacherWarningDto(
                    aggregate.value, List.copyOf(reasons), aggregate.lessonCount,
                    List.copyOf(aggregate.rowNumbers)));
        }
        return List.copyOf(warnings);
    }

    private static List<TeacherWarningReasonDto> reasonsOf(String value, Set<String> courses) {
        List<TeacherWarningReasonDto> reasons = new ArrayList<>();
        boolean sameAsCourse = false;
        for (String course : courses) {
            if (course.equals(value)) {
                reasons.add(new TeacherWarningReasonDto(
                        "sameAsCourse", "与课程「" + course + "」名称相同"));
                sameAsCourse = true;
                break;
            }
        }
        if (!sameAsCourse) {
            for (String course : courses) {
                if (isOverlap(value, course)) {
                    reasons.add(new TeacherWarningReasonDto(
                            "containsCourseName",
                            value.contains(course)
                                    ? "包含课程名「" + course + "」"
                                    : "是课程「" + course + "」名称的一部分"));
                    break;
                }
            }
        }
        for (String word : PLACEHOLDER_WORDS) {
            if (value.contains(normalize(word))) {
                reasons.add(new TeacherWarningReasonDto(
                        "placeholder", "命中占位词「" + word + "」"));
                break;
            }
        }
        return reasons;
    }

    /** Either side contains the other, and the shorter side is at least two characters. */
    private static boolean isOverlap(String value, String course) {
        int shorter = Math.min(value.length(), course.length());
        return shorter >= MIN_OVERLAP && (value.contains(course) || course.contains(value));
    }

    private static final class Aggregate {
        private final String value;
        private final Set<Integer> rowNumbers = new TreeSet<>();
        private int lessonCount;

        private Aggregate(String value) {
            this.value = value;
        }
    }
}
