package com.paiban.foundation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;

import com.paiban.foundation.service.LifecycleService;
import com.paiban.scheduling.domain.Assignment;
import com.paiban.scheduling.domain.AssignmentRepository;
import com.paiban.support.ApiIntegrationTest;
import com.paiban.support.ScheduleFixture;
import com.paiban.support.TestData;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Deletion contract for ticket 03: three levels (plain delete / cascade / disable
 * only), 7-day recycle bin, restore and audit. Assertions stay on the public HTTP
 * contract; raw SQL is only used to move an expiry date into the past.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class LifecycleIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private LifecycleService lifecycle;

    @Autowired
    private AssignmentRepository assignmentRepository;

    private record Seed(long semesterId, long gradeId, long classId, long courseId, long teacherId) {}

    private Seed seed(String token, String suffix) {
        long semesterId = TestData.semester(token);
        long gradeId = TestData.grade(token, semesterId, "初一" + suffix);
        long classId = TestData.adminClass(token, semesterId, gradeId, "初一1班" + suffix);
        long courseId = TestData.course(token, "语文" + suffix);
        long teacherId = TestData.teacher(token, "语文老师" + suffix);
        return new Seed(semesterId, gradeId, classId, courseId, teacherId);
    }

    private long assignment(String token, Seed seed, int weeklyLessons) {
        var response = as(token)
                .body(Map.of(
                        "classId", seed.classId(),
                        "courseId", seed.courseId(),
                        "teacherId", seed.teacherId(),
                        "weeklyLessons", weeklyLessons))
                .when().post("/api/assignments");
        response.then().statusCode(200);
        return response.jsonPath().getLong("id");
    }

    private List<String> batchIds(String token, boolean includeClosed) {
        var request = as(token);
        if (includeClosed) {
            request.queryParam("includeClosed", true);
        }
        return request.when().get("/api/recent-deletions")
                .then().statusCode(200)
                .extract().jsonPath().getList("batchId");
    }

    @Test
    void teacherReferencedByPublishedTimetableCanOnlyBeDisabled() {
        String token = adminToken();
        ScheduleFixture.Result world = ScheduleFixture.published(token, 5);
        long teacherId = world.teacherId();

        as(token).when().get("/api/teachers/" + teacherId + "/delete-impact")
                .then().statusCode(200)
                .body("entityType", equalTo("TEACHER"))
                .body("canDelete", equalTo(false))
                .body("publishedRefs", greaterThan(0));

        as(token).when().delete("/api/teachers/" + teacherId)
                .then().statusCode(409);

        as(token).when().post("/api/teachers/" + teacherId + "/disable")
                .then().statusCode(200);

        List<Integer> optionIds = as(token).when().get("/api/teachers/options")
                .then().statusCode(200).extract().jsonPath().getList("id");
        assertThat(optionIds).doesNotContain((int) teacherId);

        as(token).when().get("/api/teachers")
                .then().statusCode(200)
                .body("find { it.id == " + teacherId + " }.active", equalTo(false));

        as(token).when().post("/api/teachers/" + teacherId + "/enable")
                .then().statusCode(200);
        List<Integer> enabledIds = as(token).when().get("/api/teachers/options")
                .then().statusCode(200).extract().jsonPath().getList("id");
        assertThat(enabledIds).contains((int) teacherId);
    }

    @Test
    void assignmentDeleteAndRestoreAppearInRecycleBinAndAudit() {
        String token = adminToken();
        String suffix = TestData.suffix();
        Seed seed = seed(token, suffix);
        long assignmentId = assignment(token, seed, 3);

        as(token).when().delete("/api/assignments/" + assignmentId)
                .then().statusCode(200);

        List<Integer> listed = as(token).queryParam("semesterId", seed.semesterId())
                .when().get("/api/assignments")
                .then().statusCode(200).extract().jsonPath().getList("id");
        assertThat(listed).doesNotContain((int) assignmentId);

        // The solver/import query must not see the deleted row, while the full view
        // (used by impact/admin) still finds it.
        assertThat(assignmentRepository.findBySemesterIdAndDeletedAtIsNull(seed.semesterId()))
                .extracting(Assignment::getId).doesNotContain(assignmentId);
        assertThat(assignmentRepository.findBySemesterId(seed.semesterId()))
                .extracting(Assignment::getId).contains(assignmentId);

        var recent = as(token).when().get("/api/recent-deletions")
                .then().statusCode(200)
                .body("$", org.hamcrest.Matchers.hasSize(1))
                .body("[0].entityType", equalTo("ASSIGNMENT"))
                .extract().response();
        assertThat(recent.jsonPath().getLong("[0].entityId")).isEqualTo(assignmentId);
        String batchId = recent.jsonPath().getString("[0].batchId");

        as(token).when().post("/api/recent-deletions/" + batchId + "/restore")
                .then().statusCode(200);

        List<Integer> restored = as(token).queryParam("semesterId", seed.semesterId())
                .when().get("/api/assignments")
                .then().statusCode(200).extract().jsonPath().getList("id");
        assertThat(restored).contains((int) assignmentId);

        List<String> actions = as(token).when().get("/api/foundation/audit-logs")
                .then().statusCode(200).extract().jsonPath().getList("action");
        assertThat(actions).contains("DELETE_ASSIGNMENT", "RESTORE_ASSIGNMENT");
    }

    @Test
    void deletingCourseCascadesItsAssignmentsAndRestoresTogether() {
        String token = adminToken();
        String suffix = TestData.suffix();
        Seed seed = seed(token, suffix);
        long assignmentId = assignment(token, seed, 4);

        as(token).when().get("/api/courses/" + seed.courseId() + "/delete-impact")
                .then().statusCode(200)
                .body("canDelete", equalTo(true))
                .body("cascadeAssignments", equalTo(1));

        as(token).when().delete("/api/courses/" + seed.courseId() + "?mode=CASCADE")
                .then().statusCode(200);

        List<Integer> courses = as(token).when().get("/api/courses")
                .then().statusCode(200).extract().jsonPath().getList("id");
        assertThat(courses).doesNotContain((int) seed.courseId());
        List<Integer> assignments = as(token).queryParam("semesterId", seed.semesterId())
                .when().get("/api/assignments")
                .then().statusCode(200).extract().jsonPath().getList("id");
        assertThat(assignments).doesNotContain((int) assignmentId);

        var recent = as(token).when().get("/api/recent-deletions")
                .then().statusCode(200)
                .body("$", org.hamcrest.Matchers.hasSize(2))
                .extract().response();
        List<String> batches = recent.jsonPath().getList("batchId");
        assertThat(batches).allMatch(batch -> batch.equals(batches.get(0)));
        boolean hasPrimary = recent.jsonPath().getList("primary", Boolean.class)
                .stream().anyMatch(Boolean::booleanValue);
        assertThat(hasPrimary).isTrue();

        as(token).when().post("/api/recent-deletions/" + batches.get(0) + "/restore")
                .then().statusCode(200);

        List<Integer> coursesBack = as(token).when().get("/api/courses")
                .then().statusCode(200).extract().jsonPath().getList("id");
        assertThat(coursesBack).contains((int) seed.courseId());
        List<Integer> assignmentsBack = as(token).queryParam("semesterId", seed.semesterId())
                .when().get("/api/assignments")
                .then().statusCode(200).extract().jsonPath().getList("id");
        assertThat(assignmentsBack).contains((int) assignmentId);
    }

    @Test
    void restoreAfterTheSevenDayWindowIsGone() {
        String token = adminToken();
        long teacherId = TestData.teacher(token, "临时老师" + TestData.suffix());

        as(token).when().delete("/api/teachers/" + teacherId).then().statusCode(200);
        String batchId = batchIds(token, false).get(0);

        jdbc.update(
                "update recent_deletion set expires_at = ? where batch_id = ?",
                Timestamp.from(Instant.now().minusSeconds(120)),
                batchId);

        as(token).when().post("/api/recent-deletions/" + batchId + "/restore")
                .then().statusCode(410);
    }

    @Test
    void deletingGradeCascadesClassesAndAssignments() {
        String token = adminToken();
        String suffix = TestData.suffix();
        Seed seed = seed(token, suffix);
        long assignmentId = assignment(token, seed, 2);

        as(token).when().get("/api/grades/" + seed.gradeId() + "/delete-impact")
                .then().statusCode(200)
                .body("cascadeClasses", equalTo(1))
                .body("cascadeAssignments", equalTo(1));

        as(token).when().delete("/api/grades/" + seed.gradeId() + "?mode=CASCADE")
                .then().statusCode(200);

        List<Integer> grades = as(token).queryParam("semesterId", seed.semesterId())
                .when().get("/api/grades")
                .then().statusCode(200).extract().jsonPath().getList("id");
        assertThat(grades).doesNotContain((int) seed.gradeId());
        List<Integer> classes = as(token).queryParam("semesterId", seed.semesterId())
                .when().get("/api/classes")
                .then().statusCode(200).extract().jsonPath().getList("id");
        assertThat(classes).doesNotContain((int) seed.classId());
        List<Integer> assignments = as(token).queryParam("semesterId", seed.semesterId())
                .when().get("/api/assignments")
                .then().statusCode(200).extract().jsonPath().getList("id");
        assertThat(assignments).doesNotContain((int) assignmentId);

        String batchId = batchIds(token, false).get(0);
        as(token).when().post("/api/recent-deletions/" + batchId + "/restore")
                .then().statusCode(200);

        List<Integer> gradesBack = as(token).queryParam("semesterId", seed.semesterId())
                .when().get("/api/grades")
                .then().statusCode(200).extract().jsonPath().getList("id");
        assertThat(gradesBack).contains((int) seed.gradeId());
        List<Integer> assignmentsBack = as(token).queryParam("semesterId", seed.semesterId())
                .when().get("/api/assignments")
                .then().statusCode(200).extract().jsonPath().getList("id");
        assertThat(assignmentsBack).contains((int) assignmentId);
    }

    @Test
    void classWithPublishedTimetableCanOnlyBeDisabled() {
        String token = adminToken();
        ScheduleFixture.Result world = ScheduleFixture.published(token, 5);
        long classId = world.classId();

        as(token).when().get("/api/classes/" + classId + "/delete-impact")
                .then().statusCode(200)
                .body("canDelete", equalTo(false))
                .body("publishedRefs", greaterThan(0));

        as(token).when().delete("/api/classes/" + classId)
                .then().statusCode(409);

        as(token).when().post("/api/classes/" + classId + "/disable")
                .then().statusCode(200);

        as(token).queryParam("semesterId", world.semesterId())
                .when().get("/api/classes")
                .then().statusCode(200)
                .body("find { it.id == " + classId + " }.active", equalTo(false));

        as(token).when().post("/api/classes/" + classId + "/enable")
                .then().statusCode(200);
    }

    @Test
    void listsAndOptionsExcludeDeletedAndDisabled() {
        String token = adminToken();
        String suffix = TestData.suffix();
        long disabledId = TestData.teacher(token, "停用老师" + suffix);
        long deletedId = TestData.teacher(token, "删除老师" + suffix);
        long courseId = TestData.course(token, "临时课程" + suffix);

        as(token).when().post("/api/teachers/" + disabledId + "/disable")
                .then().statusCode(200);
        as(token).when().delete("/api/teachers/" + deletedId)
                .then().statusCode(200);
        as(token).when().delete("/api/courses/" + courseId)
                .then().statusCode(200);

        List<Integer> optionIds = as(token).when().get("/api/teachers/options")
                .then().statusCode(200).extract().jsonPath().getList("id");
        assertThat(optionIds).doesNotContain((int) disabledId, (int) deletedId);

        List<Integer> teacherIds = as(token).when().get("/api/teachers")
                .then().statusCode(200).extract().jsonPath().getList("id");
        assertThat(teacherIds).contains((int) disabledId).doesNotContain((int) deletedId);

        List<Integer> courseIds = as(token).when().get("/api/courses")
                .then().statusCode(200).extract().jsonPath().getList("id");
        assertThat(courseIds).doesNotContain((int) courseId);
    }

    @Test
    void restoreLeavesAlreadyDisabledObjectsDisabled() {
        String token = adminToken();
        long teacherId = TestData.teacher(token, "先停用老师" + TestData.suffix());

        as(token).when().post("/api/teachers/" + teacherId + "/disable")
                .then().statusCode(200);
        as(token).when().delete("/api/teachers/" + teacherId)
                .then().statusCode(200);

        String batchId = batchIds(token, false).get(0);
        as(token).when().post("/api/recent-deletions/" + batchId + "/restore")
                .then().statusCode(200);

        // Deletion is undone, but the deliberate disable before it is not.
        as(token).when().get("/api/teachers")
                .then().statusCode(200)
                .body("find { it.id == " + teacherId + " }.active", equalTo(false));
    }

    @Test
    void restoreReenablesAnObjectThatWasActiveBeforeDeletion() {
        String token = adminToken();
        long teacherId = TestData.teacher(token, "直接删老师" + TestData.suffix());

        as(token).when().delete("/api/teachers/" + teacherId)
                .then().statusCode(200);

        String batchId = batchIds(token, false).get(0);
        as(token).when().post("/api/recent-deletions/" + batchId + "/restore")
                .then().statusCode(200);

        as(token).when().get("/api/teachers")
                .then().statusCode(200)
                .body("find { it.id == " + teacherId + " }.active", equalTo(true));
    }

    @Test
    void semesterWithAssignmentsMustBeCascadedAndRestoresTogether() {
        String token = adminToken();
        String suffix = TestData.suffix();
        Seed seed = seed(token, suffix);
        long firstAssignment = assignment(token, seed, 3);
        long secondCourse = TestData.course(token, "数学" + suffix);
        long secondAssignment = as(token)
                .body(Map.of(
                        "classId", seed.classId(),
                        "courseId", secondCourse,
                        "teacherId", seed.teacherId(),
                        "weeklyLessons", 2))
                .when().post("/api/assignments")
                .then().statusCode(200)
                .extract().jsonPath().getLong("id");

        // Move "current" elsewhere so the seeded semester can be deleted.
        TestData.semester(token);

        as(token).when().get("/api/semesters/" + seed.semesterId() + "/delete-impact")
                .then().statusCode(200)
                .body("canDelete", equalTo(true))
                .body("cascadeAssignments", equalTo(2));

        as(token).when().delete("/api/semesters/" + seed.semesterId())
                .then().statusCode(409);
        as(token).when().delete("/api/semesters/" + seed.semesterId() + "?mode=CASCADE")
                .then().statusCode(200);

        List<Integer> assignmentsGone = as(token).queryParam("semesterId", seed.semesterId())
                .when().get("/api/assignments")
                .then().statusCode(200).extract().jsonPath().getList("id");
        assertThat(assignmentsGone).doesNotContain((int) firstAssignment, (int) secondAssignment);

        String batchId = batchIds(token, false).get(0);
        as(token).when().post("/api/recent-deletions/" + batchId + "/restore")
                .then().statusCode(200);

        List<Integer> semestersBack = as(token).when().get("/api/semesters")
                .then().statusCode(200).extract().jsonPath().getList("id");
        assertThat(semestersBack).contains((int) seed.semesterId());
        List<Integer> assignmentsBack = as(token).queryParam("semesterId", seed.semesterId())
                .when().get("/api/assignments")
                .then().statusCode(200).extract().jsonPath().getList("id");
        assertThat(assignmentsBack).contains((int) firstAssignment, (int) secondAssignment);
    }

    @Test
    void expiredRecycleRowsArePurgedButTheBusinessRowStays() {
        String token = adminToken();
        long teacherId = TestData.teacher(token, "过期老师" + TestData.suffix());

        as(token).when().delete("/api/teachers/" + teacherId).then().statusCode(200);
        String batchId = batchIds(token, false).get(0);
        jdbc.update(
                "update recent_deletion set expires_at = ? where batch_id = ?",
                Timestamp.from(Instant.now().minusSeconds(120)),
                batchId);

        int purged = lifecycle.purgeExpired(Instant.now());

        assertThat(purged).isGreaterThanOrEqualTo(1);
        assertThat(batchIds(token, true)).doesNotContain(batchId);
        Integer rows = jdbc.queryForObject(
                "select count(*) from teacher where id = ?", Integer.class, teacherId);
        assertThat(rows).isEqualTo(1);
    }
}
