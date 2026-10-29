package com.paiban.substitution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;

import com.paiban.substitution.domain.Substitution;
import com.paiban.substitution.domain.SubstitutionConversionItemRepository;
import com.paiban.substitution.domain.SubstitutionConversionRepository;
import com.paiban.substitution.domain.SubstitutionRepository;
import com.paiban.support.ApiIntegrationTest;
import com.paiban.support.ScheduleFixture;
import com.paiban.support.TestData;
import io.restassured.response.Response;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;

/**
 * 历史调代日期转换（票 02）的 HTTP 验收：预检只读、确认门、原子转换、
 * 幂等重跑与阻塞样例。旧格式数据用真实流程生成后抹掉实例日期模拟
 * （票 01 之前的存量行就是 lesson_date 为空）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SubstitutionConversionIntegrationTest extends ApiIntegrationTest {

    // 种子经真实批准流程生成生效行（批准受开课截止约束，规格第 6 节）：学期锚在未来。
    private static final LocalDate MONDAY = ScheduleFixture.nextMonday();
    private static final LocalDate SECOND_MONDAY = MONDAY.plusWeeks(1);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SubstitutionRepository substitutions;

    @Autowired
    private SubstitutionConversionRepository conversionBatches;

    @MockitoSpyBean
    private SubstitutionConversionItemRepository conversionItems;

    private record CoverContext(long semesterId, long classId, long entryId, long requestId,
                                long substitutionId, long substituteTeacherId, long teacherId,
                                LocalDate date) {}

    // ------------------------------------------------------------------
    // 种子：真实 HTTP 流程生成新格式行，再抹掉实例日期模拟历史存量
    // ------------------------------------------------------------------

    private CoverContext seedCover(String token, String suffix) {
        ScheduleFixture.Result fixture =
                ScheduleFixture.publishedIn(token, 5, TestData.semesterStarting(token, MONDAY));
        List<Map<String, Object>> entries = ScheduleFixture.classEntries(token, fixture.classId());
        Map<String, Object> entry = entries.stream()
                .filter(item -> ((Number) item.get("teacherId")).longValue() == fixture.teacherId())
                .findFirst().orElseThrow();
        long entryId = ((Number) entry.get("id")).longValue();
        int dayOfWeek = ((Number) entry.get("dayOfWeek")).intValue();
        LocalDate date = MONDAY.plusDays(dayOfWeek - 1L);
        long substituteTeacherId = TestData.teacher(token, "代课老师" + suffix);

        long requestId = as(token).body(Map.of(
                        "entryId", entryId,
                        "effectiveDate", date.toString(),
                        "reason", "历史代课",
                        "expectedSubstituteTeacherId", substituteTeacherId))
                .when().post("/api/substitution-requests")
                .then().statusCode(200).extract().jsonPath().getLong("id");
        as(token).body(Map.of("substituteTeacherId", substituteTeacherId, "comment", "同意"))
                .when().post("/api/substitution-requests/" + requestId + "/approve")
                .then().statusCode(200);

        Long substitutionId = jdbcTemplate.queryForObject(
                "select id from substitution where request_id = ?", Long.class, requestId);
        makeLegacy();
        return new CoverContext(fixture.semesterId(), fixture.classId(), entryId, requestId,
                substitutionId, substituteTeacherId, fixture.teacherId(), date);
    }

    /** 模拟票 01 之前的存量：所有生效行的实例日期都被抹掉。 */
    private void makeLegacy() {
        jdbcTemplate.update("update substitution set lesson_date = null, target_lesson_date = null");
    }

    private Response classView(String token, long classId, LocalDate date) {
        return as(token).queryParam("date", date.toString())
                .when().get("/api/timetable/class/" + classId)
                .then().statusCode(200).extract().response();
    }

    private String teacherOn(Response view, long entryId) {
        return view.jsonPath().getString("find { it.id == " + entryId + " }.teacherName");
    }

    private boolean substituted(Response view, long entryId) {
        return Boolean.TRUE.equals(view.jsonPath().getBoolean(
                "find { it.id == " + entryId + " }.substituted"));
    }

    private Substitution row(long substitutionId) {
        return substitutions.findById(substitutionId).orElseThrow();
    }

    // ------------------------------------------------------------------
    // 预检：只读、逐条依据、汇总一致
    // ------------------------------------------------------------------

    @Test
    void precheckReportsTheCoverBasisWithoutTouchingBusinessData() {
        String token = adminToken();
        CoverContext context = seedCover(token, TestData.suffix());

        Response before = classView(token, context.classId(), SECOND_MONDAY);
        String nameBefore = teacherOn(before, context.entryId());
        assertThat(substituted(before, context.entryId())).isTrue();

        Response precheck = as(token).when().get("/api/substitution-conversion/precheck")
                .then().statusCode(200).extract().response();

        assertThat(precheck.jsonPath().getInt("summary.totalRows")).isEqualTo(1);
        assertThat(precheck.jsonPath().getInt("summary.convertible")).isEqualTo(1);
        assertThat(precheck.jsonPath().getInt("summary.blocked")).isZero();
        assertThat(precheck.jsonPath().getInt("summary.totalRows"))
                .isEqualTo(precheck.jsonPath().getInt("summary.convertible")
                        + precheck.jsonPath().getInt("summary.blocked")
                        + precheck.jsonPath().getInt("summary.alreadyConverted"));

        assertThat(precheck.jsonPath().getString("items[0].status")).isEqualTo("CONVERTIBLE");
        assertThat(precheck.jsonPath().getString("items[0].lessonDate"))
                .isEqualTo(context.date().toString());
        assertThat(precheck.jsonPath().getString("items[0].basis"))
                .contains("申请 #" + context.requestId()).contains(context.date().toString());
        assertThat(precheck.jsonPath().getString("items[0].substituteTeacherName")).isNotBlank();
        // 差异清单：后续周恢复常规安排（同一基线槽位的下一个实例）
        LocalDate nextWeek = context.date().plusDays(7);
        assertThat(precheck.jsonPath().getList("items[0].affectedDates")).isNotEmpty();
        assertThat(precheck.jsonPath().getBoolean(
                "items[0].affectedDates[0].restoredToBaseline")).isTrue();
        assertThat(precheck.jsonPath().getString("items[0].affectedDates[0].date"))
                .isEqualTo(nextWeek.toString());
        assertThat(precheck.jsonPath().getInt("summary.changes"))
                .isEqualTo(precheck.jsonPath().getList("items[0].affectedDates").size());

        // 预检不改动任何业务数据：读取与行状态原样
        Response after = classView(token, context.classId(), SECOND_MONDAY);
        assertThat(teacherOn(after, context.entryId())).isEqualTo(nameBefore);
        assertThat(substituted(after, context.entryId())).isTrue();
        assertThat(row(context.substitutionId()).getLessonDate()).isNull();
        assertThat(conversionBatches.count()).isZero();
    }

    @Test
    void readingKeepsLegacySemanticsUntilTheGateIsPassed() {
        String token = adminToken();
        CoverContext context = seedCover(token, TestData.suffix());

        // 未确认前：下周同一槽位仍被旧格式行持续覆盖（ADR-0008 语义）
        assertThat(substituted(classView(token, context.classId(), SECOND_MONDAY), context.entryId()))
                .isTrue();

        // 预检与状态端点本身不改变任何读取语义
        as(token).when().get("/api/substitution-conversion/precheck").then().statusCode(200);
        Response status = as(token).when().get("/api/substitution-conversion/status")
                .then().statusCode(200).extract().response();
        assertThat(status.jsonPath().getLong("legacyRowCount")).isEqualTo(1);
        assertThat(status.jsonPath().getBoolean("converted")).isFalse();
        assertThat(status.jsonPath().getBoolean("canConfirm")).isTrue();
        assertThat(status.jsonPath().getList("conversions")).isEmpty();

        assertThat(substituted(classView(token, context.classId(), SECOND_MONDAY), context.entryId()))
                .isTrue();
    }

    // ------------------------------------------------------------------
    // 确认门：转换、读取切换、历史可追溯、幂等
    // ------------------------------------------------------------------

    @Test
    void confirmConvertsAndReadingSwitchesToLessonDates() {
        String token = adminToken();
        CoverContext context = seedCover(token, TestData.suffix());

        as(token).body(Map.of("comment", "历史数据核对无误，确认切换"))
                .when().post("/api/substitution-conversion/confirm")
                .then().statusCode(200)
                .body("convertedCount", equalTo(1))
                .body("alreadyConverted", equalTo(false))
                .body("conversion.status", equalTo("CONVERTED"))
                .body("conversion.confirmedBy", equalTo("admin"))
                .body("conversion.items[0].lessonDateBefore", equalTo(null))
                .body("conversion.items[0].lessonDateAfter", equalTo(context.date().toString()));

        // 转换后：授课日当天仍是代课安排，后续周恢复常规（新日期语义）
        assertThat(substituted(classView(token, context.classId(), context.date()), context.entryId()))
                .isTrue();
        assertThat(substituted(classView(token, context.classId(), SECOND_MONDAY), context.entryId()))
                .isFalse();

        // 原始历史仍可追溯：生效行 effective_date 未被改写，申请与流转记录保留
        Substitution converted = row(context.substitutionId());
        assertThat(converted.getLessonDate()).isEqualTo(context.date());
        assertThat(converted.getEffectiveDate()).isEqualTo(context.date());
        as(token).when().get("/api/substitution-requests/" + context.requestId())
                .then().statusCode(200).body("status", equalTo("EFFECTIVE"));
        as(token).when().get("/api/substitution-requests/" + context.requestId() + "/history")
                .then().statusCode(200).body("toStatus", hasItem("EFFECTIVE"));
        as(token).queryParam("type", "COVER").when().get("/api/substitutions")
                .then().statusCode(200).body("$", hasSize(1));

        // 确认记录可查
        Response status = as(token).when().get("/api/substitution-conversion/status")
                .then().statusCode(200).extract().response();
        assertThat(status.jsonPath().getBoolean("converted")).isTrue();
        assertThat(status.jsonPath().getBoolean("canConfirm")).isFalse();
        assertThat(status.jsonPath().getList("conversions")).hasSize(1);
        assertThat(status.jsonPath().getString("conversions[0].confirmationComment"))
                .isEqualTo("历史数据核对无误，确认切换");
    }

    @Test
    void repeatedConfirmSkipsConvertedRowsAndWritesNoSecondBatch() {
        String token = adminToken();
        CoverContext context = seedCover(token, TestData.suffix());

        as(token).when().post("/api/substitution-conversion/confirm").then().statusCode(200);
        as(token).when().post("/api/substitution-conversion/confirm")
                .then().statusCode(200)
                .body("alreadyConverted", equalTo(true))
                .body("convertedCount", equalTo(0));

        assertThat(conversionBatches.count()).isEqualTo(1);
        assertThat(row(context.substitutionId()).getLessonDate()).isEqualTo(context.date());
        as(token).when().get("/api/substitution-conversion/records")
                .then().statusCode(200).body("$", hasSize(1));
    }

    @Test
    void confirmWithoutLegacyRowsIsRejectedAndHarmless() {
        String token = adminToken();
        ScheduleFixture.publishedIn(token, 5, TestData.semesterStarting(token, MONDAY));
        // 没有任何调代历史：确认门直接拒绝，不落批次
        as(token).when().post("/api/substitution-conversion/confirm")
                .then().statusCode(400);
        assertThat(conversionBatches.count()).isZero();
    }

    // ------------------------------------------------------------------
    // 阻塞样例：缺来源申请 / 歧义移动 / 版本冲突
    // ------------------------------------------------------------------

    @Test
    void confirmIsRejectedWhileAMissingRequestBlocksTheGate() {
        String token = adminToken();
        CoverContext context = seedCover(token, TestData.suffix());

        // 来源申请被抹掉（跳过外联检查直接删行）：该行无法还原日期依据
        jdbcTemplate.execute("set referential_integrity false");
        jdbcTemplate.update("delete from substitution_request where id = ?", context.requestId());
        jdbcTemplate.execute("set referential_integrity true");

        Response precheck = as(token).when().get("/api/substitution-conversion/precheck")
                .then().statusCode(200).extract().response();
        assertThat(precheck.jsonPath().getString("items[0].status")).isEqualTo("BLOCKED");
        assertThat(precheck.jsonPath().getString("items[0].blockerCode")).isEqualTo("MISSING_REQUEST");
        assertThat(precheck.jsonPath().getString("items[0].blockerReason")).isNotBlank();
        assertThat(precheck.jsonPath().getInt("summary.blocked")).isEqualTo(1);

        as(token).when().post("/api/substitution-conversion/confirm")
                .then().statusCode(409);

        // 整单拒绝：原数据不动、读取语义不变、没有任何转换批次
        assertThat(row(context.substitutionId()).getLessonDate()).isNull();
        assertThat(substituted(classView(token, context.classId(), SECOND_MONDAY), context.entryId()))
                .isTrue();
        assertThat(conversionBatches.count()).isZero();
        Response status = as(token).when().get("/api/substitution-conversion/status")
                .then().statusCode(200).extract().response();
        assertThat(status.jsonPath().getBoolean("canConfirm")).isFalse();
    }

    @Test
    void anAmbiguousPlanMoveBlocksTheGate() {
        String token = adminToken();
        SwapWorld world = seedSwap(token, TestData.suffix());

        // 同一方案下同一条目出现第二条移动：无法唯一解释目标日
        duplicateMove(world.moveId());

        Response precheck = as(token).when().get("/api/substitution-conversion/precheck")
                .then().statusCode(200).extract().response();
        assertThat(precheck.jsonPath().getInt("summary.blocked")).isEqualTo(1);
        assertThat(precheck.jsonPath().getString("items[0].blockerCode")).isEqualTo("AMBIGUOUS_MOVE");

        as(token).when().post("/api/substitution-conversion/confirm").then().statusCode(409);
        assertThat(row(world.substitutionId()).getLessonDate()).isNull();
        assertThat(conversionBatches.count()).isZero();
    }

    @Test
    void aPlanMoveThatContradictsTheRowIsAVersionConflict() {
        String token = adminToken();
        SwapWorld world = seedSwap(token, TestData.suffix());

        // 移动的来源日与生效行日期矛盾
        jdbcTemplate.update("update substitution_plan_move set from_date = ?, to_date = ? where id = ?",
                SECOND_MONDAY.toString(), SECOND_MONDAY.plusDays(1).toString(), world.moveId());

        Response precheck = as(token).when().get("/api/substitution-conversion/precheck")
                .then().statusCode(200).extract().response();
        assertThat(precheck.jsonPath().getString("items[0].status")).isEqualTo("BLOCKED");
        assertThat(precheck.jsonPath().getString("items[0].blockerCode")).isEqualTo("VERSION_CONFLICT");

        as(token).when().post("/api/substitution-conversion/confirm").then().statusCode(409);
        assertThat(row(world.substitutionId()).getLessonDate()).isNull();
    }

    // ------------------------------------------------------------------
    // SWAP：从原方案移动还原来源与目标日期
    // ------------------------------------------------------------------

    @Test
    void confirmConvertsASwapFromTheOriginalPlanMove() {
        String token = adminToken();
        SwapWorld world = seedSwap(token, TestData.suffix());

        // 未确认前：旧语义持续覆盖——下周语文仍在目标节次
        Response beforeWeek2 = classView(token, world.classId(), SECOND_MONDAY);
        assertThat(substituted(beforeWeek2, world.chineseEntry())).isTrue();
        assertThat(beforeWeek2.jsonPath().getInt(
                "find { it.id == " + world.chineseEntry() + " }.periodNo")).isEqualTo(2);

        Response precheck = as(token).when().get("/api/substitution-conversion/precheck")
                .then().statusCode(200).extract().response();
        assertThat(precheck.jsonPath().getInt("summary.convertible")).isEqualTo(2);
        assertThat(precheck.jsonPath().getString(
                "items.find { it.substitutionId == " + world.substitutionId() + " }.lessonDate"))
                .isEqualTo(MONDAY.toString());
        assertThat(precheck.jsonPath().getString(
                "items.find { it.substitutionId == " + world.substitutionId() + " }.targetLessonDate"))
                .isEqualTo(MONDAY.toString());

        as(token).when().post("/api/substitution-conversion/confirm")
                .then().statusCode(200).body("convertedCount", equalTo(2));

        // 授课日当天：调课布局照旧（来源与落点互换）
        Response movedDay = classView(token, world.classId(), MONDAY);
        assertThat(movedDay.jsonPath().getInt(
                "find { it.id == " + world.chineseEntry() + " }.periodNo")).isEqualTo(2);
        assertThat(movedDay.jsonPath().getInt(
                "find { it.id == " + world.mathEntry() + " }.periodNo")).isEqualTo(1);
        // 下周：恢复常规安排
        Response week2 = classView(token, world.classId(), SECOND_MONDAY);
        assertThat(substituted(week2, world.chineseEntry())).isFalse();
        assertThat(week2.jsonPath().getInt(
                "find { it.id == " + world.chineseEntry() + " }.periodNo")).isEqualTo(1);
        assertThat(substituted(week2, world.mathEntry())).isFalse();
    }

    // ------------------------------------------------------------------
    // 原子性：转换中途失败整单回滚
    // ------------------------------------------------------------------

    @Test
    void aFailedConversionLeavesNoPartialSwitchBehind() {
        String token = adminToken();
        CoverContext first = seedCover(token, TestData.suffix());
        CoverContext second = seedSecondCover(token, TestData.suffix(), first);

        // 批次留痕写入阶段失败：前面已写的实例日期必须整单回滚
        doThrow(new SimulatedFailure("模拟转换中途失败"))
                .when(conversionItems).saveAll(anyList());

        as(token).when().post("/api/substitution-conversion/confirm").then().statusCode(500);

        assertThat(row(first.substitutionId()).getLessonDate()).isNull();
        assertThat(row(second.substitutionId()).getLessonDate()).isNull();
        // 只留下 FAILED 留痕，没有任何 CONVERTED 批次（部分切换为零）
        assertThat(conversionBatches.findBySemesterIdOrderByCreatedAtDesc(first.semesterId()))
                .hasSize(1)
                .allSatisfy(batch -> assertThat(batch.getStatus())
                        .isEqualTo(com.paiban.substitution.domain.SubstitutionConversion.FAILED));
        assertThat(substituted(classView(token, first.classId(), SECOND_MONDAY), first.entryId()))
                .isTrue();
        // 失败原因已用独立事务留痕，可查
        as(token).when().get("/api/substitution-conversion/records")
                .then().statusCode(200)
                .body("$", hasSize(1))
                .body("[0].status", equalTo("FAILED"))
                .body("[0].failureReason", org.hamcrest.Matchers.containsString("模拟转换中途失败"));
        // 失败后重跑：数据未损，恢复真实仓库后再次确认并成功
        org.mockito.Mockito.reset(conversionItems);
        as(token).when().post("/api/substitution-conversion/confirm")
                .then().statusCode(200).body("convertedCount", equalTo(2));
        assertThat(row(first.substitutionId()).getLessonDate()).isEqualTo(first.date());
    }

    /** 不被全局异常处理器特殊映射的意外异常：命中 500 分支。 */
    private static final class SimulatedFailure extends RuntimeException {
        private SimulatedFailure(String message) {
            super(message);
        }
    }

    // ------------------------------------------------------------------
    // 权限
    // ------------------------------------------------------------------

    @Test
    void conversionEndpointsFollowThePermissionConstants() {
        String token = adminToken();
        String suffix = TestData.suffix();
        CoverContext context = seedCover(token, suffix);
        String teacherUsername = "conv_" + suffix;
        as(token).body(Map.of(
                        "username", teacherUsername,
                        "password", "teacher123",
                        "displayName", "普通教师",
                        "roleCodes", List.of("teacher")))
                .when().post("/api/users").then().statusCode(200);
        String teacherToken = login(teacherUsername, "teacher123");

        as(teacherToken).when().get("/api/substitution-conversion/precheck").then().statusCode(403);
        as(teacherToken).when().get("/api/substitution-conversion/status").then().statusCode(403);
        as(teacherToken).when().post("/api/substitution-conversion/confirm").then().statusCode(403);
        assertThat(row(context.substitutionId()).getLessonDate()).isNull();

        as(token).when().get("/api/substitution-conversion/precheck").then().statusCode(200);
    }

    // ------------------------------------------------------------------
    // SWAP 种子：双人同日环（照搬 SwapFlowIntegrationTest 的确定性草稿）
    // ------------------------------------------------------------------

    private record SwapWorld(long semesterId, long classId, long chineseEntry, long mathEntry,
                             long substitutionId, long moveId) {}

    @Autowired
    private com.paiban.scheduling.domain.TimetableDraftRepository drafts;

    @Autowired
    private com.paiban.scheduling.domain.DraftLessonRepository draftLessons;

    @Autowired
    private com.paiban.substitution.domain.SubstitutionPlanMoveRepository planMoves;

    private SwapWorld seedSwap(String token, String suffix) {
        long semesterId = TestData.semesterStarting(token, MONDAY);
        as(token).body(Map.of("morningCount", 2, "afternoonCount", 2, "eveningCount", 0))
                .when().post("/api/semesters/" + semesterId + "/periods/default").then().statusCode(200);
        List<Map<String, Object>> periodRows = as(token)
                .when().get("/api/semesters/" + semesterId + "/periods")
                .then().statusCode(200).extract().jsonPath().getList("$");
        Map<Integer, Long> periodByNo = new LinkedHashMap<>();
        periodRows.stream()
                .filter(row -> ((Number) row.get("dayOfWeek")).intValue() == 1)
                .forEach(row -> periodByNo.put(((Number) row.get("periodNo")).intValue(),
                        ((Number) row.get("id")).longValue()));

        long gradeId = TestData.grade(token, semesterId, "高一" + suffix);
        long classId = TestData.adminClass(token, semesterId, gradeId, "高一1班" + suffix);
        long class2 = TestData.adminClass(token, semesterId, gradeId, "高一2班" + suffix);
        long teacherA = TestData.teacher(token, "语文老师" + suffix);
        long teacherB = TestData.teacher(token, "数学老师" + suffix);
        long courseA = TestData.course(token, "语文" + suffix);
        long courseB = TestData.course(token, "数学" + suffix);
        long room1 = TestData.room(token, "教室A" + suffix);
        long room2 = TestData.room(token, "教室B" + suffix);
        long room3 = TestData.room(token, "教室C" + suffix);

        var draft = new com.paiban.scheduling.domain.TimetableDraft();
        draft.setSemesterId(semesterId);
        draft.setName("转换调课" + suffix);
        draft.setStatus("DRAFT");
        draft.setCreatedBy("test");
        draft.setCreatedAt(Instant.now());
        long draftId = drafts.save(draft).getId();
        place(draftId, classId, courseA, teacherA, periodByNo.get(1), room1);
        place(draftId, classId, courseB, teacherB, periodByNo.get(2), room2);
        place(draftId, class2, courseB, teacherB, periodByNo.get(4), room3);
        as(token).when().post("/api/timetable-drafts/" + draftId + "/publish").then().statusCode(200);

        List<Map<String, Object>> published = ScheduleFixture.publishedEntries(token, classId);
        Map<Long, Long> entryByCourse = new LinkedHashMap<>();
        published.forEach(row -> entryByCourse.put(((Number) row.get("courseId")).longValue(),
                ((Number) row.get("id")).longValue()));
        long chineseEntry = entryByCourse.get(courseA);
        long mathEntry = entryByCourse.get(courseB);

        long requestId = as(token).body(Map.of(
                        "entryId", chineseEntry,
                        "effectiveDate", MONDAY.toString(),
                        "reason", "历史调课",
                        "type", "SWAP",
                        "targetPeriodId", periodByNo.get(2)))
                .when().post("/api/substitution-requests")
                .then().statusCode(200).extract().jsonPath().getLong("id");
        long planId = as(token).when().post("/api/substitution-requests/" + requestId + "/plans")
                .then().statusCode(200)
                .body("plans[0].moves", hasSize(2))
                .extract().jsonPath().getLong("plans[0].id");
        as(token).when().post("/api/substitution-plans/" + planId + "/approve")
                .then().statusCode(200);
        makeLegacy();
        long chineseSubstitutionId = jdbcTemplate.queryForObject(
                "select id from substitution where plan_id = ? and entry_id = ?",
                Long.class, planId, chineseEntry);
        Long moveId = planMoves.findByPlanIdOrderByMoveOrderAsc(planId).stream()
                .filter(move -> move.getEntryId() == chineseEntry)
                .map(move -> move.getId())
                .findFirst().orElseThrow();
        return new SwapWorld(semesterId, classId, chineseEntry, mathEntry, chineseSubstitutionId, moveId);
    }

    private void place(long draftId, long classId, long courseId, long teacherId,
                       long periodId, long roomId) {
        var lesson = new com.paiban.scheduling.domain.DraftLesson();
        lesson.setDraftId(draftId);
        lesson.setAssignmentId(0L);
        lesson.setLessonIndex(1);
        lesson.setCourseId(courseId);
        lesson.setTeacherId(teacherId);
        lesson.setAdminClassId(classId);
        lesson.setStudentCount(45);
        lesson.setPeriodId(periodId);
        lesson.setRoomId(roomId);
        draftLessons.save(lesson);
    }

    private void duplicateMove(long moveId) {
        var original = planMoves.findById(moveId).orElseThrow();
        var copy = new com.paiban.substitution.domain.SubstitutionPlanMove();
        copy.setPlanId(original.getPlanId());
        copy.setEntryId(original.getEntryId());
        copy.setClassId(original.getClassId());
        copy.setCourseId(original.getCourseId());
        copy.setTeacherId(original.getTeacherId());
        copy.setRoomId(original.getRoomId());
        copy.setFromPeriodId(original.getFromPeriodId());
        copy.setFromDayOfWeek(original.getFromDayOfWeek());
        copy.setFromPeriodNo(original.getFromPeriodNo());
        copy.setFromDate(original.getFromDate());
        copy.setToPeriodId(original.getToPeriodId());
        copy.setToDayOfWeek(original.getToDayOfWeek());
        copy.setToPeriodNo(original.getToPeriodNo());
        copy.setToDate(original.getToDate().plusDays(7));
        copy.setMoveOrder(original.getMoveOrder() + 10);
        planMoves.save(copy);
    }

    /** 第二笔旧格式代课：同一教师的另一条基线课节，用于验证多行整单回滚。 */
    private CoverContext seedSecondCover(String token, String suffix, CoverContext first) {
        List<Map<String, Object>> entries = ScheduleFixture.classEntries(token, first.classId());
        Map<String, Object> entry = entries.stream()
                .filter(item -> ((Number) item.get("teacherId")).longValue() == first.teacherId())
                .filter(item -> ((Number) item.get("id")).longValue() != first.entryId())
                .findFirst().orElseThrow();
        long entryId = ((Number) entry.get("id")).longValue();
        int dayOfWeek = ((Number) entry.get("dayOfWeek")).intValue();
        LocalDate date = MONDAY.plusDays(dayOfWeek - 1L);
        long substituteTeacherId = TestData.teacher(token, "代课老师B" + suffix);
        long requestId = as(token).body(Map.of(
                        "entryId", entryId,
                        "effectiveDate", date.toString(),
                        "reason", "历史代课二",
                        "expectedSubstituteTeacherId", substituteTeacherId))
                .when().post("/api/substitution-requests")
                .then().statusCode(200).extract().jsonPath().getLong("id");
        as(token).body(Map.of("substituteTeacherId", substituteTeacherId))
                .when().post("/api/substitution-requests/" + requestId + "/approve")
                .then().statusCode(200);
        makeLegacy();
        Long substitutionId = jdbcTemplate.queryForObject(
                "select id from substitution where request_id = ?", Long.class, requestId);
        return new CoverContext(first.semesterId(), first.classId(), entryId, requestId,
                substitutionId, substituteTeacherId, first.teacherId(), date);
    }
}
