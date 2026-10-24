package com.paiban.substitution.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
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
    void tableFormatIsHumanReadable() {
        Run run = run("plan", "--snapshot", DIR + "snapshot.json",
                "--request", DIR + "request.json", "--format", "table");

        assertThat(run.code()).isZero();
        assertThat(run.out()).contains("方案 1").contains("环长 3");
    }
}
