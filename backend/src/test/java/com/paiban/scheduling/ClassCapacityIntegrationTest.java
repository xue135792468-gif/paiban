package com.paiban.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;

import com.paiban.support.ApiIntegrationTest;
import com.paiban.support.ScheduleFixture;
import com.paiban.support.TestData;
import io.restassured.response.Response;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 票05 容量：40 个真实位置、两门课 20+19=39 节、重叠后净禁排 2 个位置时，保存、
 * 预览与真正开排的行为。容量是必要条件，提示不阻断保存，只有提交排课才拒绝。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ClassCapacityIntegrationTest extends ApiIntegrationTest {

    @Test
    void previewWarnsAboutOneMissingSlotButSaveIsStillAllowed() {
        String token = adminToken();
        Fixture fixture = fixtureWith39Lessons(token);
        List<Map<String, Object>> configs = List.of(
                conflict("TEACHER_CONFLICT"), conflict("CLASS_CONFLICT"),
                conflict("ROOM_CONFLICT"), ban(fixture.classId(), 1, 1, 1, 2));

        as(token).queryParam("semesterId", fixture.semesterId())
                .body(Map.of("configs", configs)).post("/api/constraints/preview")
                .then().statusCode(200)
                .body("errors", hasSize(0))
                .body("warnings", hasSize(1));

        String warning = as(token).queryParam("semesterId", fixture.semesterId())
                .body(Map.of("configs", configs)).post("/api/constraints/preview")
                .then().statusCode(200).extract().jsonPath().getString("warnings[0]");
        assertThat(warning).contains(fixture.className())
                .contains("39").contains("38").contains("1");

        as(token).queryParam("semesterId", fixture.semesterId())
                .queryParam("baseVersion", version(token, fixture.semesterId()))
                .body(Map.of("configs", configs)).put("/api/constraints")
                .then().statusCode(200)
                .body("find { it.type == 'CLASS_UNAVAILABLE' }.entries", hasSize(2));
    }

    @Test
    void startRefusesTheShortageWithoutCreatingAJobOrTouchingAssignments() {
        String token = adminToken();
        Fixture fixture = fixtureWith39Lessons(token);
        saveBan(token, fixture, 1, 1, 1, 2);

        Response rejected = as(token).queryParam("semesterId", fixture.semesterId())
                .body(Map.of("maxSeconds", 2)).post("/api/scheduling-jobs");

        rejected.then().statusCode(400);
        assertThat(rejected.asString()).contains(fixture.className())
                .contains("39").contains("38").contains("1");

        as(token).queryParam("semesterId", fixture.semesterId()).get("/api/scheduling-jobs")
                .then().statusCode(200).body("$", hasSize(0));
        as(token).queryParam("semesterId", fixture.semesterId()).get("/api/assignments")
                .then().statusCode(200).body("$", hasSize(2))
                .body("weeklyLessons", org.hamcrest.Matchers.containsInAnyOrder(20, 19));
    }

    @Test
    void queuedJobRechecksTheCurrentSettingsBeforeSolving() throws Exception {
        String token = adminToken();
        Fixture fixture = fixtureWith39Lessons(token);
        // One blocked slot leaves exactly 39 positions, so the submission is accepted.
        saveBan(token, fixture, 1, 1);

        long blocker = -1;
        long queued = -1;
        try {
            blocker = smallJob(token);
            Response accepted = as(token).queryParam("semesterId", fixture.semesterId())
                    .body(Map.of("maxSeconds", 20)).post("/api/scheduling-jobs");
            accepted.then().statusCode(200);
            queued = accepted.jsonPath().getLong("id");
            assertThat(status(token, queued)).isEqualTo("PENDING");

            // The requirement tightens while the job waits: two distinct slots now blocked.
            saveBan(token, fixture, 1, 1, 1, 2);
            // Still queued, so the solve has not read the old settings yet.
            assertThat(status(token, queued)).isEqualTo("PENDING");

            as(token).delete("/api/scheduling-jobs/" + blocker).then().statusCode(200);

            Map<String, Object> job = awaitTerminal(token, queued);
            assertThat(job.get("status")).isEqualTo("FAILED");
            assertThat(String.valueOf(job.get("errorMessage"))).contains(fixture.className())
                    .contains("39").contains("38").contains("1");
            assertThat(job.get("draftId")).isNull();
            as(token).queryParam("semesterId", fixture.semesterId()).get("/api/timetable-drafts")
                    .then().statusCode(200).body("$", hasSize(0));
            as(token).queryParam("semesterId", fixture.semesterId()).get("/api/assignments")
                    .then().statusCode(200).body("$", hasSize(2));
        } finally {
            // Never leave solver work behind for the following test classes, whether the
            // assertions above passed or threw.
            cancelQuietly(token, blocker);
            cancelQuietly(token, queued);
            awaitSettled(token, blocker, queued);
        }
    }

    // ------------------------------------------------------------------ helpers

    private record Fixture(String token, long semesterId, long classId, String className) {}

    /** 40 real positions (5 days × 8 periods) and one class with 20 + 19 weekly lessons. */
    private Fixture fixtureWith39Lessons(String token) {
        long semester = TestData.semesterStarting(token, ScheduleFixture.nextMonday());
        as(token).body(Map.of("morningCount", 8, "afternoonCount", 0, "eveningCount", 0,
                        "days", List.of(1, 2, 3, 4, 5)))
                .post("/api/semesters/" + semester + "/periods/default").then().statusCode(200);
        String suffix = TestData.suffix();
        long grade = TestData.grade(token, semester, "容量年级" + suffix);
        String className = "容量班" + suffix;
        long classId = TestData.adminClass(token, semester, grade, className);
        TestData.room(token, "容量教室" + suffix);
        long firstCourse = TestData.course(token, "容量甲" + suffix);
        long secondCourse = TestData.course(token, "容量乙" + suffix);
        assign(token, classId, firstCourse, TestData.teacher(token, "容量甲师" + suffix), 20);
        assign(token, classId, secondCourse, TestData.teacher(token, "容量乙师" + suffix), 19);
        return new Fixture(token, semester, classId, className);
    }

    private void assign(String token, long classId, long courseId, long teacherId, int weekly) {
        as(token).body(Map.of("classId", classId, "teacherId", teacherId,
                        "courseId", courseId, "weeklyLessons", weekly))
                .post("/api/assignments").then().statusCode(200);
    }

    /** Another semester's tiny job occupies the single solver thread so this one queues. */
    private long smallJob(String token) {
        long semester = TestData.semesterStarting(token, ScheduleFixture.nextMonday());
        as(token).body(Map.of("morningCount", 2, "afternoonCount", 0, "eveningCount", 0,
                        "days", List.of(1)))
                .post("/api/semesters/" + semester + "/periods/default").then().statusCode(200);
        String suffix = TestData.suffix();
        long grade = TestData.grade(token, semester, "占位年级" + suffix);
        long classId = TestData.adminClass(token, semester, grade, "占位班" + suffix);
        TestData.room(token, "占位教室" + suffix);
        assign(token, classId, TestData.course(token, "占位课" + suffix),
                TestData.teacher(token, "占位师" + suffix), 1);
        return as(token).queryParam("semesterId", semester).body(Map.of("maxSeconds", 30))
                .post("/api/scheduling-jobs").then().statusCode(200)
                .extract().jsonPath().getLong("id");
    }

    private String status(String token, long jobId) {
        return as(token).get("/api/scheduling-jobs/" + jobId).then().statusCode(200)
                .extract().jsonPath().getString("status");
    }

    private Map<String, Object> awaitTerminal(String token, long jobId) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        Map<String, Object> job = Map.of();
        while (System.nanoTime() < deadline) {
            job = as(token).get("/api/scheduling-jobs/" + jobId).then().statusCode(200)
                    .extract().jsonPath().getMap("$");
            if (List.of("COMPLETED", "FAILED", "CANCELLED").contains(String.valueOf(job.get("status")))) {
                return job;
            }
            Thread.sleep(100);
        }
        return job;
    }

    private void cancelQuietly(String token, long jobId) {
        if (jobId <= 0) {
            return;
        }
        try {
            as(token).delete("/api/scheduling-jobs/" + jobId);
        } catch (RuntimeException ignored) {
            // Cleanup only: the job may already be terminal or the context may be closing.
        }
    }

    private void awaitSettled(String token, long... jobIds) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        for (long jobId : jobIds) {
            if (jobId <= 0) {
                continue;
            }
            while (System.nanoTime() < deadline) {
                String current = statusQuietly(token, jobId);
                if (current == null
                        || List.of("COMPLETED", "FAILED", "CANCELLED").contains(current)) {
                    break;
                }
                cancelQuietly(token, jobId);
                Thread.sleep(100);
            }
        }
    }

    private String statusQuietly(String token, long jobId) {
        try {
            return status(token, jobId);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private void saveBan(String token, Fixture fixture, int... dayPeriods) {
        List<Map<String, Object>> configs = new ArrayList<>();
        configs.add(conflict("TEACHER_CONFLICT"));
        configs.add(conflict("CLASS_CONFLICT"));
        configs.add(conflict("ROOM_CONFLICT"));
        configs.add(ban(fixture.classId(), dayPeriods));
        as(token).queryParam("semesterId", fixture.semesterId())
                .queryParam("baseVersion", version(token, fixture.semesterId()))
                .body(Map.of("configs", configs)).put("/api/constraints").then().statusCode(200);
    }

    private long version(String token, long semester) {
        return as(token).queryParam("semesterId", semester).get("/api/constraints/state")
                .then().statusCode(200).extract().jsonPath().getLong("version");
    }

    private Map<String, Object> conflict(String type) {
        return new LinkedHashMap<>(Map.of("type", type, "params", Map.of(),
                "enabled", true, "hard", true, "weight", 100));
    }

    /**
     * One requirement expanded to several slots. Two evenly indexed values are one day,
     * the odd values the periods, so {@code saveBan(1, 1, 1, 2)} means Monday 1st and 2nd.
     */
    private Map<String, Object> ban(long classId, int... dayPeriods) {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (int index = 0; index < dayPeriods.length; index += 2) {
            entries.add(Map.of("params", Map.of("classId", classId,
                    "dayOfWeek", dayPeriods[index], "periodNo", dayPeriods[index + 1]),
                    "enabled", true, "hard", true, "groupKey", "capacity"));
        }
        return new LinkedHashMap<>(Map.of("type", "CLASS_UNAVAILABLE", "params", Map.of(),
                "enabled", true, "hard", true, "weight", 100, "entries", entries));
    }
}
