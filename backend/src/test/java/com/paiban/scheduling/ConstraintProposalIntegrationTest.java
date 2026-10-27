package com.paiban.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

import com.paiban.ai.AiGateway;
import com.paiban.ai.AiRequest;
import com.paiban.ai.AiResult;
import com.paiban.ai.AiTaskType;
import com.paiban.common.settings.SettingsService;
import com.paiban.identity.domain.PermissionRepository;
import com.paiban.identity.domain.Permissions;
import com.paiban.identity.domain.Role;
import com.paiban.identity.domain.RoleRepository;
import com.paiban.scheduling.domain.ConstraintGenerationRunRepository;
import com.paiban.scheduling.domain.ConstraintSaveAuditRepository;
import com.paiban.support.ApiIntegrationTest;
import com.paiban.support.TestData;
import io.restassured.response.Response;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

/**
 * Ticket 14/08 through the public REST surface and a deterministic gateway stub. The
 * real model is never called: the point is the normalization contract, the run
 * identity, the time/retry boundary, cancellation and record permissions.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ConstraintProposalIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private FakeAiGateway gateway;

    @Autowired
    private SettingsService settingsService;

    @Autowired
    private ConstraintGenerationRunRepository runs;

    @Autowired
    private ConstraintSaveAuditRepository audits;

    @Autowired
    private RoleRepository roles;

    @Autowired
    private PermissionRepository permissions;

    @BeforeEach
    void enableFeature() {
        settingsService.put(SettingsService.AI_NATURAL_LANGUAGE_CONSTRAINTS, "true");
        gateway.reset();
    }

    @Test
    void c01ExpandsTheWholeAfternoonFromTheRealTimetable() {
        String token = adminToken();
        long semesterId = semesterWithPeriods(token);
        String teacherName = "王明" + TestData.suffix();
        long teacher = TestData.teacher(token, teacherName);
        gateway.respond = ok("""
                {"proposals":[{"sourceText":"王老师周三下午不排课。","type":"TEACHER_UNAVAILABLE",
                "strength":"HARD","status":"READY","teacherName":"%s","days":[3],
                "section":"AFTERNOON"}]}""".formatted(teacherName));

        Response run = generate(token, semesterId, "run-c01", "王老师周三下午不排课。");

        run.then().statusCode(200)
                .body("status", equalTo("SUCCEEDED"))
                .body("proposals", hasSize(1))
                .body("proposals[0].status", equalTo("READY"))
                .body("proposals[0].effectiveHard", equalTo(true))
                .body("proposals[0].entries", hasSize(4));
        List<Integer> periods = run.jsonPath().getList("proposals[0].entries.params.periodNo", Integer.class);
        assertThat(periods).containsExactly(5, 6, 7, 8);
        List<Map<String, Object>> entries = run.jsonPath().getList("proposals[0].entries");
        assertThat(entries).allMatch(entry -> ((Number) ((Map<?, ?>) entry.get("params"))
                .get("teacherId")).longValue() == teacher);
        assertThat(entries.stream().map(entry -> entry.get("groupKey")).distinct().count())
                .isEqualTo(1);
    }

    @Test
    void c21ExpandsEveryTeachingDayAndTheNamedMorningPeriods() {
        String token = adminToken();
        long semesterId = semesterWithPeriods(token);
        String courseName = "体育" + TestData.suffix();
        long course = TestData.course(token, courseName);
        gateway.respond = ok("""
                {"proposals":[{"sourceText":"体育课不排上午第1、2节。","type":"COURSE_UNAVAILABLE",
                "strength":"HARD","status":"READY","courseName":"%s","allDays":true,
                "section":"MORNING","sectionOrdinals":[1,2]}]}""".formatted(courseName));

        Response run = generate(token, semesterId, "run-c21", "体育课不排上午第1、2节。");

        run.then().statusCode(200)
                .body("proposals[0].entries", hasSize(10));
        List<Map<String, Object>> entries = run.jsonPath().getList("proposals[0].entries");
        assertThat(entries).allMatch(entry -> {
            Map<?, ?> params = (Map<?, ?>) entry.get("params");
            int period = ((Number) params.get("periodNo")).intValue();
            return period == 1 || period == 2;
        });
        assertThat(entries.stream()
                .map(entry -> ((Map<?, ?>) entry.get("params")).get("dayOfWeek"))
                .distinct().count()).isEqualTo(5);
        assertThat(entries).allMatch(entry -> ((Number) ((Map<?, ?>) entry.get("params"))
                .get("courseId")).longValue() == course);
    }

    @Test
    void c22CoversOnlyTheNamedDiscontinuousSlots() {
        String token = adminToken();
        long semesterId = semesterWithPeriods(token);
        String courseName = "化学" + TestData.suffix();
        TestData.course(token, courseName);
        gateway.respond = ok("""
                {"proposals":[{"sourceText":"化学周三、周五上午第1节和下午第2节都不排。",
                "type":"COURSE_UNAVAILABLE","strength":"HARD","status":"READY",
                "courseName":"%s","days":[3,5],"periods":[1,6]}]}""".formatted(courseName));

        Response run = generate(token, semesterId, "run-c22",
                "化学周三、周五上午第1节和下午第2节都不排。");

        run.then().statusCode(200).body("proposals[0].entries", hasSize(4));
        List<Map<String, Object>> entries = run.jsonPath().getList("proposals[0].entries");
        assertThat(entries.stream().map(entry -> {
            Map<?, ?> params = (Map<?, ?>) entry.get("params");
            return params.get("dayOfWeek") + "-" + params.get("periodNo");
        })).containsExactlyInAnyOrder("3-1", "3-6", "5-1", "5-6");
    }

    @Test
    void c16AsksTheUserToChooseTheHomonymWhileTheClearCourseStaysReady() {
        String token = adminToken();
        long semesterId = semesterWithPeriods(token);
        TestData.teacher(token, "张伟甲" + TestData.suffix());
        TestData.teacher(token, "张伟乙" + TestData.suffix());
        String courseName = "体育" + TestData.suffix();
        TestData.course(token, courseName);
        // Both names start with 张: the prefix match is ambiguous and must not pick one.
        gateway.respond = ok("""
                {"proposals":[
                  {"sourceText":"张老师周三下午不排","type":"TEACHER_UNAVAILABLE",
                   "strength":"HARD","status":"READY","teacherName":"张老师","days":[3],
                   "section":"AFTERNOON"},
                  {"sourceText":"体育不排晚自习","type":"COURSE_NOT_IN_EVENING",
                   "strength":"HARD","status":"READY","courseName":"%s"}
                ]}""".formatted(courseName));

        Response run = generate(token, semesterId, "run-c16", "张老师周三下午不排，体育不排晚自习。");

        run.then().statusCode(200)
                .body("proposals", hasSize(2))
                .body("proposals[0].status", equalTo("NEEDS_INPUT"))
                .body("proposals[0].entries", hasSize(0))
                .body("proposals[0].candidates", hasSize(1))
                .body("proposals[0].candidates[0].options", hasSize(2))
                .body("proposals[1].status", equalTo("READY"))
                .body("proposals[1].entries", hasSize(1));
    }

    @Test
    void c20ReportsTheIllegalPeriodInsteadOfTruncatingIt() {
        String token = adminToken();
        long semesterId = semesterWithPeriods(token);
        String teacherName = "王明" + TestData.suffix();
        TestData.teacher(token, teacherName);
        gateway.respond = ok("""
                {"proposals":[{"sourceText":"王老师周三第99节不排","type":"TEACHER_UNAVAILABLE",
                "strength":"HARD","status":"READY","teacherName":"%s","days":[3],
                "periods":[99]}]}""".formatted(teacherName));

        Response run = generate(token, semesterId, "run-c20", "王老师周三第99节不排。");

        run.then().statusCode(200)
                .body("proposals[0].status", equalTo("INVALID"))
                .body("proposals[0].reason", containsString("99"))
                .body("proposals[0].entries", hasSize(0));
    }

    @Test
    void availabilityExplainsWhyTheEntryCannotCallAModel() {
        String token = adminToken();
        long semesterId = semesterWithPeriods(token);

        settingsService.put(SettingsService.AI_NATURAL_LANGUAGE_CONSTRAINTS, "false");
        as(token).when().get("/api/constraint-proposals/availability")
                .then().statusCode(200)
                .body("available", equalTo(false))
                .body("reason", equalTo("FEATURE_DISABLED"));
        generate(token, semesterId, "run-off", "王老师周三不排").then().statusCode(400);
        assertThat(gateway.calls).isEmpty();

        settingsService.put(SettingsService.AI_NATURAL_LANGUAGE_CONSTRAINTS, "true");
        gateway.configured = false;
        as(token).when().get("/api/constraint-proposals/availability")
                .then().statusCode(200)
                .body("available", equalTo(false))
                .body("reason", equalTo("NOT_CONFIGURED"));
        generate(token, semesterId, "run-no-model", "王老师周三不排").then().statusCode(400);
        assertThat(gateway.calls).isEmpty();
        assertThat(runs.count()).isZero();
    }

    @Test
    void repeatingTheSameRunIdentityDoesNotCallTheModelTwice() {
        String token = adminToken();
        long semesterId = semesterWithPeriods(token);
        String teacherName = "王明" + TestData.suffix();
        TestData.teacher(token, teacherName);
        gateway.respond = ok("""
                {"proposals":[{"sourceText":"王老师周三下午不排","type":"TEACHER_UNAVAILABLE",
                "strength":"HARD","status":"READY","teacherName":"%s","days":[3],
                "section":"AFTERNOON"}]}""".formatted(teacherName));

        Response first = generate(token, semesterId, "same-run", "王老师周三下午不排课");
        Response second = generate(token, semesterId, "same-run", "王老师周三下午不排课");

        assertThat(gateway.calls).hasSize(1);
        assertThat(second.jsonPath().getLong("id")).isEqualTo(first.jsonPath().getLong("id"));
        assertThat(runs.count()).isEqualTo(1);
    }

    @Test
    void aFailedCallIsNotRetriedAutomatically() {
        String token = adminToken();
        long semesterId = semesterWithPeriods(token);
        gateway.respond = failure("PROVIDER_ERROR", "供应商暂时不可用");

        Response run = generate(token, semesterId, "run-fail", "王老师周三不排课");

        run.then().statusCode(200)
                .body("status", equalTo("FAILED"))
                .body("errorCode", equalTo("PROVIDER_ERROR"));
        assertThat(gateway.calls).hasSize(1);
        assertThat(run.jsonPath().getString("errorMessage")).contains("供应商");
    }

    @Test
    void aTimeoutKeepsTheOriginalTextAndIsReportedAsTimeout() {
        String token = adminToken();
        long semesterId = semesterWithPeriods(token);
        gateway.respond = failure("TIMEOUT", "AI 调用超时（60 秒）");

        Response run = generate(token, semesterId, "run-timeout", "王老师周三不排课");

        run.then().statusCode(200)
                .body("status", equalTo("TIMEOUT"))
                .body("inputText", equalTo("王老师周三不排课"));
        assertThat(gateway.calls).hasSize(1);
    }

    @Test
    void aCancelledRunKeepsItsStatusAndFilesTheLateAnswer() throws Exception {
        String token = adminToken();
        long semesterId = semesterWithPeriods(token);
        String teacherName = "王明" + TestData.suffix();
        TestData.teacher(token, teacherName);
        gateway.entered = new CountDownLatch(1);
        gateway.release = new CountDownLatch(1);
        gateway.respond = ok("""
                {"proposals":[{"sourceText":"王老师周三下午不排","type":"TEACHER_UNAVAILABLE",
                "strength":"HARD","status":"READY","teacherName":"%s","days":[3],
                "section":"AFTERNOON"}]}""".formatted(teacherName));

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Response> pending = pool.submit(() -> generate(
                    token, semesterId, "run-cancel", "王老师周三下午不排课"));
            assertThat(gateway.entered.await(10, TimeUnit.SECONDS)).isTrue();
            long runId = runs.findAll().get(0).getId();

            as(token).when().post("/api/constraint-proposals/" + runId + "/cancel")
                    .then().statusCode(200)
                    .body("status", equalTo("CANCELLED"));

            gateway.release.countDown();
            pending.get(30, TimeUnit.SECONDS);

            Response detail = as(token).when()
                    .get("/api/constraint-proposals/" + runId)
                    .then().statusCode(200).extract().response();
            assertThat(detail.jsonPath().getString("run.status")).isEqualTo("CANCELLED");
            assertThat(detail.jsonPath().getList("run.proposals")).isEmpty();
            assertThat(detail.jsonPath().getString("lateResponse")).contains("proposals");
            assertThat(detail.jsonPath().getString("rawResponse")).isNull();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void anotherBusinessUserCannotReadOrCancelTheRun() {
        String admin = adminToken();
        long semesterId = semesterWithPeriods(admin);
        String teacherName = "王明" + TestData.suffix();
        TestData.teacher(admin, teacherName);
        gateway.respond = ok("""
                {"proposals":[{"sourceText":"王老师周三不排","type":"TEACHER_UNAVAILABLE",
                "strength":"HARD","status":"READY","teacherName":"%s","days":[3],
                "periods":[1]}]}""".formatted(teacherName));
        Response run = generate(admin, semesterId, "run-private", "王老师周三不排课");
        long runId = run.jsonPath().getLong("id");

        String roleCode = "constraint_user_" + TestData.suffix();
        Role role = new Role(roleCode, "约束管理员");
        role.getPermissions().add(permissions.findByCode(Permissions.CONSTRAINT_MANAGE).orElseThrow());
        roles.save(role);
        String otherUser = "other" + TestData.suffix();
        createUser(admin, otherUser, "other123", role.getCode());
        String other = login(otherUser, "other123");

        as(other).when().get("/api/constraint-proposals/" + runId).then().statusCode(403);
        as(other).when().post("/api/constraint-proposals/" + runId + "/cancel")
                .then().statusCode(403);
        as(other).queryParam("semesterId", semesterId)
                .when().get("/api/constraint-proposals")
                .then().statusCode(200).body("$", hasSize(0));
        as(other).queryParam("semesterId", semesterId).queryParam("scope", "all")
                .when().get("/api/constraint-proposals").then().statusCode(403);
        as(admin).queryParam("semesterId", semesterId).queryParam("scope", "all")
                .when().get("/api/constraint-proposals")
                .then().statusCode(200).body("$", hasSize(1));
    }

    @Test
    void theRecordKeepsTheCompleteRawAnswerAndTheSentContext() {
        String token = adminToken();
        long semesterId = semesterWithPeriods(token);
        String teacherName = "王明" + TestData.suffix();
        TestData.teacher(token, teacherName);
        String answer = """
                {"proposals":[{"sourceText":"王老师周三不排","type":"TEACHER_UNAVAILABLE",
                "strength":"HARD","status":"READY","teacherName":"%s","days":[3],
                "periods":[1]}]}""".formatted(teacherName);
        gateway.respond = ok(answer);

        long runId = generate(token, semesterId, "run-record", "王老师周三不排课")
                .jsonPath().getLong("id");
        Response detail = as(token).when().get("/api/constraint-proposals/" + runId)
                .then().statusCode(200).extract().response();

        assertThat(detail.jsonPath().getString("rawResponse")).isEqualTo(answer);
        assertThat(detail.jsonPath().getString("contextJson")).contains("teachers")
                .doesNotContain("employeeNo");
        assertThat(detail.jsonPath().getString("run.inputText")).isEqualTo("王老师周三不排课");
    }

    @Test
    void resolvingAHomonymReusesTheNormalizerWithoutAnotherModelCall() {
        String token = adminToken();
        long semesterId = semesterWithPeriods(token);
        TestData.teacher(token, "张伟甲" + TestData.suffix());
        TestData.teacher(token, "张伟乙" + TestData.suffix());
        gateway.respond = ok("""
                {"proposals":[{"sourceText":"张老师周三下午不排","type":"TEACHER_UNAVAILABLE",
                "strength":"HARD","status":"READY","teacherName":"张老师","days":[3],
                "section":"AFTERNOON"}]}""");

        Response run = generate(token, semesterId, "run-resolve", "张老师周三下午不排");
        long runId = run.jsonPath().getLong("id");
        long candidateId = run.jsonPath().getLong("proposals[0].candidates[0].options[0].id");

        Response resolved = as(token).body(Map.of(
                        "proposalId", "p1", "teacherId", candidateId))
                .when().post("/api/constraint-proposals/" + runId + "/resolve")
                .then().statusCode(200).extract().response();

        resolved.then()
                .body("status", equalTo("READY"))
                .body("entries", hasSize(4));
        assertThat(resolved.jsonPath().getList("entries.params.teacherId"))
                .allMatch(value -> ((Number) value).longValue() == candidateId);
        assertThat(gateway.calls).hasSize(1);
        assertThat(runs.findById(runId).orElseThrow().getModelContent()).contains("proposals");
    }

    @Test
    void savingWithDecisionsRecordsTheAuditAtomicallyAndReplayCannotDoubleIt() {
        String token = adminToken();
        long semesterId = semesterWithPeriods(token);
        String teacherName = "王明" + TestData.suffix();
        long teacherId = TestData.teacher(token, teacherName);
        gateway.respond = ok("""
                {"proposals":[{"sourceText":"王老师周三不排","type":"TEACHER_UNAVAILABLE",
                "strength":"HARD","status":"READY","teacherName":"%s","days":[3],
                "periods":[1]}]}""".formatted(teacherName));
        long runId = generate(token, semesterId, "run-save", "王老师周三不排")
                .jsonPath().getLong("id");

        List<Map<String, Object>> entries = List.of(Map.of(
                "params", Map.of("teacherId", teacherId, "dayOfWeek", 3, "periodNo", 1),
                "enabled", true, "hard", true));
        List<Map<String, Object>> decisions = List.of(Map.of(
                "proposalId", "p1", "action", "ADDED", "type", "TEACHER_UNAVAILABLE",
                "reason", "用户确认加入", "entries", entries));
        saveWithAudit(token, semesterId, 0L, runId, decisions, List.of(
                Map.of("type", "TEACHER_UNAVAILABLE", "weight", 100, "hard", true,
                        "enabled", true, "entries", entries)))
                .then().statusCode(200);

        assertThat(audits.count()).isEqualTo(1);
        var audit = audits.findAll().get(0);
        assertThat(audit.getRunId()).isEqualTo(runId);
        assertThat(audit.getDecisionsJson()).contains("ADDED").contains("用户确认加入");
        assertThat(audit.getFinalConfigJson()).contains("TEACHER_UNAVAILABLE");
        assertThat(audit.getPreviousConfigJson()).isNotNull();
        assertThat(audit.getDiffJson()).contains("addedTypes").contains("TEACHER_UNAVAILABLE");
        as(token).queryParam("semesterId", semesterId)
                .when().get("/api/constraint-audits")
                .then().statusCode(200)
                .body("$", hasSize(1))
                .body("[0].runId", equalTo((int) runId))
                .body("[0].diff.addedTypes", hasSize(1));

        // Replaying the same base version is a conflict and never writes a second audit.
        saveWithAudit(token, semesterId, 0L, runId, decisions, List.of())
                .then().statusCode(409);
        assertThat(audits.count()).isEqualTo(1);
    }

    @Test
    void anotherUsersRunCannotBeAttachedToASave() {
        String admin = adminToken();
        long semesterId = semesterWithPeriods(admin);
        String teacherName = "王明" + TestData.suffix();
        TestData.teacher(admin, teacherName);
        gateway.respond = ok("""
                {"proposals":[{"sourceText":"王老师周三不排","type":"TEACHER_UNAVAILABLE",
                "strength":"HARD","status":"READY","teacherName":"%s","days":[3],
                "periods":[1]}]}""".formatted(teacherName));
        long runId = generate(admin, semesterId, "run-private-save", "王老师周三不排")
                .jsonPath().getLong("id");

        String roleCode = "constraint_save_" + TestData.suffix();
        Role role = new Role(roleCode, "约束管理员");
        role.getPermissions().add(permissions.findByCode(Permissions.CONSTRAINT_MANAGE).orElseThrow());
        roles.save(role);
        String otherUser = "saver" + TestData.suffix();
        createUser(admin, otherUser, "other123", role.getCode());
        String other = login(otherUser, "other123");
        String before = as(admin).queryParam("semesterId", semesterId)
                .when().get("/api/constraints/state").then().statusCode(200)
                .extract().asString();

        saveWithAudit(other, semesterId, 0L, runId, List.of(), List.of())
                .then().statusCode(403);
        assertThat(as(admin).queryParam("semesterId", semesterId)
                .when().get("/api/constraints/state").then().statusCode(200)
                .extract().asString()).isEqualTo(before);
        assertThat(audits.count()).isZero();
    }

    @Test
    void aPreviewRefusesAnInvalidMergeWithoutWritingOrAdvancingTheVersion() {
        String token = adminToken();
        long semesterId = semesterWithPeriods(token);
        String before = as(token).queryParam("semesterId", semesterId)
                .when().get("/api/constraints/state").then().statusCode(200)
                .extract().asString();

        Response preview = as(token).queryParam("semesterId", semesterId)
                .body(Map.of("configs", List.of(Map.of(
                        "type", "TEACHER_UNAVAILABLE", "weight", 100, "hard", true,
                        "enabled", true,
                        "entries", List.of(Map.of("params", Map.of(
                                "teacherId", 9_999_999, "dayOfWeek", 1, "periodNo", 1)))))))
                .when().post("/api/constraints/preview")
                .then().statusCode(200).extract().response();

        assertThat(preview.jsonPath().getList("invalidReferences")).isNotEmpty();
        assertThat(as(token).queryParam("semesterId", semesterId)
                .when().get("/api/constraints/state").then().statusCode(200)
                .extract().asString()).isEqualTo(before);
    }

    @Test
    void auditListingSeparatesMineFromTheGlobalView() {
        String admin = adminToken();
        long semesterId = semesterWithPeriods(admin);
        saveWithAudit(admin, semesterId, 0L, null, List.of(), List.of(
                Map.of("type", "TEACHER_CONFLICT", "params", Map.of(), "weight", 100,
                        "hard", true, "enabled", true))).then().statusCode(200);

        String roleCode = "constraint_audit_" + TestData.suffix();
        Role role = new Role(roleCode, "约束管理员");
        role.getPermissions().add(permissions.findByCode(Permissions.CONSTRAINT_MANAGE).orElseThrow());
        roles.save(role);
        String otherUser = "auditor" + TestData.suffix();
        createUser(admin, otherUser, "other123", role.getCode());
        String other = login(otherUser, "other123");

        as(admin).queryParam("semesterId", semesterId)
                .when().get("/api/constraint-audits").then().statusCode(200)
                .body("$", hasSize(1));
        as(other).queryParam("semesterId", semesterId)
                .when().get("/api/constraint-audits").then().statusCode(200)
                .body("$", hasSize(0));
        as(other).queryParam("semesterId", semesterId).queryParam("scope", "all")
                .when().get("/api/constraint-audits").then().statusCode(403);
    }

    private Response saveWithAudit(
            String token, long semesterId, Long baseVersion, Long runId,
            List<Map<String, Object>> decisions, List<Map<String, Object>> configs) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("configs", configs);
        body.put("runId", runId);
        body.put("decisions", decisions);
        io.restassured.specification.RequestSpecification request = as(token)
                .queryParam("semesterId", semesterId);
        if (baseVersion != null) {
            request = request.queryParam("baseVersion", baseVersion);
        }
        return request.body(body).when().put("/api/constraints");
    }

    // ------------------------------------------------------------------ helpers

    private long semesterWithPeriods(String token) {
        long semesterId = TestData.semester(token);
        as(token).body(Map.of("morningCount", 4, "afternoonCount", 4, "eveningCount", 2))
                .when().post("/api/semesters/" + semesterId + "/periods/default")
                .then().statusCode(200);
        return semesterId;
    }

    private Response generate(String token, long semesterId, String clientRunId, String text) {
        return as(token).queryParam("semesterId", semesterId)
                .body(Map.of("clientRunId", clientRunId, "text", text))
                .when().post("/api/constraint-proposals");
    }

    private Function<AiRequest, AiResult> ok(String content) {
        // The provider's raw body is the answer itself, as the strategies store it.
        return request -> new AiResult(true, content, null, null, 1L, "stub", 1L, "stub-model",
                100, 200, 120L, content);
    }

    private Function<AiRequest, AiResult> failure(String code, String message) {
        return request -> new AiResult(false, null, code, message, 1L, "stub", 1L, "stub-model",
                50, 0, 120L, "{\"error\":\"" + code + "\"}");
    }

    @TestConfiguration
    static class FakeAiConfig {

        @Bean
        @Primary
        FakeAiGateway fakeAiGateway() {
            return new FakeAiGateway();
        }
    }

    static class FakeAiGateway implements AiGateway {

        volatile boolean configured = true;

        volatile Function<AiRequest, AiResult> respond =
                request -> new AiResult(true, "{\"proposals\":[]}", null, null,
                        1L, "stub", 1L, "stub-model", 1, 1, 1L, "{}");

        volatile CountDownLatch entered;

        volatile CountDownLatch release;

        final List<AiRequest> calls = Collections.synchronizedList(new ArrayList<>());

        void reset() {
            configured = true;
            calls.clear();
            entered = null;
            release = null;
            respond = request -> new AiResult(true, "{\"proposals\":[]}", null, null,
                    1L, "stub", 1L, "stub-model", 1, 1, 1L, "{}");
        }

        @Override
        public AiResult chat(AiRequest request) {
            return invoke(request);
        }

        @Override
        public AiResult chatWithVision(AiRequest request) {
            return invoke(request);
        }

        private AiResult invoke(AiRequest request) {
            calls.add(request);
            if (entered != null) {
                entered.countDown();
            }
            if (release != null) {
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
            return respond.apply(request);
        }

        @Override
        public boolean configured() {
            return configured;
        }

        @Override
        public boolean configured(AiTaskType taskType) {
            return configured && taskType == AiTaskType.NATURAL_LANGUAGE_CONSTRAINTS;
        }
    }
}
