package com.paiban.substitution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

import com.paiban.ai.AiGateway;
import com.paiban.ai.AiRequest;
import com.paiban.ai.AiResult;
import com.paiban.ai.AiTaskType;
import com.paiban.ai.domain.AiCallLog;
import com.paiban.ai.domain.AiCallLogRepository;
import com.paiban.ai.service.AiCallLogWriter;
import com.paiban.support.ApiIntegrationTest;
import com.paiban.support.ScheduleFixture;
import com.paiban.support.TestData;
import io.restassured.response.Response;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

/**
 * REST contract for {@code POST /api/substitution-requests/{id}/recommend}.
 *
 * <p>The gateway is replaced by a deterministic fake that still writes the audit row
 * through the production {@link AiCallLogWriter}, so no test ever reaches the network.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SubstitutionRecommendationIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private FakeAiGateway gateway;

    @Autowired
    private AiCallLogRepository callLogs;

    @BeforeEach
    void resetGateway() {
        gateway.reset();
    }

    private record Context(long requestId, long sameSubjectOne, long sameSubjectTwo, long otherSubject,
                           long inactiveTeacher, int dayOfWeek, int periodNo) {}

    private Context prepare(String token) {
        ScheduleFixture.Result fixture = ScheduleFixture.published(token, 5);
        List<Map<String, Object>> entries = ScheduleFixture.classEntries(token, fixture.classId());
        Map<String, Object> entry = entries.stream()
                .filter(item -> ((Number) item.get("teacherId")).longValue() == fixture.teacherId())
                .findFirst()
                .orElseThrow();
        long entryId = ((Number) entry.get("id")).longValue();
        int dayOfWeek = ((Number) entry.get("dayOfWeek")).intValue();
        int periodNo = ((Number) entry.get("periodNo")).intValue();
        LocalDate date = LocalDate.of(2025, 9, 1).plusDays(dayOfWeek - 1L);

        String suffix = TestData.suffix();
        String courseSubject = String.valueOf(entry.get("courseName"));
        long sameOne = createTeacher(token, "李老师" + suffix, courseSubject, true);
        long sameTwo = createTeacher(token, "王老师" + suffix, courseSubject, true);
        long other = createTeacher(token, "陈老师" + suffix, "数学" + suffix, true);
        long inactive = createTeacher(token, "离岗老师" + suffix, courseSubject, false);

        Response response = as(token).body(Map.of(
                        "entryId", entryId,
                        "effectiveDate", date.toString(),
                        "reason", "家中有事"))
                .when().post("/api/substitution-requests");
        response.then().statusCode(200);
        long requestId = response.jsonPath().getLong("id");
        assertThat(response.jsonPath().getString("status")).isEqualTo("PENDING_APPROVAL");
        return new Context(requestId, sameOne, sameTwo, other, inactive, dayOfWeek, periodNo);
    }

    private long createTeacher(String token, String name, String subject, boolean active) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", name);
        payload.put("employeeNo", "T" + TestData.suffix());
        payload.put("subject", subject);
        payload.put("active", active);
        return as(token).body(payload)
                .when().post("/api/teachers")
                .then().statusCode(200)
                .extract().jsonPath().getLong("id");
    }

    @Test
    void recommendsThreeCandidatesAndAuditsTheCall() {
        String token = adminToken();
        Context context = prepare(token);
        gateway.respondWith("""
                [
                  {"teacherId":%d,"name":"李老师","reason":"同学科、当天该时段无课、本学期代课 0 次","score":92},
                  {"teacherId":%d,"name":"王老师","reason":"同学科、当天该时段无课","score":85},
                  {"teacherId":%d,"name":"陈老师","reason":"当天该时段无课，可跨学科代课","score":70}
                ]
                """.formatted(context.sameSubjectOne(), context.sameSubjectTwo(), context.otherSubject()));

        as(token).when().post("/api/substitution-requests/" + context.requestId() + "/recommend")
                .then().statusCode(200)
                .body("requestId", equalTo((int) context.requestId()))
                .body("recommendations", hasSize(3))
                .body("recommendations[0].teacherId", equalTo((int) context.sameSubjectOne()))
                .body("recommendations[0].name", containsString("李老师"))
                .body("recommendations[0].reason", containsString("同学科"))
                .body("recommendations[0].score", equalTo(92))
                .body("recommendations[1].teacherId", equalTo((int) context.sameSubjectTwo()))
                .body("recommendations[2].teacherId", equalTo((int) context.otherSubject()))
                .body("providerName", equalTo("FakeProvider"))
                .body("modelName", equalTo("fake-model"));

        AiRequest sent = gateway.lastRequest;
        assertThat(sent.taskType()).isEqualTo(AiTaskType.SUBSTITUTION_RECOMMEND);
        assertThat(sent.options().jsonMode()).isTrue();
        assertThat(sent.subjectType()).isEqualTo("SUBSTITUTION_REQUEST");
        assertThat(sent.subjectId()).isEqualTo(context.requestId());
        assertThat(sent.messages()).hasSize(2);

        List<AiCallLog> rows = callLogs.findAll().stream()
                .filter(row -> "substitution_recommend".equals(row.getTaskType()))
                .toList();
        assertThat(rows).hasSize(1);
        AiCallLog row = rows.get(0);
        assertThat(row.isSuccess()).isTrue();
        assertThat(row.getSubjectType()).isEqualTo("SUBSTITUTION_REQUEST");
        assertThat(row.getSubjectId()).isEqualTo(context.requestId());
        assertThat(row.getUserId()).isNotNull();
        assertThat(row.getRequestJson()).contains("语文", "同学科=是", "本学期代课次数=");
        assertThat(row.getResponseJson()).contains("李老师");
    }

    @Test
    void dropsIdsThatAreNotInTheCandidatePool() {
        String token = adminToken();
        Context context = prepare(token);
        gateway.respondWith("""
                [
                  {"teacherId":%d,"name":"李老师","reason":"同学科","score":91},
                  {"teacherId":999999,"name":"幽灵教师","reason":"编造","score":99},
                  {"teacherId":%d,"name":"李老师重复","reason":"同学科","score":96},
                  {"teacherId":%d,"name":"离岗老师","reason":"不该出现","score":95},
                  {"teacherId":%d,"name":"陈老师","reason":"空闲","score":60}
                ]
                """.formatted(
                        context.sameSubjectOne(), context.sameSubjectOne(),
                        context.inactiveTeacher(), context.otherSubject()));

        as(token).when().post("/api/substitution-requests/" + context.requestId() + "/recommend")
                .then().statusCode(200)
                .body("recommendations", hasSize(2))
                .body("recommendations[0].teacherId", equalTo((int) context.sameSubjectOne()))
                .body("recommendations[0].score", equalTo(96))
                .body("recommendations[1].teacherId", equalTo((int) context.otherSubject()));
    }

    @Test
    void unconfiguredAiIsReportedByStatusAndRecommendationFailsClearly() {
        String token = adminToken();
        Context context = prepare(token);

        as(token).when().get("/api/ai/status")
                .then().statusCode(200)
                .body("tasks.substitution_recommend.configured", equalTo(false));

        gateway.failWith("NOT_CONFIGURED", "该任务未配置可用的 AI 供应商与模型");
        as(token).when().post("/api/substitution-requests/" + context.requestId() + "/recommend")
                .then().statusCode(502)
                .body("message", containsString("未配置"))
                .body("message", containsString("AI 供应商"));

        List<AiCallLog> rows = callLogs.findAll().stream()
                .filter(row -> "substitution_recommend".equals(row.getTaskType()))
                .toList();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).isSuccess()).isFalse();
        assertThat(rows.get(0).getErrorCode()).isEqualTo("NOT_CONFIGURED");
        assertThat(rows.get(0).getSubjectId()).isEqualTo(context.requestId());
    }

    @Test
    void unparseableAiOutputReturnsBadGateway() {
        String token = adminToken();
        Context context = prepare(token);
        gateway.respondWith("抱歉，我无法完成这个请求。");

        as(token).when().post("/api/substitution-requests/" + context.requestId() + "/recommend")
                .then().statusCode(502)
                .body("message", containsString("无法解析"));
    }

    @Test
    void teacherCannotAskForARecommendation() {
        String token = adminToken();
        Context context = prepare(token);
        String username = "rec_teacher_" + TestData.suffix();
        createUser(token, username, "teacher123", "teacher");
        String teacher = login(username, "teacher123");

        as(teacher).when().post("/api/substitution-requests/" + context.requestId() + "/recommend")
                .then().statusCode(403);
        assertThat(gateway.calls()).isZero();
    }

    @Test
    void requestThatIsNoLongerPendingReturnsConflict() {
        String token = adminToken();
        Context context = prepare(token);
        as(token).body(Map.of("comment", "当天已有安排"))
                .when().post("/api/substitution-requests/" + context.requestId() + "/reject")
                .then().statusCode(200)
                .body("status", equalTo("REJECTED"));

        as(token).when().post("/api/substitution-requests/" + context.requestId() + "/recommend")
                .then().statusCode(409)
                .body("message", containsString("REJECTED"));
        assertThat(gateway.calls()).isZero();

        as(token).when().post("/api/substitution-requests/999999/recommend")
                .then().statusCode(404)
                .body("message", notNullValue());
    }

    @TestConfiguration
    static class FakeGatewayConfig {

        @Bean
        @Primary
        FakeAiGateway fakeAiGateway(AiCallLogWriter callLogWriter) {
            return new FakeAiGateway(callLogWriter);
        }
    }

    /** Deterministic gateway; every call still produces the production audit row. */
    static final class FakeAiGateway implements AiGateway {

        private final AiCallLogWriter callLogWriter;
        private final AtomicInteger calls = new AtomicInteger();
        private volatile String content = "[]";
        private volatile String errorCode;
        private volatile String errorMessage;
        private volatile AiRequest lastRequest;

        FakeAiGateway(AiCallLogWriter callLogWriter) {
            this.callLogWriter = callLogWriter;
        }

        void respondWith(String json) {
            this.content = json;
            this.errorCode = null;
            this.errorMessage = null;
        }

        void failWith(String code, String message) {
            this.errorCode = code;
            this.errorMessage = message;
        }

        void reset() {
            this.content = "[]";
            this.errorCode = null;
            this.errorMessage = null;
            this.lastRequest = null;
            this.calls.set(0);
        }

        int calls() {
            return calls.get();
        }

        @Override
        public AiResult chat(AiRequest request) {
            lastRequest = request;
            calls.incrementAndGet();
            AiResult result;
            if (errorCode != null) {
                result = new AiResult(
                        false, null, errorCode, errorMessage,
                        null, null, null, null, null, null, 3L, null);
            } else {
                result = new AiResult(
                        true, content, null, null,
                        11L, "FakeProvider", 22L, "fake-model",
                        120, 80, 15L, content);
            }
            callLogWriter.record(request, null, result);
            return result;
        }

        @Override
        public AiResult chatWithVision(AiRequest request) {
            return chat(request);
        }

        @Override
        public boolean configured() {
            return !"NOT_CONFIGURED".equals(errorCode);
        }
    }
}
