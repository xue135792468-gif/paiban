package com.paiban.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import com.paiban.scheduling.domain.ConstraintEntry;
import com.paiban.scheduling.domain.ConstraintEntryRepository;
import com.paiban.scheduling.domain.ConstraintType;
import com.paiban.scheduling.domain.DraftLesson;
import com.paiban.scheduling.domain.DraftLessonRepository;
import com.paiban.scheduling.domain.TimetableDraft;
import com.paiban.scheduling.domain.TimetableDraftRepository;
import com.paiban.scheduling.service.ConstraintConfigService;
import com.paiban.scheduling.solver.ConstraintSettings;
import com.paiban.substitution.plan.PlanSnapshot;
import com.paiban.substitution.service.PlanSnapshotFactory;
import com.paiban.support.ApiIntegrationTest;
import com.paiban.support.TestData;
import io.restassured.response.Response;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Ticket 04 backend contract: user groups are batch selectors. A rule stores the group
 * id, the shared snapshot expands it into members, so a member change reaches every
 * referencing rule without resaving it. Deletion of a referenced group is refused
 * unless the caller asks to delete the referencing rule cards too, and the whole
 * operation is recoverable through the 7-day recycle bin.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RuleGroupIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private ConstraintConfigService configService;

    @Autowired
    private ConstraintEntryRepository entryRepository;

    @Autowired
    private TimetableDraftRepository draftRepository;

    @Autowired
    private DraftLessonRepository draftLessonRepository;

    @Autowired
    private PlanSnapshotFactory planSnapshots;

    // ------------------------------------------------------- group CRUD

    @Test
    void createsTeacherGroupAndRejectsADuplicateLiveName() {
        String token = adminToken();
        long semesterId = semesterWithPeriods(token);
        long first = TestData.teacher(token, "教导主任甲" + TestData.suffix());
        long second = TestData.teacher(token, "教导主任乙" + TestData.suffix());
        String name = "教导主任" + TestData.suffix();

        Response created = as(token).body(Map.of(
                        "type", "TEACHER",
                        "name", name,
                        "members", List.of(
                                Map.of("teacherId", first),
                                Map.of("teacherId", second))))
                .when().post("/api/rule-groups");
        created.then().statusCode(200)
                .body("type", org.hamcrest.Matchers.equalTo("TEACHER"))
                .body("name", org.hamcrest.Matchers.equalTo(name))
                .body("members.size()", org.hamcrest.Matchers.equalTo(2));
        long groupId = created.jsonPath().getLong("id");

        List<Map<String, Object>> members = as(token)
                .queryParam("type", "TEACHER")
                .when().get("/api/rule-groups")
                .then().statusCode(200).extract()
                .jsonPath().getList("find { it.id == " + groupId + " }.members");
        assertThat(members).hasSize(2);
        assertThat(members).extracting(row -> ((Number) row.get("objectId")).longValue())
                .containsExactlyInAnyOrder(first, second);
        assertThat(members).extracting(row -> row.get("kind"))
                .containsOnly("TEACHER");

        // Same live name is refused, but the semester is irrelevant for a global group.
        as(token).body(Map.of(
                        "type", "TEACHER",
                        "name", name,
                        "semesterId", semesterId,
                        "members", List.of(Map.of("teacherId", first))))
                .when().post("/api/rule-groups")
                .then().statusCode(400);
        as(token).body(Map.of(
                        "type", "TEACHER",
                        "name", name,
                        "members", List.of(Map.of("teacherId", first))))
                .when().post("/api/rule-groups")
                .then().statusCode(409);
    }

    @Test
    void timeGroupNeedsASemesterAndExistingCoordinates() {
        String token = adminToken();
        long semesterId = semesterWithPeriods(token);
        String name = "上午" + TestData.suffix();

        as(token).body(Map.of(
                        "type", "TIME",
                        "name", name,
                        "members", List.of(Map.of("dayOfWeek", 1, "periodNo", 1))))
                .when().post("/api/rule-groups")
                .then().statusCode(400);
        as(token).queryParam("type", "TIME")
                .when().get("/api/rule-groups")
                .then().statusCode(400);

        Response created = as(token).body(Map.of(
                        "type", "TIME",
                        "name", name,
                        "semesterId", semesterId,
                        "members", List.of(Map.of("dayOfWeek", 1, "periodNo", 1))))
                .when().post("/api/rule-groups");
        created.then().statusCode(200)
                .body("semesterId", org.hamcrest.Matchers.equalTo((int) semesterId))
                .body("members[0].kind", org.hamcrest.Matchers.equalTo("TIME"));

        // The semester also carries the two prebuilt 「上午」/「下午」 groups (ticket 06),
        // so the user group is asserted by id rather than by list size.
        as(token).queryParam("type", "TIME").queryParam("semesterId", semesterId)
                .when().get("/api/rule-groups")
                .then().statusCode(200)
                .body("find { it.id == " + created.jsonPath().getLong("id") + " }.name",
                        org.hamcrest.Matchers.equalTo(name));
    }

    // ---------------------------------------------------- snapshot expansion

    @Test
    void aGroupRuleExpandsIntoSettingsMembers() {
        String token = adminToken();
        long semesterId = semesterWithPeriods(token);
        long first = TestData.teacher(token, "展开甲" + TestData.suffix());
        long second = TestData.teacher(token, "展开乙" + TestData.suffix());
        long groupId = createTeacherGroup(token, "展开组" + TestData.suffix(), first, second);

        saveTeacherGroupRule(token, semesterId, null, groupId, 1, 1).then().statusCode(200);

        ConstraintSettings settings = configService.settings(semesterId);
        assertThat(settings.requirements(ConstraintType.TEACHER_UNAVAILABLE)).hasSize(1);
        assertThat(settings.requirements(ConstraintType.TEACHER_UNAVAILABLE).get(0).entries())
                .hasSize(2);
        assertThat(teacherIds(settings)).containsExactlyInAnyOrder(first, second);
        // The stored row keeps the group reference; expansion happens only in the snapshot.
        assertThat(entryRepository.findBySemesterIdAndTypeOrderByIdAsc(
                semesterId, "TEACHER_UNAVAILABLE"))
                .hasSize(1)
                .allMatch(row -> row.getParamsJson().contains("\"teacherGroupId\":" + groupId));
    }

    @Test
    void changingMembersTakesEffectWithoutResavingTheRule() {
        String token = adminToken();
        long semesterId = semesterWithPeriods(token);
        long first = TestData.teacher(token, "改组成员甲" + TestData.suffix());
        long second = TestData.teacher(token, "改组成员乙" + TestData.suffix());
        long third = TestData.teacher(token, "改组成员丙" + TestData.suffix());
        String name = "改组成员组" + TestData.suffix();
        long groupId = createTeacherGroup(token, name, first, second);
        saveTeacherGroupRule(token, semesterId, null, groupId, 2, 2).then().statusCode(200);
        assertThat(teacherIds(configService.settings(semesterId)))
                .containsExactlyInAnyOrder(first, second);

        as(token).body(Map.of(
                        "type", "TEACHER",
                        "name", name,
                        "members", List.of(
                                Map.of("teacherId", second),
                                Map.of("teacherId", third))))
                .when().put("/api/rule-groups/" + groupId)
                .then().statusCode(200);

        assertThat(teacherIds(configService.settings(semesterId)))
                .containsExactlyInAnyOrder(second, third);
    }

    @Test
    void disabledOrDeletedMembersLeaveTheSnapshot() {
        String token = adminToken();
        long semesterId = semesterWithPeriods(token);
        long first = TestData.teacher(token, "停用成员甲" + TestData.suffix());
        long second = TestData.teacher(token, "停用成员乙" + TestData.suffix());
        long groupId = createTeacherGroup(token, "停用成员组" + TestData.suffix(), first, second);
        saveTeacherGroupRule(token, semesterId, null, groupId, 3, 3).then().statusCode(200);

        as(token).when().post("/api/teachers/" + first + "/disable")
                .then().statusCode(200);
        ConstraintSettings afterDisable = configService.settings(semesterId);
        assertThat(teacherIds(afterDisable)).containsExactly(second);

        // The last live member is deleted: the requirement silently expands to nothing.
        as(token).when().delete("/api/teachers/" + second)
                .then().statusCode(200);
        ConstraintSettings afterDelete = configService.settings(semesterId);
        assertThat(afterDelete.requirements(ConstraintType.TEACHER_UNAVAILABLE)).isEmpty();
    }

    // ------------------------------------------------------- save validation

    @Test
    void savingRejectsInvalidOrAmbiguousGroupReferences() {
        String token = adminToken();
        long semesterId = semesterWithPeriods(token);
        long teacher = TestData.teacher(token, "校验教师" + TestData.suffix());
        long course = TestData.course(token, "校验课程" + TestData.suffix());
        long teacherGroup = createTeacherGroup(
                token, "校验教师组" + TestData.suffix(), teacher);

        // Unknown group, wrong type, deleted group.
        assertBadRequest(saveTeacherGroupRule(
                token, semesterId, null, 9_999_999L, 1, 1), "分组");
        long courseGroup = createCourseGroup(token, "校验课程组" + TestData.suffix(), course);
        assertBadRequest(saveTeacherGroupRule(
                token, semesterId, null, courseGroup, 1, 1), "类型不匹配");
        long deletedGroup = createTeacherGroup(
                token, "待删教师组" + TestData.suffix(), teacher);
        as(token).when().delete("/api/rule-groups/" + deletedGroup)
                .then().statusCode(200);
        assertBadRequest(saveTeacherGroupRule(
                token, semesterId, null, deletedGroup, 1, 1), "不存在或已被删除");

        // A concrete object and its group may not be named together.
        Map<String, Object> both = new LinkedHashMap<>();
        both.put("teacherId", teacher);
        both.put("teacherGroupId", teacherGroup);
        both.put("dayOfWeek", 1);
        both.put("periodNo", 1);
        assertBadRequest(saveSingle(token, semesterId, "TEACHER_UNAVAILABLE", both), "不能同时指定");

        // A group key the type does not support is not silently ignored.
        Map<String, Object> wrongType = new LinkedHashMap<>();
        wrongType.put("teacherGroupId", teacherGroup);
        wrongType.put("dayOfWeek", 1);
        wrongType.put("periodNo", 1);
        assertBadRequest(saveSingle(token, semesterId, "CLASS_UNAVAILABLE", wrongType),
                "不能用于");
    }

    // ------------------------------------------------------ delete / restore

    @Test
    void deletingAReferencedGroupOffersTheRulesAndCanDeleteThemTogether() {
        String token = adminToken();
        long semesterId = semesterWithPeriods(token);
        long first = TestData.teacher(token, "删组甲" + TestData.suffix());
        long second = TestData.teacher(token, "删组乙" + TestData.suffix());
        String name = "删组组" + TestData.suffix();
        long groupId = createTeacherGroup(token, name, first, second);
        saveTeacherGroupRule(token, semesterId, null, groupId, 4, 4).then().statusCode(200);

        as(token).when().get("/api/rule-groups/" + groupId + "/delete-impact")
                .then().statusCode(200)
                .body("rules.size()", org.hamcrest.Matchers.equalTo(1))
                .body("message", org.hamcrest.Matchers.containsString("1 条规则"));

        as(token).when().delete("/api/rule-groups/" + groupId + "?mode=DELETE")
                .then().statusCode(409)
                .body("rules.size()", org.hamcrest.Matchers.equalTo(1))
                .body("message", org.hamcrest.Matchers.notNullValue());
        // Nothing was written by the refused attempt.
        assertThat(as(token).queryParam("type", "TEACHER")
                .when().get("/api/rule-groups")
                .then().statusCode(200).extract().jsonPath().getList("id"))
                .contains((int) groupId);

        Response deleted = as(token)
                .when().delete("/api/rule-groups/" + groupId + "?mode=WITH_RULES");
        deleted.then().statusCode(200)
                .body("deletedRules", org.hamcrest.Matchers.equalTo(1))
                .body("batchIds.size()", org.hamcrest.Matchers.greaterThanOrEqualTo(2));
        List<String> batchIds = deleted.jsonPath().getList("batchIds", String.class);
        assertThat(batchIds).hasSize(2);

        assertThat(as(token).queryParam("type", "TEACHER")
                .when().get("/api/rule-groups")
                .then().statusCode(200).extract().jsonPath().getList("id"))
                .doesNotContain((int) groupId);
        assertThat(configService.settings(semesterId)
                .requirements(ConstraintType.TEACHER_UNAVAILABLE)).isEmpty();
        assertThat(entryRepository.findBySemesterIdAndTypeOrderByIdAsc(
                semesterId, "TEACHER_UNAVAILABLE"))
                .hasSize(1)
                .allMatch(row -> row.getDeletedAt() != null);

        Map<String, String> batches = batchesByType(token);
        assertThat(batches).containsKeys("RULE_GROUP", "CONSTRAINT_RULE");

        // The group and the rule card are separate recycle batches: restore the group
        // first, then the rule, and watch the expanded snapshot come back.
        as(token).when().post("/api/recent-deletions/" + batches.get("RULE_GROUP") + "/restore")
                .then().statusCode(200);
        as(token).queryParam("type", "TEACHER")
                .when().get("/api/rule-groups")
                .then().statusCode(200)
                .body("find { it.id == " + groupId + " }.name",
                        org.hamcrest.Matchers.equalTo(name));
        // The rule is still in the bin, so the group expands nothing.
        assertThat(configService.settings(semesterId)
                .requirements(ConstraintType.TEACHER_UNAVAILABLE)).isEmpty();

        as(token).when().post("/api/recent-deletions/" + batches.get("CONSTRAINT_RULE") + "/restore")
                .then().statusCode(200);
        assertThat(teacherIds(configService.settings(semesterId)))
                .containsExactlyInAnyOrder(first, second);
    }

    // ---------------------------------------------------- draft + substitution

    @Test
    void draftValidationSeesTheExpandedGroupRule() {
        String token = adminToken();
        long semesterId = semesterWithPeriods(token);
        long member = TestData.teacher(token, "草稿组成员" + TestData.suffix());
        long outsider = TestData.teacher(token, "草稿组外" + TestData.suffix());
        long groupId = createTeacherGroup(token, "草稿组" + TestData.suffix(), member);
        saveTeacherGroupRule(token, semesterId, null, groupId, 5, 7).then().statusCode(200);

        Map<String, Long> periods = periodIds(token, semesterId);
        long course = TestData.course(token, "草稿课" + TestData.suffix());
        long gradeId = TestData.grade(token, semesterId, "高一" + TestData.suffix());
        long classId = TestData.adminClass(token, semesterId, gradeId, "高一1班" + TestData.suffix());

        long memberDraft = draft(semesterId, classId, course, member, periods.get("5-7"));
        Response memberValidation = validate(token, memberDraft);
        assertThat(memberValidation.jsonPath().getList("conflicts.type", String.class))
                .contains("TEACHER_UNAVAILABLE");

        long outsiderDraft = draft(semesterId, classId, course, outsider, periods.get("5-7"));
        Response outsiderValidation = validate(token, outsiderDraft);
        assertThat(outsiderValidation.jsonPath().getList("conflicts.type", String.class))
                .doesNotContain("TEACHER_UNAVAILABLE");
    }

    /**
     * The substitution planner reads {@link PlanSnapshotFactory}'s snapshot, which goes
     * through the same {@code ConstraintService.settings} seam. Building the snapshot is
     * the contract-level proof; a full substitution request needs published entries and
     * dates, which would test the substitution feature rather than group expansion.
     */
    @Test
    void substitutionSnapshotUsesTheSameExpandedGroups() {
        String token = adminToken();
        long semesterId = semesterWithPeriods(token);
        long first = TestData.teacher(token, "调代甲" + TestData.suffix());
        long second = TestData.teacher(token, "调代乙" + TestData.suffix());
        long groupId = createTeacherGroup(token, "调代组" + TestData.suffix(), first, second);
        saveTeacherGroupRule(token, semesterId, null, groupId, 5, 4).then().statusCode(200);

        PlanSnapshot snapshot = planSnapshots.build(semesterId);
        assertThat(snapshot.settings().requirements(ConstraintType.TEACHER_UNAVAILABLE))
                .hasSize(1)
                .allSatisfy(requirement -> assertThat(requirement.entries()).hasSize(2));
        assertThat(teacherIds(snapshot.settings())).containsExactlyInAnyOrder(first, second);
    }

    // -------------------------------------------------------- course groups

    @Test
    void createsCourseGroupAndRejectsDuplicateNameOrWrongMemberType() {
        String token = adminToken();
        long first = TestData.course(token, "体育" + TestData.suffix());
        long second = TestData.course(token, "美术" + TestData.suffix());
        String name = "技能课" + TestData.suffix();

        Response created = as(token).body(Map.of(
                        "type", "COURSE",
                        "name", name,
                        "members", List.of(
                                Map.of("courseId", first),
                                Map.of("courseId", second))))
                .when().post("/api/rule-groups");
        created.then().statusCode(200)
                .body("type", org.hamcrest.Matchers.equalTo("COURSE"))
                .body("semesterId", org.hamcrest.Matchers.nullValue())
                .body("members.size()", org.hamcrest.Matchers.equalTo(2));
        long groupId = created.jsonPath().getLong("id");

        List<Map<String, Object>> members = as(token)
                .queryParam("type", "COURSE")
                .when().get("/api/rule-groups")
                .then().statusCode(200).extract()
                .jsonPath().getList("find { it.id == " + groupId + " }.members");
        assertThat(members).extracting(row -> ((Number) row.get("objectId")).longValue())
                .containsExactlyInAnyOrder(first, second);
        assertThat(members).extracting(row -> row.get("kind")).containsOnly("COURSE");

        // Course groups are global: a semester id is refused, a live duplicate name is 409.
        as(token).body(Map.of(
                        "type", "COURSE",
                        "name", name,
                        "semesterId", 1,
                        "members", List.of(Map.of("courseId", first))))
                .when().post("/api/rule-groups")
                .then().statusCode(400);
        as(token).body(Map.of(
                        "type", "COURSE",
                        "name", name,
                        "members", List.of(Map.of("courseId", first))))
                .when().post("/api/rule-groups")
                .then().statusCode(409);

        // A teacher member on a course group is rejected by the shared validation.
        long teacher = TestData.teacher(token, "课程组成员校验" + TestData.suffix());
        assertBadRequest(as(token).body(Map.of(
                        "type", "COURSE",
                        "name", "错误成员组" + TestData.suffix(),
                        "members", List.of(Map.of("teacherId", teacher))))
                .when().post("/api/rule-groups")
                .then().statusCode(400).extract().response(), "不能指定教师");
    }

    @Test
    void courseGroupThresholdExpandsIntoSettingsAndTracksMemberChanges() {
        String token = adminToken();
        long semesterId = semesterWithPeriods(token);
        long first = TestData.course(token, "体育甲" + TestData.suffix());
        long second = TestData.course(token, "美术甲" + TestData.suffix());
        long third = TestData.course(token, "音乐甲" + TestData.suffix());
        String name = "技能课甲" + TestData.suffix();
        long groupId = createCourseGroup(token, name, first, second);

        saveCourseGroupRule(token, semesterId, "COURSE_MAX_PER_DAY", Map.of(
                        "courseGroupId", groupId, "maxPerDay", 1))
                .then().statusCode(200);

        ConstraintSettings settings = configService.settings(semesterId);
        assertThat(settings.requirements(ConstraintType.COURSE_MAX_PER_DAY)).hasSize(1);
        assertThat(settings.entries(ConstraintType.COURSE_MAX_PER_DAY)).hasSize(2);
        assertThat(courseIds(settings, ConstraintType.COURSE_MAX_PER_DAY))
                .containsExactlyInAnyOrder(first, second);
        assertThat(settings.entries(ConstraintType.COURSE_MAX_PER_DAY))
                .allSatisfy(row -> {
                    assertThat(row).doesNotContainKey("courseGroupId");
                    assertThat(ConstraintSettings.longValue(row.get("maxPerDay"))).isEqualTo(1L);
                });
        // The stored rows keep the group reference; expansion happens only in the snapshot.
        assertThat(entryRepository.findBySemesterIdAndTypeOrderByIdAsc(
                semesterId, "COURSE_MAX_PER_DAY"))
                .hasSize(1)
                .allMatch(row -> row.getParamsJson().contains("\"courseGroupId\":" + groupId));

        // Editing members reaches the rule without resaving it.
        as(token).body(Map.of(
                        "type", "COURSE",
                        "name", name,
                        "members", List.of(
                                Map.of("courseId", second),
                                Map.of("courseId", third))))
                .when().put("/api/rule-groups/" + groupId)
                .then().statusCode(200);
        assertThat(courseIds(configService.settings(semesterId), ConstraintType.COURSE_MAX_PER_DAY))
                .containsExactlyInAnyOrder(second, third);
    }

    @Test
    void courseUnavailableGroupReachesSettingsAndDraftValidation() {
        String token = adminToken();
        long semesterId = semesterWithPeriods(token);
        long memberCourse = TestData.course(token, "体育乙" + TestData.suffix());
        long outsiderCourse = TestData.course(token, "数学乙" + TestData.suffix());
        long groupId = createCourseGroup(token, "技能课乙" + TestData.suffix(), memberCourse);
        saveCourseGroupRule(token, semesterId, "COURSE_UNAVAILABLE", Map.of(
                        "courseGroupId", groupId, "dayOfWeek", 5, "periodNo", 7))
                .then().statusCode(200);

        ConstraintSettings settings = configService.settings(semesterId);
        assertThat(settings.entries(ConstraintType.COURSE_UNAVAILABLE)).hasSize(1);
        assertThat(courseIds(settings, ConstraintType.COURSE_UNAVAILABLE))
                .containsExactly(memberCourse);

        Map<String, Long> periods = periodIds(token, semesterId);
        long gradeId = TestData.grade(token, semesterId, "高一" + TestData.suffix());
        long classId = TestData.adminClass(token, semesterId, gradeId, "高一1班" + TestData.suffix());
        long teacher = TestData.teacher(token, "课程草稿教师" + TestData.suffix());

        long memberDraft = draft(semesterId, classId, memberCourse, teacher, periods.get("5-7"));
        assertThat(validate(token, memberDraft).jsonPath()
                .getList("conflicts.type", String.class))
                .contains("COURSE_UNAVAILABLE");

        long outsiderDraft = draft(semesterId, classId, outsiderCourse, teacher, periods.get("5-7"));
        assertThat(validate(token, outsiderDraft).jsonPath()
                .getList("conflicts.type", String.class))
                .doesNotContain("COURSE_UNAVAILABLE");
    }

    @Test
    void deletingAReferencedCourseGroupRefusesUnlessRulesGoTogether() {
        String token = adminToken();
        long semesterId = semesterWithPeriods(token);
        long first = TestData.course(token, "体育丙" + TestData.suffix());
        long second = TestData.course(token, "美术丙" + TestData.suffix());
        String name = "技能课丙" + TestData.suffix();
        long groupId = createCourseGroup(token, name, first, second);
        saveCourseGroupRule(token, semesterId, "COURSE_MAX_PER_DAY", Map.of(
                        "courseGroupId", groupId, "maxPerDay", 1))
                .then().statusCode(200);

        as(token).when().get("/api/rule-groups/" + groupId + "/delete-impact")
                .then().statusCode(200)
                .body("rules.size()", org.hamcrest.Matchers.equalTo(1))
                .body("type", org.hamcrest.Matchers.equalTo("COURSE"));

        as(token).when().delete("/api/rule-groups/" + groupId)
                .then().statusCode(409)
                .body("rules.size()", org.hamcrest.Matchers.equalTo(1));

        as(token).when().delete("/api/rule-groups/" + groupId + "?mode=WITH_RULES")
                .then().statusCode(200)
                .body("deletedRules", org.hamcrest.Matchers.equalTo(1));

        assertThat(configService.settings(semesterId)
                .requirements(ConstraintType.COURSE_MAX_PER_DAY)).isEmpty();
        assertThat(entryRepository.findBySemesterIdAndTypeOrderByIdAsc(
                semesterId, "COURSE_MAX_PER_DAY"))
                .hasSize(1)
                .allMatch(row -> row.getDeletedAt() != null);
    }

    // ------------------------------------------------------------------ helpers

    private void assertBadRequest(Response response, String expected) {
        response.then().statusCode(400);
        assertThat(response.asString()).contains(expected);
    }

    private long createTeacherGroup(String token, String name, long... teacherIds) {
        List<Map<String, Object>> members = new ArrayList<>();
        for (long id : teacherIds) {
            members.add(Map.of("teacherId", id));
        }
        Response response = as(token).body(Map.of(
                        "type", "TEACHER", "name", name, "members", members))
                .when().post("/api/rule-groups");
        response.then().statusCode(200);
        return response.jsonPath().getLong("id");
    }

    private long createCourseGroup(String token, String name, long... courseIds) {
        List<Map<String, Object>> members = new ArrayList<>();
        for (long id : courseIds) {
            members.add(Map.of("courseId", id));
        }
        Response response = as(token).body(Map.of(
                        "type", "COURSE", "name", name, "members", members))
                .when().post("/api/rule-groups");
        response.then().statusCode(200);
        return response.jsonPath().getLong("id");
    }

    private Response saveCourseGroupRule(
            String token, long semesterId, String type, Map<String, Object> params) {
        return as(token).queryParam("semesterId", semesterId)
                .body(Map.of("configs", List.of(config(
                        type, params, "group-" + params.get("courseGroupId")))))
                .when().put("/api/constraints");
    }

    private Response saveTeacherGroupRule(
            String token, long semesterId, Long baseVersion, long groupId,
            int dayOfWeek, int periodNo) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("teacherGroupId", groupId);
        params.put("dayOfWeek", dayOfWeek);
        params.put("periodNo", periodNo);
        var request = as(token).queryParam("semesterId", semesterId);
        if (baseVersion != null) {
            request = request.queryParam("baseVersion", baseVersion);
        }
        return request.body(Map.of("configs", List.of(config(
                        "TEACHER_UNAVAILABLE", params, "group-" + groupId))))
                .when().put("/api/constraints");
    }

    private Response saveSingle(
            String token, long semesterId, String type, Map<String, Object> params) {
        return as(token).queryParam("semesterId", semesterId)
                .body(Map.of("configs", List.of(config(type, params, null))))
                .when().put("/api/constraints");
    }

    private Map<String, Object> config(
            String type, Map<String, Object> params, String groupKey) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("params", params);
        row.put("hard", true);
        if (groupKey != null) {
            row.put("groupKey", groupKey);
        }
        return Map.of(
                "type", type,
                "weight", 100,
                "hard", true,
                "enabled", true,
                "entries", List.of(row));
    }

    private List<Long> teacherIds(ConstraintSettings settings) {
        return settings.entries(ConstraintType.TEACHER_UNAVAILABLE).stream()
                .map(row -> ConstraintSettings.longValue(row.get("teacherId")))
                .toList();
    }

    private List<Long> courseIds(ConstraintSettings settings, ConstraintType type) {
        return settings.entries(type).stream()
                .map(row -> ConstraintSettings.longValue(row.get("courseId")))
                .toList();
    }

    private Map<String, String> batchesByType(String token) {
        List<Map<String, Object>> rows = as(token).when().get("/api/recent-deletions")
                .then().statusCode(200).extract().jsonPath().getList("$");
        Map<String, String> byType = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            byType.put(String.valueOf(row.get("entityType")), String.valueOf(row.get("batchId")));
        }
        return byType;
    }

    private long draft(
            long semesterId, long classId, long courseId, long teacherId, long periodId) {
        TimetableDraft draft = new TimetableDraft();
        draft.setSemesterId(semesterId);
        draft.setName("分组草稿" + TestData.suffix());
        draft.setStatus("DRAFT");
        draft.setCreatedBy("test");
        draft.setCreatedAt(Instant.now());
        long draftId = draftRepository.save(draft).getId();
        DraftLesson lesson = new DraftLesson();
        lesson.setDraftId(draftId);
        lesson.setAssignmentId(0L);
        lesson.setLessonIndex(1);
        lesson.setCourseId(courseId);
        lesson.setTeacherId(teacherId);
        lesson.setAdminClassId(classId);
        lesson.setStudentCount(45);
        lesson.setPeriodId(periodId);
        draftLessonRepository.save(lesson);
        return draftId;
    }

    private Response validate(String token, long draftId) {
        return as(token)
                .when().get("/api/timetable-drafts/" + draftId + "/validation")
                .then().statusCode(200).extract().response();
    }

    private long semesterWithPeriods(String token) {
        long semesterId = TestData.semester(token);
        as(token).body(Map.of("morningCount", 4, "afternoonCount", 4, "eveningCount", 0))
                .when().post("/api/semesters/" + semesterId + "/periods/default")
                .then().statusCode(200);
        return semesterId;
    }

    private Map<String, Long> periodIds(String token, long semesterId) {
        List<Map<String, Object>> rows = as(token)
                .when().get("/api/semesters/" + semesterId + "/periods")
                .then().statusCode(200).extract().jsonPath().getList("$");
        Map<String, Long> bySlot = new LinkedHashMap<>();
        rows.forEach(row -> bySlot.put(
                ((Number) row.get("dayOfWeek")).intValue() + "-"
                        + ((Number) row.get("periodNo")).intValue(),
                ((Number) row.get("id")).longValue()));
        return bySlot;
    }
}
