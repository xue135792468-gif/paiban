package com.paiban.scheduling.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SubjectMatchTest {

    @Test
    void caseAndSurroundingSpacesAreTheSameSubject() {
        assertThat(SubjectMatch.of(" 语文 ", "语文")).isEqualTo(SubjectMatch.Result.MATCH);
        assertThat(SubjectMatch.of("Math", " math ")).isEqualTo(SubjectMatch.Result.MATCH);
        assertThat(SubjectMatch.of("语文", "语文")).isEqualTo(SubjectMatch.Result.MATCH);
    }

    @Test
    void differentSubjectsMismatch() {
        assertThat(SubjectMatch.of("语文", "数学")).isEqualTo(SubjectMatch.Result.MISMATCH);
    }

    @Test
    void missingOrBlankSubjectsAreUnknown() {
        assertThat(SubjectMatch.of(null, "语文")).isEqualTo(SubjectMatch.Result.UNKNOWN);
        assertThat(SubjectMatch.of("语文", null)).isEqualTo(SubjectMatch.Result.UNKNOWN);
        assertThat(SubjectMatch.of("  ", "语文")).isEqualTo(SubjectMatch.Result.UNKNOWN);
        assertThat(SubjectMatch.of(null, null)).isEqualTo(SubjectMatch.Result.UNKNOWN);
    }

    @Test
    void displayFallsBackForMissingSubjects() {
        assertThat(SubjectMatch.display(" 语文 ")).isEqualTo("语文");
        assertThat(SubjectMatch.display(" ")).isEqualTo("未设置");
        assertThat(SubjectMatch.display(null)).isEqualTo("未设置");
    }
}
