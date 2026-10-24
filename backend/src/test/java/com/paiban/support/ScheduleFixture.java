package com.paiban.support;

import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Builds a small solved timetable through the public API. */
public final class ScheduleFixture {

    public record Result(long semesterId, long classId, long otherClassId, long teacherId,
                         long otherTeacherId, long roomId, long draftId, int lessonCount) {}

    private ScheduleFixture() {}

    public static Result solved(String token, int maxSeconds) {
        String suffix = TestData.suffix();
        long semesterId = TestData.semester(token);
        RestAssuredHolder.given(token)
                .body(Map.of("morningCount", 4, "afternoonCount", 4, "eveningCount", 2))
                .when().post("/api/semesters/" + semesterId + "/periods/default")
                .then().statusCode(200);

        long gradeId = TestData.grade(token, semesterId, "高一" + suffix);
        long headTeacherId = TestData.teacher(token, "班主任" + suffix);
        long classId = classWithHeadTeacher(token, semesterId, gradeId, "高一1班" + suffix, headTeacherId);
        long otherClassId = TestData.adminClass(token, semesterId, gradeId, "高一2班" + suffix);

        long courseA = TestData.course(token, "语文" + suffix);
        long courseB = TestData.course(token, "数学" + suffix);
        long teacherA = TestData.teacher(token, "语文老师" + suffix);
        long teacherB = TestData.teacher(token, "数学老师" + suffix);
        long roomId = TestData.room(token, "教室A" + suffix);
        TestData.room(token, "教室B" + suffix);

        assign(token, classId, courseA, teacherA, 3);
        assign(token, classId, courseB, teacherB, 3);
        assign(token, otherClassId, courseA, teacherA, 3);
        assign(token, otherClassId, courseB, teacherB, 3);

        long jobId = RestAssuredHolder.given(token)
                .queryParam("semesterId", semesterId)
                .body(Map.of("maxSeconds", maxSeconds))
                .when().post("/api/scheduling-jobs")
                .then().statusCode(200)
                .extract().jsonPath().getLong("id");

        long deadline = System.currentTimeMillis() + 90_000;
        long draftId = -1;
        while (System.currentTimeMillis() < deadline) {
            Map<String, Object> job = RestAssuredHolder.given(token)
                    .when().get("/api/scheduling-jobs/" + jobId)
                    .then().statusCode(200).extract().jsonPath().getMap("$");
            String status = String.valueOf(job.get("status"));
            if ("COMPLETED".equals(status)) {
                draftId = ((Number) job.get("draftId")).longValue();
                break;
            }
            if ("FAILED".equals(status) || "CANCELLED".equals(status)) {
                throw new IllegalStateException("排课失败: " + job.get("errorMessage"));
            }
            sleep(300);
        }
        if (draftId < 0) {
            throw new IllegalStateException("排课超时");
        }
        return new Result(semesterId, classId, otherClassId, teacherA, teacherB, roomId, draftId, 12);
    }

    public static Result published(String token, int maxSeconds) {
        Result result = solved(token, maxSeconds);
        RestAssuredHolder.given(token)
                .when().post("/api/timetable-drafts/" + result.draftId() + "/publish")
                .then().statusCode(200);
        return result;
    }

    /**
     * Published entries for a class. Publishing copies draft lessons into
     * timetable_entry with <em>new</em> ids, so callers must map by slot/course;
     * never reuse a {@code DraftLesson} id as a timetable entry id.
     */
    public static List<Map<String, Object>> publishedEntries(String token, long classId) {
        return RestAssuredHolder.given(token)
                .queryParam("view", "baseline")
                .when().get("/api/timetable/class/" + classId)
                .then().statusCode(200).extract().jsonPath().getList("$");
    }

    public static List<Map<String, Object>> classEntries(String token, long classId) {
        return RestAssuredHolder.given(token)
                .when().get("/api/timetable/class/" + classId)
                .then().statusCode(200)
                .extract().jsonPath().getList("$");
    }

    public static long classWithHeadTeacher(
            String token, long semesterId, long gradeId, String name, long headTeacherId) {
        Response response = RestAssuredHolder.given(token)
                .body(Map.of("gradeId", gradeId, "name", name, "studentCount", 45, "headTeacherId", headTeacherId))
                .queryParam("semesterId", semesterId)
                .when().post("/api/classes");
        response.then().statusCode(200);
        return response.jsonPath().getLong("id");
    }

    public static void assign(String token, long classId, long courseId, long teacherId, int weekly) {
        RestAssuredHolder.given(token)
                .body(Map.of(
                        "classId", classId,
                        "courseId", courseId,
                        "teacherId", teacherId,
                        "weeklyLessons", weekly))
                .when().post("/api/assignments")
                .then().statusCode(200);
    }

    public static List<Map<String, Object>> lessons(String token, long draftId) {
        return RestAssuredHolder.given(token)
                .when().get("/api/timetable-drafts/" + draftId)
                .then().statusCode(200)
                .extract().jsonPath().getList("lessons");
    }

    public static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    static final class RestAssuredHolder {

        private RestAssuredHolder() {}

        static io.restassured.specification.RequestSpecification given(String token) {
            return io.restassured.RestAssured.given()
                    .contentType(ContentType.JSON)
                    .header("Authorization", "Bearer " + token);
        }
    }
}
