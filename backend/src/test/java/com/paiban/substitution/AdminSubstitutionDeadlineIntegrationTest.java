package com.paiban.substitution;

import static org.assertj.core.api.Assertions.assertThat;

import com.paiban.substitution.domain.SubstitutionRepository;
import com.paiban.support.ApiIntegrationTest;
import com.paiban.support.TestData;
import io.restassured.response.Response;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import org.mockito.Mockito;
import java.time.Clock;

/**
 * 开课截止的三分边界（票据清单第 9 条）：截止前一瞬可确认；恰好开课与开课之后拒绝。
 * 用固定时钟驱动服务端权威时间，验证预览与确认都以「当时的时钟面」实时判定——
 * 预览时还没到截止、确认时（可能已等待过行锁）跨过截止的，必须拒绝。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AdminSubstitutionDeadlineIntegrationTest extends ApiIntegrationTest {

    private static final ZoneId ZONE = com.paiban.substitution.service.LessonDeadline.SCHOOL_ZONE;
    private static final LocalDate MONDAY = LocalDate.of(2025, 9, 1);
    private static final Instant JUST_BEFORE = Instant.parse("2025-09-02T23:59:59Z");
    private static final Instant EXACTLY_AT_START = Instant.parse("2025-09-03T00:00:00Z");
    private static final Instant AFTER_START = Instant.parse("2025-09-03T00:00:01Z");

    @MockitoBean
    private Clock clock;

    @Autowired
    private SubstitutionRepository substitutionRows;

    private final AtomicReference<Instant> now = new AtomicReference<>(JUST_BEFORE);

    @BeforeEach
    void fixClock() {
        now.set(JUST_BEFORE);
        Mockito.when(clock.instant()).thenAnswer(invocation -> now.get());
    }

    @Test
    void confirmAllowedJustBeforeTheDeadline() {
        var fx = AdminSubstitutionIntegrationTest.buildFixture(adminToken(), TestData.suffix(), MONDAY);
        long voucherId = preview(fx);
        long requestId = as(adminToken()).body(Map.of())
                .when().post("/api/substitution-workbench/previews/" + voucherId + "/confirm")
                .then().statusCode(200)
                .extract().jsonPath().getLong("requestId");
        as(adminToken()).when().get("/api/substitution-requests/" + requestId)
                .then().statusCode(200)
                .body("status", org.hamcrest.Matchers.equalTo("EFFECTIVE"));
    }

    @Test
    void confirmRejectedExactlyAtLessonStartAfterWaiting() {
        var fx = AdminSubstitutionIntegrationTest.buildFixture(adminToken(), TestData.suffix(), MONDAY);
        long voucherId = preview(fx);
        // 预览后（可能等待过行锁）时钟走到恰好开课：确认必须被拒绝。
        now.set(EXACTLY_AT_START);
        as(adminToken()).body(Map.of())
                .when().post("/api/substitution-workbench/previews/" + voucherId + "/confirm")
                .then().statusCode(409)
                .body("message", org.hamcrest.Matchers.containsString("开课"));
        assertThat(substitutionRows.count()).isZero();
    }

    @Test
    void confirmRejectedAfterTheLessonStarted() {
        var fx = AdminSubstitutionIntegrationTest.buildFixture(adminToken(), TestData.suffix(), MONDAY);
        long voucherId = preview(fx);
        now.set(AFTER_START);
        as(adminToken()).body(Map.of())
                .when().post("/api/substitution-workbench/previews/" + voucherId + "/confirm")
                .then().statusCode(409);
        assertThat(substitutionRows.count()).isZero();
    }

    @Test
    void previewCannotBeCreatedAtOrAfterTheDeadline() {
        var fx = AdminSubstitutionIntegrationTest.buildFixture(adminToken(), TestData.suffix(), MONDAY);
        now.set(EXACTLY_AT_START);
        as(adminToken()).body(Map.of(
                        "entryId", fx.entryAId(),
                        "lessonDate", fx.lessonDate().toString(),
                        "substituteTeacherId", fx.teacherCId()))
                .when().post("/api/substitution-workbench/previews")
                .then().statusCode(409)
                .body("message", org.hamcrest.Matchers.containsString("开课"));
    }

    @Test
    void submitApprovalIsAlsoBlockedByTheDeadline() {
        var fx = AdminSubstitutionIntegrationTest.buildFixture(adminToken(), TestData.suffix(), MONDAY);
        long voucherId = preview(fx);
        now.set(EXACTLY_AT_START);
        as(adminToken()).body(Map.of())
                .when().post("/api/substitution-workbench/previews/" + voucherId + "/submit-approval")
                .then().statusCode(409);
        assertThat(substitutionRows.count()).isZero();
    }

    private long preview(AdminSubstitutionIntegrationTest.Fixture fx) {
        Response response = as(adminToken()).body(Map.of(
                        "entryId", fx.entryAId(),
                        "lessonDate", fx.lessonDate().toString(),
                        "substituteTeacherId", fx.teacherCId(),
                        "reason", "截止测试"))
                .when().post("/api/substitution-workbench/previews");
        response.then().statusCode(200);
        // 服务端返回的权威截止 = 授课日 08:00（Asia/Shanghai）。
        assertThat(response.jsonPath().getString("deadlineAt")).contains("00:00:00");
        return response.jsonPath().getLong("voucherId");
    }
}
