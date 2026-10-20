package com.paiban.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

import com.paiban.support.ApiIntegrationTest;
import com.paiban.support.TestData;
import io.restassured.response.Response;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SchedulingIntegrationTest extends ApiIntegrationTest {

    private record Fixture(long semesterId, int lessonCount) {}

    private Fixture prepare(String token, String suffix, int classes, int courses, int weeklyLessons) {
        long semesterId = TestData.semester(token);
        as(token).body(Map.of("morningCount", 4, "afternoonCount", 4, "eveningCount", 2))
                .when().post("/api/semesters/" + semesterId + "/periods/default")
                .then().statusCode(200);

        long gradeId = TestData.grade(token, semesterId, "高一" + suffix);
        List<Long> classIds = new java.util.ArrayList<>();
        for (int i = 1; i <= classes; i++) {
            classIds.add(TestData.adminClass(token, semesterId, gradeId, "高一" + i + "班" + suffix));
        }
        String[] names = {"语文", "数学", "英语", "物理", "化学"};
        List<Long> courseIds = new java.util.ArrayList<>();
        List<Long> teacherIds = new java.util.ArrayList<>();
        for (int i = 0; i < courses; i++) {
            courseIds.add(TestData.course(token, names[i] + suffix));
            teacherIds.add(TestData.teacher(token, names[i] + "老师" + suffix));
        }
        for (int i = 0; i < classes; i++) {
            TestData.room(token, "教室" + i + suffix);
        }

        int lessonCount = 0;
        int created = 0;
        outer:
        for (int c = 0; c < classIds.size(); c++) {
            for (int k = 0; k < courseIds.size(); k++) {
                if (created >= 10) {
                    break outer;
                }
                as(token).body(Map.of(
                                "classId", classIds.get(c),
                                "courseId", courseIds.get(k),
                                "teacherId", teacherIds.get(k),
                                "weeklyLessons", weeklyLessons))
                        .when().post("/api/assignments")
                        .then().statusCode(200);
                lessonCount += weeklyLessons;
                created++;
            }
        }
        return new Fixture(semesterId, lessonCount);
    }

    private Map<String, Object> awaitJob(String token, long jobId, int timeoutSeconds) {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            Map<String, Object> job = as(token).when().get("/api/scheduling-jobs/" + jobId)
                    .then().statusCode(200).extract().jsonPath().getMap("$");
            String status = String.valueOf(job.get("status"));
            if (List.of("COMPLETED", "FAILED", "CANCELLED").contains(status)) {
                return job;
            }
            try {
                Thread.sleep(300);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("排课任务超时未结束: " + jobId);
    }

    @Test
    void solvePersistsDraftWithNoHardConflicts() {
        String token = adminToken();
        Fixture fixture = prepare(token, TestData.suffix(), 3, 4, 2);

        long jobId = as(token).queryParam("semesterId", fixture.semesterId())
                .body(Map.of("draftName", "方案A", "maxSeconds", 5))
                .when().post("/api/scheduling-jobs")
                .then().statusCode(200)
                .body("status", equalTo("PENDING"))
                .extract().jsonPath().getLong("id");

        Map<String, Object> job = awaitJob(token, jobId, 60);
        assertThat(job.get("status")).isEqualTo("COMPLETED");
        long draftId = ((Number) job.get("draftId")).longValue();

        as(token).when().get("/api/timetable-drafts/" + draftId)
                .then().statusCode(200)
                .body("lessons", hasSize(fixture.lessonCount()))
                .body("draft.scoreHard", equalTo(0))
                .body("draft.status", equalTo("DRAFT"));

        List<Map<String, Object>> lessons = as(token).when().get("/api/timetable-drafts/" + draftId)
                .then().statusCode(200).extract().jsonPath().getList("lessons");
        assertThat(lessons).allSatisfy(lesson -> assertThat(lesson.get("periodId")).isNotNull());
    }

    @Test
    void onlyOneJobMayRunPerSemester() {
        String token = adminToken();
        Fixture fixture = prepare(token, TestData.suffix(), 2, 3, 2);

        long first = as(token).queryParam("semesterId", fixture.semesterId())
                .body(Map.of("maxSeconds", 30))
                .when().post("/api/scheduling-jobs")
                .then().statusCode(200).extract().jsonPath().getLong("id");

        as(token).queryParam("semesterId", fixture.semesterId())
                .body(Map.of("maxSeconds", 30))
                .when().post("/api/scheduling-jobs")
                .then().statusCode(409);

        as(token).when().delete("/api/scheduling-jobs/" + first).then().statusCode(200);
    }

    @Test
    void runningJobCanBeCancelled() {
        String token = adminToken();
        Fixture fixture = prepare(token, TestData.suffix(), 2, 3, 2);

        long jobId = as(token).queryParam("semesterId", fixture.semesterId())
                .body(Map.of("maxSeconds", 60))
                .when().post("/api/scheduling-jobs")
                .then().statusCode(200).extract().jsonPath().getLong("id");

        as(token).when().delete("/api/scheduling-jobs/" + jobId)
                .then().statusCode(200)
                .body("status", equalTo("CANCELLED"));

        Map<String, Object> job = awaitJob(token, jobId, 30);
        assertThat(job.get("status")).isEqualTo("CANCELLED");
    }

    @Test
    void cancellingAJobFreesTheSolverForTheNextSubmission() {
        String token = adminToken();
        Fixture slow = prepare(token, TestData.suffix(), 1, 2, 1);
        long slowJob = as(token).queryParam("semesterId", slow.semesterId())
                .body(Map.of("maxSeconds", 60))
                .when().post("/api/scheduling-jobs")
                .then().statusCode(200).extract().jsonPath().getLong("id");
        as(token).when().delete("/api/scheduling-jobs/" + slowJob)
                .then().statusCode(200)
                .body("status", equalTo("CANCELLED"));
        awaitJob(token, slowJob, 30);

        Fixture next = prepare(token, TestData.suffix(), 2, 3, 2);
        long nextJob = as(token).queryParam("semesterId", next.semesterId())
                .body(Map.of("maxSeconds", 5))
                .when().post("/api/scheduling-jobs")
                .then().statusCode(200).extract().jsonPath().getLong("id");
        Map<String, Object> job = awaitJob(token, nextJob, 30);
        assertThat(job.get("status")).isEqualTo("COMPLETED");
    }

    @Test
    void schedulingRequiresAssignments() {
        String token = adminToken();
        long semesterId = TestData.semester(token);
        as(token).body(Map.of("morningCount", 4, "afternoonCount", 4, "eveningCount", 0))
                .when().post("/api/semesters/" + semesterId + "/periods/default").then().statusCode(200);

        as(token).queryParam("semesterId", semesterId)
                .body(Map.of("maxSeconds", 5))
                .when().post("/api/scheduling-jobs")
                .then().statusCode(400);
    }

    @Test
    void validateMoveReportsClassConflictAndAcceptsFreeSlot() {
        String token = adminToken();
        Fixture fixture = prepare(token, TestData.suffix(), 2, 3, 2);

        long jobId = as(token).queryParam("semesterId", fixture.semesterId())
                .body(Map.of("maxSeconds", 5))
                .when().post("/api/scheduling-jobs")
                .then().statusCode(200).extract().jsonPath().getLong("id");
        Map<String, Object> job = awaitJob(token, jobId, 60);
        assertThat(job.get("status")).isEqualTo("COMPLETED");
        long draftId = ((Number) job.get("draftId")).longValue();

        List<Map<String, Object>> lessons = as(token).when().get("/api/timetable-drafts/" + draftId)
                .then().statusCode(200).extract().jsonPath().getList("lessons");
        Map<String, Object> first = lessons.get(0);
        long classId = ((Number) first.get("classId")).longValue();

        Map<String, Object> sameClassOther = lessons.stream()
                .filter(lesson -> ((Number) lesson.get("classId")).longValue() == classId)
                .filter(lesson -> !((Number) lesson.get("id")).equals(((Number) first.get("id"))))
                .findFirst()
                .orElseThrow();

        as(token).body(Map.of(
                        "lessonId", ((Number) first.get("id")).longValue(),
                        "periodId", ((Number) sameClassOther.get("periodId")).longValue()))
                .when().post("/api/timetable-drafts/" + draftId + "/validate-move")
                .then().statusCode(200)
                .body("valid", equalTo(false))
                .body("hardConflicts", greaterThanOrEqualTo(1))
                .body("conflicts.message", org.hamcrest.Matchers.hasItem(
                        org.hamcrest.Matchers.containsString("已排")));

        List<Map<String, Object>> periods = as(token).when().get("/api/timetable-drafts/" + draftId)
                .then().statusCode(200).extract().jsonPath().getList("periods");
        java.util.Set<Long> usedPeriods = new java.util.HashSet<>();
        lessons.forEach(lesson -> {
            if (lesson.get("periodId") != null) {
                usedPeriods.add(((Number) lesson.get("periodId")).longValue());
            }
        });
        long freePeriod = periods.stream()
                .map(period -> ((Number) period.get("id")).longValue())
                .filter(id -> !usedPeriods.contains(id))
                .findFirst()
                .orElseThrow();

        as(token).body(Map.of(
                        "lessonId", ((Number) first.get("id")).longValue(),
                        "periodId", freePeriod))
                .when().post("/api/timetable-drafts/" + draftId + "/validate-move")
                .then().statusCode(200)
                .body("valid", equalTo(true))
                .body("hardConflicts", equalTo(0));
    }

    @Test
    void movingLessonToConflictIsRejectedUnlessForced() {
        String token = adminToken();
        Fixture fixture = prepare(token, TestData.suffix(), 2, 3, 2);
        long jobId = as(token).queryParam("semesterId", fixture.semesterId())
                .body(Map.of("maxSeconds", 5))
                .when().post("/api/scheduling-jobs")
                .then().statusCode(200).extract().jsonPath().getLong("id");
        Map<String, Object> job = awaitJob(token, jobId, 60);
        long draftId = ((Number) job.get("draftId")).longValue();

        List<Map<String, Object>> lessons = as(token).when().get("/api/timetable-drafts/" + draftId)
                .then().statusCode(200).extract().jsonPath().getList("lessons");
        Map<String, Object> first = lessons.get(0);
        long classId = ((Number) first.get("classId")).longValue();
        Map<String, Object> sameClassOther = lessons.stream()
                .filter(lesson -> ((Number) lesson.get("classId")).longValue() == classId)
                .filter(lesson -> !((Number) lesson.get("id")).equals(((Number) first.get("id"))))
                .findFirst()
                .orElseThrow();

        long lessonId = ((Number) first.get("id")).longValue();
        long targetPeriod = ((Number) sameClassOther.get("periodId")).longValue();
        Map<String, Object> move = new java.util.HashMap<>();
        move.put("periodId", targetPeriod);
        if (first.get("roomId") != null) {
            move.put("roomId", first.get("roomId"));
        }

        as(token).body(move)
                .when().patch("/api/timetable-drafts/" + draftId + "/lessons/" + lessonId)
                .then().statusCode(200)
                .body("saved", equalTo(false))
                .body("valid", equalTo(false));

        move.put("force", true);
        as(token).body(move)
                .when().patch("/api/timetable-drafts/" + draftId + "/lessons/" + lessonId)
                .then().statusCode(200)
                .body("saved", equalTo(true));
    }

    @Test
    void draftCanBeRenamedAndDeleted() {
        String token = adminToken();
        Fixture fixture = prepare(token, TestData.suffix(), 2, 3, 2);
        long jobId = as(token).queryParam("semesterId", fixture.semesterId())
                .body(Map.of("maxSeconds", 5))
                .when().post("/api/scheduling-jobs")
                .then().statusCode(200).extract().jsonPath().getLong("id");
        Map<String, Object> job = awaitJob(token, jobId, 60);
        long draftId = ((Number) job.get("draftId")).longValue();

        as(token).body(Map.of("name", "改名为方案B"))
                .when().put("/api/timetable-drafts/" + draftId + "/name")
                .then().statusCode(200)
                .body("name", equalTo("改名为方案B"));

        as(token).when().delete("/api/timetable-drafts/" + draftId).then().statusCode(200);

        as(token).queryParam("semesterId", fixture.semesterId())
                .when().get("/api/timetable-drafts")
                .then().statusCode(200)
                .body("$", hasSize(0));
    }
}
