package com.paiban.substitution;

import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.paiban.support.ApiIntegrationTest;
import com.paiban.support.TestData;

/**
 * 票 15 后「再次调整」场景的共享 fixture。
 *
 * <p>三节课分布在三天：语文周三 1、数学周五 1、英语周一 1；另备一位无课教师丙。
 * 学期跨两周，第二次调课的目标（下周一）落在学期与窗口之内。教室整套已删除，
 * 课节只按节次落位（作息默认按 periodCount 顺序生成 1..N）。
 *
 * <p>本类只提供 fixture；开课截止的验收在 {@link SubstitutionRescheduleDeadlineIntegrationTest}。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SubstitutionRescheduleIntegrationTest extends ApiIntegrationTest {

    record Fixture(long semesterId, long classAId,
                   long teacherChineseId, long teacherMathId, long teacherEnglishId,
                   long teacherSpareId,
                   long entryChineseId, long entryMathId, long entryEnglishId,
                   LocalDate monday, LocalDate wednesday, LocalDate friday, LocalDate nextMonday,
                   long wednesdayPeriodId, long fridayPeriodId, long mondayPeriodId,
                   long fridaySecondPeriodId) {}

    static Fixture buildFixture(String token, String suffix, LocalDate monday) {
        return buildFixture(token, suffix, monday, false);
    }

    /** {@code englishOnFridaySecondPeriod}：英语摆到周五第 2 节（冻结场景用）。 */
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

        com.paiban.support.ScheduleFixture.assign(token, classAId, chineseId, teacherChineseId, 1);
        com.paiban.support.ScheduleFixture.assign(token, classAId, mathId, teacherMathId, 1);
        com.paiban.support.ScheduleFixture.assign(token, classAId, englishId, teacherEnglishId, 1);

        long draftId = solve(token, semesterId);
        List<Map<String, Object>> lessons = com.paiban.support.ScheduleFixture.lessons(token, draftId);
        place(token, draftId, lessons, classAId, chineseId, wednesdayPeriodId);
        place(token, draftId, lessons, classAId, mathId, fridayPeriodId);
        place(token, draftId, lessons, classAId, englishId,
                englishOnFridaySecondPeriod ? fridaySecondPeriodId : mondayPeriodId);
        auth(token).when().post("/api/timetable-drafts/" + draftId + "/publish")
                .then().statusCode(200);

        List<Map<String, Object>> entriesA = com.paiban.support.ScheduleFixture
                .publishedEntries(token, classAId);
        return new Fixture(semesterId, classAId,
                teacherChineseId, teacherMathId, teacherEnglishId, teacherSpareId,
                entryByCourse(entriesA, chineseId), entryByCourse(entriesA, mathId),
                entryByCourse(entriesA, englishId),
                monday, wednesday, friday, nextMonday,
                wednesdayPeriodId, fridayPeriodId, mondayPeriodId, fridaySecondPeriodId);
    }

    private static long entryByCourse(List<Map<String, Object>> entries, long courseId) {
        return entries.stream()
                .filter(row -> ((Number) row.get("courseId")).longValue() == courseId)
                .map(row -> ((Number) row.get("id")).longValue())
                .findFirst().orElseThrow();
    }

    private static RequestSpecification auth(String token) {
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
                              long classId, long courseId, long periodId) {
        Map<String, Object> lesson = lessons.stream()
                .filter(row -> ((Number) row.get("classId")).longValue() == classId
                        && ((Number) row.get("courseId")).longValue() == courseId)
                .findFirst().orElseThrow();
        auth(token).body(Map.of("periodId", periodId, "force", true))
                .when().patch("/api/timetable-drafts/" + draftId + "/lessons/"
                        + ((Number) lesson.get("id")).longValue())
                .then().statusCode(200);
    }
}
