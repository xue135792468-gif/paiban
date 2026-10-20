package com.paiban.timetable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;

import com.paiban.support.ApiIntegrationTest;
import com.paiban.support.ScheduleFixture;
import com.paiban.support.TestData;
import io.restassured.response.Response;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class TimetablePublishIntegrationTest extends ApiIntegrationTest {

    private void publish(String token, long draftId) {
        as(token).when().post("/api/timetable-drafts/" + draftId + "/publish")
                .then().statusCode(200)
                .body("entryCount", greaterThan(0));
    }

    @Test
    void publishedDraftIsQueryableFromFourViews() {
        String token = adminToken();
        ScheduleFixture.Result fixture = ScheduleFixture.solved(token, 5);
        publish(token, fixture.draftId());

        as(token).when().get("/api/timetable/class/" + fixture.classId())
                .then().statusCode(200)
                .body("$", hasSize(greaterThan(0)))
                .body("classId", org.hamcrest.Matchers.everyItem(equalTo((int) fixture.classId())))
                .body("[0].courseName", org.hamcrest.Matchers.notNullValue())
                .body("[0].periodLabel", org.hamcrest.Matchers.notNullValue());

        as(token).when().get("/api/timetable/teacher/" + fixture.teacherId())
                .then().statusCode(200)
                .body("$", hasSize(greaterThan(0)))
                .body("teacherId", org.hamcrest.Matchers.everyItem(equalTo((int) fixture.teacherId())));

        as(token).when().get("/api/timetable/room/" + fixture.roomId())
                .then().statusCode(200)
                .body("$", hasSize(greaterThan(0)));

        as(token).when().get("/api/timetable/school")
                .then().statusCode(200)
                .body("entries", hasSize(greaterThan(0)))
                .body("classIds", hasSize(2));

        as(token).when().get("/api/timetable/publish-info")
                .then().statusCode(200)
                .body("draftId", equalTo((int) fixture.draftId()));
    }

    @Test
    void baselineViewReturnsSameEntriesAsLatestWithoutSubstitutions() {
        String token = adminToken();
        ScheduleFixture.Result fixture = ScheduleFixture.solved(token, 5);
        publish(token, fixture.draftId());

        List<Map<String, Object>> latest = as(token)
                .queryParam("view", "latest")
                .when().get("/api/timetable/class/" + fixture.classId())
                .then().statusCode(200).extract().jsonPath().getList("$");
        List<Map<String, Object>> baseline = as(token)
                .queryParam("view", "baseline")
                .when().get("/api/timetable/class/" + fixture.classId())
                .then().statusCode(200).extract().jsonPath().getList("$");

        assertThat(baseline).hasSameSizeAs(latest);
        assertThat(baseline.get(0).get("id")).isEqualTo(latest.get(0).get("id"));
    }

    @Test
    void teacherSeesOwnTimetableOnlyAndHeadTeacherSeesOwnClass() {
        String token = adminToken();
        ScheduleFixture.Result fixture = ScheduleFixture.solved(token, 5);
        publish(token, fixture.draftId());

        String teacherUser = "tt_" + TestData.suffix();
        as(token).body(Map.of(
                        "username", teacherUser,
                        "password", "teacher123",
                        "displayName", "任课教师",
                        "roleCodes", List.of("teacher"),
                        "teacherId", fixture.teacherId()))
                .when().post("/api/users").then().statusCode(200);
        String teacherToken = login(teacherUser, "teacher123");

        as(teacherToken).when().get("/api/timetable/teacher/" + fixture.teacherId())
                .then().statusCode(200);
        as(teacherToken).when().get("/api/timetable/teacher/" + fixture.otherTeacherId())
                .then().statusCode(403);
        as(teacherToken).when().get("/api/timetable/school")
                .then().statusCode(403);

        String headUser = "ht_" + TestData.suffix();
        as(token).body(Map.of(
                        "username", headUser,
                        "password", "teacher123",
                        "displayName", "班主任",
                        "roleCodes", List.of("head_teacher"),
                        "teacherId", headTeacherOf(token, fixture.classId())))
                .when().post("/api/users").then().statusCode(200);
        String headToken = login(headUser, "teacher123");

        as(headToken).when().get("/api/timetable/class/" + fixture.classId())
                .then().statusCode(200);
        as(headToken).when().get("/api/timetable/class/" + fixture.otherClassId())
                .then().statusCode(403);
    }

    @Test
    void publishRejectsDraftWithHardConflicts() {
        String token = adminToken();
        ScheduleFixture.Result fixture = ScheduleFixture.solved(token, 5);

        List<Map<String, Object>> lessons = ScheduleFixture.lessons(token, fixture.draftId());
        Map<String, Object> first = lessons.get(0);
        long classId = ((Number) first.get("classId")).longValue();
        Map<String, Object> sameClassOther = lessons.stream()
                .filter(lesson -> ((Number) lesson.get("classId")).longValue() == classId)
                .filter(lesson -> !((Number) lesson.get("id")).equals(((Number) first.get("id"))))
                .findFirst()
                .orElseThrow();

        Map<String, Object> move = new java.util.HashMap<>();
        move.put("periodId", ((Number) sameClassOther.get("periodId")).longValue());
        if (first.get("roomId") != null) {
            move.put("roomId", first.get("roomId"));
        }
        move.put("force", true);
        as(token).body(move)
                .when().patch("/api/timetable-drafts/" + fixture.draftId() + "/lessons/"
                        + ((Number) first.get("id")).longValue())
                .then().statusCode(200)
                .body("saved", equalTo(true));

        as(token).when().post("/api/timetable-drafts/" + fixture.draftId() + "/publish")
                .then().statusCode(409);
    }

    @Test
    void publishingArchivesPreviousBaselineDraft() {
        String token = adminToken();
        ScheduleFixture.Result fixture = ScheduleFixture.solved(token, 5);
        publish(token, fixture.draftId());

        as(token).queryParam("semesterId", fixture.semesterId())
                .when().get("/api/timetable-drafts")
                .then().statusCode(200)
                .body("find { it.id == " + fixture.draftId() + " }.status", equalTo("PUBLISHED"));

        as(token).when().post("/api/timetable-drafts/" + fixture.draftId() + "/publish")
                .then().statusCode(409);
    }

    private long headTeacherOf(String token, long classId) {
        Response response = as(token).when().get("/api/classes")
                .then().statusCode(200).extract().response();
        List<Map<String, Object>> classes = response.jsonPath().getList("$");
        return classes.stream()
                .filter(item -> ((Number) item.get("id")).longValue() == classId)
                .map(item -> ((Number) item.get("headTeacherId")).longValue())
                .findFirst()
                .orElseThrow();
    }
}
