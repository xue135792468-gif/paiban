package com.paiban.scheduling.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paiban.ai.AiResult;
import com.paiban.scheduling.eval.ConstraintEvaluationCorpus.Sentence;
import com.paiban.scheduling.service.ConstraintProposalParser;
import com.paiban.scheduling.service.ConstraintProposalPrompt;
import com.paiban.scheduling.web.ConstraintProposalDtos.ConstraintProposalDto;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Ticket 14/09: the corpus, the judge and the budget guard are rehearsed without a
 * real provider. Passing here proves the plumbing, not that any model understands the
 * sentences.
 */
class ConstraintEvaluationHarnessTest {

    private final ConstraintEvaluationHarness harness = new ConstraintEvaluationHarness(
            new ConstraintProposalPrompt(),
            new ConstraintProposalParser(new ObjectMapper()),
            new ObjectMapper());

    @Test
    void theStubAnswerPassesEverySupportedSentenceAndEveryBoundary() {
        for (Sentence sentence : ConstraintEvaluationCorpus.sentences()) {
            ConstraintEvaluationHarness.Attempt attempt = harness.evaluate(
                    sentence, answer(sentence));
            assertThat(attempt.verdict().passed())
                    .as("%s: %s", sentence.code(), attempt.verdict().reason())
                    .isTrue();
        }
    }

    @Test
    void aGuessedHomonymFailsC16EvenWhenTheSportItemIsRight() {
        Sentence c16 = ConstraintEvaluationCorpus.sentences().stream()
                .filter(sentence -> sentence.code().equals("C16"))
                .findFirst().orElseThrow();
        String guessed = """
                {"proposals":[
                  {"sourceText":"张老师周三下午不排","type":"TEACHER_UNAVAILABLE",
                   "strength":"HARD","status":"READY","teacherName":"张伟甲","days":[3],
                   "section":"AFTERNOON"},
                  {"sourceText":"体育不排晚自习","type":"COURSE_NOT_IN_EVENING",
                   "strength":"HARD","status":"READY","courseName":"体育"}
                ]}""";
        ConstraintEvaluationHarness.Attempt attempt = harness.evaluate(
                c16, success(guessed));
        assertThat(attempt.verdict().passed()).isFalse();
        assertThat(attempt.verdict().reason()).contains("同名");
    }

    @Test
    void c05InheritsTheTypeDefaultWhenTheSentenceHasNoStrengthWord() {
        Sentence c05 = byCode("C05");
        String inherited = """
                {"proposals":[{"sourceText":"化学课安排在实验室",
                  "type":"COURSE_REQUIRED_ROOM_TYPE","strength":"INHERIT","status":"READY",
                  "courseName":"化学","roomType":"LAB"}]}""";
        assertThat(harness.evaluate(c05, success(inherited)).verdict().passed()).isTrue();

        String upgraded = inherited.replace("\"INHERIT\"", "\"HARD\"");
        assertThat(harness.evaluate(c05, success(upgraded)).verdict().passed()).isFalse();
    }

    @Test
    void c16TurnsTheSpokenHomonymIntoConcreteCandidates() {
        Sentence c16 = byCode("C16");
        String answer = """
                {"proposals":[
                  {"sourceText":"张老师周三下午不排","type":"TEACHER_UNAVAILABLE",
                   "strength":"HARD","status":"NEEDS_INPUT","teacherName":"张老师",
                   "reason":"有两位张姓教师","days":[3],"section":"AFTERNOON"},
                  {"sourceText":"体育不排晚自习","type":"COURSE_NOT_IN_EVENING",
                   "strength":"HARD","status":"READY","courseName":"体育"}
                ]}""";
        ConstraintEvaluationHarness.Attempt attempt = harness.evaluate(c16, success(answer));
        assertThat(attempt.verdict().passed()).as(attempt.verdict().reason()).isTrue();

        ConstraintProposalDto teacher = attempt.proposals().stream()
                .filter(proposal -> "TEACHER_UNAVAILABLE".equals(proposal.type()))
                .findFirst().orElseThrow();
        assertThat(teacher.candidates()).hasSize(1);
        assertThat(teacher.candidates().get(0).options()).hasSize(2);
    }

    @Test
    void aScopeWidenedC19FailsEvenWhenAnotherProposalSaysUnsupported() {
        Sentence c19 = ConstraintEvaluationCorpus.sentences().stream()
                .filter(sentence -> sentence.code().equals("C19"))
                .findFirst().orElseThrow();
        String widened = """
                {"proposals":[
                  {"sourceText":"毕业班的数学尽量上午上","type":"MAIN_SUBJECT_MORNING",
                   "strength":"SOFT","status":"READY","courseName":"数学",
                   "beforePeriodNo":4},
                  {"sourceText":"其他年级不作要求","type":"MAIN_SUBJECT_MORNING",
                   "strength":"SOFT","status":"UNSUPPORTED","reason":"不能限定毕业班"}
                ]}""";
        ConstraintEvaluationHarness.Attempt attempt = harness.evaluate(
                c19, success(widened));
        assertThat(attempt.verdict().passed()).isFalse();
        assertThat(attempt.verdict().reason()).contains("全校数学");
    }

    @Test
    void theBudgetReservesTheWorstCaseAndStopsBeforeAnUnaffordableCall() {
        ConstraintEvaluationBudget guard = new ConstraintEvaluationBudget(
                BigDecimal.valueOf(20), BigDecimal.valueOf(1), BigDecimal.valueOf(1));
        BigDecimal worst = guard.worstCaseCny("system", "user");
        assertThat(worst).isGreaterThan(BigDecimal.valueOf(4));
        int calls = 0;
        while (guard.canAfford(worst)) {
            guard.reserve(worst);
            calls++;
        }
        assertThat(calls).isBetween(1, 5);
        assertThat(guard.remaining()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(guard.reserved()).isLessThanOrEqualTo(BigDecimal.valueOf(20));
    }

    @Test
    void anUnreliablePriceBlocksTheRoundBeforeAnyCall() {
        assertThat(ConstraintEvaluationBudget.reliable(null, BigDecimal.ONE)).isFalse();
        assertThat(ConstraintEvaluationBudget.reliable(BigDecimal.ZERO, BigDecimal.ONE)).isFalse();
        assertThat(ConstraintEvaluationBudget.reliable(BigDecimal.ONE, BigDecimal.ONE)).isTrue();
    }

    private Sentence byCode(String code) {
        return ConstraintEvaluationCorpus.sentences().stream()
                .filter(sentence -> sentence.code().equals(code))
                .findFirst().orElseThrow();
    }

    private AiResult answer(Sentence sentence) {
        return success(ConstraintEvaluationStub.answer(sentence));
    }

    private AiResult success(String content) {
        return new AiResult(true, content, null, null, 1L, "stub", 1L, "stub",
                10, 20, 1L, content);
    }
}
