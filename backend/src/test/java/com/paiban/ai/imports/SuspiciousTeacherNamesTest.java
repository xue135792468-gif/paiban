package com.paiban.ai.imports;

import static org.assertj.core.api.Assertions.assertThat;

import com.paiban.ai.imports.ImportDtos.TeacherWarningDto;
import com.paiban.ai.imports.SuspiciousTeacherNames.Occurrence;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The rule that flags a teacher value which is really a course name or a placeholder.
 * It is deterministic on purpose: the same input always yields the same reasons, no
 * fuzzy matching and no model call.
 */
class SuspiciousTeacherNamesTest {

    private static final List<String> COURSES = List.of("语文", "信息科技");

    private List<TeacherWarningDto> inspect(String... values) {
        List<Occurrence> occurrences = new java.util.ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            occurrences.add(new Occurrence(values[i], i + 1));
        }
        return SuspiciousTeacherNames.inspect(occurrences, COURSES);
    }

    private List<String> codes(TeacherWarningDto warning) {
        return warning.reasons().stream().map(reason -> reason.code()).toList();
    }

    @Test
    void flagsAValueThatIsExactlyACourseName() {
        List<TeacherWarningDto> warnings = inspect("语文");

        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).value()).isEqualTo("语文");
        assertThat(codes(warnings.get(0))).containsExactly("sameAsCourse");
        assertThat(warnings.get(0).reasons().get(0).message()).contains("语文");
    }

    @Test
    void flagsMutualContainmentWhenTheShorterSideHasTwoCharacters() {
        List<TeacherWarningDto> warnings = SuspiciousTeacherNames.inspect(
                List.of(new Occurrence("语文教研组", 1)),
                List.of("语文"));

        assertThat(warnings).hasSize(1);
        assertThat(codes(warnings.get(0))).containsExactly("containsCourseName");
        assertThat(warnings.get(0).reasons().get(0).message()).contains("语文");
    }

    @Test
    void flagsACourseNameThatSwallowsTheTeacherValue() {
        List<TeacherWarningDto> warnings = SuspiciousTeacherNames.inspect(
                List.of(new Occurrence("体育", 2)),
                List.of("体育与健康"));

        assertThat(warnings).hasSize(1);
        assertThat(codes(warnings.get(0))).containsExactly("containsCourseName");
    }

    @Test
    void doesNotFlagAOneCharacterOverlap() {
        List<TeacherWarningDto> warnings = SuspiciousTeacherNames.inspect(
                List.of(new Occurrence("化学", 1)),
                List.of("化"));

        assertThat(warnings).isEmpty();
    }

    @Test
    void flagsEveryPlaceholderWordIncludingTheObservedInformationTechnologyValue() {
        for (String placeholder : List.of("老师", "教师", "待定", "暂无", "机房", "自习", "信息技术")) {
            List<TeacherWarningDto> warnings = inspect(placeholder);

            assertThat(warnings).as("占位词 %s 应命中", placeholder).hasSize(1);
            assertThat(codes(warnings.get(0))).containsExactly("placeholder");
            assertThat(warnings.get(0).reasons().get(0).message()).contains(placeholder);
        }
    }

    @Test
    void theInformationTechnologyValueIsNotFlaggedByASubstringGuessAlone() {
        // 信息技术 vs 信息科技 is a lookalike, not a substring: only the word list can catch it.
        List<TeacherWarningDto> warnings = inspect("信息技术");

        assertThat(warnings).hasSize(1);
        assertThat(codes(warnings.get(0))).containsExactly("placeholder");
        assertThat(warnings.get(0).reasons().get(0).message()).contains("信息技术");
    }

    @Test
    void doesNotFlagOrdinaryChineseNames() {
        List<TeacherWarningDto> warnings = inspect("张伟", "李娜", "欧阳明");

        assertThat(warnings).isEmpty();
    }

    @Test
    void collectsMoreThanOneReasonForTheSameValue() {
        List<TeacherWarningDto> warnings = SuspiciousTeacherNames.inspect(
                List.of(new Occurrence("信息技术", 1)),
                List.of("信息技术"));

        assertThat(warnings).hasSize(1);
        assertThat(codes(warnings.get(0))).containsExactlyInAnyOrder("sameAsCourse", "placeholder");
    }

    @Test
    void aggregatesOneWarningPerValueWithLessonCountsAndRowNumbers() {
        List<TeacherWarningDto> warnings = inspect("信息 技术", "张伟", "信息技术", "信息技术（原教师）");

        assertThat(warnings).hasSize(1);
        TeacherWarningDto warning = warnings.get(0);
        assertThat(warning.value()).isEqualTo("信息 技术");
        assertThat(warning.lessonCount()).isEqualTo(3);
        assertThat(warning.rowNumbers()).containsExactly(1, 3, 4);
    }

    @Test
    void normalizesFullWidthSpacesAndBracketedNotes() {
        List<TeacherWarningDto> warnings = inspect("　信息\u3000技术（代课）　");

        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).value()).isEqualTo("信息\u3000技术（代课）");
    }
}
