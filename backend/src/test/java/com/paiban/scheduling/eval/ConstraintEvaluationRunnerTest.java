package com.paiban.scheduling.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.paiban.scheduling.eval.ConstraintEvaluationRunner.ModelReport;
import com.paiban.scheduling.eval.ConstraintEvaluationRunner.Report;
import com.paiban.scheduling.eval.ConstraintEvaluationRunner.Request;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Ticket 14/09: the harness loop and its budget guard, run with the deterministic stub
 * model. No real provider is reached, and the report files are the deliverable shape.
 */
@SpringBootTest
@ActiveProfiles("test")
class ConstraintEvaluationRunnerTest {

    @Autowired
    private ConstraintEvaluationRunner runner;

    @TempDir
    private Path tempDir;

    @Test
    void aStubRoundPassesTheCorpusAndWritesBothReports() throws Exception {
        Report report = runner.run(new Request(
                tempDir.resolve("stub-eval"),
                BigDecimal.valueOf(20),
                new BigDecimal("0.001"),
                new BigDecimal("0.002"),
                3,
                List.of(),
                true,
                false));

        assertThat(report.verdict()).isEqualTo("PASSED");
        assertThat(report.models()).hasSize(1);
        ModelReport model = report.models().get(0);
        assertThat(model.supportedTotal()).isEqualTo(54);
        assertThat(model.supportedPass()).isEqualTo(54);
        assertThat(model.boundaryTotal()).isEqualTo(12);
        assertThat(model.boundaryPass()).isEqualTo(12);
        assertThat(model.runs()).hasSize(66);
        assertThat(report.reservedCny()).isLessThanOrEqualTo(BigDecimal.valueOf(20));
        assertThat(Files.readString(tempDir.resolve("stub-eval.md")))
                .contains("明确支持").contains("边界");
        assertThat(Files.readString(tempDir.resolve("stub-eval.json")))
                .contains("C21").contains("PASSED");
    }

    @Test
    void changingTheFixedRepetitionCountIsRefused() {
        Report report = runner.run(new Request(
                tempDir.resolve("ten-runs"), BigDecimal.valueOf(20),
                new BigDecimal("0.001"), new BigDecimal("0.002"), 10,
                List.of(), true, false));

        assertThat(report.verdict()).isEqualTo("INVALID_REQUEST");
        assertThat(report.blockedReason()).contains("3 次");
        assertThat(report.models()).isEmpty();
    }

    @Test
    void aBudgetAboveTwentyIsRefused() {
        Report report = runner.run(new Request(
                tempDir.resolve("big-budget"), BigDecimal.valueOf(21),
                new BigDecimal("0.001"), new BigDecimal("0.002"), 3,
                List.of(), true, false));

        assertThat(report.verdict()).isEqualTo("INVALID_REQUEST");
        assertThat(report.blockedReason()).contains("20 元");
        assertThat(report.models()).isEmpty();
    }

    @Test
    void moreThanTwoModelsIsRefused() {
        Report report = runner.run(new Request(
                tempDir.resolve("too-many"),
                BigDecimal.valueOf(20),
                new BigDecimal("0.001"),
                new BigDecimal("0.002"),
                3,
                List.of(
                        new ConstraintEvaluationRunner.ModelRef(1L, 1L, "a"),
                        new ConstraintEvaluationRunner.ModelRef(1L, 2L, "b"),
                        new ConstraintEvaluationRunner.ModelRef(1L, 3L, "c")),
                true,
                false));

        assertThat(report.verdict()).isEqualTo("INVALID_REQUEST");
        assertThat(report.blockedReason()).contains("两个");
    }

    @Test
    void anUnknownPriceStopsBeforeAnyCall() {
        Report report = runner.run(new Request(
                tempDir.resolve("no-price"), BigDecimal.valueOf(20), null, null, 3,
                List.of(), true, false));

        assertThat(report.verdict()).isEqualTo("PRICE_UNRELIABLE");
        assertThat(report.models()).isEmpty();
        assertThat(report.reservedCny()).isEqualByComparingTo("0");
    }

    @Test
    void aBudgetThatCannotCoverTheFirstWorstCaseStopsIncomplete() {
        Report report = runner.run(new Request(
                tempDir.resolve("tiny-budget"),
                BigDecimal.ONE,
                BigDecimal.TEN,
                BigDecimal.TEN,
                3,
                List.of(),
                true,
                false));

        assertThat(report.verdict()).isEqualTo("INCOMPLETE");
        assertThat(report.blockedReason()).isEqualTo("BUDGET_EXHAUSTED");
        assertThat(report.models()).hasSize(1);
        assertThat(report.models().get(0).runs()).isEmpty();
        assertThat(report.models().get(0).complete()).isFalse();
    }
}
