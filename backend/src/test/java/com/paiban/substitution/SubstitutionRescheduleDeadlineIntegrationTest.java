package com.paiban.substitution;

import static org.assertj.core.api.Assertions.assertThat;

import com.paiban.substitution.domain.Substitution;
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
 * 票 07 的开课截止与冻结规则（规格第 6 节，固定服务端时钟）：
 * <ul>
 *   <li>再次调整的本单截止 = 本次将改变的所有实例「当前与拟生效」开课时刻的最小值，
 *       严格早于才可确认；预览后时钟跨点（含等待行锁后重读）整单拒绝。</li>
 *   <li>原始基线日期已过去，不单独锁死尚未开始的当前授课——截止按当前实际安排判定。</li>
 *   <li>已开始或已结束的实例任何路径都不可改变（代课、恢复基线、再调课）。</li>
 *   <li>已开始的槽位作为目标：生成方案即拒绝；作为旧环成员：新方案绕开它。</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SubstitutionRescheduleDeadlineIntegrationTest extends ApiIntegrationTest {

    private static final LocalDate MONDAY = LocalDate.of(2025, 9, 1);
    /** 周五 08:00（Asia/Shanghai）= 00:00Z：再次调课环内最早开课时刻（当前落点）。 */
    private static final Instant FRIDAY_START = Instant.parse("2025-09-05T00:00:00Z");
    private static final Instant SUNDAY_NIGHT = Instant.parse("2025-08-31T16:00:00Z");
    private static final Instant THURSDAY_NOON = Instant.parse("2025-09-04T04:00:00Z");
    /** 周五 08:20：第 1 节（08:00）已开始、第 2 节（08:45）未开始。 */
    private static final Instant FRIDAY_P2_WINDOW = Instant.parse("2025-09-05T00:20:00Z");

    @MockitoBean
    private Clock clock;

    @Autowired
    private SubstitutionRepository substitutionRows;

    private final AtomicReference<Instant> now = new AtomicReference<>(SUNDAY_NIGHT);

    @BeforeEach
    void fixClock() {
        now.set(SUNDAY_NIGHT);
        Mockito.when(clock.instant()).thenAnswer(invocation -> now.get());
    }

    private SubstitutionRescheduleIntegrationTest.Fixture fx() {
        return SubstitutionRescheduleIntegrationTest.buildFixture(
                adminToken(), TestData.suffix(), MONDAY);
    }

    private SubstitutionRescheduleIntegrationTest.Fixture fxFrozen() {
        return SubstitutionRescheduleIntegrationTest.buildFixture(
                adminToken(), TestData.suffix(), MONDAY, true);
    }

    /** 首次调课（语文 周三 1 → 周五 1）亲自确认生效。 */
    private long firstSwap(String admin, SubstitutionRescheduleIntegrationTest.Fixture fx) {
        long planId = generatePlan(admin, fx, fx.entryChineseId(), fx.wednesday(),
                fx.friday(), fx.fridayPeriodId());
        long voucherId = previewSwap(admin, fx, fx.entryChineseId(), fx.wednesday(), planId);
        return consumeOk(admin, voucherId, "confirm-swap");
    }

    private long generatePlan(String admin, SubstitutionRescheduleIntegrationTest.Fixture fx,
                              long entryId, LocalDate lessonDate, LocalDate targetDate,
                              long targetPeriodId) {
        Response response = as(admin).body(Map.of(
                        "entryId", entryId,
                        "lessonDate", lessonDate.toString(),
                        "targetDate", targetDate.toString(),
                        "targetPeriodId", targetPeriodId,
                        "reason", "再次调整"))
                .when().post("/api/substitution-workbench/swap-plans");
        response.then().statusCode(200);
        return response.jsonPath().getLong("plans[0].planId");
    }

    private long previewSwap(String admin, SubstitutionRescheduleIntegrationTest.Fixture fx,
                             long entryId, LocalDate lessonDate, long planId) {
        Response response = as(admin).body(Map.of(
                        "entryId", entryId,
                        "lessonDate", lessonDate.toString(),
                        "planId", planId,
                        "reason", "再次调整"))
                .when().post("/api/substitution-workbench/swap-previews");
        response.then().statusCode(200);
        return response.jsonPath().getLong("voucherId");
    }

    private long consumeOk(String admin, long voucherId, String action) {
        Response response = as(admin).body(Map.of())
                .when().post("/api/substitution-workbench/previews/" + voucherId + "/" + action);
        assertThat(response.getStatusCode())
                .as(response.jsonPath().getString("message"))
                .isEqualTo(200);
        return response.jsonPath().getLong("requestId");
    }

    // --------------------------------------- 截止三分与锁后重读（再次调整）

    @Test
    void rescheduleConfirmRejectedExactlyAtTheEarliestStartThenAllowedAfterRollback() {
        var fx = fx();
        String admin = adminToken();
        firstSwap(admin, fx);

        // 对已调到周五的语文再次调课（目标 = 下周一，英语的基线槽）。
        // 环内最早开课 = 周五 08:00（当前落点），不是下周一。
        now.set(THURSDAY_NOON);
        long planId = generatePlan(admin, fx, fx.entryChineseId(), fx.friday(),
                fx.nextMonday(), fx.mondayPeriodId());
        long voucherId = previewSwap(admin, fx, fx.entryChineseId(), fx.friday(), planId);
        long rowsBefore = substitutionRows.count();

        // 时钟推到恰好开课：等于截止也拒绝（等待行锁后重读时钟，排队跨点同样拒绝）。
        now.set(FRIDAY_START);
        as(admin).body(Map.of())
                .when().post("/api/substitution-workbench/previews/" + voucherId + "/confirm-swap")
                .then().statusCode(409)
                .body("message", org.hamcrest.Matchers.containsString("开课"));
        assertThat(substitutionRows.count()).isEqualTo(rowsBefore);

        // 时钟回到截止之前：同一凭证照常生效——拒绝的是「跨点」而不是凭证本身。
        now.set(THURSDAY_NOON);
        long requestId = consumeOk(admin, voucherId, "confirm-swap");
        assertThat(requestId).isPositive();
        assertThat(substitutionRows.findByRequestId(requestId)).hasSize(2);
        assertThat(substitutionRows.count()).isEqualTo(rowsBefore + 2);
    }

    @Test
    void baselineDatePassingDoesNotLockTheCurrentFutureLesson() {
        var fx = fx();
        String admin = adminToken();
        firstSwap(admin, fx);

        // 周四：基线日（周三）已过去，但当前实际授课（周五）还没开始——
        // 再次调整与代课都以当前实际安排判定截止，不因基线日期已过而锁死。
        now.set(THURSDAY_NOON);
        long planId = generatePlan(admin, fx, fx.entryChineseId(), fx.friday(),
                fx.nextMonday(), fx.mondayPeriodId());
        Response preview = as(admin).body(Map.of(
                        "entryId", fx.entryChineseId(),
                        "lessonDate", fx.friday().toString(),
                        "planId", planId,
                        "reason", "基线已过仍可调整"))
                .when().post("/api/substitution-workbench/swap-previews");
        preview.then().statusCode(200);
        long swapVoucher = preview.jsonPath().getLong("voucherId");
        // 截止 = 当前落点（周五 08:00），不是已过去的基线日（周三 08:00）。
        assertThat(preview.jsonPath().getString("deadlineAt")).contains("2025-09-05T00:00:00");

        // 代课路径同样按当前实际安排判定。
        Response coverPreview = as(admin).body(Map.of(
                        "entryId", fx.entryChineseId(),
                        "lessonDate", fx.friday().toString(),
                        "substituteTeacherId", fx.teacherSpareId(),
                        "reason", "基线已过仍可代课"))
                .when().post("/api/substitution-workbench/previews");
        coverPreview.then().statusCode(200);
        long coverVoucher = coverPreview.jsonPath().getLong("voucherId");
        consumeOk(admin, coverVoucher, "confirm");
        assertThat(substitutionRows.findByEntryId(fx.entryChineseId()))
                .anySatisfy(row -> {
                    assertThat(row.getStatus()).isEqualTo(Substitution.EFFECTIVE);
                    assertThat(row.getType()).isEqualTo(Substitution.TYPE_COVER);
                    assertThat(row.getLessonDate()).isEqualTo(fx.friday());
                });

        // 代课生效后课表已变化：早先的再次调课凭证以明确冲突拒绝（基准被替代）。
        as(admin).body(Map.of())
                .when().post("/api/substitution-workbench/previews/" + swapVoucher + "/confirm-swap")
                .then().statusCode(409);
    }

    // --------------------------------------- 已开始/已结束实例任何路径不可变

    @Test
    void startedInstanceCannotBeChangedByAnyPath() {
        var fx = fx();
        String admin = adminToken();
        firstSwap(admin, fx);
        // 周日前再代课一次（丙接管周五的语文），让「恢复基线」路径有明确语义。
        Response cover = as(admin).body(Map.of(
                        "entryId", fx.entryChineseId(),
                        "lessonDate", fx.friday().toString(),
                        "substituteTeacherId", fx.teacherSpareId(),
                        "reason", "丙代课"))
                .when().post("/api/substitution-workbench/previews");
        cover.then().statusCode(200);
        consumeOk(admin, cover.jsonPath().getLong("voucherId"), "confirm");

        // 周五 12:00：语文（当前落点周五 08:00 开课）已开始。
        now.set(FRIDAY_START.plusSeconds(4 * 3600));
        long rowsBefore = substitutionRows.count();

        // 恢复基线（代课教师 = 基线张）：已开始的实例不可改，含恢复基线路径。
        Response restore = as(admin).body(Map.of(
                        "entryId", fx.entryChineseId(),
                        "lessonDate", fx.friday().toString(),
                        "substituteTeacherId", fx.teacherChineseId(),
                        "reason", "已开始后恢复基线"))
                .when().post("/api/substitution-workbench/previews");
        assertThat(restore.getStatusCode()).as(restore.jsonPath().getString("message")).isEqualTo(409);

        // 继续换人（丙 → 李）：同样被开课截止挡下。
        Response change = as(admin).body(Map.of(
                        "entryId", fx.entryChineseId(),
                        "lessonDate", fx.friday().toString(),
                        "substituteTeacherId", fx.teacherEnglishId(),
                        "reason", "已开始后再换人"))
                .when().post("/api/substitution-workbench/previews");
        assertThat(change.getStatusCode()).as(change.jsonPath().getString("message")).isEqualTo(409);

        // 再调课路径：来源槽（周五 1）已开始 → 生成方案明确拒绝。
        Response reschedule = as(admin).body(Map.of(
                        "entryId", fx.entryChineseId(),
                        "lessonDate", fx.friday().toString(),
                        "targetDate", fx.nextMonday().toString(),
                        "targetPeriodId", fx.mondayPeriodId(),
                        "reason", "已开始后再调课"))
                .when().post("/api/substitution-workbench/swap-plans");
        assertThat(reschedule.getStatusCode()).as(reschedule.jsonPath().getString("message")).isEqualTo(409);

        // 零写入：旧安排原样保留。
        assertThat(substitutionRows.count()).isEqualTo(rowsBefore);
    }

    // --------------------------------------- 部分已开始的环：冻结与合法新方案

    @Test
    void legalNewPlanDetoursFrozenCycleMembersAndTakesEffect() {
        var fx = fx();
        String admin = adminToken();
        firstSwap(admin, fx);

        // 周四：旧环的数学已被换到周三 1（已开始 → 冻结）；语文（周五）未开始。
        now.set(THURSDAY_NOON);
        long planId = generatePlan(admin, fx, fx.entryChineseId(), fx.friday(),
                fx.nextMonday(), fx.mondayPeriodId());
        long voucherId = previewSwap(admin, fx, fx.entryChineseId(), fx.friday(), planId);
        long requestId = consumeOk(admin, voucherId, "confirm-swap");

        // 新方案只动语文（周五 → 下周一）与英语（下周一 → 周五），数学（冻结）不动。
        assertThat(substitutionRows.findByRequestId(requestId))
                .extracting(Substitution::getEntryId)
                .containsExactlyInAnyOrder(fx.entryChineseId(), fx.entryEnglishId());
        // 冻结实例的旧生效行保持 EFFECTIVE；语文旧行转 SUPERSEDED。
        assertThat(substitutionRows.findByEntryId(fx.entryMathId()))
                .allSatisfy(row -> assertThat(row.getStatus()).isEqualTo(Substitution.EFFECTIVE));
        assertThat(substitutionRows.findByEntryId(fx.entryChineseId()))
                .anySatisfy(row -> {
                    assertThat(row.getRequestId()).isEqualTo(requestId);
                    assertThat(row.getStatus()).isEqualTo(Substitution.EFFECTIVE);
                    assertThat(row.getLessonDate()).isEqualTo(fx.wednesday());
                    assertThat(row.getTargetLessonDate()).isEqualTo(fx.nextMonday());
                });
    }

    @Test
    void startedSlotAsTargetIsRejectedAtGeneration() {
        var fxF = fxFrozen();
        String admin = adminToken();
        firstSwap(admin, fxF);

        // 周五 08:20：第 1 节（语文当前落点 + 数学旧槽）已开始冻结；
        // 第 2 节的英语（08:45）未开始，是窗口内唯一可动的来源。
        now.set(FRIDAY_P2_WINDOW);
        long rowsBefore = substitutionRows.count();

        // 把已开始的第 1 节指定为目标：生成方案明确拒绝（不能把课移进已开始的时间）。
        as(admin).body(Map.of(
                        "entryId", fxF.entryEnglishId(),
                        "lessonDate", fxF.friday().toString(),
                        "targetDate", fxF.friday().toString(),
                        "targetPeriodId", fxF.fridayPeriodId(),
                        "reason", "目标已开始"))
                .when().post("/api/substitution-workbench/swap-plans")
                .then().statusCode(409)
                .body("message", org.hamcrest.Matchers.containsString("开课"));

        // 零写入。
        assertThat(substitutionRows.count()).isEqualTo(rowsBefore);
    }
}
