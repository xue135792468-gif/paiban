package com.paiban.substitution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;

import com.paiban.foundation.domain.AuditLog;
import com.paiban.foundation.domain.AuditLogRepository;
import com.paiban.identity.domain.AppUser;
import com.paiban.identity.domain.AppUserRepository;
import com.paiban.identity.domain.Permission;
import com.paiban.identity.domain.PermissionRepository;
import com.paiban.identity.domain.Role;
import com.paiban.identity.domain.RoleRepository;
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
 * 票 03 的 HTTP 验收主缝：无教师绑定的管理员经工作台完成代人预览、提交审批与亲自确认。
 * 覆盖票据 13 条清单：代发全链、待批不生效、权限矩阵、冲突拒绝、预览零副作用、
 * 幂等重试、快照冲突、单实例单活跃修改、旧格式只读与审计字段。
 *
 * <p>fixture 用「未来学期 + 强制放置」：两班各一节课都被摆到周三第 1 节（08:00 开课），
 * 同槽冲突、同科优先与截止边界都成为确定性事实。学期日期取当前日期之后，
 * 否则服务端权威时钟会正确地以「已开课」拒绝一切管理写入。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AdminSubstitutionIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private SubstitutionRequestRepository requestRows;
    @Autowired
    private SubstitutionRepository substitutionRows;
    @Autowired
    private AppUserRepository userRows;
    @Autowired
    private AuditLogRepository auditLogs;
    @Autowired
    private RoleRepository roleRows;
    @Autowired
    private PermissionRepository permissionRows;

    record Fixture(long semesterId, long classAId, long classBId,
                   long teacherAId, long teacherBId, long teacherCId, long teacherDId,
                   long entryAId, long entryBId, LocalDate lessonDate, long periodId,
                   long headTeacherId) {}

    /** 未来学期：从下一个周一开始，周三第 1 节是两班共同的槽位。 */
    private Fixture fixture() {
        return buildFixture(adminToken(), TestData.suffix(),
                LocalDate.now().with(TemporalAdjusters.next(java.time.DayOfWeek.MONDAY)));
    }

    /**
     * 共享工作台 fixture（两个测试类使用）：学期从给定周一开始，
     * 两班各一节课强制摆到周三第 1 节（08:00 开课），同槽冲突与同科判定都是确定性事实。
     */
    static Fixture buildFixture(String token, String suffix, LocalDate monday) {
        LocalDate wednesday = monday.plusDays(2);
        Response semester = auth(token).body(Map.of(
                        "name", "工作台学期-" + suffix,
                        "startDate", monday.toString(),
                        "endDate", monday.plusDays(100).toString()))
                .when().post("/api/semesters");
        semester.then().statusCode(200);
        long semesterId = semester.jsonPath().getLong("id");
        TestData.setCurrent(token, semesterId);
        auth(token).body(Map.of("morningCount", 1, "afternoonCount", 0, "eveningCount", 0))
                .when().post("/api/semesters/" + semesterId + "/periods/default")
                .then().statusCode(200);
        List<Map<String, Object>> periodRows = auth(token).when().get("/api/semesters/" + semesterId + "/periods")
                .then().statusCode(200).extract().jsonPath().getList("$");
        long periodId = periodRows.stream()
                .filter(row -> ((Number) row.get("dayOfWeek")).intValue() == 3)
                .map(row -> ((Number) row.get("id")).longValue())
                .findFirst().orElseThrow();

        long gradeId = TestData.grade(token, semesterId, "高一" + suffix);
        long headTeacherId = TestData.teacher(token, "班主任" + suffix);
        long classAId = com.paiban.support.ScheduleFixture.classWithHeadTeacher(
                token, semesterId, gradeId, "高一1班" + suffix, headTeacherId);
        long classBId = TestData.adminClass(token, semesterId, gradeId, "高一2班" + suffix);
        // 学科与课程 subject 完全一致（课程 subject = 带后缀的名称），同科判定才成立。
        long teacherAId = teacher(token, "语文甲" + suffix, "语文" + suffix);
        long teacherBId = teacher(token, "数学乙" + suffix, "数学" + suffix);
        long teacherCId = teacher(token, "代课丙" + suffix, null);
        long teacherDId = teacher(token, "同科丁" + suffix, "语文" + suffix);
        long chineseId = TestData.course(token, "语文" + suffix);
        long mathId = TestData.course(token, "数学" + suffix);
        long roomA = TestData.room(token, "教室A" + suffix);
        long roomB = TestData.room(token, "教室B" + suffix);

        com.paiban.support.ScheduleFixture.assign(token, classAId, chineseId, teacherAId, 1);
        com.paiban.support.ScheduleFixture.assign(token, classBId, mathId, teacherBId, 1);

        long draftId = solve(token, semesterId);
        List<Map<String, Object>> lessons = com.paiban.support.ScheduleFixture.lessons(token, draftId);
        place(token, draftId, lessons, classAId, chineseId, periodId, roomA);
        place(token, draftId, lessons, classBId, mathId, periodId, roomB);
        auth(token).when().post("/api/timetable-drafts/" + draftId + "/publish")
                .then().statusCode(200);

        List<Map<String, Object>> entries = com.paiban.support.ScheduleFixture.publishedEntries(token, classAId);
        long entryAId = entries.stream()
                .filter(row -> ((Number) row.get("courseId")).longValue() == chineseId)
                .map(row -> ((Number) row.get("id")).longValue())
                .findFirst().orElseThrow();
        List<Map<String, Object>> entriesB = com.paiban.support.ScheduleFixture.publishedEntries(token, classBId);
        long entryBId = entriesB.stream()
                .filter(row -> ((Number) row.get("courseId")).longValue() == mathId)
                .map(row -> ((Number) row.get("id")).longValue())
                .findFirst().orElseThrow();
        return new Fixture(semesterId, classAId, classBId,
                teacherAId, teacherBId, teacherCId, teacherDId, entryAId, entryBId, wednesday, periodId,
                headTeacherId);
    }

    /** 静态 helper 专用的请求规格（与父类的 as() 等价，方便静态 fixture 复用）。 */
    private static io.restassured.specification.RequestSpecification auth(String token) {
        return io.restassured.RestAssured.given()
                .contentType(io.restassured.http.ContentType.JSON)
                .header("Authorization", "Bearer " + token);
    }

    /** jsonPath 的数值列表统一转成 long，避免 Integer/Long 装箱差异造成假失败。 */
    private static List<Long> idList(Response response) {
        return response.jsonPath().<Map<String, Object>>getList("candidates").stream()
                .map(row -> ((Number) row.get("teacherId")).longValue())
                .toList();
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

    private long preview(String token, Fixture fx, long substituteTeacherId) {
        return as(token).body(Map.of(
                        "entryId", fx.entryAId(),
                        "lessonDate", fx.lessonDate().toString(),
                        "substituteTeacherId", substituteTeacherId,
                        "reason", "甲老师开会"))
                .when().post("/api/substitution-workbench/previews")
                .then().statusCode(200)
                .extract().jsonPath().getLong("voucherId");
    }

    private long consume(String token, long voucherId, String action, String comment, int expectedStatus) {
        Response response = as(token)
                .body(comment == null ? Map.of() : Map.of("comment", comment))
                .when().post("/api/substitution-workbench/previews/" + voucherId + "/" + action);
        response.then().statusCode(expectedStatus);
        return response.jsonPath().getLong("requestId");
    }

    /** 一个只有「查看全部调代记录」权限的角色与账号：证明仅查看不可写。 */
    private String viewOnlyUser(String suffix) {
        Permission view = permissionRows.findByCode("substitution:view").orElseThrow();
        Permission notification = permissionRows.findByCode("notification:view").orElseThrow();
        Role role = roleRows.findByCode("viewer_" + suffix)
                .orElseGet(() -> roleRows.save(new Role("viewer_" + suffix, "仅查看")));
        role.getPermissions().clear();
        role.getPermissions().add(view);
        role.getPermissions().add(notification);
        roleRows.save(role);
        String username = "viewer_" + suffix;
        createUser(adminToken(), username, "teacher123", "viewer_" + suffix);
        return login(username, "teacher123");
    }

    private String teacherUser(String suffix, long teacherId) {
        return roleUser(suffix, teacherId, "teacher");
    }

    private String roleUser(String suffix, long teacherId, String roleCode) {
        String username = "wt_" + suffix;
        as(adminToken()).body(Map.of(
                        "username", username,
                        "password", "teacher123",
                        "displayName", "用户",
                        "roleCodes", List.of(roleCode),
                        "teacherId", teacherId))
                .when().post("/api/users").then().statusCode(200);
        return login(username, "teacher123");
    }

    // ------------------------------------------------------------ 1/2/13: 代发全链

    @Test
    void adminDispatchSubmitApprovalKeepsOldPlanUntilApproved() {
        Fixture fx = fixture();
        String admin = adminToken();
        long adminUserId = userRows.findByUsername("admin").orElseThrow().getId();

        // 候选：同科优先、排除当前教师。
        var candidates = as(admin)
                .queryParam("entryId", fx.entryAId())
                .queryParam("lessonDate", fx.lessonDate().toString())
                .when().get("/api/substitution-workbench/candidates")
                .then().statusCode(200).extract().response();
        assertThat(candidates.jsonPath().getLong("currentTeacherId")).isEqualTo(fx.teacherAId());
        assertThat(candidates.jsonPath().getBoolean("legacyLocked")).isFalse();
        assertThat(idList(candidates)).doesNotContain(fx.teacherAId());

        // 预览零副作用：申请表与生效表都不动。
        long voucherId = preview(admin, fx, fx.teacherCId());
        assertThat(requestRows.count()).isZero();
        assertThat(substitutionRows.count()).isZero();

        // 提交审批：进入待批队列，旧安排继续生效。
        long requestId = consume(admin, voucherId, "submit-approval", null, 200);
        as(admin).when().get("/api/substitution-requests/" + requestId)
                .then().statusCode(200)
                .body("status", equalTo("PENDING_APPROVAL"))
                .body("approvalMode", equalTo("approval"));
        as(admin).queryParam("status", "PENDING_APPROVAL")
                .when().get("/api/substitution-requests")
                .then().statusCode(200)
                .body("id", hasItem((int) requestId));

        // 待批期间：没有生效行，班级课表仍是原教师。
        assertThat(substitutionRows.count()).isZero();
        as(admin).queryParam("date", fx.lessonDate().toString())
                .when().get("/api/timetable/class/" + fx.classAId())
                .then().statusCode(200)
                .body("find { it.id == " + fx.entryAId() + " }.substituted", equalTo(false));

        // 再走普通批准（现有工作流）：生效、课表改变。
        as(admin).body(Map.of("substituteTeacherId", fx.teacherCId(), "comment", "同意"))
                .when().post("/api/substitution-requests/" + requestId + "/approve")
                .then().statusCode(200)
                .body("status", equalTo("EFFECTIVE"));
        as(admin).queryParam("date", fx.lessonDate().toString())
                .when().get("/api/timetable/class/" + fx.classAId())
                .then().statusCode(200)
                .body("find { it.id == " + fx.entryAId() + " }.teacherId",
                        equalTo((int) fx.teacherCId()));

        // 审计字段：操作者 = 真实管理员账号；代发对象与基线原教师 = 原任课教师；
        // 调整前教师 = 原教师；调整后教师 = 代课人；审批人 = 管理员账号。
        SubstitutionRequest saved = requestRows.findById(requestId).orElseThrow();
        assertThat(saved.getOperatorUserId()).isEqualTo(adminUserId);
        assertThat(saved.getRequesterTeacherId()).isEqualTo(fx.teacherAId());
        assertThat(saved.getOriginalTeacherId()).isEqualTo(fx.teacherAId());
        assertThat(saved.getPreviousTeacherId()).isEqualTo(fx.teacherAId());
        assertThat(saved.getFinalTeacherId()).isEqualTo(fx.teacherCId());
        assertThat(saved.getApproverId()).isEqualTo(adminUserId);
        assertThat(auditLogs.findAll().stream()
                .filter(log -> "SUBSTITUTION_ADMIN_DISPATCHED".equals(log.getAction())
                        && log.getDetail().contains("operator=admin"))
                .count()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void adminPersonalConfirmRunsTheApprovalPath() {
        Fixture fx = fixture();
        String admin = adminToken();
        long adminUserId = userRows.findByUsername("admin").orElseThrow().getId();

        long voucherId = preview(admin, fx, fx.teacherCId());
        long requestId = consume(admin, voucherId, "confirm", "紧急安排", 200);

        // 与普通批准相同的终态：生效、审计历史完整、课表变化。
        as(admin).when().get("/api/substitution-requests/" + requestId)
                .then().statusCode(200)
                .body("status", equalTo("EFFECTIVE"))
                .body("substituteTeacherName", org.hamcrest.Matchers.notNullValue());
        as(admin).when().get("/api/substitution-requests/" + requestId + "/history")
                .then().statusCode(200)
                .body("toStatus", hasItem("SUBMITTED"))
                .body("toStatus", hasItem("PENDING_APPROVAL"))
                .body("toStatus", hasItem("EFFECTIVE"));
        as(admin).queryParam("date", fx.lessonDate().toString())
                .when().get("/api/timetable/class/" + fx.classAId())
                .then().statusCode(200)
                .body("find { it.id == " + fx.entryAId() + " }.teacherId",
                        equalTo((int) fx.teacherCId()));

        SubstitutionRequest saved = requestRows.findById(requestId).orElseThrow();
        assertThat(saved.getOperatorUserId()).isEqualTo(adminUserId);
        assertThat(saved.getRequesterTeacherId()).isEqualTo(fx.teacherAId());
        assertThat(saved.getPreviousTeacherId()).isEqualTo(fx.teacherAId());
        assertThat(saved.getFinalTeacherId()).isEqualTo(fx.teacherCId());
        assertThat(saved.getApproverId()).isEqualTo(adminUserId);
        assertThat(auditLogs.findAll().stream()
                .filter(log -> "SUBSTITUTION_ADMIN_CONFIRMED".equals(log.getAction()))
                .count()).isEqualTo(1);
        // 生效行：一次性实例（lesson_date 命中当天），教师换成代课人。
        List<Substitution> rows = substitutionRows.findByRequestId(requestId);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getLessonDate()).isEqualTo(fx.lessonDate());
        assertThat(rows.get(0).getSubstituteTeacherId()).isEqualTo(fx.teacherCId());
    }

    @Test
    void previewHasZeroSideEffects() {
        Fixture fx = fixture();
        String admin = adminToken();

        long voucherId = preview(admin, fx, fx.teacherCId());
        assertThat(requestRows.count()).isZero();
        assertThat(substitutionRows.count()).isZero();
        assertThat(auditLogs.findAll().stream()
                .filter(log -> log.getEntityType() != null
                        && log.getEntityType().startsWith("SUBSTITUTION"))
                .count()).isZero();

        // 课表不变。
        as(admin).queryParam("date", fx.lessonDate().toString())
                .when().get("/api/timetable/class/" + fx.classAId())
                .then().statusCode(200)
                .body("find { it.id == " + fx.entryAId() + " }.substituted", equalTo(false));

        // 原教师与代课教师都没有收到任何通知。
        String teacherToken = teacherUser(TestData.suffix(), fx.teacherAId());
        as(teacherToken).when().get("/api/notifications/unread-count")
                .then().statusCode(200)
                .body("count", equalTo(0));
    }

    // ---------------------------------------------------------------- 3/4: 权限矩阵

    @Test
    void viewOnlyUserCannotDispatchOrApprove() {
        Fixture fx = fixture();
        String viewer = viewOnlyUser(TestData.suffix());

        as(viewer).queryParam("entryId", fx.entryAId())
                .queryParam("lessonDate", fx.lessonDate().toString())
                .when().get("/api/substitution-workbench/candidates")
                .then().statusCode(403);
        as(viewer).body(Map.of(
                        "entryId", fx.entryAId(),
                        "lessonDate", fx.lessonDate().toString(),
                        "substituteTeacherId", fx.teacherCId()))
                .when().post("/api/substitution-workbench/previews")
                .then().statusCode(403);
        // 旧入口同样收紧：仅有查看权限不能代人提交。
        as(viewer).body(Map.of(
                        "entryId", fx.entryAId(),
                        "effectiveDate", fx.lessonDate().toString()))
                .when().post("/api/substitution-requests")
                .then().statusCode(403);
        // 查看全部记录仍然允许（该权限本来就只管读）。
        as(viewer).when().get("/api/substitutions")
                .then().statusCode(200);
    }

    @Test
    void teacherCannotDispatchOthersOrUseWorkbench() {
        Fixture fx = fixture();
        String teacher = teacherUser(TestData.suffix(), fx.teacherAId());

        // 缺全校课表查看权限：不能取工作台候选/预览/确认。
        as(teacher).queryParam("entryId", fx.entryAId())
                .queryParam("lessonDate", fx.lessonDate().toString())
                .when().get("/api/substitution-workbench/candidates")
                .then().statusCode(403);
        as(teacher).body(Map.of(
                        "entryId", fx.entryAId(),
                        "lessonDate", fx.lessonDate().toString(),
                        "substituteTeacherId", fx.teacherCId()))
                .when().post("/api/substitution-workbench/previews")
                .then().statusCode(403);
        // 教师不能代其他教师提交（原入口行为保持）。
        as(teacher).body(Map.of(
                        "entryId", fx.entryBId(),
                        "effectiveDate", fx.lessonDate().toString()))
                .when().post("/api/substitution-requests")
                .then().statusCode(403);
        // 教师自助入口照常：为自己的课提交仍然可行。
        as(teacher).body(Map.of(
                        "entryId", fx.entryAId(),
                        "effectiveDate", fx.lessonDate().toString(),
                        "expectedSubstituteTeacherId", fx.teacherCId()))
                .when().post("/api/substitution-requests")
                .then().statusCode(200)
                .body("status", equalTo("PENDING_APPROVAL"));
    }

    @Test
    void headTeacherScopeIsNotExpanded() {
        Fixture fx = fixture();
        // 班主任属于 fixture 班级 A；教师自助权限有，但没有审批/全校课表权限。
        String head = roleUser(TestData.suffix(), fx.headTeacherId(), "head_teacher");

        as(head).queryParam("entryId", fx.entryAId())
                .queryParam("lessonDate", fx.lessonDate().toString())
                .when().get("/api/substitution-workbench/candidates")
                .then().statusCode(403);
        as(head).body(Map.of(
                        "entryId", fx.entryAId(),
                        "lessonDate", fx.lessonDate().toString(),
                        "substituteTeacherId", fx.teacherCId()))
                .when().post("/api/substitution-workbench/previews")
                .then().statusCode(403);
        // 数据范围不扩大：调代记录仍必须指定本班。
        as(head).when().get("/api/substitutions")
                .then().statusCode(400);
    }

    // ------------------------------------------------------- 5/6: 候选与硬冲突

    @Test
    void candidatesPreferSameSubjectAndExposeConflictReasons() {
        Fixture fx = fixture();
        String admin = adminToken();

        var response = as(admin)
                .queryParam("entryId", fx.entryAId())
                .queryParam("lessonDate", fx.lessonDate().toString())
                .when().get("/api/substitution-workbench/candidates")
                .then().statusCode(200).extract().response();

        // 数学乙在周三第 1 节有课（高一2班数学），冲突原因可见。
        assertThat(response.jsonPath().getBoolean(
                "candidates.find { it.teacherId == " + fx.teacherBId() + " }.busyAtPeriod")).isTrue();
        assertThat(response.jsonPath().getString(
                "candidates.find { it.teacherId == " + fx.teacherBId() + " }.busyReason"))
                .contains("该时段已有课");
        // 同科（语文）教师标记 sameSubject；跨科标记 crossSubject。
        assertThat(response.jsonPath().getBoolean(
                "candidates.find { it.teacherId == " + fx.teacherDId() + " }.sameSubject")).isTrue();
        assertThat(response.jsonPath().getBoolean(
                "candidates.find { it.teacherId == " + fx.teacherBId() + " }.crossSubject")).isTrue();
        // 排序：同科在前。列表只含启用教师且排除当前教师。
        List<Long> idList = idList(response);
        assertThat(idList.indexOf(fx.teacherDId()))
                .isLessThan(idList.indexOf(fx.teacherBId()));
    }

    @Test
    void hardConflictIsRejectedByServerOnConfirm() {
        Fixture fx = fixture();
        String admin = adminToken();

        // 预览允许创建（界面拿到提示），但确认被服务端拒绝：数学乙同一时段在别的班有课。
        Response previewResponse = as(admin).body(Map.of(
                        "entryId", fx.entryAId(),
                        "lessonDate", fx.lessonDate().toString(),
                        "substituteTeacherId", fx.teacherBId()))
                .when().post("/api/substitution-workbench/previews");
        previewResponse.then().statusCode(200);
        assertThat(previewResponse.<String>jsonPath().getList("issues")).isNotEmpty();
        long voucherId = previewResponse.jsonPath().getLong("voucherId");

        as(admin).body(Map.of())
                .when().post("/api/substitution-workbench/previews/" + voucherId + "/confirm")
                .then().statusCode(409)
                .body("message", org.hamcrest.Matchers.containsString("已有课"));
        // 失败零生效、凭证未消费（可重新预览或改选他人）。
        assertThat(substitutionRows.count()).isZero();
        assertThat(requestRows.count()).isZero();
    }

    // ------------------------------------------------------- 9/10: 幂等与快照

    @Test
    void idempotentConfirmRetryReturnsFirstResultAndRejectsParamChange() {
        Fixture fx = fixture();
        String admin = adminToken();
        long voucherId = preview(admin, fx, fx.teacherCId());

        long requestId = consume(admin, voucherId, "confirm", "同意", 200);
        int historyRows = as(admin).when().get("/api/substitution-requests/" + requestId + "/history")
                .then().statusCode(200).extract().jsonPath().<Object>getList("$").size();
        int effectiveRows = substitutionRows.findByRequestId(requestId).size();

        // 相同凭证 + 相同参数的重试：返回第一次结果，不再产生新的行或历史。
        Response retry = as(admin).body(Map.of("comment", "同意"))
                .when().post("/api/substitution-workbench/previews/" + voucherId + "/confirm");
        retry.then().statusCode(200);
        assertThat(retry.jsonPath().getLong("requestId")).isEqualTo(requestId);
        assertThat(retry.jsonPath().getBoolean("replayed")).isTrue();
        assertThat(substitutionRows.findByRequestId(requestId)).hasSize(effectiveRows);
        assertThat(as(admin).when().get("/api/substitution-requests/" + requestId + "/history")
                .then().statusCode(200).extract().jsonPath().<Object>getList("$"))
                .hasSize(historyRows);

        // 参数改变（不同 comment）：不复用已消费凭证。
        as(admin).body(Map.of("comment", "改成别的备注"))
                .when().post("/api/substitution-workbench/previews/" + voucherId + "/confirm")
                .then().statusCode(409);
    }

    @Test
    void snapshotChangeForcesRepreviewInsteadOfConfirming() {
        Fixture fx = fixture();
        String admin = adminToken();
        long voucherId = preview(admin, fx, fx.teacherCId());

        // 另一位管理员在同一快照上生效了别处调代：快照哈希变化。
        as(admin).body(Map.of("substituteTeacherId", fx.teacherDId()))
                .when().post("/api/substitution-requests/" + otherCoverRequest(admin, fx) + "/approve")
                .then().statusCode(200);

        as(admin).body(Map.of())
                .when().post("/api/substitution-workbench/previews/" + voucherId + "/confirm")
                .then().statusCode(409)
                .body("message", org.hamcrest.Matchers.containsString("重新预览"));

        // 重新预览以当前有效安排计算，可以正常确认。
        long freshVoucher = preview(admin, fx, fx.teacherCId());
        long requestId = consume(admin, freshVoucher, "confirm", null, 200);
        as(admin).when().get("/api/substitution-requests/" + requestId)
                .then().statusCode(200)
                .body("status", equalTo("EFFECTIVE"));
    }

    /** 用高一2班的数学课制造一次快照变化：teacherA 停用？不——直接为 entryB 走教师申请+批准。 */
    private long otherCoverRequest(String admin, Fixture fx) {
        // entryB 的当前教师是数学乙；让他本人提交申请，管理员批准——现有一键批准路径即可。
        String mathTeacherToken = teacherUser("math" + TestData.suffix(), fx.teacherBId());
        Response response = as(mathTeacherToken).body(Map.of(
                        "entryId", fx.entryBId(),
                        "effectiveDate", fx.lessonDate().toString(),
                        "expectedSubstituteTeacherId", fx.teacherDId()))
                .when().post("/api/substitution-requests");
        response.then().statusCode(200);
        return response.jsonPath().getLong("id");
    }

    // ------------------------------------------------------------- 单活跃与旧格式

    @Test
    void singleActiveModificationPerInstance() {
        Fixture fx = fixture();
        String admin = adminToken();
        long voucherId = preview(admin, fx, fx.teacherCId());
        long requestId = consume(admin, voucherId, "submit-approval", null, 200);

        // 已有待审批单：不允许再开并行修改，展示并引导处理已有单。
        as(admin).body(Map.of(
                        "entryId", fx.entryAId(),
                        "lessonDate", fx.lessonDate().toString(),
                        "substituteTeacherId", fx.teacherDId()))
                .when().post("/api/substitution-workbench/previews")
                .then().statusCode(409)
                .body("message", org.hamcrest.Matchers.containsString("#" + requestId));
    }

    @Test
    void legacyFormatInstanceIsReadOnlyForManagementWrites() {
        Fixture fx = fixture();
        String admin = adminToken();
        // 先用普通路径生效一笔代课，再把生效行改成旧格式（lesson_date 置空），模拟迁移前数据。
        String teacherToken = teacherUser("legacy" + TestData.suffix(), fx.teacherAId());
        Response submitted = as(teacherToken).body(Map.of(
                        "entryId", fx.entryAId(),
                        "effectiveDate", fx.lessonDate().toString(),
                        "expectedSubstituteTeacherId", fx.teacherCId()))
                .when().post("/api/substitution-requests");
        submitted.then().statusCode(200);
        long requestId = submitted.jsonPath().getLong("id");
        as(admin).body(Map.of("substituteTeacherId", fx.teacherCId()))
                .when().post("/api/substitution-requests/" + requestId + "/approve")
                .then().statusCode(200);

        Substitution row = substitutionRows.findByRequestId(requestId).get(0);
        row.setLessonDate(null);
        row.setTargetLessonDate(null);
        substitutionRows.save(row);

        // 管理写入被阻止并说明转换状态；候选接口也给出锁定标记。
        as(admin).body(Map.of(
                        "entryId", fx.entryAId(),
                        "lessonDate", fx.lessonDate().toString(),
                        "substituteTeacherId", fx.teacherDId()))
                .when().post("/api/substitution-workbench/previews")
                .then().statusCode(409)
                .body("message", org.hamcrest.Matchers.containsString("旧格式"));
        as(admin).queryParam("entryId", fx.entryAId())
                .queryParam("lessonDate", fx.lessonDate().toString())
                .when().get("/api/substitution-workbench/candidates")
                .then().statusCode(200)
                .body("legacyLocked", equalTo(true));
    }

    @Test
    void teacherBoundAdminDoesNotImpersonateAnyone() {
        Fixture fx = fixture();
        // 管理员账号绑定了教师身份（历史遗留配置），代发时不能顶替任何人。
        long boundTeacherId = fx.teacherDId();
        String username = "bound_admin_" + TestData.suffix();
        as(adminToken()).body(Map.of(
                        "username", username,
                        "password", "teacher123",
                        "displayName", "绑定教师的管理员",
                        "roleCodes", List.of("admin"),
                        "teacherId", boundTeacherId))
                .when().post("/api/users").then().statusCode(200);
        String boundAdmin = login(username, "teacher123");
        long boundUserId = userRows.findByUsername(username).orElseThrow().getId();

        long voucherId = preview(boundAdmin, fx, fx.teacherCId());
        long requestId = consume(boundAdmin, voucherId, "submit-approval", null, 200);

        SubstitutionRequest saved = requestRows.findById(requestId).orElseThrow();
        // 操作者 = 该管理员账号；代发对象/基线原教师 = 语文甲，而不是管理员绑定的教师丁。
        assertThat(saved.getOperatorUserId()).isEqualTo(boundUserId);
        assertThat(saved.getRequesterTeacherId()).isEqualTo(fx.teacherAId());
        assertThat(saved.getOriginalTeacherId()).isEqualTo(fx.teacherAId());
        assertThat(saved.getRequesterTeacherId()).isNotEqualTo(boundTeacherId);
    }
}
