package com.paiban.ai.imports;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Grade tokens come from how schools actually name classes, so both numeral styles appear. */
class GradeInferenceTest {

    @Test
    void aBareNumeralBeforeAParenthesisBecomesAYearGrade() {
        assertThat(GradeInference.infer("八（1）")).isEqualTo("八年级");
    }

    @Test
    void anExplicitYearGradePrefixIsKeptAsWritten() {
        assertThat(GradeInference.infer("八年级1班")).isEqualTo("八年级");
    }

    @Test
    void aSeniorHighPrefixIsKeptAsWritten() {
        assertThat(GradeInference.infer("高一1班")).isEqualTo("高一");
    }

    @Test
    void anArabicNumeralYearGradePrefixIsKeptAsWritten() {
        assertThat(GradeInference.infer("7年级(2)")).isEqualTo("7年级");
    }

    @Test
    void aJuniorHighPrefixIsKeptAsWritten() {
        assertThat(GradeInference.infer("初一3班")).isEqualTo("初一");
    }

    @Test
    void aNameWithoutAGradePrefixInfersNothing() {
        assertThat(GradeInference.infer("实验班")).isNull();
    }

    @Test
    void anExistingGradeIsMatchedIgnoringWidthWhitespaceAndCase() {
        List<String> existing = List.of("一年级", "Grade 7", "８年级");

        assertThat(GradeInference.matchExisting("8年级", existing)).isEqualTo("８年级");
        assertThat(GradeInference.matchExisting("grade7", existing)).isEqualTo("Grade 7");
        assertThat(GradeInference.matchExisting("一年级", existing)).isEqualTo("一年级");
    }

    @Test
    void anInferredGradeWithNoExistingMatchComesBackUnchanged() {
        assertThat(GradeInference.matchExisting("七年级", List.of("一年级"))).isEqualTo("七年级");
        assertThat(GradeInference.matchExisting("八年级", List.of())).isEqualTo("八年级");
        assertThat(GradeInference.matchExisting(null, List.of("一年级"))).isNull();
    }
}
