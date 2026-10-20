package com.paiban.substitution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;

import com.paiban.support.ApiIntegrationTest;
import com.paiban.support.ScheduleFixture;
import com.paiban.support.TestData;
import io.restassured.response.Response;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SubstitutionIntegrationTest extends ApiIntegrationTest {

    @AfterEach
    void resetApprovalMode() {
        try {
            as(adminToken()).body(Map.of("substitution.approval-mode", "approval"))
                    .when().put("/api/settings").then().statusCode(200);
        } catch (RuntimeException ignored) {
            // best effort cleanup
        }
    }

    private record Context(ScheduleFixture.Result fixture, String teacherToken, long teacherA,
                           long teacherB, long classId, long entryId, LocalDate date, String username) {}

    private Context prepare(String token, String suffix) {
        ScheduleFixture.Result fixture = ScheduleFixture.published(token, 5);
        List<Map<String, Object>> entries = ScheduleFixture.classEntries(token, fixture.classId());
        Map<String, Object> entry = entries.stream()
                .filter(item -> ((Number) item.get("teacherId")).longValue() == fixture.teacherId())
                .findFirst()
                .orElseThrow();
        long entryId = ((Number) entry.get("id")).longValue();
        int dayOfWeek = ((Number) entry.get("dayOfWeek")).intValue();
        LocalDate date = LocalDate.of(2025, 9, 1).plusDays(dayOfWeek - 1L);

        String username = "sub_" + suffix;
        as(token).body(Map.of(
                        "username", username,
                        "password", "teacher123",
                        "displayName", "原任课",
                        "roleCodes", List.of("teacher"),
                        "teacherId", fixture.teacherId()))
                .when().post("/api/users").then().statusCode(200);
        as(token).body(Map.of(
                        "username", "subs_" + suffix,
                        "password", "teacher123",
                        "displayName", "代课人",
                        "roleCodes", List.of("teacher"),
                        "teacherId", fixture.otherTeacherId()))
                .when().post("/api/users").then().statusCode(200);

        return new Context(
                fixture,
                login(username, "teacher123"),
                fixture.teacherId(),
                fixture.otherTeacherId(),
                fixture.classId(),
                entryId,
                date,
                username);
    }

    private long submit(Context context, String token, Long expectedSubstitute) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("entryId", context.entryId());
        body.put("effectiveDate", context.date().toString());
        body.put("reason", "家中有事");
        if (expectedSubstitute != null) {
            body.put("expectedSubstituteTeacherId", expectedSubstitute);
        }
        Response response = as(token).body(body)
                .when().post("/api/substitution-requests");
        response.then().statusCode(200);
        return response.jsonPath().getLong("id");
    }

    @Test
    void approvalModeRunsTheFullFlowAndOverlaysTimetable() {
        String token = adminToken();
        Context context = prepare(token, TestData.suffix());

        long requestId = submit(context, context.teacherToken(), context.teacherB());
        as(context.teacherToken()).when().get("/api/substitution-requests/" + requestId)
                .then().statusCode(200)
                .body("status", equalTo("PENDING_APPROVAL"))
                .body("approvalMode", equalTo("approval"));

        as(token).queryParam("status", "PENDING_APPROVAL")
                .when().get("/api/substitution-requests")
                .then().statusCode(200)
                .body("id", hasItem((int) requestId));

        as(token).body(Map.of("substituteTeacherId", context.teacherB(), "comment", "同意"))
                .when().post("/api/substitution-requests/" + requestId + "/approve")
                .then().statusCode(200)
                .body("status", equalTo("EFFECTIVE"))
                .body("substituteTeacherName", org.hamcrest.Matchers.notNullValue());

        as(token).queryParam("date", context.date().toString())
                .when().get("/api/timetable/class/" + context.classId())
                .then().statusCode(200)
                .body("find { it.id == " + context.entryId() + " }.substituted", equalTo(true))
                .body("find { it.id == " + context.entryId() + " }.teacherId",
                        equalTo((int) context.teacherB()));

        as(token).queryParam("view", "baseline").queryParam("date", context.date().toString())
                .when().get("/api/timetable/class/" + context.classId())
                .then().statusCode(200)
                .body("find { it.id == " + context.entryId() + " }.substituted", equalTo(false))
                .body("find { it.id == " + context.entryId() + " }.teacherId",
                        equalTo((int) context.teacherA()));

        as(token).when().get("/api/substitution-requests/" + requestId + "/history")
                .then().statusCode(200)
                .body("$", hasSize(4))
                .body("toStatus", hasItem("SUBMITTED"))
                .body("toStatus", hasItem("EFFECTIVE"));

        as(token).queryParam("teacherId", context.teacherB())
                .when().get("/api/substitutions/stats")
                .then().statusCode(200)
                .body("substitutedIn", greaterThanOrEqualTo(1));

        as(context.teacherToken()).when().get("/api/substitutions/my")
                .then().statusCode(200)
                .body("$", hasSize(greaterThanOrEqualTo(1)));
    }

    @Test
    void approvalSendsNotificationsToOriginalAndSubstitute() {
        String token = adminToken();
        Context context = prepare(token, TestData.suffix());
        long requestId = submit(context, context.teacherToken(), context.teacherB());
        as(token).body(Map.of("substituteTeacherId", context.teacherB()))
                .when().post("/api/substitution-requests/" + requestId + "/approve")
                .then().statusCode(200);

        as(context.teacherToken()).when().get("/api/notifications/unread-count")
                .then().statusCode(200)
                .body("count", greaterThanOrEqualTo(1));

        as(context.teacherToken()).when().get("/api/notifications/recent")
                .then().statusCode(200)
                .body("title", hasItem("调代申请已批准"))
                .body("content", hasItem(containsString("代课")));

        as(context.teacherToken()).when().post("/api/notifications/read-all")
                .then().statusCode(200);
        as(context.teacherToken()).when().get("/api/notifications/unread-count")
                .then().statusCode(200)
                .body("count", equalTo(0));
    }

    @Test
    void directModeTakesEffectImmediately() {
        String token = adminToken();
        as(token).body(Map.of("substitution.approval-mode", "direct"))
                .when().put("/api/settings").then().statusCode(200);

        Context context = prepare(token, TestData.suffix());
        long requestId = submit(context, context.teacherToken(), context.teacherB());

        as(context.teacherToken()).when().get("/api/substitution-requests/" + requestId)
                .then().statusCode(200)
                .body("status", equalTo("EFFECTIVE"))
                .body("approvalMode", equalTo("direct"));

        as(token).queryParam("date", context.date().toString())
                .when().get("/api/timetable/class/" + context.classId())
                .then().statusCode(200)
                .body("find { it.id == " + context.entryId() + " }.substituted", equalTo(true));
    }

    @Test
    void modeSwitchDoesNotAffectRequestsAlreadyInFlow() {
        String token = adminToken();
        Context context = prepare(token, TestData.suffix());
        long requestId = submit(context, context.teacherToken(), context.teacherB());

        as(token).body(Map.of("substitution.approval-mode", "direct"))
                .when().put("/api/settings").then().statusCode(200);

        as(token).body(Map.of("substituteTeacherId", context.teacherB()))
                .when().post("/api/substitution-requests/" + requestId + "/approve")
                .then().statusCode(200)
                .body("status", equalTo("EFFECTIVE"));
    }

    @Test
    void rejectionNotifiesRequester() {
        String token = adminToken();
        Context context = prepare(token, TestData.suffix());
        long requestId = submit(context, context.teacherToken(), context.teacherB());

        as(token).body(Map.of("comment", "当天已有安排"))
                .when().post("/api/substitution-requests/" + requestId + "/reject")
                .then().statusCode(200)
                .body("status", equalTo("REJECTED"))
                .body("approvalComment", equalTo("当天已有安排"));

        as(context.teacherToken()).when().get("/api/notifications/recent")
                .then().statusCode(200)
                .body("title", hasItem("调代申请被驳回"));
    }

    @Test
    void teacherCannotSubmitForAnotherTeachersLesson() {
        String token = adminToken();
        Context context = prepare(token, TestData.suffix());
        List<Map<String, Object>> entries = ScheduleFixture.classEntries(token, context.classId());
        Map<String, Object> otherEntry = entries.stream()
                .filter(item -> ((Number) item.get("teacherId")).longValue() == context.teacherB())
                .findFirst()
                .orElseThrow();
        LocalDate date = LocalDate.of(2025, 9, 1)
                .plusDays(((Number) otherEntry.get("dayOfWeek")).intValue() - 1L);

        as(context.teacherToken()).body(Map.of(
                        "entryId", ((Number) otherEntry.get("id")).longValue(),
                        "effectiveDate", date.toString(),
                        "expectedSubstituteTeacherId", context.teacherA()))
                .when().post("/api/substitution-requests")
                .then().statusCode(403);
    }

    @Test
    void dateWeekdayMustMatchTheLesson() {
        String token = adminToken();
        Context context = prepare(token, TestData.suffix());
        LocalDate wrongDate = context.date().plusDays(1);
        if (wrongDate.getDayOfWeek().getValue() == context.date().getDayOfWeek().getValue()) {
            wrongDate = context.date().plusDays(2);
        }

        as(context.teacherToken()).body(Map.of(
                        "entryId", context.entryId(),
                        "effectiveDate", wrongDate.toString(),
                        "expectedSubstituteTeacherId", context.teacherB()))
                .when().post("/api/substitution-requests")
                .then().statusCode(400);
    }

    @Test
    void substituteTeacherSeesTheLessonInTheirTimetable() {
        String token = adminToken();
        Context context = prepare(token, TestData.suffix());
        long requestId = submit(context, context.teacherToken(), context.teacherB());
        as(token).body(Map.of("substituteTeacherId", context.teacherB()))
                .when().post("/api/substitution-requests/" + requestId + "/approve")
                .then().statusCode(200);

        as(token).queryParam("date", context.date().toString())
                .when().get("/api/timetable/teacher/" + context.teacherB())
                .then().statusCode(200)
                .body("find { it.id == " + context.entryId() + " }.substituted", equalTo(true));

        as(token).queryParam("date", context.date().toString())
                .when().get("/api/timetable/teacher/" + context.teacherA())
                .then().statusCode(200)
                .body("find { it.id == " + context.entryId() + " }.substituteTeacherName",
                        org.hamcrest.Matchers.notNullValue());
    }
}
