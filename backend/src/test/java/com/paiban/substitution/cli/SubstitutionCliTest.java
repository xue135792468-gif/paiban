package com.paiban.substitution.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Golden-file coverage for the read-only CLI. */
class SubstitutionCliTest {

    private static final String DIR = "src/test/resources/substitution/cli/";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private record Run(int code, String out, String err) {}

    private static Run run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = SubstitutionCli.run(args,
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Run(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void planProducesThreeMovesAndExitsZero() throws Exception {
        Run run = run("plan", "--snapshot", DIR + "snapshot.json", "--request", DIR + "request.json");

        assertThat(run.code()).isZero();
        JsonNode json = MAPPER.readTree(run.out());
        assertThat(json.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(json.get("status").asText()).isEqualTo("OK");
        JsonNode candidate = json.get("candidates").get(0);
        assertThat(candidate.get("cycleLength").asInt()).isEqualTo(3);
        assertThat(candidate.get("impactDays").asInt()).isEqualTo(1);
        assertThat(candidate.get("impactTeachers").asInt()).isEqualTo(3);
        List<Long> entries = new ArrayList<>();
        List<Long> targets = new ArrayList<>();
        candidate.get("moves").forEach(move -> {
            entries.add(move.get("entryId").asLong());
            targets.add(move.get("toPeriodId").asLong());
        });
        assertThat(entries).containsExactlyInAnyOrder(1000L, 1001L, 1002L);
        assertThat(targets).containsExactlyInAnyOrder(102L, 103L, 101L);
    }

    @Test
    void planExitsTwoWhenTheTargetSlotIsEmpty() throws Exception {
        Run run = run("plan", "--snapshot", DIR + "snapshot.json",
                "--request", DIR + "request-empty-target.json");

        assertThat(run.code()).isEqualTo(2);
        JsonNode json = MAPPER.readTree(run.out());
        assertThat(json.get("status").asText()).isEqualTo("NO_SOLUTION");
        assertThat(json.get("diagnoses").toString()).contains("TARGET_EMPTY");
    }

    @Test
    void verifyPassesForThePlannedAfterState() throws Exception {
        Run run = run("verify", "--before", DIR + "snapshot.json", "--after", DIR + "after.json");

        assertThat(run.code()).isZero();
        JsonNode json = MAPPER.readTree(run.out());
        assertThat(json.get("status").asText()).isEqualTo("PASS");
        assertThat(json.get("issues")).isEmpty();
    }

    @Test
    void verifyRejectsATamperedAfterState() throws Exception {
        Run run = run("verify", "--before", DIR + "snapshot.json", "--after", DIR + "after-broken.json");

        assertThat(run.code()).isEqualTo(1);
        JsonNode json = MAPPER.readTree(run.out());
        assertThat(json.get("status").asText()).isEqualTo("FAIL");
        assertThat(json.get("issues").toString()).contains("CLASS_DOUBLE_BOOKED");
    }

    @Test
    void missingFieldsExitOneWithAReadableError() {
        Run run = run("plan", "--snapshot", DIR + "snapshot.json");

        assertThat(run.code()).isEqualTo(1);
        assertThat(run.err()).contains("--request");
    }

    @Test
    void planOutputCanBeFedBackIntoVerify() throws Exception {
        Run plan = run("plan", "--snapshot", DIR + "snapshot.json", "--request", DIR + "request.json");
        assertThat(plan.code()).isZero();

        Path planFile = Files.createTempFile("substitution-plan", ".json");
        Files.writeString(planFile, plan.out(), StandardCharsets.UTF_8);
        Run verify = run("verify", "--snapshot", DIR + "snapshot.json", "--plan", planFile.toString());

        assertThat(verify.code()).isZero();
        assertThat(MAPPER.readTree(verify.out()).get("status").asText()).isEqualTo("PASS");
    }

    @Test
    void unknownFlagsAndPresetsAreRejected() {
        Run unknownFlag = run("plan", "--snapshot", DIR + "snapshot.json",
                "--request", DIR + "request.json", "--bogus", "1");
        assertThat(unknownFlag.code()).isEqualTo(1);
        assertThat(unknownFlag.err()).contains("未知参数");

        Run badPreset = run("plan", "--snapshot", DIR + "snapshot.json",
                "--request", DIR + "request.json", "--preset", "bogus");
        assertThat(badPreset.code()).isEqualTo(1);
        assertThat(badPreset.err()).contains("未知排序预设");
    }

    @Test
    void tableFormatIsHumanReadable() {
        Run run = run("plan", "--snapshot", DIR + "snapshot.json",
                "--request", DIR + "request.json", "--format", "table");

        assertThat(run.code()).isZero();
        assertThat(run.out()).contains("方案 1").contains("环长 3");
    }

    @Test
    void plannerReadsEveryTeacherUnavailableEntry() throws Exception {
        // Two entries: teacher 3 may not teach in Mon-1, which is the only slot the
        // three-teacher cycle would hand back, so the plan disappears.
        Run run = run("plan", "--snapshot", DIR + "snapshot-unavailable.json",
                "--request", DIR + "request.json");

        assertThat(run.code()).isEqualTo(2);
        JsonNode json = MAPPER.readTree(run.out());
        assertThat(json.get("status").asText()).isEqualTo("NO_SOLUTION");
        assertThat(json.get("diagnoses").toString()).contains("TEACHER_UNAVAILABLE");
    }

    @Test
    void verifierRejectsTheSameEntryThePlannerRefused() throws Exception {
        // after.json moves teacher 3 into Mon-1, exactly what the entry forbids.
        Run run = run("verify", "--before", DIR + "snapshot-unavailable.json",
                "--after", DIR + "after.json");

        assertThat(run.code()).isEqualTo(1);
        JsonNode json = MAPPER.readTree(run.out());
        assertThat(json.get("status").asText()).isEqualTo("FAIL");
        assertThat(json.get("issues").toString()).contains("TEACHER_UNAVAILABLE");
    }

    @Test
    void plannerReadsEveryPreferredSlotEntry() throws Exception {
        // The only cycle hands Mon-1 back to teacher 3, whose preferred slots are
        // Mon-3 and Mon-4: the OR set cannot be satisfied there, so no plan survives.
        Run run = run("plan", "--snapshot", DIR + "snapshot-target-entries.json",
                "--request", DIR + "request.json");

        assertThat(run.code()).isEqualTo(2);
        assertThat(MAPPER.readTree(run.out()).get("status").asText()).isEqualTo("NO_SOLUTION");
    }

    @Test
    void verifierReadsEveryPreferredSlotEntry() throws Exception {
        // after.json moves teacher 3 into Mon-1, outside both preferred slots.
        Run run = run("verify", "--before", DIR + "snapshot-target-entries.json",
                "--after", DIR + "after.json");

        assertThat(run.code()).isEqualTo(1);
        JsonNode json = MAPPER.readTree(run.out());
        assertThat(json.get("status").asText()).isEqualTo("FAIL");
        assertThat(json.get("issues").toString()).contains("TEACHER_PREFERRED_SLOT");
    }

    @Test
    void theCliReadsAGlobalThresholdEntry() throws Exception {
        // Ticket 10/04: the day limits are entry-backed now, so a snapshot stores the
        // global default as one entry. Everyone stays inside 6, so nothing changes.
        Run plan = run("plan", "--snapshot", DIR + "snapshot-threshold-global.json",
                "--request", DIR + "request.json");
        assertThat(plan.code()).isZero();
        assertThat(MAPPER.readTree(plan.out()).get("candidates").get(0).get("cycleLength").asInt())
                .isEqualTo(3);

        Run verify = run("verify", "--before", DIR + "snapshot-threshold-global.json",
                "--after", DIR + "after.json");
        assertThat(verify.code()).isZero();
        assertThat(MAPPER.readTree(verify.out()).get("status").asText()).isEqualTo("PASS");
    }

    @Test
    void aThresholdExceptionOverridesTheGlobalDefaultInTheCli() throws Exception {
        // The plan turns teacher 2's Monday gap into two consecutive lessons: over the
        // exception's 1, still inside the untouched objects' global 3.
        Run rejected = run("verify", "--before", DIR + "snapshot-threshold-scope.json",
                "--after", DIR + "after-threshold.json");
        assertThat(rejected.code()).isEqualTo(1);
        JsonNode rejectedJson = MAPPER.readTree(rejected.out());
        assertThat(rejectedJson.get("status").asText()).isEqualTo("FAIL");
        assertThat(rejectedJson.get("issues").toString()).contains("TEACHER_MAX_CONSECUTIVE");

        Run accepted = run("verify", "--before", DIR + "snapshot-threshold-consecutive-global.json",
                "--after", DIR + "after-threshold.json");
        assertThat(accepted.err()).isEmpty();
        assertThat(accepted.code()).isZero();
        assertThat(MAPPER.readTree(accepted.out()).get("status").asText()).isEqualTo("PASS");
    }

    @Test
    void legacyParamsSnapshotKeepsItsTargetRule() throws Exception {
        // A pre-ADR snapshot carries one `params` map per type: it is that type's single
        // entry, so the rule still judges the plan it produces.
        Run rejected = run("verify", "--before", DIR + "snapshot-legacy-no-first.json",
                "--after", DIR + "after.json");
        assertThat(rejected.code()).isEqualTo(1);
        assertThat(MAPPER.readTree(rejected.out()).get("issues").toString())
                .contains("TEACHER_NO_FIRST_PERIOD");

        Run accepted = run("verify", "--before", DIR + "snapshot-legacy-no-first.json",
                "--after", DIR + "after-later-slot.json");
        assertThat(accepted.err()).isEmpty();
        assertThat(accepted.code()).isZero();
        assertThat(MAPPER.readTree(accepted.out()).get("status").asText()).isEqualTo("PASS");
    }
}
