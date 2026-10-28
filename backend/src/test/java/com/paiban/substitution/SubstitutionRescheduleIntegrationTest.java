package com.paiban.substitution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

import com.paiban.identity.domain.AppUserRepository;
import com.paiban.substitution.domain.Substitution;
import com.paiban.substitution.domain.SubstitutionRepository;
import com.paiban.substitution.domain.SubstitutionRequest;
import com.paiban.substitution.domain.SubstitutionRequestRepository;
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

/**
 * 票 07 的 HTTP 验收主缝：已经调过课（SWAP 生效）或已代过课的实例，课前能
 * <b>以当前实际安排为起点</b>继续调课或代课；所有重复修订都是新版本——
 * 预览 → 校验 → 审批/确认 → 原子替换当前有效安排，历史全留；旧环里未涉及的
 * 实例（如被上次环移走的数学）保持生效，绝不允许半环生效。
 *
 * <p>fixture：高一1班语文周三第 1 节（张）、数学周五第 1 节（王）、英语周一第 1 节（李）。
 * 链路：语文 周三→周五（调课）→ 再调到下周一 / 代课丙 / 恢复基线，四种路径全覆盖。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SubstitutionRescheduleIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private SubstitutionRequestRepository requestRows;
    @Autowired
    private SubstitutionRepository substitutionRows;
    @Autowired
    private AppUserRepository userRows;

    record Fixture(long semesterId, long classAId,
                   long teacherChineseId, long teacherMathId, long teacherEnglishId,
                   long teacherSpareId,
                   long entryChineseId, long entryMathId, long entryEnglishId,
                   LocalDate monday, LocalDate wednesday, LocalDate friday, LocalDate nextMonday,
                   long wednesdayPeriodId, long fridayPeriodId, long mondayPeriodId,
                   long roomAId, long fridaySecondPeriodId) {}

    private Fixture fx() {
        return buildFixture(adminToken(), TestData.suffix(),
                LocalDate.now().with(TemporalAdjusters.next(java.time.DayOfWeek.MONDAY)));
    }

    /**
     * 三节课分布在三天：语文周三 1、数学周五 1、英语周一 1；另备一位无课教师丙。
     * 学期跨两周，第二次调课的目标（下周一）落在学期与窗口之内。
     */
    static Fixture buildFixture(String token, String suffix, LocalDate monday) {
        return buildFixture(token, suffix, monday, false);
    }

    /**
     * {@code englishOnFridaySecondPeriod}：英语摆到周五第 2 节（冻结场景用——
     * 同日第 1 节已开始时，第 2 节的课仍是唯一可动的来源）。
     */
    static Fixture buildFixture(String token, String suffix, LocalDate monday,
                                boolean englishOnFridaySecondPeriod) {
        LocalDate wednesday = monday.plusDays(2);
        LocalDate friday = monday.plusDays(4);
        LocalDate nextMonday = monday.plusDays(7);
        Response semester = auth(token).body(Map.of(
                        "name", "再次调整学期-" + suffix,
                        "startDate", monday.toString(),
                        "endDate", monday.plusDays(13).toString()))
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
        long mondayPeriodId = periodByDayNo.get("1-1");
        long fridaySecondPeriodId = periodByDayNo.get("5-2");

        long gradeId = TestData.grade(token, semesterId, "高一" + suffix);
        long classAId = TestData.adminClass(token, semesterId, gradeId, "高一1班" + suffix);
        long teacherChineseId = teacher(token, "语文张" + suffix, "语文" + suffix);
        long teacherMathId = teacher(token, "数学王" + suffix, "数学" + suffix);
        long teacherEnglishId = teacher(token, "英语李" + suffix, "英语" + suffix);
        long teacherSpareId = teacher(token, "备用丙" + suffix, "语文" + suffix);
        long chineseId = TestData.course(token, "语文" + suffix);
        long mathId = TestData.course(token, "数学" + suffix);
        long englishId = TestData.course(token, "英语" + suffix);
        long roomA = TestData.room(token, "教室A" + suffix);
        long roomB = TestData.room(token, "教室B" + suffix);

        com.paiban.support.ScheduleFixture.assign(token, classAId, chineseId, teacherChineseId, 1);
        com.paiban.support.ScheduleFixture.assign(token, classAId, mathId, teacherMathId, 1);
        com.paiban.support.ScheduleFixture.assign(token, classAId, englishId, teacherEnglishId, 1);

        long draftId = solve(token, semesterId);
        List<Map<String, Object>> lessons = com.paiban.support.ScheduleFixture.lessons(token, draftId);
        place(token, draftId, lessons, classAId, chineseId, wednesdayPeriodId, roomA);
        place(token, draftId, lessons, classAId, mathId, fridayPeriodId, roomB);
        place(token, draftId, lessons, classAId, englishId,
                englishOnFridaySecondPeriod ? fridaySecondPeriodId : mondayPeriodId,
                englishOnFridaySecondPeriod ? roomB : roomA);
        auth(token).when().post("/api/timetable-drafts/" + draftId + "/publish")
                .then().statusCode(200);

        List<Map<String, Object>> entriesA = com.paiban.support.ScheduleFixture
                .publishedEntries(token, classAId);
        return new Fixture(semesterId, classAId,
                teacherChineseId, teacherMathId, teacherEnglishId, teacherSpareId,
                entryByCourse(entriesA, chineseId), entryByCourse(entriesA, mathId),
                entryByCourse(entriesA, englishId),
                monday, wednesday, friday, nextMonday,
                wednesdayPeriodId, fridayPeriodId, mondayPeriodId, roomA, fridaySecondPeriodId);
    }

    private static long entryByCourse(List<Map<String, Object>> entries, long courseId) {
        return entries.stream()
                .filter(row -> ((Number) row.get("courseId")).longValue() == courseId)
                .map(row -> ((Number) row.get("id")).longValue())
                .findFirst().orElseThrow();
    }

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

    // ------------------------------------------------------------------ helpers

    /** 管理端调课全流程：生成方案 → 预览 → 消费凭证，返回申请 id。 */
    private long swapOnce(String admin, Fixture fx, long entryId, LocalDate lessonDate,
                          LocalDate targetDate, long targetPeriodId, String action) {
        long planId = generatePlan(admin, fx, entryId, lessonDate, targetDate, targetPeriodId);
        long voucherId = previewSwap(admin, fx, entryId, lessonDate, planId);
        return consume(admin, voucherId, action, null, 200);
    }

    private long generatePlan(String admin, Fixture fx, long entryId, LocalDate lessonDate,
                              LocalDate targetDate, long targetPeriodId) {
        Response response = as(admin).body(Map.of(
                        "entryId", entryId,
                        "lessonDate", lessonDate.toString(),
                        "targetDate", targetDate.toString(),
                        "targetPeriodId", targetPeriodId,
                        "reason", "运动会调课"))
                .when().post("/api/substitution-workbench/swap-plans");
        response.then().statusCode(200);
        return response.jsonPath().getLong("plans[0].planId");
    }

    private long previewSwap(String admin, Fixture fx, long entryId, LocalDate lessonDate, long planId) {
        Response response = as(admin).body(Map.of(
                        "entryId", entryId,
                        "lessonDate", lessonDate.toString(),
                        "planId", planId,
                        "reason", "再次调整"))
                .when().post("/api/substitution-workbench/swap-previews");
        response.then().statusCode(200);
        return response.jsonPath().getLong("voucherId");
    }

    private Response previewSwapRaw(String admin, Fixture fx, long entryId,
                                    LocalDate lessonDate, long planId) {
        return as(admin).body(Map.of(
                        "entryId", entryId,
                        "lessonDate", lessonDate.toString(),
                        "planId", planId,
                        "reason", "再次调整"))
                .when().post("/api/substitution-workbench/swap-previews");
    }

    private long consume(String admin, long voucherId, String action, String comment, int expected) {
        Response response = as(admin)
                .body(comment == null ? Map.of() : Map.of("comment", comment))
                .when().post("/api/substitution-workbench/previews/" + voucherId + "/" + action);
        response.then().statusCode(expected);
        return response.jsonPath().getLong("requestId");
    }

    /** 管理端代课全流程（票 03/04 路径）：预览 COVER → 消费凭证，返回申请 id。 */
    private long coverOnce(String admin, Fixture fx, long entryId, LocalDate lessonDate,
                           long substituteTeacherId) {
        Response response = as(admin).body(Map.of(
                        "entryId", entryId,
                        "lessonDate", lessonDate.toString(),
                        "substituteTeacherId", substituteTeacherId,
                        "reason", "代课"))
                .when().post("/api/substitution-workbench/previews");
        response.then().statusCode(200);
        return consume(admin, response.jsonPath().getLong("voucherId"), "confirm", null, 200);
    }

    private String classTeacher(String admin, Fixture fx, long entryId, LocalDate date) {
        return as(admin).queryParam("date", date.toString())
                .when().get("/api/timetable/class/" + fx.classAId())
                .then().statusCode(200)
                .extract().jsonPath().getString(
                        "find { it.id == " + entryId + " }.teacherId");
    }

    private String userForTeacher(String suffix, long teacherId) {
        String username = "res_user_" + suffix + "_" + TestData.suffix();
        as(adminToken()).body(Map.of(
                        "username", username,
                        "password", "teacher123",
                        "displayName", "用户" + suffix,
                        "roleCodes", List.of("teacher"),
                        "teacherId", teacherId))
                .when().post("/api/users").then().statusCode(200);
        return login(username, "teacher123");
    }

    private long unreadCount(String token) {
        return as(token).when().get("/api/notifications/unread-count")
                .then().statusCode(200)
                .extract().jsonPath().getLong("count");
    }

    private String latestContent(String token) {
        return as(token).when().get("/api/notifications/recent")
                .then().statusCode(200)
                .extract().jsonPath().getString("[0].content");
    }

    // ---------------------------------------------- 移动后再移动（修订调课全链）

    @Test
    void movedInstanceCanBeRescheduledAgainAsRevision() {
        Fixture fx = fx();
        String admin = adminToken();
        // 第一次：语文 周三 1 → 周五 1（二人环：数学 周五 1 → 周三 1）。
        long firstRequestId = swapOnce(admin, fx, fx.entryChineseId(), fx.wednesday(),
                fx.friday(), fx.fridayPeriodId(), "confirm-swap");
        assertThat(classTeacher(admin, fx, fx.entryChineseId(), fx.friday()))
                .isEqualTo(String.valueOf(fx.teacherChineseId()));

        // 首次管理调课的修订类别 = RESCHEDULE（新调课），无前驱。
        assertThat(requestRows.findById(firstRequestId).orElseThrow().getRevisionKind())
                .isEqualTo(com.paiban.substitution.service.RevisionPolicy.RESCHEDULE);

        // 第二次：对已调到周五的语文再调课，目标 = 下周一（英语的基线槽）。
        long planId = generatePlan(admin, fx, fx.entryChineseId(), fx.friday(),
                fx.nextMonday(), fx.mondayPeriodId());
        Response preview = previewSwapRaw(admin, fx, fx.entryChineseId(), fx.friday(), planId);
        preview.then().statusCode(200)
                .body("revisesRequestId", equalTo((int) firstRequestId))
                .body("revisionKind", equalTo("RESCHEDULE_REVISION"));
        long secondRequestId = consume(admin, preview.jsonPath().getLong("voucherId"),
                "confirm-swap", null, 200);

        // 净差行：语文行 lessonDate = 基线来源日（周三），落点 = 下周一；基准行转 SUPERSEDED。
        var chineseRows = substitutionRows.findByEntryId(fx.entryChineseId());
        assertThat(chineseRows).hasSize(2);
        assertThat(chineseRows).anySatisfy(row -> {
            assertThat(row.getRequestId()).isEqualTo(firstRequestId);
            assertThat(row.getStatus()).isEqualTo(Substitution.SUPERSEDED);
        });
        assertThat(chineseRows).anySatisfy(row -> {
            assertThat(row.getRequestId()).isEqualTo(secondRequestId);
            assertThat(row.getStatus()).isEqualTo(Substitution.EFFECTIVE);
            assertThat(row.getLessonDate()).isEqualTo(fx.wednesday());
            assertThat(row.getTargetLessonDate()).isEqualTo(fx.nextMonday());
            assertThat(row.getSubstituteTeacherId()).isEqualTo(fx.teacherChineseId());
        });
        // 英语行：首次调课（来源日 = 下周一 = 基线日）。
        assertThat(substitutionRows.findByEntryId(fx.entryEnglishId()))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.getLessonDate()).isEqualTo(fx.nextMonday());
                    assertThat(row.getTargetLessonDate()).isEqualTo(fx.friday());
                });
        // 旧环未涉及的数学保持生效（周三 1 = 数学落点），绝无半环。
        assertThat(substitutionRows.findByRequestId(firstRequestId))
                .anySatisfy(row -> {
                    assertThat(row.getEntryId()).isEqualTo(fx.entryMathId());
                    assertThat(row.getStatus()).isEqualTo(Substitution.EFFECTIVE);
                });

        // 最终安排（读模型：周模板里每个条目恰好出现一次，标注当前有效槽位）。
        // 周三视图：数学停在周三 1；语文被再次移出、标注下周一（净差行来源日）。
        as(admin).queryParam("date", fx.wednesday().toString())
                .when().get("/api/timetable/class/" + fx.classAId())
                .then().statusCode(200)
                .body("find { it.id == " + fx.entryMathId() + " }.substituted", equalTo(true))
                .body("find { it.id == " + fx.entryChineseId() + " }.dayOfWeek", equalTo(1));
        // 周五视图：英语已移入周五；数学标注回周三（旧环成员保持）。
        as(admin).queryParam("date", fx.friday().toString())
                .when().get("/api/timetable/class/" + fx.classAId())
                .then().statusCode(200)
                .body("find { it.id == " + fx.entryEnglishId() + " }.teacherId",
                        equalTo((int) fx.teacherEnglishId()))
                .body("find { it.id == " + fx.entryMathId() + " }.dayOfWeek", equalTo(3));
        as(admin).queryParam("date", fx.nextMonday().toString())
                .when().get("/api/timetable/class/" + fx.classAId())
                .then().statusCode(200)
                .body("find { it.id == " + fx.entryChineseId() + " }.substituted", equalTo(true))
                .body("find { it.id == " + fx.entryChineseId() + " }.dayOfWeek", equalTo(1));

        // 修订申请的类别与前驱可追溯。
        SubstitutionRequest revision = requestRows.findById(secondRequestId).orElseThrow();
        assertThat(revision.getRevisesRequestId()).isEqualTo(firstRequestId);
        assertThat(revision.getRevisionKind()).isEqualTo("RESCHEDULE_REVISION");
    }

    // ---------------------------------------------- 代课后调课（保留当前教师）

    @Test
    void coveredLessonCanBeRescheduledKeepingCurrentTeacher() {
        Fixture fx = fx();
        String admin = adminToken();
        long coverRequestId = coverOnce(admin, fx, fx.entryChineseId(), fx.wednesday(),
                fx.teacherSpareId());
        assertThat(classTeacher(admin, fx, fx.entryChineseId(), fx.wednesday()))
                .isEqualTo(String.valueOf(fx.teacherSpareId()));

        // 对已代课的实例（当前教师丙）发起调课：语文 周三 1 → 周五 1。
        long planId = generatePlan(admin, fx, fx.entryChineseId(), fx.wednesday(),
                fx.friday(), fx.fridayPeriodId());
        Response preview = previewSwapRaw(admin, fx, fx.entryChineseId(), fx.wednesday(), planId);
        preview.then().statusCode(200)
                .body("revisesRequestId", equalTo((int) coverRequestId))
                .body("revisionKind", equalTo("RESCHEDULE_REVISION"));
        long swapRequestId = consume(admin, preview.jsonPath().getLong("voucherId"),
                "confirm-swap", null, 200);

        // 丙的 COVER 行退出当前有效安排；SWAP 净差行以基线日为来源日、教师 = 丙。
        assertThat(substitutionRows.findByRequestId(coverRequestId).get(0).getStatus())
                .isEqualTo(Substitution.SUPERSEDED);
        assertThat(substitutionRows.findByRequestId(swapRequestId))
                .anySatisfy(row -> {
                    assertThat(row.getEntryId()).isEqualTo(fx.entryChineseId());
                    assertThat(row.getLessonDate()).isEqualTo(fx.wednesday());
                    assertThat(row.getTargetLessonDate()).isEqualTo(fx.friday());
                    assertThat(row.getSubstituteTeacherId()).isEqualTo(fx.teacherSpareId());
                });

        // 最终：周五 1 = 语文（丙承担），周三 1 = 数学（环成员），丙的教师视图只有周五。
        as(admin).queryParam("date", fx.friday().toString())
                .when().get("/api/timetable/class/" + fx.classAId())
                .then().statusCode(200)
                .body("find { it.id == " + fx.entryChineseId() + " }.teacherId",
                        equalTo((int) fx.teacherSpareId()));
        as(admin).queryParam("date", fx.wednesday().toString())
                .when().get("/api/timetable/class/" + fx.classAId())
                .then().statusCode(200)
                .body("find { it.id == " + fx.entryMathId() + " }.teacherId",
                        equalTo((int) fx.teacherMathId()));
        as(admin).queryParam("date", fx.friday().toString())
                .when().get("/api/timetable/teacher/" + fx.teacherSpareId())
                .then().statusCode(200)
                .body("find { it.id == " + fx.entryChineseId() + " }.id",
                        equalTo((int) fx.entryChineseId()));
    }

    // ---------------------------------------------- 调课后代课 + 四视图一致

    @Test
    void rescheduledLessonCanBeCoveredAndAllViewsAgree() {
        Fixture fx = fx();
        String admin = adminToken();
        long swapRequestId = swapOnce(admin, fx, fx.entryChineseId(), fx.wednesday(),
                fx.friday(), fx.fridayPeriodId(), "confirm-swap");

        // 对已调到周五的语文实例指定代课丙：预览产出以 SWAP 申请为基准的修订。
        Response preview = as(admin).body(Map.of(
                        "entryId", fx.entryChineseId(),
                        "lessonDate", fx.friday().toString(),
                        "substituteTeacherId", fx.teacherSpareId(),
                        "reason", "周五临时有事"))
                .when().post("/api/substitution-workbench/previews");
        preview.then().statusCode(200)
                .body("revisesRequestId", equalTo((int) swapRequestId))
                .body("revisionKind", equalTo("CHANGE_SUBSTITUTE"));
        long voucherId = preview.jsonPath().getLong("voucherId");

        // 提交审批：待批期间上一版（张在周五）继续生效。
        long coverRequestId = consume(admin, voucherId, "submit-approval", null, 200);
        assertThat(classTeacher(admin, fx, fx.entryChineseId(), fx.friday()))
                .isEqualTo(String.valueOf(fx.teacherChineseId()));
        // SWAP 行在待批期间保持 EFFECTIVE——来源日（周三）的移出语义不受影响。
        assertThat(substitutionRows.findByRequestId(swapRequestId))
                .allSatisfy(row -> assertThat(row.getStatus()).isEqualTo(Substitution.EFFECTIVE));

        // 队列批准：丙接管周五的语文。
        as(admin).body(Map.of("substituteTeacherId", fx.teacherSpareId(), "comment", "同意"))
                .when().post("/api/substitution-requests/" + coverRequestId + "/approve")
                .then().statusCode(200)
                .body("status", equalTo("EFFECTIVE"));

        // SWAP 行保留（来源日移出语义），COVER 行接管落点教师。
        assertThat(substitutionRows.findByRequestId(swapRequestId))
                .allSatisfy(row -> assertThat(row.getStatus()).isEqualTo(Substitution.EFFECTIVE));
        assertThat(substitutionRows.findByRequestId(coverRequestId).get(0).getStatus())
                .isEqualTo(Substitution.EFFECTIVE);

        String friday = fx.friday().toString();
        // 班级视图：周五 1 语文（丙）。
        as(admin).queryParam("date", friday)
                .when().get("/api/timetable/class/" + fx.classAId())
                .then().statusCode(200)
                .body("find { it.id == " + fx.entryChineseId() + " }.teacherId",
                        equalTo((int) fx.teacherSpareId()));
        // 教师视图：丙恰有一节；张（基线 + SWAP 教师）不再承担这节课。
        as(admin).queryParam("view", "latest").queryParam("date", friday)
                .when().get("/api/timetable/teacher/" + fx.teacherSpareId())
                .then().statusCode(200)
                .body("find { it.id == " + fx.entryChineseId() + " }.id",
                        equalTo((int) fx.entryChineseId()));
        as(admin).queryParam("view", "latest").queryParam("date", friday)
                .when().get("/api/timetable/teacher/" + fx.teacherChineseId())
                .then().statusCode(200)
                .body("findAll { it.id == " + fx.entryChineseId() + " }.size()", equalTo(0));
        // 教室视图：课仍在原教室（教室A 跟课走）。
        as(admin).queryParam("view", "latest").queryParam("date", friday)
                .when().get("/api/timetable/room/" + fx.roomAId())
                .then().statusCode(200)
                .body("find { it.id == " + fx.entryChineseId() + " }.id",
                        equalTo((int) fx.entryChineseId()));
        // 全校视图同日一致。
        as(admin).queryParam("view", "latest").queryParam("date", friday)
                .when().get("/api/timetable/school")
                .then().statusCode(200)
                .body("entries.find { it.id == " + fx.entryChineseId() + " }.teacherId",
                        equalTo((int) fx.teacherSpareId()));
    }

    // ---------------------------------------------- 待批/驳回期间上一版生效

    @Test
    void pendingOrRejectedRevisionKeepsPreviousVersionEffective() {
        Fixture fx = fx();
        String admin = adminToken();
        long firstRequestId = swapOnce(admin, fx, fx.entryChineseId(), fx.wednesday(),
                fx.friday(), fx.fridayPeriodId(), "confirm-swap");

        // 修订（语文 周五 1 → 下周一 1）提交审批后驳回：上一版继续生效，可重新预览。
        long planId = generatePlan(admin, fx, fx.entryChineseId(), fx.friday(),
                fx.nextMonday(), fx.mondayPeriodId());
        long voucherId = previewSwap(admin, fx, fx.entryChineseId(), fx.friday(), planId);
        long requestId = consume(admin, voucherId, "submit-approval-swap", null, 200);
        assertThat(classTeacher(admin, fx, fx.entryChineseId(), fx.friday()))
                .isEqualTo(String.valueOf(fx.teacherChineseId()));

        as(admin).body(Map.of("comment", "先不动"))
                .when().post("/api/substitution-requests/" + requestId + "/reject")
                .then().statusCode(200)
                .body("status", equalTo("REJECTED"));
        assertThat(substitutionRows.findByRequestId(firstRequestId))
                .allSatisfy(row -> assertThat(row.getStatus()).isEqualTo(Substitution.EFFECTIVE));
        assertThat(substitutionRows.findByRequestId(requestId)).isEmpty();

        // 驳回后旧单结束：可以再次预览修订并确认生效。
        long retryPlanId = generatePlan(admin, fx, fx.entryChineseId(), fx.friday(),
                fx.nextMonday(), fx.mondayPeriodId());
        long retryVoucher = previewSwap(admin, fx, fx.entryChineseId(), fx.friday(), retryPlanId);
        consume(admin, retryVoucher, "confirm-swap", null, 200);
        // 重新修订生效后：周五视图里数学仍标注回周三（旧环成员保持，无半环）；
        // 语文已落到下周一（见下方断言）。
        as(admin).queryParam("date", fx.friday().toString())
                .when().get("/api/timetable/class/" + fx.classAId())
                .then().statusCode(200)
                .body("find { it.id == " + fx.entryMathId() + " }.dayOfWeek", equalTo(3));
        as(admin).queryParam("date", fx.nextMonday().toString())
                .when().get("/api/timetable/class/" + fx.classAId())
                .then().statusCode(200)
                .body("find { it.id == " + fx.entryChineseId() + " }.substituted", equalTo(true));
    }

    // ---------------------------------------------- 历史与统计

    @Test
    void historyKeepsEveryVersionAndStatsDoNotDoubleCount() {
        Fixture fx = fx();
        String admin = adminToken();
        long firstRequestId = swapOnce(admin, fx, fx.entryChineseId(), fx.wednesday(),
                fx.friday(), fx.fridayPeriodId(), "confirm-swap");
        long planId = generatePlan(admin, fx, fx.entryChineseId(), fx.friday(),
                fx.nextMonday(), fx.mondayPeriodId());
        long voucherId = previewSwap(admin, fx, fx.entryChineseId(), fx.friday(), planId);
        long secondRequestId = consume(admin, voucherId, "confirm-swap", null, 200);

        // 历史链：修订申请绑定前驱；两单的状态流转（含确认时间）都完整保留。
        as(admin).when().get("/api/substitution-requests/" + secondRequestId)
                .then().statusCode(200)
                .body("revisesRequestId", equalTo((int) firstRequestId))
                .body("revisionKind", equalTo("RESCHEDULE_REVISION"))
                .body("status", equalTo("EFFECTIVE"));
        as(admin).when().get("/api/substitution-requests/" + secondRequestId + "/history")
                .then().statusCode(200)
                .body("toStatus", hasSize(org.hamcrest.Matchers.greaterThanOrEqualTo(2)));
        as(admin).when().get("/api/substitution-requests/" + firstRequestId + "/history")
                .then().statusCode(200)
                .body("toStatus", hasSize(org.hamcrest.Matchers.greaterThanOrEqualTo(2)));

        // 统计：同一实例多次移动不增加课量；SWAP 不改教师，代入/代出保持零。
        long swapsAfterFirst = swapsCount(admin, fx.teacherChineseId());
        long swapsAfterSecond = swapsCount(admin, fx.teacherChineseId());
        assertThat(swapsAfterSecond).isEqualTo(swapsAfterFirst);
        assertThat(substitutedOut(admin, fx.teacherChineseId())).isZero();
        // 周课量（基线口径）不因修订数变化：张的基线只有语文一节。
        assertThat(weeklyLessons(admin, fx.teacherChineseId())).isEqualTo(1);
    }

    private long swapsCount(String admin, long teacherId) {
        return as(admin).queryParam("teacherId", teacherId)
                .when().get("/api/substitutions/stats")
                .then().statusCode(200)
                .extract().jsonPath().getLong("swapParticipation");
    }

    private long substitutedOut(String admin, long teacherId) {
        return as(admin).queryParam("teacherId", teacherId)
                .when().get("/api/substitutions/stats")
                .then().statusCode(200)
                .extract().jsonPath().getLong("substitutedOut");
    }

    private long weeklyLessons(String admin, long teacherId) {
        return as(admin).queryParam("teacherId", teacherId)
                .when().get("/api/substitutions/stats")
                .then().statusCode(200)
                .extract().jsonPath().getLong("weeklyLessons");
    }

    // ---------------------------------------------- 通知（取代申请 + 去重）

    @Test
    void revisionNotifiesAffectedTeachersWithSupersededRequestOnce() {
        Fixture fx = fx();
        String admin = adminToken();
        String chineseToken = userForTeacher("res_c", fx.teacherChineseId());
        String englishToken = userForTeacher("res_e", fx.teacherEnglishId());
        long firstRequestId = swapOnce(admin, fx, fx.entryChineseId(), fx.wednesday(),
                fx.friday(), fx.fridayPeriodId(), "confirm-swap");
        long chineseUnread = unreadCount(chineseToken);

        // 修订生效：环内受影响教师（张/李）收到含「取代申请 #x」的通知。
        long planId = generatePlan(admin, fx, fx.entryChineseId(), fx.friday(),
                fx.nextMonday(), fx.mondayPeriodId());
        long voucherId = previewSwap(admin, fx, fx.entryChineseId(), fx.friday(), planId);
        consume(admin, voucherId, "confirm-swap", null, 200);
        assertThat(unreadCount(chineseToken)).isEqualTo(chineseUnread + 1);
        assertThat(latestContent(chineseToken)).contains("取代申请 #" + firstRequestId);
        assertThat(latestContent(englishToken)).contains("取代申请 #" + firstRequestId);

        // 相同凭证重试：幂等返回第一次结果，通知不重复。
        as(admin).body(Map.of())
                .when().post("/api/substitution-workbench/previews/" + voucherId + "/confirm-swap")
                .then().statusCode(200)
                .body("replayed", equalTo(true));
        assertThat(unreadCount(chineseToken)).isEqualTo(chineseUnread + 1);
        assertThat(unreadCount(englishToken)).isEqualTo(1);
    }

    // ---------------------------------------------- 竞争与幂等

    @Test
    void concurrentRevisionsFromSameBaseAllowOnlyOneWinner() {
        Fixture fx = fx();
        String admin = adminToken();
        long firstRequestId = swapOnce(admin, fx, fx.entryChineseId(), fx.wednesday(),
                fx.friday(), fx.fridayPeriodId(), "confirm-swap");

        // 两位管理员基于同一基准各拿一份预览凭证（同一方案）。
        long planId = generatePlan(admin, fx, fx.entryChineseId(), fx.friday(),
                fx.nextMonday(), fx.mondayPeriodId());
        long voucherA = previewSwap(admin, fx, fx.entryChineseId(), fx.friday(), planId);
        long voucherB = previewSwap(admin, fx, fx.entryChineseId(), fx.friday(), planId);

        long winnerId = consume(admin, voucherA, "confirm-swap", null, 200);
        // 后确认者明确冲突：最多一个修订生效，胜者的安排保持完整。
        as(admin).body(Map.of())
                .when().post("/api/substitution-workbench/previews/" + voucherB + "/confirm-swap")
                .then().statusCode(409);
        assertThat(substitutionRows.findByRequestId(winnerId))
                .allSatisfy(row -> assertThat(row.getStatus()).isEqualTo(Substitution.EFFECTIVE));
        assertThat(substitutionRows.count())
                .isEqualTo(substitutionRows.findByRequestId(firstRequestId).size()
                        + substitutionRows.findByRequestId(winnerId).size());

        // 冲突后以当前有效安排重新预览（COVER 路径）：新的基准 = 胜者申请。
        Response fresh = as(admin).body(Map.of(
                        "entryId", fx.entryChineseId(),
                        "lessonDate", fx.nextMonday().toString(),
                        "substituteTeacherId", fx.teacherSpareId(),
                        "reason", "以最新安排重新预览"))
                .when().post("/api/substitution-workbench/previews");
        fresh.then().statusCode(200)
                .body("revisesRequestId", equalTo((int) winnerId));
    }
}
