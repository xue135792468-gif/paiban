package com.paiban.ai.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;

import com.paiban.ai.domain.AiCallLog;
import com.paiban.ai.domain.AiCallLogRepository;
import com.paiban.ai.imports.AiImportLog;
import com.paiban.ai.imports.AiImportLogRepository;
import com.paiban.foundation.domain.Teacher;
import com.paiban.foundation.domain.TeacherRepository;
import com.paiban.identity.domain.AppUser;
import com.paiban.identity.domain.AppUserRepository;
import com.paiban.substitution.domain.Substitution;
import com.paiban.substitution.domain.SubstitutionRepository;
import com.paiban.substitution.domain.SubstitutionRequest;
import com.paiban.substitution.domain.SubstitutionRequestRepository;
import com.paiban.support.ApiIntegrationTest;
import com.paiban.support.ScheduleFixture;
import com.paiban.support.TestData;
import io.restassured.response.Response;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AiHistoryIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private AiImportLogRepository imports;

    @Autowired
    private AiCallLogRepository calls;

    @Autowired
    private AppUserRepository users;

    @Autowired
    private TeacherRepository teachers;

    @Autowired
    private SubstitutionRequestRepository substitutionRequests;

    @Autowired
    private SubstitutionRepository substitutions;

    private Long adminId;

    @BeforeEach
    void setUp() {
        adminId = users.findByUsername("admin").map(AppUser::getId).orElseThrow();
    }

    @Test
    void mergedHistorySupportsFiltersAndPagination() {
        long importId = importLog("teachers.xlsx", AiImportLog.COMMITTED, 23, 23,
                Instant.parse("2026-10-19T10:00:00Z"));
        callLog("data_import", true, null, null, null,
                Instant.parse("2026-10-19T11:00:00Z"));
        callLog("substitution_recommend", false, "PROVIDER_ERROR", "超时", null,
                Instant.parse("2026-10-19T12:00:00Z"));
        importLog("pasted.txt", AiImportLog.FAILED, 0, 0,
                Instant.parse("2026-10-18T09:00:00Z"));

        as(adminToken()).when().get("/api/ai/history")
                .then().statusCode(200)
                .body("total", equalTo(4))
                .body("items", hasSize(4))
                .body("items[0].type", equalTo("call"))
                .body("items[0].taskType", equalTo("substitution_recommend"))
                .body("items[0].success", equalTo(false));

        as(adminToken()).queryParam("type", "import")
                .when().get("/api/ai/history")
                .then().statusCode(200)
                .body("total", equalTo(2))
                .body("items[0].refId", equalTo((int) importId));

        as(adminToken()).queryParam("taskType", "substitution_recommend")
                .when().get("/api/ai/history")
                .then().statusCode(200)
                .body("total", equalTo(1))
                .body("items[0].providerName", equalTo("DeepSeek"));

        as(adminToken()).queryParam("username", "admin")
                .when().get("/api/ai/history")
                .then().statusCode(200)
                .body("total", equalTo(4));

        as(adminToken()).queryParam("userId", adminId)
                .when().get("/api/ai/history")
                .then().statusCode(200)
                .body("total", equalTo(4));

        as(adminToken()).queryParam("from", "2026-10-19").queryParam("to", "2026-10-19")
                .when().get("/api/ai/history")
                .then().statusCode(200)
                .body("total", equalTo(3));

        as(adminToken()).queryParam("page", 1).queryParam("size", 2)
                .when().get("/api/ai/history")
                .then().statusCode(200)
                .body("total", equalTo(4))
                .body("items", hasSize(2))
                .body("page", equalTo(1))
                .body("size", equalTo(2));
    }

    @Test
    void importDetailReturnsPreviewConfirmedAndResult() {
        long id = importLog("teachers.xlsx", AiImportLog.COMMITTED, 2, 2, Instant.now());

        as(adminToken()).when().get("/api/ai/history/imports/" + id)
                .then().statusCode(200)
                .body("fileName", equalTo("teachers.xlsx"))
                .body("status", equalTo("COMMITTED"))
                .body("rowCount", equalTo(2))
                .body("importedCount", equalTo(2))
                .body("preview.rows[0].values.name", equalTo("张三"))
                .body("confirmed.rows[0].values.name", equalTo("张三"))
                .body("result.imported", equalTo(2));
    }

    @Test
    void recommendationDetailJoinsTheRequestAndFinalSubstitute() throws Exception {
        String admin = adminToken();
        ScheduleFixture.Result fixture = ScheduleFixture.published(admin, 5);
        Map<String, Object> entry = ScheduleFixture.classEntries(admin, fixture.classId()).get(0);
        Teacher original = teachers.findById(fixture.teacherId()).orElseThrow();
        Teacher substitute = teacher("代课老师");
        long requestId = substitutionRequest(fixture, entry, original.getId());
        Substitution substitution = new Substitution();
        substitution.setSemesterId(fixture.semesterId());
        substitution.setRequestId(requestId);
        substitution.setEntryId(((Number) entry.get("id")).longValue());
        substitution.setEffectiveDate(LocalDate.of(2025, 9, 1).plusDays(((Number) entry.get("dayOfWeek")).longValue() - 1));
        substitution.setOriginalTeacherId(original.getId());
        substitution.setSubstituteTeacherId(substitute.getId());
        substitution.setStatus(Substitution.EFFECTIVE);
        substitution.setCreatedAt(Instant.now());
        substitutions.save(substitution);

        String content = """
                [{"teacherId":%d,"name":"%s","reason":"同学科，当天该时段无课","score":92}]
                """.formatted(substitute.getId(), substitute.getName()).strip();
        // The audit row keeps the provider's raw OpenAI-shaped body, not the extracted answer.
        String response = "{\"choices\":[{\"message\":{\"content\":"
                + new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(content)
                + "}}]}";
        long callId = callLog("substitution_recommend", true, null, null, requestId, Instant.now(), response);

        as(adminToken()).when().get("/api/ai/history/calls/" + callId)
                .then().statusCode(200)
                .body("taskType", equalTo("substitution_recommend"))
                .body("context.requestId", equalTo((int) requestId))
                .body("context.originalTeacher", equalTo(original.getName()))
                .body("context.periodNo", equalTo(((Number) entry.get("periodNo")).intValue()))
                .body("context.finalSubstituteTeacher", equalTo(substitute.getName()))
                .body("context.recommendations", hasSize(1))
                .body("context.recommendations[0].teacherId", equalTo(substitute.getId().intValue()))
                .body("context.recommendations[0].score", equalTo(92));
    }

    @Test
    void exportsContainRowsForBothFormats() {
        importLog("teachers.xlsx", AiImportLog.COMMITTED, 2, 2, Instant.now());
        callLog("data_import", true, null, null, null, Instant.now());

        Response xlsx = as(adminToken()).queryParam("format", "xlsx")
                .when().get("/api/ai/history/export");
        xlsx.then().statusCode(200)
                .contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        assertThat(xlsx.asByteArray()).isNotEmpty();

        Response pdf = as(adminToken()).queryParam("format", "pdf")
                .when().get("/api/ai/history/export");
        pdf.then().statusCode(200).contentType("application/pdf");
        assertThat(pdf.asByteArray()).isNotEmpty();
    }

    @Test
    void teacherCannotReadHistory() {
        String admin = adminToken();
        String username = "hist_" + TestData.suffix();
        createUser(admin, username, "teacher123", "teacher");
        String teacher = login(username, "teacher123");

        as(teacher).when().get("/api/ai/history").then().statusCode(403);
        as(teacher).when().get("/api/ai/history/imports/1").then().statusCode(403);
        as(teacher).queryParam("format", "xlsx")
                .when().get("/api/ai/history/export").then().statusCode(403);
    }

    @Test
    void unknownRecordIsNotFound() {
        as(adminToken()).when().get("/api/ai/history/imports/999999")
                .then().statusCode(404);
        as(adminToken()).when().get("/api/ai/history/calls/999999")
                .then().statusCode(404);
        as(adminToken()).queryParam("format", "csv")
                .when().get("/api/ai/history/export")
                .then().statusCode(400);
    }

    @Test
    void pageSizeIsCapped() {
        for (int i = 0; i < 5; i++) {
            importLog("f" + i + ".xlsx", AiImportLog.PREVIEW, 1, 0, Instant.now());
        }
        as(adminToken()).queryParam("size", 500)
                .when().get("/api/ai/history")
                .then().statusCode(200)
                .body("size", equalTo(100))
                .body("items.size()", lessThanOrEqualTo(100));
    }

    // ------------------------------------------------------------------ helpers

    private long importLog(String name, String status, int rows, int imported, Instant createdAt) {
        AiImportLog log = new AiImportLog();
        log.setUserId(adminId);
        log.setUsername("admin");
        log.setImportType(name.endsWith(".txt") ? "text" : "excel");
        log.setTargetType("teacher");
        log.setFileName(name);
        log.setFileSize(1024L);
        log.setStatus(status);
        log.setRowCount(rows);
        log.setImportedCount(imported);
        log.setPreviewJson("""
                {"mappings":[{"source":"姓名","target":"name","note":"原始列 → name"}],
                 "rows":[{"values":{"name":"张三"},"confidence":{"name":0.99}}],
                 "explanation":"测试映射"}
                """);
        log.setConfirmedJson("""
                {"mappings":[],"rows":[{"values":{"name":"张三"},"confidence":{"name":1.0}}],"explanation":""}
                """);
        log.setResultJson("{\"imported\":" + imported + "}");
        log.setAiExplanation("测试映射");
        log.setCreatedAt(createdAt);
        log.setUpdatedAt(createdAt);
        return imports.save(log).getId();
    }

    private long callLog(
            String taskType,
            boolean success,
            String errorCode,
            String errorMessage,
            Long subjectId,
            Instant createdAt) {
        return callLog(taskType, success, errorCode, errorMessage, subjectId, createdAt, "[]");
    }

    private long callLog(
            String taskType,
            boolean success,
            String errorCode,
            String errorMessage,
            Long subjectId,
            Instant createdAt,
            String responseJson) {
        AiCallLog log = new AiCallLog();
        log.setTaskType(taskType);
        log.setProviderId(1L);
        log.setProviderName("DeepSeek");
        log.setModelId(2L);
        log.setModelName("deepseek-chat");
        log.setUserId(adminId);
        log.setSubjectType(subjectId == null ? null : "SUBSTITUTION_REQUEST");
        log.setSubjectId(subjectId);
        log.setSuccess(success);
        log.setErrorCode(errorCode);
        log.setErrorMessage(errorMessage);
        log.setInputTokens(12);
        log.setOutputTokens(8);
        log.setLatencyMs(520L);
        log.setRequestJson("{\"taskType\":\"" + taskType + "\",\"messages\":[]}");
        log.setResponseJson(responseJson);
        log.setCreatedAt(createdAt);
        return calls.save(log).getId();
    }

    private Teacher teacher(String name) {
        Teacher teacher = new Teacher();
        teacher.setName(name + "-" + TestData.suffix());
        teacher.setActive(true);
        teacher.setCreatedAt(Instant.now());
        return teachers.save(teacher);
    }

    private long substitutionRequest(
            ScheduleFixture.Result fixture, Map<String, Object> entry, long originalTeacherId) {
        int dayOfWeek = ((Number) entry.get("dayOfWeek")).intValue();
        SubstitutionRequest request = new SubstitutionRequest();
        request.setSemesterId(fixture.semesterId());
        request.setEntryId(((Number) entry.get("id")).longValue());
        request.setAdminClassId(fixture.classId());
        request.setCourseId(((Number) entry.get("courseId")).longValue());
        request.setOriginalTeacherId(originalTeacherId);
        request.setEffectiveDate(LocalDate.of(2025, 9, 1).plusDays(dayOfWeek - 1L));
        request.setPeriodId(((Number) entry.get("periodId")).longValue());
        request.setDayOfWeek(dayOfWeek);
        request.setPeriodNo(((Number) entry.get("periodNo")).intValue());
        request.setStatus(SubstitutionRequest.PENDING_APPROVAL);
        request.setApprovalMode("approval");
        request.setCreatedAt(Instant.now());
        request.setUpdatedAt(Instant.now());
        return substitutionRequests.save(request).getId();
    }
}
