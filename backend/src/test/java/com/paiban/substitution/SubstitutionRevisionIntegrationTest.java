package com.paiban.substitution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;

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
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 票 04 的 HTTP 验收主缝：A→B 已生效的课前实例，管理员再次预览「改为 C」或
 * 「恢复基线教师 A」。修订 = 新的申请 + 新的生效安排（ADR-0030）：
 * 待批期间旧安排 B 继续执行，批准后原子切换到新版本；历史只追加、不改写。
 *
 * <p>fixture 复用票 03 的共享构造器：两班各一节课摆到周三第 1 节（08:00 开课）。
 * 链路用「甲 → 丙 → 丁 → 恢复甲」：丙/丁在该时段无课，不会触发与票 04 无关的忙档冲突。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SubstitutionRevisionIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private SubstitutionRequestRepository requestRows;
    @Autowired
    private SubstitutionRepository substitutionRows;
    @Autowired
    private AppUserRepository userRows;

    private AdminSubstitutionIntegrationTest.Fixture fx() {
        return AdminSubstitutionIntegrationTest.buildFixture(adminToken(), TestData.suffix(),
                LocalDate.now().with(TemporalAdjusters.next(java.time.DayOfWeek.MONDAY)));
    }

    /** 票 03 路径走一遍首代（预览 + 亲自确认），返回生效申请 id。 */
    private long makeCover(String admin, AdminSubstitutionIntegrationTest.Fixture fx, long substituteTeacherId) {
        long voucherId = previewVoucher(admin, fx, substituteTeacherId, "首代请假");
        return consume(admin, voucherId, "confirm", null, 200);
    }

    private long previewVoucher(String token, AdminSubstitutionIntegrationTest.Fixture fx, long substituteTeacherId, String reason) {
        Response response = as(token).body(Map.of(
                        "entryId", fx.entryAId(),
                        "lessonDate", fx.lessonDate().toString(),
                        "substituteTeacherId", substituteTeacherId,
                        "reason", reason))
                .when().post("/api/substitution-workbench/previews");
        response.then().statusCode(200);
        return response.jsonPath().getLong("voucherId");
    }

    private long consume(String token, long voucherId, String action, String comment, int expectedStatus) {
        Response response = as(token)
                .body(comment == null ? Map.of() : Map.of("comment", comment))
                .when().post("/api/substitution-workbench/previews/" + voucherId + "/" + action);
        response.then().statusCode(expectedStatus);
        return response.jsonPath().getLong("requestId");
    }

    private Response previewRaw(String token, AdminSubstitutionIntegrationTest.Fixture fx, long substituteTeacherId) {
        return as(token).body(Map.of(
                        "entryId", fx.entryAId(),
                        "lessonDate", fx.lessonDate().toString(),
                        "substituteTeacherId", substituteTeacherId,
                        "reason", "课前再次调整"))
                .when().post("/api/substitution-workbench/previews");
    }

    private String classViewTeacher(String admin, AdminSubstitutionIntegrationTest.Fixture fx) {
        return as(admin).queryParam("date", fx.lessonDate().toString())
                .when().get("/api/timetable/class/" + fx.classAId())
                .then().statusCode(200)
                .extract().jsonPath().getString(
                        "find { it.id == " + fx.entryAId() + " }.teacherId");
    }

    // ------------------------------------------------------- 修订预览与 B→C 全链

    @Test
    void coveredInstancePreviewProducesRevisionInsteadOfConflict() {
        var fx = fx();
        String admin = adminToken();
        long firstRequestId = makeCover(admin, fx, fx.teacherCId());

        // 已有生效代课的实例：预览不再 409，而是产出绑定基准的修订凭证。
        Response response = previewRaw(admin, fx, fx.teacherDId());
        response.then().statusCode(200);
        assertThat(response.jsonPath().getLong("revisesRequestId")).isEqualTo(firstRequestId);
        assertThat(response.jsonPath().getString("revisionKind"))
                .isEqualTo(com.paiban.substitution.service.RevisionPolicy.CHANGE_SUBSTITUTE);
        // 左侧对照 = 调整前最新安排（含当前代课人丙），变化清单教师从丙到丁。
        assertThat(response.jsonPath().getLong("previousTeacherId")).isEqualTo(fx.teacherCId());
        assertThat(response.jsonPath().getLong(
                "changes[0].teacherIdBefore")).isEqualTo(fx.teacherCId());
        assertThat(response.jsonPath().getLong(
                "changes[0].teacherIdAfter")).isEqualTo(fx.teacherDId());
        // 预览仍然零申请、零生效。
        assertThat(substitutionRows.count()).isEqualTo(1);
        assertThat(requestRows.count()).isEqualTo(1);
    }

    @Test
    void revisingCoverKeepsCurrentTeacherUntilApprovedThenSwitches() {
        var fx = fx();
        String admin = adminToken();
        long firstRequestId = makeCover(admin, fx, fx.teacherCId());

        // 预览改为丁并提交审批：进待批队列，期间丙继续承担。
        long voucherId = previewVoucher(admin, fx, fx.teacherDId(), "丙临时有事");
        long requestId = consume(admin, voucherId, "submit-approval", null, 200);
        assertThat(classViewTeacher(admin, fx)).isEqualTo(String.valueOf(fx.teacherCId()));
        assertThat(substitutionRows.findByRequestId(firstRequestId).get(0).getStatus())
                .isEqualTo(Substitution.EFFECTIVE);
        assertThat(substitutionRows.findByRequestId(requestId)).isEmpty();
        SubstitutionRequest revision = requestRows.findById(requestId).orElseThrow();
        assertThat(revision.getStatus()).isEqualTo(SubstitutionRequest.PENDING_APPROVAL);
        assertThat(revision.getRevisesRequestId()).isEqualTo(firstRequestId);
        assertThat(revision.getPreviousTeacherId()).isEqualTo(fx.teacherCId());

        // 队列批准：与普通批准同一路径，生效后丁接管、丙的生效行退出当前安排。
        as(admin).body(Map.of("substituteTeacherId", fx.teacherDId(), "comment", "同意"))
                .when().post("/api/substitution-requests/" + requestId + "/approve")
                .then().statusCode(200)
                .body("status", equalTo("EFFECTIVE"));

        assertThat(classViewTeacher(admin, fx)).isEqualTo(String.valueOf(fx.teacherDId()));
        assertThat(substitutionRows.findByRequestId(firstRequestId).get(0).getStatus())
                .isEqualTo(Substitution.SUPERSEDED);
        List<Substitution> newRows = substitutionRows.findByRequestId(requestId);
        assertThat(newRows).hasSize(1);
        assertThat(newRows.get(0).getStatus()).isEqualTo(Substitution.EFFECTIVE);
        assertThat(newRows.get(0).getLessonDate()).isEqualTo(fx.lessonDate());
        assertThat(newRows.get(0).getSubstituteTeacherId()).isEqualTo(fx.teacherDId());
        // 修订申请的调整后教师如实落库；历史 A→C 与 C→D 两单都可追溯。
        assertThat(requestRows.findById(requestId).orElseThrow().getFinalTeacherId())
                .isEqualTo(fx.teacherDId());
        assertThat(requestRows.findById(firstRequestId).orElseThrow().getStatus())
                .isEqualTo(SubstitutionRequest.EFFECTIVE);

        // 历史展示（评审修复 P3）：被后续修订替代的生效行以 SUPERSEDED 出现在记录里，
        // 与当前生效行并列可追溯（规格第 7 节「区分…已被后续安排替代」）。
        // 记录接口默认只回 EFFECTIVE（既有调用方语义不变）；历史视图显式要 ALL。
        as(admin).when().get("/api/substitutions")
                .then().statusCode(200)
                .body("findAll { it.requestId == " + firstRequestId + " }.status",
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.contains(
                                Substitution.SUPERSEDED)));
        as(admin).queryParam("status", "ALL").when().get("/api/substitutions")
                .then().statusCode(200)
                .body("findAll { it.requestId == " + firstRequestId + " }.status",
                        org.hamcrest.Matchers.contains(Substitution.SUPERSEDED))
                .body("findAll { it.requestId == " + requestId + " }.status",
                        org.hamcrest.Matchers.contains(Substitution.EFFECTIVE));
    }

    @Test
    void rejectingRevisionKeepsPreviousCoverEffectiveAndAllowsRepreview() {
        var fx = fx();
        String admin = adminToken();
        long firstRequestId = makeCover(admin, fx, fx.teacherCId());

        // 预览 C→D 并提交审批，然后驳回。
        long voucherId = previewVoucher(admin, fx, fx.teacherDId(), "想换人");
        long requestId = consume(admin, voucherId, "submit-approval", null, 200);
        as(admin).body(Map.of("comment", "不同意换"))
                .when().post("/api/substitution-requests/" + requestId + "/reject")
                .then().statusCode(200)
                .body("status", equalTo("REJECTED"));

        // 红线：驳回后旧安排（丙）继续生效，零新增生效行。
        assertThat(classViewTeacher(admin, fx)).isEqualTo(String.valueOf(fx.teacherCId()));
        assertThat(substitutionRows.findByRequestId(firstRequestId).get(0).getStatus())
                .isEqualTo(Substitution.EFFECTIVE);
        assertThat(substitutionRows.count()).isEqualTo(1);

        // 旧单已结束：可以再次预览修订，不被「待处理单」挡住。
        previewRaw(admin, fx, fx.teacherDId()).then().statusCode(200);
    }

    @Test
    void restoreBaselineThroughRevisionKeepsHistoryTraceable() {
        var fx = fx();
        String admin = adminToken();
        long firstRequestId = makeCover(admin, fx, fx.teacherCId());

        // 恢复基线是一次经预览、校验的再次调整，不是删除生效行。
        Response preview = previewRaw(admin, fx, fx.teacherAId());
        preview.then().statusCode(200);
        assertThat(preview.jsonPath().getString("revisionKind"))
                .isEqualTo(com.paiban.substitution.service.RevisionPolicy.RESTORE_BASELINE);
        assertThat(preview.jsonPath().getLong("revisesRequestId")).isEqualTo(firstRequestId);
        long voucherId = preview.jsonPath().getLong("voucherId");

        long restoreRequestId = consume(admin, voucherId, "confirm", "代课取消", 200);
        assertThat(classViewTeacher(admin, fx)).isEqualTo(String.valueOf(fx.teacherAId()));

        // 丙的生效行退出当前安排但保留；恢复行代课教师 = 基线教师。
        assertThat(substitutionRows.findByRequestId(firstRequestId).get(0).getStatus())
                .isEqualTo(Substitution.SUPERSEDED);
        List<Substitution> restoreRows = substitutionRows.findByRequestId(restoreRequestId);
        assertThat(restoreRows).hasSize(1);
        assertThat(restoreRows.get(0).getStatus()).isEqualTo(Substitution.EFFECTIVE);
        assertThat(restoreRows.get(0).getSubstituteTeacherId()).isEqualTo(fx.teacherAId());
        assertThat(restoreRows.get(0).getLessonDate()).isEqualTo(fx.lessonDate());

        // 追溯：恢复修订绑定前驱；两笔申请的历史（状态流转）都完整保留。
        as(admin).when().get("/api/substitution-requests/" + restoreRequestId)
                .then().statusCode(200)
                .body("revisesRequestId", equalTo((int) firstRequestId))
                .body("revisionKind", equalTo("RESTORE_BASELINE"))
                .body("status", equalTo("EFFECTIVE"));
        as(admin).when().get("/api/substitution-requests/" + firstRequestId)
                .then().statusCode(200)
                .body("status", equalTo("EFFECTIVE"));
        as(admin).when().get("/api/substitution-requests/" + restoreRequestId + "/history")
                .then().statusCode(200)
                .body("toStatus", hasItem("EFFECTIVE"));
        as(admin).when().get("/api/substitution-requests/" + firstRequestId + "/history")
                .then().statusCode(200)
                .body("toStatus", hasItem("EFFECTIVE"));
    }

    @Test
    void statsCountOnlyCurrentArrangementAgainstBaselineTeacher() {
        var fx = fx();
        String admin = adminToken();

        // A→C：A 被代 1，C 代课 1。
        makeCover(admin, fx, fx.teacherCId());
        assertThat(substitutedOut(admin, fx.teacherAId())).isEqualTo(1);
        assertThat(substitutedIn(admin, fx.teacherCId())).isEqualTo(1);
        assertThat(substitutedOut(admin, fx.teacherCId())).isZero();

        // A→C→D：口径按当前有效安排——A 仍被代 1、D 代课 1、C 归零（中间安排不重复计数）。
        long voucherId = previewVoucher(admin, fx, fx.teacherDId(), "改丁");
        long revisionId = consume(admin, voucherId, "confirm", null, 200);
        assertThat(substitutedOut(admin, fx.teacherAId())).isEqualTo(1);
        assertThat(substitutedIn(admin, fx.teacherDId())).isEqualTo(1);
        assertThat(substitutedIn(admin, fx.teacherCId())).isZero();
        assertThat(substitutedOut(admin, fx.teacherDId())).isZero();

        // 恢复 A：本次代入/代出全部归零；历史仍存在（上一测试已断言追溯）。
        long restoreVoucher = previewVoucher(admin, fx, fx.teacherAId(), "恢复");
        consume(admin, restoreVoucher, "confirm", null, 200);
        assertThat(substitutedOut(admin, fx.teacherAId())).isZero();
        assertThat(substitutedIn(admin, fx.teacherDId())).isZero();
        assertThat(substitutedIn(admin, fx.teacherCId())).isZero();
        // 被替代的申请行与恢复行都还在（历史只追加）。
        assertThat(substitutionRows.count()).isEqualTo(3);
        assertThat(requestRows.findById(revisionId)).isPresent();
    }

    private long substitutedOut(String admin, long teacherId) {
        return as(admin).queryParam("teacherId", teacherId)
                .when().get("/api/substitutions/stats")
                .then().statusCode(200)
                .extract().jsonPath().getLong("substitutedOut");
    }

    private long substitutedIn(String admin, long teacherId) {
        return as(admin).queryParam("teacherId", teacherId)
                .when().get("/api/substitutions/stats")
                .then().statusCode(200)
                .extract().jsonPath().getLong("substitutedIn");
    }

    // ------------------------------------------------------------- 通知与去重

    @Test
    void revisionNotifiesReleasedAndAssignedTeachersWithoutDuplicates() {
        var fx = fx();
        String admin = adminToken();
        String coverTeacherToken = userForTeacher("rev_c", fx.teacherCId());
        String nextTeacherToken = userForTeacher("rev_d", fx.teacherDId());

        long firstRequestId = makeCover(admin, fx, fx.teacherCId());
        assertThat(unreadCount(coverTeacherToken)).isEqualTo(1);
        assertThat(unreadCount(nextTeacherToken)).isZero();

        // 修订生效：被解除的丙与接替的丁都要收到通知，文案指明被取代的申请（基准单）。
        long voucherId = previewVoucher(admin, fx, fx.teacherDId(), "丙有事改丁");
        long revisionId = consume(admin, voucherId, "confirm", null, 200);
        assertThat(revisionId).isNotEqualTo(firstRequestId);
        assertThat(unreadCount(coverTeacherToken)).isEqualTo(2);
        assertThat(unreadCount(nextTeacherToken)).isEqualTo(1);
        assertThat(latestContent(coverTeacherToken)).contains("取代申请 #" + firstRequestId);
        assertThat(latestContent(nextTeacherToken)).contains("取代申请 #" + firstRequestId);

        // 相同修订重试（同凭证同参数）：幂等返回第一次结果，通知不重复。
        as(admin).body(Map.of())
                .when().post("/api/substitution-workbench/previews/" + voucherId + "/confirm")
                .then().statusCode(200)
                .body("replayed", equalTo(true));
        assertThat(unreadCount(coverTeacherToken)).isEqualTo(2);
        assertThat(unreadCount(nextTeacherToken)).isEqualTo(1);
    }

    // --------------------------------------------------------- 单活跃与竞争

    @Test
    void pendingRevisionBlocksParallelModification() {
        var fx = fx();
        String admin = adminToken();
        makeCover(admin, fx, fx.teacherCId());
        long voucherId = previewVoucher(admin, fx, fx.teacherDId(), "想改丁");
        long requestId = consume(admin, voucherId, "submit-approval", null, 200);

        // 已有活跃待审批修改：阻止并行新单并引导查看原单，不静默取消他人申请。
        as(admin).body(Map.of(
                        "entryId", fx.entryAId(),
                        "lessonDate", fx.lessonDate().toString(),
                        "substituteTeacherId", fx.teacherAId()))
                .when().post("/api/substitution-workbench/previews")
                .then().statusCode(409)
                .body("message", org.hamcrest.Matchers.containsString("#" + requestId));
        assertThat(classViewTeacher(admin, fx)).isEqualTo(String.valueOf(fx.teacherCId()));
    }

    @Test
    void twoRevisionsFromSameBaseAllowOnlyOneToTakeEffect() {
        var fx = fx();
        String admin = adminToken();
        long baseRequestId = makeCover(admin, fx, fx.teacherCId());

        // 两位管理员基于同一旧版本（同一快照）各准备一个修订：换代课人 vs 恢复基线。
        long changeVoucher = previewVoucher(admin, fx, fx.teacherDId(), "改丁");
        long restoreVoucher = previewVoucher(admin, fx, fx.teacherAId(), "恢复甲");

        long changeRequestId = consume(admin, changeVoucher, "confirm", null, 200);
        assertThat(classViewTeacher(admin, fx)).isEqualTo(String.valueOf(fx.teacherDId()));

        // 后确认者得到明确冲突：最多一个修订生效，上一有效安排保持完整。
        as(admin).body(Map.of())
                .when().post("/api/substitution-workbench/previews/" + restoreVoucher + "/confirm")
                .then().statusCode(409);
        assertThat(classViewTeacher(admin, fx)).isEqualTo(String.valueOf(fx.teacherDId()));
        assertThat(substitutionRows.count()).isEqualTo(2);
        assertThat(substitutionRows.findByRequestId(baseRequestId).get(0).getStatus())
                .isEqualTo(Substitution.SUPERSEDED);
        assertThat(substitutionRows.findByRequestId(changeRequestId))
                .allSatisfy(row -> assertThat(row.getStatus()).isEqualTo(Substitution.EFFECTIVE));

        // 冲突后以当前有效安排重新预览：基准换成丁的申请，恢复基线仍然可行。
        Response fresh = previewRaw(admin, fx, fx.teacherAId());
        fresh.then().statusCode(200);
        assertThat(fresh.jsonPath().getLong("revisesRequestId")).isEqualTo(changeRequestId);
        assertThat(fresh.jsonPath().getString("revisionKind"))
                .isEqualTo(com.paiban.substitution.service.RevisionPolicy.RESTORE_BASELINE);
    }

    // ------------------------------------------------------------- 四视图与导出

    @Test
    void allViewsAndExportsFollowTheCurrentArrangement() throws Exception {
        var fx = fx();
        String admin = adminToken();
        makeCover(admin, fx, fx.teacherCId());
        long voucherId = previewVoucher(admin, fx, fx.teacherDId(), "改丁");
        consume(admin, voucherId, "confirm", null, 200);
        String date = fx.lessonDate().toString();

        // 班级视图与全校视图：丁接管。
        as(admin).queryParam("date", date)
                .when().get("/api/timetable/class/" + fx.classAId())
                .then().statusCode(200)
                .body("find { it.id == " + fx.entryAId() + " }.teacherId",
                        equalTo((int) fx.teacherDId()));
        as(admin).queryParam("view", "latest").queryParam("date", date)
                .when().get("/api/timetable/school")
                .then().statusCode(200)
                .body("entries.find { it.id == " + fx.entryAId() + " }.teacherId",
                        equalTo((int) fx.teacherDId()));
        // 教师视图：新代课人恰好看到一次；被解除的丙与基线甲都不再看到这节课。
        as(admin).queryParam("view", "latest").queryParam("date", date)
                .when().get("/api/timetable/teacher/" + fx.teacherDId())
                .then().statusCode(200)
                .body("size()", equalTo(1))
                .body("[0].id", equalTo((int) fx.entryAId()));
        as(admin).queryParam("view", "latest").queryParam("date", date)
                .when().get("/api/timetable/teacher/" + fx.teacherCId())
                .then().statusCode(200)
                .body("size()", equalTo(0));
        as(admin).queryParam("view", "latest").queryParam("date", date)
                .when().get("/api/timetable/teacher/" + fx.teacherAId())
                .then().statusCode(200)
                .body("size()", equalTo(0));
        // 教室视图：教室未变，课仍在原教室。
        as(admin).queryParam("view", "latest").queryParam("date", date)
                .when().get("/api/timetable/room/" + fx.roomAId())
                .then().statusCode(200)
                .body("find { it.id == " + fx.entryAId() + " }.id", equalTo((int) fx.entryAId()));

        // 导出走同一读取模型：丙的导出为空，丁的导出恰有课。
        byte[] coverExcel = as(admin).queryParam("view", "latest").queryParam("date", date)
                .when().get("/api/timetable/teacher/" + fx.teacherCId() + "/export/excel")
                .then().statusCode(200).extract().asByteArray();
        assertThat(excelHasAnyLesson(coverExcel)).isFalse();
        byte[] nextExcel = as(admin).queryParam("view", "latest").queryParam("date", date)
                .when().get("/api/timetable/teacher/" + fx.teacherDId() + "/export/excel")
                .then().statusCode(200).extract().asByteArray();
        assertThat(excelHasAnyLesson(nextExcel)).isTrue();
    }

    // ------------------------------------------------------------------ helpers

    private String userForTeacher(String suffix, long teacherId) {
        String username = "rev_user_" + suffix + "_" + TestData.suffix();
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

    private static boolean excelHasAnyLesson(byte[] excel) throws Exception {
        try (var workbook = org.apache.poi.ss.usermodel.WorkbookFactory.create(
                new java.io.ByteArrayInputStream(excel))) {
            var sheet = workbook.getSheetAt(0);
            for (int r = 3; r <= sheet.getLastRowNum(); r++) {
                var row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }
                for (int c = 1; c < row.getLastCellNum(); c++) {
                    var cell = row.getCell(c);
                    if (cell != null
                            && cell.getCellType() == org.apache.poi.ss.usermodel.CellType.STRING
                            && !cell.getStringCellValue().isBlank()) {
                        return true;
                    }
                }
            }
            return false;
        }
    }
}
