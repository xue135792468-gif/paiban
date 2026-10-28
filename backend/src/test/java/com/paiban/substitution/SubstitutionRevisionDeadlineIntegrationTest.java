package com.paiban.substitution;

import static org.assertj.core.api.Assertions.assertThat;

import com.paiban.substitution.domain.SubstitutionRepository;
import com.paiban.support.ApiIntegrationTest;
import com.paiban.support.TestData;
import io.restassured.response.Response;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import org.mockito.Mockito;

/**
 * 修订与恢复基线的开课截止三分边界（票 04，规格第 6 节）：本单截止 =
 * 本次改变实例「当前安排与拟生效安排」开课时刻的最小值；COVER 槽位不变，
 * 就是这节课的开课时刻。截止前一瞬可确认；恰好开课与之后拒绝——预览同样
 * 不得跨截止创建或确认，包括恢复基线。用固定时钟驱动服务端权威时间。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SubstitutionRevisionDeadlineIntegrationTest extends ApiIntegrationTest {

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
    void revisionConfirmAllowedJustBeforeTheDeadline() {
        var fx = AdminSubstitutionIntegrationTest.buildFixture(
                adminToken(), TestData.suffix(), MONDAY);
        makeCover(fx);
        long voucherId = previewRevision(fx);
        long requestId = as(adminToken()).body(Map.of())
                .when().post("/api/substitution-workbench/previews/" + voucherId + "/confirm")
                .then().statusCode(200)
                .extract().jsonPath().getLong("requestId");
        as(adminToken()).when().get("/api/substitution-requests/" + requestId)
                .then().statusCode(200)
                .body("status", org.hamcrest.Matchers.equalTo("EFFECTIVE"));
    }

    @Test
    void revisionConfirmRejectedExactlyAtLessonStart() {
        var fx = AdminSubstitutionIntegrationTest.buildFixture(
                adminToken(), TestData.suffix(), MONDAY);
        makeCover(fx);
        long voucherId = previewRevision(fx);
        // 预览后（可能等待过行锁）时钟走到恰好开课：修订确认必须被拒绝，丙仍生效。
        now.set(EXACTLY_AT_START);
        as(adminToken()).body(Map.of())
                .when().post("/api/substitution-workbench/previews/" + voucherId + "/confirm")
                .then().statusCode(409)
                .body("message", org.hamcrest.Matchers.containsString("开课"));
        assertThat(classTeacher(fx)).isEqualTo(String.valueOf(fx.teacherCId()));
        assertThat(substitutionRows.count()).isEqualTo(1);
    }

    @Test
    void revisionPreviewRejectedExactlyAtLessonStart() {
        var fx = AdminSubstitutionIntegrationTest.buildFixture(
                adminToken(), TestData.suffix(), MONDAY);
        makeCover(fx);
        now.set(EXACTLY_AT_START);
        as(adminToken()).body(Map.of(
                        "entryId", fx.entryAId(),
                        "lessonDate", fx.lessonDate().toString(),
                        "substituteTeacherId", fx.teacherDId()))
                .when().post("/api/substitution-workbench/previews")
                .then().statusCode(409)
                .body("message", org.hamcrest.Matchers.containsString("开课"));
    }

    @Test
    void restoreBaselineBlockedAfterLessonStart() {
        var fx = AdminSubstitutionIntegrationTest.buildFixture(
                adminToken(), TestData.suffix(), MONDAY);
        makeCover(fx);
        now.set(AFTER_START);
        // 恢复基线与更换代课人同规则：已开课一律拒绝。
        as(adminToken()).body(Map.of(
                        "entryId", fx.entryAId(),
                        "lessonDate", fx.lessonDate().toString(),
                        "substituteTeacherId", fx.teacherAId()))
                .when().post("/api/substitution-workbench/previews")
                .then().statusCode(409)
                .body("message", org.hamcrest.Matchers.containsString("开课"));
        assertThat(substitutionRows.count()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ helpers

    /** A→C 首代（预览 + 亲自确认），全程在当前固定时钟下进行。 */
    private void makeCover(AdminSubstitutionIntegrationTest.Fixture fx) {
        Response preview = as(adminToken()).body(Map.of(
                        "entryId", fx.entryAId(),
                        "lessonDate", fx.lessonDate().toString(),
                        "substituteTeacherId", fx.teacherCId(),
                        "reason", "截止测试首代"))
                .when().post("/api/substitution-workbench/previews");
        preview.then().statusCode(200);
        long voucherId = preview.jsonPath().getLong("voucherId");
        as(adminToken()).body(Map.of())
                .when().post("/api/substitution-workbench/previews/" + voucherId + "/confirm")
                .then().statusCode(200);
        assertThat(classTeacher(fx)).isEqualTo(String.valueOf(fx.teacherCId()));
    }

    private long previewRevision(AdminSubstitutionIntegrationTest.Fixture fx) {
        Response response = as(adminToken()).body(Map.of(
                        "entryId", fx.entryAId(),
                        "lessonDate", fx.lessonDate().toString(),
                        "substituteTeacherId", fx.teacherDId(),
                        "reason", "截止测试修订"))
                .when().post("/api/substitution-workbench/previews");
        response.then().statusCode(200);
        return response.jsonPath().getLong("voucherId");
    }

    private String classTeacher(AdminSubstitutionIntegrationTest.Fixture fx) {
        return as(adminToken()).queryParam("date", fx.lessonDate().toString())
                .when().get("/api/timetable/class/" + fx.classAId())
                .then().statusCode(200)
                .extract().jsonPath().getString(
                        "find { it.id == " + fx.entryAId() + " }.teacherId");
    }
}
