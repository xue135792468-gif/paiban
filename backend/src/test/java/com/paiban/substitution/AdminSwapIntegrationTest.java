package com.paiban.substitution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

import com.paiban.support.ApiIntegrationTest;
import com.paiban.support.TestData;
import io.restassured.response.Response;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.paiban.substitution.domain.SubstitutionRepository;
import com.paiban.substitution.domain.SubstitutionRequestRepository;

/**
 * 票 05 的 HTTP 验收主缝：管理员在工作台为实际授课实例指定明确的目标日期与课节，
 * 在同班已有课的槽位里求闭合置换环（≤3 候选），检查整环对照后提交审批或亲自确认。
 *
 * <p>fixture 用「未来学期 + 强制放置」：高一1班周三第 1 节语文（张老师）、周五第 1 节
 * 数学（王老师），可选第三节英语（李老师）周三第 2 节。{@code blockCycle} 让王老师
 * 在高一2班周三第 1 节另有数学课，阻断二人环。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AdminSwapIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private SubstitutionRequestRepository requestRows;
    @Autowired
    private SubstitutionRepository substitutionRows;
    @Autowired
    private com.paiban.identity.domain.RoleRepository roleRows;
    @Autowired
    private com.paiban.identity.domain.PermissionRepository permissionRows;

    record Fixture(long semesterId, long classAId, long classBId,
                   long teacherChineseId, long teacherMathId, long teacherEnglishId,
                   long entryChineseId, long entryMathId, long entryEnglishId,
                   LocalDate wednesday, LocalDate friday, LocalDate nextThursday,
                   long wednesdayPeriodId, long fridayPeriodId, long thursdayPeriodId,
                   long tuesdayPeriodId, long roomBId) {}

    private Fixture fixture(boolean blockCycle, boolean withThirdLesson) {
        return buildFixture(adminToken(), TestData.suffix(),
                LocalDate.now().with(TemporalAdjusters.next(java.time.DayOfWeek.MONDAY)),
                blockCycle, withThirdLesson);
    }

    /**
     * 共享工作台调课 fixture：学期从给定周一开始；高一1班周三第 1 语文、周五第 1 数学，
     * {@code withThirdLesson} 再加周三第 2 英语；{@code blockCycle} 让王老师在高一2班
     * 周三第 1 节另有数学课（阻断语文↔数学二人环）。
     */
    static Fixture buildFixture(String token, String suffix, LocalDate monday,
                                boolean blockCycle, boolean withThirdLesson) {
        LocalDate wednesday = monday.plusDays(2);
        LocalDate friday = monday.plusDays(4);
        Response semester = auth(token).body(Map.of(
                        "name", "调课学期-" + suffix,
                        "startDate", monday.toString(),
                        // 学期只到下周一：下周二在默认 7 天窗口内但已越过学期末，
                        // 用于「窗口内但学期外」的拒绝路径。
                        "endDate", monday.plusDays(7).toString()))
                .when().post("/api/semesters");
        semester.then().statusCode(200);
        long semesterId = semester.jsonPath().getLong("id");
        TestData.setCurrent(token, semesterId);
        auth(token).body(Map.of("morningCount", 2, "afternoonCount", 0, "eveningCount", 0))
                .when().post("/api/semesters/" + semesterId + "/periods/default")
                .then().statusCode(200);
        List<Map<String, Object>> periodRows = auth(token).when()
                .get("/api/semesters/" + semesterId + "/periods")
                .then().statusCode(200).extract().jsonPath().getList("$");
        Map<String, Long> periodByDayNo = new HashMap<>();
        for (Map<String, Object> row : periodRows) {
            periodByDayNo.put(((Number) row.get("dayOfWeek")).intValue() + "-"
                            + ((Number) row.get("periodNo")).intValue(),
                    ((Number) row.get("id")).longValue());
        }
        long wednesdayPeriodId = periodByDayNo.get("3-1");
        long fridayPeriodId = periodByDayNo.get("5-1");
        long thursdayPeriodId = periodByDayNo.get("4-1");
        long tuesdayPeriodId = periodByDayNo.get("2-1");

        long gradeId = TestData.grade(token, semesterId, "高一" + suffix);
        long classAId = TestData.adminClass(token, semesterId, gradeId, "高一1班" + suffix);
        long classBId = TestData.adminClass(token, semesterId, gradeId, "高一2班" + suffix);
        long teacherChineseId = teacher(token, "语文张" + suffix, "语文" + suffix);
        long teacherMathId = teacher(token, "数学王" + suffix, "数学" + suffix);
        long teacherEnglishId = teacher(token, "英语李" + suffix, "英语" + suffix);
        long chineseId = TestData.course(token, "语文" + suffix);
        long mathId = TestData.course(token, "数学" + suffix);
        long englishId = TestData.course(token, "英语" + suffix);
        long roomA = TestData.room(token, "教室A" + suffix);
        long roomB = TestData.room(token, "教室B" + suffix);

        com.paiban.support.ScheduleFixture.assign(token, classAId, chineseId, teacherChineseId, 1);
        com.paiban.support.ScheduleFixture.assign(token, classAId, mathId, teacherMathId, 1);
        if (withThirdLesson) {
            com.paiban.support.ScheduleFixture.assign(token, classAId, englishId, teacherEnglishId, 1);
        }
        if (blockCycle) {
            // 王老师周三第 1 节在高一2班另有数学课：语文↔数学二人环被硬冲突阻断。
            com.paiban.support.ScheduleFixture.assign(token, classBId, mathId, teacherMathId, 1);
        }

        long draftId = solve(token, semesterId);
        List<Map<String, Object>> lessons = com.paiban.support.ScheduleFixture.lessons(token, draftId);
        place(token, draftId, lessons, classAId, chineseId, wednesdayPeriodId, roomA);
        place(token, draftId, lessons, classAId, mathId, fridayPeriodId, roomB);
        if (withThirdLesson) {
            place(token, draftId, lessons, classAId, englishId, periodByDayNo.get("3-2"), roomA);
        }
        if (blockCycle) {
            place(token, draftId, lessons, classBId, mathId, wednesdayPeriodId, roomB);
        }
        auth(token).when().post("/api/timetable-drafts/" + draftId + "/publish")
                .then().statusCode(200);

        List<Map<String, Object>> entriesA = com.paiban.support.ScheduleFixture
                .publishedEntries(token, classAId);
        long entryChineseId = entryByCourse(entriesA, chineseId);
        long entryMathId = entryByCourse(entriesA, mathId);
        long entryEnglishId = withThirdLesson ? entryByCourse(entriesA, englishId) : 0L;
        return new Fixture(semesterId, classAId, classBId,
                teacherChineseId, teacherMathId, teacherEnglishId,
                entryChineseId, entryMathId, entryEnglishId,
                wednesday, friday, monday.plusDays(3),
                wednesdayPeriodId, fridayPeriodId, thursdayPeriodId, tuesdayPeriodId, roomB);
    }

    private static long entryByCourse(List<Map<String, Object>> entries, long courseId) {
        return entries.stream()
                .filter(row -> ((Number) row.get("courseId")).longValue() == courseId)
                .map(row -> ((Number) row.get("id")).longValue())
                .findFirst().orElseThrow();
    }

    /** 静态 helper 专用的请求规格（与父类的 as() 等价，方便静态 fixture 复用）。 */
    private static io.restassured.specification.RequestSpecification auth(String token) {
        return io.restassured.RestAssured.given()
                .contentType(io.restassured.http.ContentType.JSON)
                .header("Authorization", "Bearer " + token);
    }

    private static long teacher(String token, String name, String subject) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("employeeNo", "T" + TestData.suffix());
        if (subject != null) {
            body.put("subject", subject);
        }
        Response response = auth(token).body(body).when().post("/api/teachers");
        response.then().statusCode(200);
        return response.jsonPath().getLong("id");
    }

    private static long solve(String token, long semesterId) {
        long jobId = auth(token).queryParam("semesterId", semesterId)
                .body(Map.of("maxSeconds", 5))
                .when().post("/api/scheduling-jobs")
                .then().statusCode(200).extract().jsonPath().getLong("id");
        long deadline = System.currentTimeMillis() + 90_000;
        while (System.currentTimeMillis() < deadline) {
            Map<String, Object> job = auth(token).when().get("/api/scheduling-jobs/" + jobId)
                    .then().statusCode(200).extract().jsonPath().getMap("$");
            String status = String.valueOf(job.get("status"));
            if ("COMPLETED".equals(status)) {
                return ((Number) job.get("draftId")).longValue();
            }
            if ("FAILED".equals(status) || "CANCELLED".equals(status)) {
                throw new IllegalStateException("排课失败: " + job.get("errorMessage"));
            }
            com.paiban.support.ScheduleFixture.sleep(300);
        }
        throw new IllegalStateException("排课超时");
    }

    private static void place(String token, long draftId, List<Map<String, Object>> lessons,
                              long classId, long courseId, long periodId, long roomId) {
        Map<String, Object> lesson = lessons.stream()
                .filter(row -> ((Number) row.get("classId")).longValue() == classId
                        && ((Number) row.get("courseId")).longValue() == courseId)
                .findFirst().orElseThrow();
        auth(token).body(Map.of("periodId", periodId, "roomId", roomId, "force", true))
                .when().patch("/api/timetable-drafts/" + draftId + "/lessons/"
                        + ((Number) lesson.get("id")).longValue())
                .then().statusCode(200);
    }

    private Response generatePlans(String token, Fixture fx, LocalDate targetDate,
                                   long targetPeriodId) {
        return as(token).body(Map.of(
                        "entryId", fx.entryChineseId(),
                        "lessonDate", fx.wednesday().toString(),
                        "targetDate", targetDate.toString(),
                        "targetPeriodId", targetPeriodId,
                        "reason", "运动会调课"))
                .when().post("/api/substitution-workbench/swap-plans");
    }

    /** 生成方案并取第一个候选的 planId（fixture(false,false) 下唯一候选是二人环）。 */
    private long firstPlanId(String token, Fixture fx) {
        Response response = generatePlans(token, fx, fx.friday(), fx.fridayPeriodId());
        response.then().statusCode(200);
        return response.jsonPath().getLong("plans[0].planId");
    }

    private Response preview(String token, Fixture fx, long planId) {
        return as(token).body(Map.of(
                        "entryId", fx.entryChineseId(),
                        "lessonDate", fx.wednesday().toString(),
                        "planId", planId,
                        "reason", "运动会调课"))
                .when().post("/api/substitution-workbench/swap-previews");
    }

    private String teacherUser(String suffix, long teacherId) {
        String username = "swap" + suffix;
        as(adminToken()).body(Map.of(
                        "username", username,
                        "password", "teacher123",
                        "displayName", username,
                        "roleCodes", List.of("teacher"),
                        "teacherId", teacherId))
                .when().post("/api/users").then().statusCode(200);
        return login(username, "teacher123");
    }

    // ---------------------------------------------- swap targets（目标槽位候选）

    @Test
    void swapTargetsListTheOccupiedSlotsOfTheSourceClassOnTheTargetDate() {
        Fixture fx = fixture(false, false);
        String admin = adminToken();

        // 周五：本班第 1 节有数学（王老师）——这就是合法目标槽。
        as(admin).queryParam("entryId", fx.entryChineseId())
                .queryParam("lessonDate", fx.wednesday().toString())
                .queryParam("targetDate", fx.friday().toString())
                .when().get("/api/substitution-workbench/swap-targets")
                .then().statusCode(200)
                .body("targetDate", equalTo(fx.friday().toString()))
                .body("slots", hasSize(1))
                .body("slots[0].periodId", equalTo((int) fx.fridayPeriodId()))
                .body("slots[0].courseName", containsString("数学"));

        // 周四：本班没有课，目标槽列表为空（空格不可作目标）。
        as(admin).queryParam("entryId", fx.entryChineseId())
                .queryParam("lessonDate", fx.wednesday().toString())
                .queryParam("targetDate", fx.nextThursday().toString())
                .when().get("/api/substitution-workbench/swap-targets")
                .then().statusCode(200)
                .body("slots", hasSize(0));
    }

    // ------------------------------------------------ 生成方案：明确目标日期

    @Test
    void generatesPlansForAnExplicitTargetDateAndReturnsActualDates() {
        Fixture fx = fixture(false, false);
        String admin = adminToken();

        Response response = generatePlans(admin, fx, fx.friday(), fx.fridayPeriodId());
        response.then().statusCode(200)
                .body("plans", hasSize(1))
                .body("plans[0].cycleLength", equalTo(2))
                .body("plans[0].moves", hasSize(2))
                // 规格：目标 API 必须回传实际日期。
                .body("targetDate", equalTo(fx.friday().toString()));
        // 语文（来源）的落点就是传入的周五；数学从周五移回周三。
        assertThat(response.jsonPath().getString(
                "plans[0].moves.find { it.entryId == " + fx.entryChineseId() + " }.toDate"))
                .isEqualTo(fx.friday().toString());
        assertThat(response.jsonPath().getString(
                "plans[0].moves.find { it.entryId == " + fx.entryMathId() + " }.fromDate"))
                .isEqualTo(fx.friday().toString());
        assertThat(response.jsonPath().getString(
                "plans[0].moves.find { it.entryId == " + fx.entryMathId() + " }.toDate"))
                .isEqualTo(fx.wednesday().toString());
    }

    @Test
    void offersAMultiTeacherCycleWhenTheTwoTeacherPathIsBlocked() {
        Fixture fx = fixture(true, true);
        String admin = adminToken();

        Response response = generatePlans(admin, fx, fx.friday(), fx.fridayPeriodId());
        response.then().statusCode(200);
        // 二人环被王老师的busy阻断；周三第 2 的英语课让三人环成立。
        List<Integer> cycleLengths = response.jsonPath().getList("plans.cycleLength");
        assertThat(cycleLengths).contains(3);
    }

    @Test
    void noSolutionExplainsTheReasonAndKeepsTheTimetableUntouched() {
        Fixture fx = fixture(true, false);
        String admin = adminToken();

        // 王老师周三第 1 节在高一2班有课，本班又没有第三节课可成环：无解。
        Response response = generatePlans(admin, fx, fx.friday(), fx.fridayPeriodId());
        response.then().statusCode(200)
                .body("plans", hasSize(0));
        assertThat(response.jsonPath().getList("diagnoses.code"))
                .contains("NO_SOLUTION");

        // 原课表纹丝不动：没有申请、没有生效行。
        assertThat(requestRows.count()).isZero();
        assertThat(substitutionRows.count()).isZero();
        as(admin).queryParam("date", fx.wednesday().toString())
                .when().get("/api/timetable/class/" + fx.classAId())
                .then().statusCode(200)
                .body("find { it.id == " + fx.entryChineseId() + " }.periodNo", equalTo(1))
                .body("find { it.id == " + fx.entryChineseId() + " }.substituted", equalTo(false));
    }

    // ------------------------------------------------ 非法目标：明确拒绝并说明原因

    @Test
    void rejectsAnEmptyTargetSlotWithAReason() {
        Fixture fx = fixture(false, false);
        // 周四本班没有课：空格只挪不补。
        Response response = generatePlans(adminToken(), fx, fx.nextThursday(), fx.thursdayPeriodId());
        response.then().statusCode(400)
                .body("message", containsString("空堂"));
        assertThat(requestRows.count()).isZero();
        assertThat(substitutionRows.count()).isZero();
    }

    @Test
    void rejectsTargetDatesBeforeTheSourceOrOutsideTheWindowOrSemester() {
        Fixture fx = fixture(false, false);
        String admin = adminToken();

        // 向来源日期之前搜索不支持（窗口从来源日期向后展开）。
        as(admin).body(Map.of(
                        "entryId", fx.entryChineseId(),
                        "lessonDate", fx.wednesday().toString(),
                        "targetDate", fx.wednesday().minusDays(1).toString(),
                        "targetPeriodId", fx.fridayPeriodId()))
                .when().post("/api/substitution-workbench/swap-plans")
                .then().statusCode(400)
                .body("message", containsString("窗口"));

        // 窗口（默认 7 天）之外。
        as(admin).body(Map.of(
                        "entryId", fx.entryChineseId(),
                        "lessonDate", fx.wednesday().toString(),
                        "targetDate", fx.wednesday().plusDays(9).toString(),
                        "targetPeriodId", fx.fridayPeriodId()))
                .when().post("/api/substitution-workbench/swap-plans")
                .then().statusCode(400)
                .body("message", containsString("窗口"));

        // 学期之外：学期到下周一为止，下周二仍在默认 7 天窗口内但已越过学期末。
        as(admin).body(Map.of(
                        "entryId", fx.entryChineseId(),
                        "lessonDate", fx.wednesday().toString(),
                        "targetDate", fx.wednesday().plusDays(6).toString(),
                        "targetPeriodId", fx.tuesdayPeriodId()))
                .when().post("/api/substitution-workbench/swap-plans")
                .then().statusCode(400)
                .body("message", containsString("学期"));
    }

    // ------------------------------------------------ 预览：整环对照与变化清单

    @Test
    void previewShowsWholeCycleCompareAndChangeListWithZeroSideEffects() {
        Fixture fx = fixture(false, false);
        String admin = adminToken();
        long planId = firstPlanId(admin, fx);

        Response response = preview(admin, fx, planId);
        response.then().statusCode(200)
                // 目标 API 回传实际日期（从方案权威读回）。
                .body("targetDate", equalTo(fx.friday().toString()))
                .body("days", hasSize(2))
                .body("changes", hasSize(2));
        // 环内最小开课截止 = 周三 08:00（Asia/Shanghai）= 00:00Z，不是周五的。
        assertThat(response.jsonPath().getString("deadlineAt")).contains("00:00:00");

        // 周三：左=语文/张（来源格高亮），右=数学/王（目标格高亮，条目从周五移入）。
        String wednesday = "days.find { it.date == '" + fx.wednesday() + "' }";
        assertThat(response.jsonPath().getString(wednesday
                + ".left.find { it.periodNo == 1 }.courseName")).contains("语文");
        assertThat(response.jsonPath().getBoolean(wednesday
                + ".left.find { it.periodNo == 1 }.changed")).isTrue();
        assertThat(response.jsonPath().getLong(wednesday
                + ".right.find { it.periodNo == 1 }.entryId")).isEqualTo(fx.entryMathId());
        assertThat(response.jsonPath().getBoolean(wednesday
                + ".right.find { it.periodNo == 1 }.changed")).isTrue();

        // 周五：左=数学/王，右=语文/张——整环的另一半。
        String friday = "days.find { it.date == '" + fx.friday() + "' }";
        assertThat(response.jsonPath().getLong(friday
                + ".left.find { it.periodNo == 1 }.entryId")).isEqualTo(fx.entryMathId());
        assertThat(response.jsonPath().getLong(friday
                + ".right.find { it.periodNo == 1 }.entryId")).isEqualTo(fx.entryChineseId());

        // 变化清单完整列出环内每一步：课程、班级、前后实际日期+节次、教师。
        assertThat(response.jsonPath().getString(
                "changes.find { it.entryId == " + fx.entryChineseId() + " }.fromDate"))
                .isEqualTo(fx.wednesday().toString());
        assertThat(response.jsonPath().getString(
                "changes.find { it.entryId == " + fx.entryChineseId() + " }.toDate"))
                .isEqualTo(fx.friday().toString());
        assertThat(response.jsonPath().getLong(
                "changes.find { it.entryId == " + fx.entryChineseId() + " }.teacherId"))
                .isEqualTo(fx.teacherChineseId());

        // 预览零副作用：没有申请、没有生效行、没有审计写入。
        assertThat(requestRows.count()).isZero();
        assertThat(substitutionRows.count()).isZero();

        // 切换教师视角：王老师周三的课表右侧出现移入的数学课（同视角同日期联动）。
        long voucherId = response.jsonPath().getLong("voucherId");
        as(admin).queryParam("view", "teacher")
                .queryParam("teacherId", fx.teacherMathId())
                .queryParam("date", fx.wednesday().toString())
                .when().get("/api/substitution-workbench/previews/" + voucherId + "/swap-compare")
                .then().statusCode(200)
                .body("view", equalTo("teacher"))
                .body("right.find { it.periodNo == 1 }.entryId",
                        equalTo((int) fx.entryMathId()));
    }

    // ------------------------------------------------ 提交审批：旧课表继续生效

    @Test
    void submitApprovalKeepsTheOldTimetableUntilTheApproverApprovesThePlan() {
        Fixture fx = fixture(false, false);
        String admin = adminToken();
        long planId = firstPlanId(admin, fx);
        long voucherId = preview(admin, fx, planId).jsonPath().getLong("voucherId");

        long requestId = as(admin).body(Map.of("comment", "请审批"))
                .when().post("/api/substitution-workbench/previews/" + voucherId + "/submit-approval-swap")
                .then().statusCode(200)
                .body("status", equalTo("PENDING_APPROVAL"))
                .extract().jsonPath().getLong("requestId");

        // 申请进待批队列：SWAP 类型、明确目标日期、操作者与管理口径都落在申请上。
        as(admin).when().get("/api/substitution-requests/" + requestId)
                .then().statusCode(200)
                .body("status", equalTo("PENDING_APPROVAL"))
                .body("type", equalTo("SWAP"))
                .body("approvalMode", equalTo("approval"))
                .body("targetPeriodId", equalTo((int) fx.fridayPeriodId()));

        // 待批期间旧课表继续：没有生效行，周三第 1 节仍是语文。
        assertThat(substitutionRows.count()).isZero();
        as(admin).queryParam("date", fx.wednesday().toString())
                .when().get("/api/timetable/class/" + fx.classAId())
                .then().statusCode(200)
                .body("find { it.id == " + fx.entryChineseId() + " }.periodNo", equalTo(1))
                .body("find { it.id == " + fx.entryChineseId() + " }.substituted", equalTo(false));

        // 审批人走普通批准入口批准整环方案（同一有效预览中的方案）。
        as(admin).when().post("/api/substitution-plans/" + planId + "/approve")
                .then().statusCode(200)
                .body("status", equalTo("EFFECTIVE"));

        // 生效后：周模板里数学移到周三第 1 节（移入），语文移到周五位（移出各显示一次）。
        as(admin).queryParam("date", fx.wednesday().toString())
                .when().get("/api/timetable/class/" + fx.classAId())
                .then().statusCode(200)
                .body("find { it.id == " + fx.entryMathId() + " }.dayOfWeek", equalTo(3))
                .body("find { it.id == " + fx.entryMathId() + " }.periodNo", equalTo(1))
                .body("find { it.id == " + fx.entryMathId() + " }.substituted", equalTo(true))
                .body("find { it.id == " + fx.entryChineseId() + " }.dayOfWeek", equalTo(5))
                .body("findAll { it.id == " + fx.entryMathId() + " }.size()", equalTo(1));
    }

    // ------------------------------------------------ 亲自确认：整环原子生效

    @Test
    void personalConfirmMakesTheWholeCycleEffectiveAcrossViewsAndExports() {
        Fixture fx = fixture(false, false);
        String admin = adminToken();
        long planId = firstPlanId(admin, fx);
        long voucherId = preview(admin, fx, planId).jsonPath().getLong("voucherId");

        long requestId = as(admin).body(Map.of("comment", "运动会确认"))
                .when().post("/api/substitution-workbench/previews/" + voucherId + "/confirm-swap")
                .then().statusCode(200)
                .body("status", equalTo("EFFECTIVE"))
                .extract().jsonPath().getLong("requestId");

        // 整环生效行：语文（周三→周五）+ 数学（周五→周三），实例日期成对出现。
        var rows = substitutionRows.findByRequestId(requestId);
        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(row -> assertThat(row.getType()).isEqualTo("SWAP"));
        assertThat(rows).anySatisfy(row -> {
            assertThat(row.getEntryId()).isEqualTo(fx.entryChineseId());
            assertThat(row.getLessonDate()).isEqualTo(fx.wednesday());
            assertThat(row.getTargetLessonDate()).isEqualTo(fx.friday());
        });
        assertThat(rows).anySatisfy(row -> {
            assertThat(row.getEntryId()).isEqualTo(fx.entryMathId());
            assertThat(row.getLessonDate()).isEqualTo(fx.friday());
            assertThat(row.getTargetLessonDate()).isEqualTo(fx.wednesday());
        });

        // 申请审计字段：操作者=管理员账号、代发对象=基线原教师、类型 SWAP、明确目标。
        com.paiban.substitution.domain.SubstitutionRequest saved =
                requestRows.findById(requestId).orElseThrow();
        assertThat(saved.getType()).isEqualTo("SWAP");
        assertThat(saved.getOriginalTeacherId()).isEqualTo(fx.teacherChineseId());
        assertThat(saved.getTargetDate()).isEqualTo(fx.friday());
        assertThat(saved.getTargetPeriodId()).isEqualTo(fx.fridayPeriodId());
        assertThat(saved.getApproverId()).isNotNull();

        // 班级视图：调课日的周模板里，数学移到周三第 1 节、语文移到周五第 1 节，
        // 移出与移入各显示一次（跨日对照的读模型与票 01 实例语义一致）。
        as(admin).queryParam("date", fx.wednesday().toString())
                .when().get("/api/timetable/class/" + fx.classAId())
                .then().statusCode(200)
                .body("find { it.id == " + fx.entryMathId() + " }.dayOfWeek", equalTo(3))
                .body("find { it.id == " + fx.entryMathId() + " }.periodNo", equalTo(1))
                .body("find { it.id == " + fx.entryMathId() + " }.substituted", equalTo(true))
                .body("find { it.id == " + fx.entryMathId() + " }.substitutionType", equalTo("SWAP"))
                .body("find { it.id == " + fx.entryChineseId() + " }.dayOfWeek", equalTo(5))
                .body("findAll { it.id == " + fx.entryMathId() + " }.size()", equalTo(1));
        as(admin).queryParam("date", fx.friday().toString())
                .when().get("/api/timetable/class/" + fx.classAId())
                .then().statusCode(200)
                .body("find { it.id == " + fx.entryChineseId() + " }.periodNo", equalTo(1))
                .body("find { it.id == " + fx.entryChineseId() + " }.substitutionType", equalTo("SWAP"));

        // 教师视图：张老师在周五有语文课，王老师在周三有数学课，各自原槽没有课。
        as(admin).queryParam("date", fx.friday().toString())
                .when().get("/api/timetable/teacher/" + fx.teacherChineseId())
                .then().statusCode(200)
                .body("find { it.id == " + fx.entryChineseId() + " }.periodNo", equalTo(1))
                .body("find { it.id == " + fx.entryChineseId() + " }.dayOfWeek", equalTo(5));
        as(admin).queryParam("date", fx.wednesday().toString())
                .when().get("/api/timetable/teacher/" + fx.teacherMathId())
                .then().statusCode(200)
                .body("find { it.id == " + fx.entryMathId() + " }.periodNo", equalTo(1))
                .body("find { it.id == " + fx.entryMathId() + " }.dayOfWeek", equalTo(3));

        // 教室视图：数学的教室（教室B）周三第 1 节出现数学课（教室跟课走）。
        as(admin).queryParam("date", fx.wednesday().toString())
                .when().get("/api/timetable/room/" + fx.roomBId())
                .then().statusCode(200)
                .body("find { it.id == " + fx.entryMathId() + " }.periodNo", equalTo(1))
                .body("find { it.id == " + fx.entryMathId() + " }.dayOfWeek", equalTo(3));

        // 全校视图同一天一致（同一日期实例语义；响应是 {classIds, entries} 包装）。
        as(admin).queryParam("date", fx.wednesday().toString())
                .when().get("/api/timetable/school")
                .then().statusCode(200)
                .body("entries.find { it.id == " + fx.entryMathId() + " }.periodNo", equalTo(1))
                .body("entries.find { it.id == " + fx.entryMathId() + " }.dayOfWeek", equalTo(3));

        // 导出（周网格）按同一读模型生成：班级 Excel 导出成功。
        as(admin).queryParam("date", fx.wednesday().toString())
                .when().get("/api/timetable/class/" + fx.classAId() + "/export/excel")
                .then().statusCode(200);

        // 后续周常规不变：下周三语文回到基线槽位。
        as(admin).queryParam("date", fx.wednesday().plusDays(7).toString())
                .when().get("/api/timetable/class/" + fx.classAId())
                .then().statusCode(200)
                .body("find { it.id == " + fx.entryChineseId() + " }.periodNo", equalTo(1))
                .body("find { it.id == " + fx.entryChineseId() + " }.substituted", equalTo(false));
    }

    // ------------------------------------------------ 幂等与快照冲突

    @Test
    void confirmRetryIsIdempotentAndParamChangesAreRejected() {
        Fixture fx = fixture(false, false);
        String admin = adminToken();
        long planId = firstPlanId(admin, fx);
        long voucherId = preview(admin, fx, planId).jsonPath().getLong("voucherId");

        long requestId = as(admin).body(Map.of("comment", "确认"))
                .when().post("/api/substitution-workbench/previews/" + voucherId + "/confirm-swap")
                .then().statusCode(200)
                .extract().jsonPath().getLong("requestId");
        int effectiveRows = substitutionRows.findByRequestId(requestId).size();

        // 相同凭证 + 相同参数重试：返回第一次结果，不再新增生效行。
        Response retry = as(admin).body(Map.of("comment", "确认"))
                .when().post("/api/substitution-workbench/previews/" + voucherId + "/confirm-swap");
        retry.then().statusCode(200);
        assertThat(retry.jsonPath().getLong("requestId")).isEqualTo(requestId);
        assertThat(retry.jsonPath().getBoolean("replayed")).isTrue();
        assertThat(substitutionRows.findByRequestId(requestId)).hasSize(effectiveRows);

        // 参数改变：不复用已消费凭证。
        as(admin).body(Map.of("comment", "改成别的备注"))
                .when().post("/api/substitution-workbench/previews/" + voucherId + "/confirm-swap")
                .then().statusCode(409);
    }

    @Test
    void snapshotChangeBeforeConfirmRejectsTheWholeCycle() {
        Fixture fx = fixture(true, true);
        String admin = adminToken();

        // 三人环（语文周三1 / 数学周五1 / 英语周三2），王老师在高一2班周三1另有课。
        Response generated = generatePlans(admin, fx, fx.friday(), fx.fridayPeriodId());
        generated.then().statusCode(200);
        long planId = generated.jsonPath().getLong("plans[0].planId");
        long voucherId = preview(admin, fx, planId).jsonPath().getLong("voucherId");

        // 预览后，另一位教师在高一2班的课走 COVER 生效：整份快照哈希变化。
        String mathTeacher = teacherUser(TestData.suffix() + "m", fx.teacherMathId());
        Response cover = as(mathTeacher).body(Map.of(
                        "entryId", classBMathEntry(admin, fx),
                        "effectiveDate", fx.wednesday().toString(),
                        "expectedSubstituteTeacherId", fx.teacherEnglishId()))
                .when().post("/api/substitution-requests");
        cover.then().statusCode(200);
        as(admin).body(Map.of("substituteTeacherId", fx.teacherEnglishId()))
                .when().post("/api/substitution-requests/" + cover.jsonPath().getLong("id") + "/approve")
                .then().statusCode(200);

        // 确认被拒绝：整单零生效（环的生效行一条都没有）。
        as(admin).body(Map.of())
                .when().post("/api/substitution-workbench/previews/" + voucherId + "/confirm-swap")
                .then().statusCode(409)
                .body("message", containsString("重新预览"));
        assertThat(substitutionRows.findByEntryId(fx.entryChineseId())).isEmpty();
        assertThat(substitutionRows.findByEntryId(fx.entryMathId())).isEmpty();
        assertThat(substitutionRows.findByEntryId(fx.entryEnglishId())).isEmpty();
    }

    private long classBMathEntry(String admin, Fixture fx) {
        List<Map<String, Object>> entries = com.paiban.support.ScheduleFixture
                .publishedEntries(admin, fx.classBId());
        return entries.stream()
                .map(row -> ((Number) row.get("id")).longValue())
                .findFirst().orElseThrow();
    }

    // ------------------------------------------------ 权限与凭证归属

    @Test
    void viewOnlyUserAndOtherOperatorsCannotDriveTheSwapFlow() {
        Fixture fx = fixture(false, false);
        String admin = adminToken();
        long planId = firstPlanId(admin, fx);
        long voucherId = preview(admin, fx, planId).jsonPath().getLong("voucherId");

        // 仅查看权限的用户：生成方案与消费凭证都不可写。
        String viewer = viewOnlyUser(TestData.suffix());
        as(viewer).body(Map.of(
                        "entryId", fx.entryChineseId(),
                        "lessonDate", fx.wednesday().toString(),
                        "targetDate", fx.friday().toString(),
                        "targetPeriodId", fx.fridayPeriodId()))
                .when().post("/api/substitution-workbench/swap-plans")
                .then().statusCode(403);
        as(viewer).body(Map.of())
                .when().post("/api/substitution-workbench/previews/" + voucherId + "/confirm-swap")
                .then().statusCode(403);

        // 凭证绑定操作者：另一位管理员不能用他人的预览。
        String username = "other_admin_" + TestData.suffix();
        as(admin).body(Map.of(
                        "username", username,
                        "password", "teacher123",
                        "displayName", username,
                        "roleCodes", List.of("admin")))
                .when().post("/api/users").then().statusCode(200);
        String otherAdmin = login(username, "teacher123");
        as(otherAdmin).body(Map.of())
                .when().post("/api/substitution-workbench/previews/" + voucherId + "/confirm-swap")
                .then().statusCode(403);

        // 零副作用：方案仍 PROPOSED，凭证仍 ACTIVE，可以由本人继续操作。
        assertThat(substitutionRows.count()).isZero();
        long requestId = as(admin).body(Map.of())
                .when().post("/api/substitution-workbench/previews/" + voucherId + "/confirm-swap")
                .then().statusCode(200)
                .extract().jsonPath().getLong("requestId");
        assertThat(requestId).isPositive();
    }

    /** 一个只有「查看全部调代记录」权限的角色与账号（与票 03 的矩阵一致）。 */
    private String viewOnlyUser(String suffix) {
        var view = permissionRows.findByCode("substitution:view").orElseThrow();
        var notification = permissionRows.findByCode("notification:view").orElseThrow();
        var role = roleRows.findByCode("swapviewer_" + suffix)
                .orElseGet(() -> roleRows.save(new com.paiban.identity.domain.Role(
                        "swapviewer_" + suffix, "仅查看")));
        role.getPermissions().clear();
        role.getPermissions().add(view);
        role.getPermissions().add(notification);
        roleRows.save(role);
        String username = "swapviewer_" + suffix;
        createUser(adminToken(), username, "teacher123", username);
        return login(username, "teacher123");
    }
}
