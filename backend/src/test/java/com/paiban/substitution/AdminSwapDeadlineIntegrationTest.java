package com.paiban.substitution;

import static org.assertj.core.api.Assertions.assertThat;

import com.paiban.substitution.domain.SubstitutionRepository;
import com.paiban.support.ApiIntegrationTest;
import com.paiban.support.TestData;
import io.restassured.response.Response;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
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

/**
 * 票 05 的开课截止三分（规格第 6 节）：调课环的本单截止 = 环内所有被改变实例
 * 「当前与拟生效」开课时刻的最小值。跨日二人环（周三 08:00 ↔ 周五 08:00）的
 * 截止取较早的周三 08:00——即使目标日（周五）还没开课，时钟越过周三 08:00 后
 * 整环也不能再生效。固定时钟驱动服务端权威时间，验证预览与确认都以实时时钟面判定。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AdminSwapDeadlineIntegrationTest extends ApiIntegrationTest {

    private static final LocalDate MONDAY = LocalDate.of(2025, 9, 1);
    /** 周三 08:00（Asia/Shanghai）= 00:00Z：环内最早的开课时刻。 */
    private static final Instant WEDNESDAY_START = Instant.parse("2025-09-03T00:00:00Z");
    private static final Instant JUST_BEFORE = WEDNESDAY_START.minusSeconds(1);
    private static final Instant WEDNESDAY_NOON = Instant.parse("2025-09-03T04:00:00Z");
    private static final Instant THURSDAY = Instant.parse("2025-09-04T04:00:00Z");

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
    void confirmAllowedJustBeforeTheEarliestLessonStart() {
        var fx = AdminSwapIntegrationTest.buildFixture(
                adminToken(), TestData.suffix(), MONDAY, false, false);
        long voucherId = preview(fx);
        long requestId = as(adminToken()).body(Map.of("comment", "确认"))
                .when().post("/api/substitution-workbench/previews/" + voucherId + "/confirm-swap")
                .then().statusCode(200)
                .body("status", org.hamcrest.Matchers.equalTo("EFFECTIVE"))
                .extract().jsonPath().getLong("requestId");
        assertThat(substitutionRows.findByRequestId(requestId)).hasSize(2);
    }

    @Test
    void confirmRejectedExactlyAtTheEarliestLessonStart() {
        var fx = AdminSwapIntegrationTest.buildFixture(
                adminToken(), TestData.suffix(), MONDAY, false, false);
        long voucherId = preview(fx);
        // 预览后（可能等待过行锁）时钟走到恰好开课：等于截止也拒绝。
        now.set(WEDNESDAY_START);
        as(adminToken()).body(Map.of())
                .when().post("/api/substitution-workbench/previews/" + voucherId + "/confirm-swap")
                .then().statusCode(409)
                .body("message", org.hamcrest.Matchers.containsString("开课"));
        assertThat(substitutionRows.count()).isZero();
    }

    @Test
    void wholeCycleBlockedEvenWhenOnlyTheSourceDayHasStarted() {
        var fx = AdminSwapIntegrationTest.buildFixture(
                adminToken(), TestData.suffix(), MONDAY, false, false);
        long voucherId = preview(fx);
        // 周三中午：目标日（周五 08:00）还没到，但环内最早开课（周三）已过——整单拒绝。
        now.set(WEDNESDAY_NOON);
        as(adminToken()).body(Map.of())
                .when().post("/api/substitution-workbench/previews/" + voucherId + "/confirm-swap")
                .then().statusCode(409);
        assertThat(substitutionRows.count()).isZero();

        // 周四同理：等待行锁后重读时钟，排队跨点同样拒绝。
        now.set(THURSDAY);
        as(adminToken()).body(Map.of())
                .when().post("/api/substitution-workbench/previews/" + voucherId + "/confirm-swap")
                .then().statusCode(409);
        assertThat(substitutionRows.count()).isZero();
    }

    @Test
    void previewCannotBeCreatedAtOrAfterTheEarliestLessonStart() {
        var fx = AdminSwapIntegrationTest.buildFixture(
                adminToken(), TestData.suffix(), MONDAY, false, false);
        now.set(WEDNESDAY_START);
        as(adminToken()).body(Map.of(
                        "entryId", fx.entryChineseId(),
                        "lessonDate", fx.wednesday().toString(),
                        "targetDate", fx.friday().toString(),
                        "targetPeriodId", fx.fridayPeriodId()))
                .when().post("/api/substitution-workbench/swap-plans")
                .then().statusCode(409)
                .body("message", org.hamcrest.Matchers.containsString("开课"));
    }

    private long preview(AdminSwapIntegrationTest.Fixture fx) {
        Response generated = as(adminToken()).body(Map.of(
                        "entryId", fx.entryChineseId(),
                        "lessonDate", fx.wednesday().toString(),
                        "targetDate", fx.friday().toString(),
                        "targetPeriodId", fx.fridayPeriodId()))
                .when().post("/api/substitution-workbench/swap-plans");
        generated.then().statusCode(200);
        long planId = generated.jsonPath().getLong("plans[0].planId");
        Response response = as(adminToken()).body(Map.of(
                        "entryId", fx.entryChineseId(),
                        "lessonDate", fx.wednesday().toString(),
                        "planId", planId))
                .when().post("/api/substitution-workbench/swap-previews");
        response.then().statusCode(200);
        // 环内最小截止 = 周三 08:00（Asia/Shanghai），不是周五的。
        assertThat(response.jsonPath().getString("deadlineAt")).contains("2025-09-03T00:00:00");
        return response.jsonPath().getLong("voucherId");
    }
}
