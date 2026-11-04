package com.paiban.timetable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

import com.paiban.substitution.domain.Substitution;
import com.paiban.substitution.domain.SubstitutionRepository;
import com.paiban.substitution.domain.SubstitutionRequest;
import com.paiban.substitution.domain.SubstitutionRequestRepository;
import com.paiban.support.ApiIntegrationTest;
import com.paiban.support.ScheduleFixture;
import com.paiban.support.TestData;
import com.paiban.timetable.domain.TimetableEntryRepository;
import com.paiban.timetable.domain.TimetablePublish;
import com.paiban.timetable.domain.TimetablePublishRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Ticket 14: withdrawing a published baseline must keep substitution history and
 * cancel only future effective rows; re-publishing afterwards rebuilds the baseline.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class WithdrawIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private SubstitutionRepository substitutions;

    @Autowired
    private SubstitutionRequestRepository substitutionRequests;

    @Autowired
    private TimetablePublishRepository publishes;

    @Autowired
    private TimetableEntryRepository entries;

    private record Seeded(ScheduleFixture.Result fixture, long entryId,
                          LocalDate pastDate, List<LocalDate> futureDates) {}

    /**
     * A future semester with a published baseline and three effective substitutions:
     * one past row plus two future rows, all pointing at the same timetable entry.
     */
    private Seeded publishedWithSubstitutions(String token) {
        LocalDate monday = ScheduleFixture.nextMonday();
        long semesterId = TestData.semesterStarting(token, monday);
        ScheduleFixture.Result fixture = ScheduleFixture.publishedIn(token, 5, semesterId);
        List<Map<String, Object>> classEntries = ScheduleFixture.classEntries(token, fixture.classId());
        Map<String, Object> entry = classEntries.get(0);
        long entryId = number(entry, "id");
        long classId = number(entry, "classId");
        long courseId = number(entry, "courseId");
        long teacherId = number(entry, "teacherId");
        long periodId = number(entry, "periodId");
        int dayOfWeek = ((Number) entry.get("dayOfWeek")).intValue();
        int periodNo = ((Number) entry.get("periodNo")).intValue();

        LocalDate today = LocalDate.now();
        LocalDate pastDate = today.minusDays(5);
        List<LocalDate> futureDates = List.of(monday, monday.plusDays(1));
        for (LocalDate date : List.of(pastDate, monday, monday.plusDays(1))) {
            SubstitutionRequest request = new SubstitutionRequest();
            request.setSemesterId(semesterId);
            request.setEntryId(entryId);
            request.setAdminClassId(classId);
            request.setCourseId(courseId);
            request.setOriginalTeacherId(teacherId);
            request.setEffectiveDate(date);
            request.setPeriodId(periodId);
            request.setDayOfWeek(dayOfWeek);
            request.setPeriodNo(periodNo);
            request.setType(Substitution.TYPE_COVER);
            request.setStatus(SubstitutionRequest.EFFECTIVE);
            request.setApprovalMode("approval");
            substitutionRequests.save(request);

            Substitution substitution = new Substitution();
            substitution.setSemesterId(semesterId);
            substitution.setRequestId(request.getId());
            substitution.setEntryId(entryId);
            substitution.setEffectiveDate(date);
            substitution.setLessonDate(date);
            substitution.setOriginalTeacherId(teacherId);
            substitution.setSubstituteTeacherId(fixture.otherTeacherId());
            substitution.setType(Substitution.TYPE_COVER);
            substitution.setStatus(Substitution.EFFECTIVE);
            substitutions.save(substitution);
        }
        return new Seeded(fixture, entryId, pastDate, futureDates);
    }

    private static long number(Map<String, Object> map, String key) {
        return ((Number) map.get(key)).longValue();
    }

    private void withdraw(String token, long draftId) {
        as(token).when().post("/api/timetable-drafts/" + draftId + "/withdraw")
                .then().statusCode(200);
    }

    @Test
    void withdrawKeepsHistoryAndCancelsOnlyFutureSubstitutions() {
        String token = adminToken();
        Seeded seeded = publishedWithSubstitutions(token);
        long semesterId = seeded.fixture().semesterId();
        long draftId = seeded.fixture().draftId();

        as(token).queryParam("semesterId", semesterId)
                .when().get("/api/timetable/withdraw-preview")
                .then().statusCode(200)
                .body("published", equalTo(true))
                .body("draftId", equalTo((int) draftId))
                .body("futureSubstitutions", equalTo(2))
                .body("totalSubstitutions", equalTo(3));

        as(token).when().post("/api/timetable-drafts/" + draftId + "/withdraw")
                .then().statusCode(200)
                .body("cancelledSubstitutions", equalTo(2))
                .body("removedEntries", greaterThan(0));

        as(token).when().get("/api/timetable-drafts/" + draftId)
                .then().statusCode(200)
                .body("draft.status", equalTo("DRAFT"));
        as(token).when().get("/api/timetable/class/" + seeded.fixture().classId())
                .then().statusCode(200)
                .body("$", empty());

        List<TimetablePublish> history = publishes.findBySemesterIdOrderByPublishedAtDesc(semesterId);
        assertThat(history).hasSize(1);
        assertThat(history.get(0).getStatus()).isEqualTo(TimetablePublish.WITHDRAWN);
        assertThat(history.get(0).getWithdrawnAt()).isNotNull();
        assertThat(history.get(0).getWithdrawnBy()).isEqualTo("admin");

        // Every row survives; the baseline deletion only nulls entry_id via the FK.
        List<Substitution> rows = substitutions.findBySemesterIdOrderByEffectiveDateDesc(semesterId);
        assertThat(rows).hasSize(3);
        assertThat(rows).allSatisfy(row -> assertThat(row.getEntryId()).isNull());
        assertThat(rows).filteredOn(row -> row.getEffectiveDate().isAfter(seeded.pastDate()))
                .allSatisfy(row -> assertThat(row.getStatus()).isEqualTo(Substitution.CANCELLED));
        assertThat(rows).filteredOn(row -> row.getEffectiveDate().equals(seeded.pastDate()))
                .singleElement()
                .satisfies(row -> assertThat(row.getStatus()).isEqualTo(Substitution.EFFECTIVE));

        assertThat(substitutionRequests.findBySemesterIdOrderByCreatedAtDesc(semesterId))
                .hasSize(3)
                .allSatisfy(request -> assertThat(request.getEntryId()).isNull());

        as(token).when().get("/api/foundation/audit-logs")
                .then().statusCode(200)
                .body("action", hasItem("WITHDRAW_TIMETABLE"));
    }

    @Test
    void withdrawnDraftCanBeEditedAndRepublishedWithFreshPublishInfo() {
        String token = adminToken();
        Seeded seeded = publishedWithSubstitutions(token);
        long semesterId = seeded.fixture().semesterId();
        long draftId = seeded.fixture().draftId();
        withdraw(token, draftId);

        // The new terminal state no longer blocks publishing, and the content is valid.
        as(token).when().get("/api/timetable-drafts/" + draftId + "/validation")
                .then().statusCode(200)
                .body("publishable", equalTo(true))
                .body("blockers.code", org.hamcrest.Matchers.not(hasItem("SEMESTER_ALREADY_PUBLISHED")));

        List<Map<String, Object>> lessons = ScheduleFixture.lessons(token, draftId);
        Map<String, Object> first = lessons.get(0);
        Map<String, Object> move = new HashMap<>();
        move.put("periodId", first.get("periodId"));
        move.put("force", true);
        as(token).body(move)
                .when().patch("/api/timetable-drafts/" + draftId + "/lessons/" + first.get("id"))
                .then().statusCode(200)
                .body("saved", equalTo(true));

        as(token).when().post("/api/timetable-drafts/" + draftId + "/publish")
                .then().statusCode(200)
                .body("entryCount", greaterThan(0));

        as(token).when().get("/api/timetable/publish-info")
                .then().statusCode(200)
                .body("published", equalTo(true))
                .body("status", equalTo(TimetablePublish.ACTIVE))
                .body("withdrawnAt", org.hamcrest.Matchers.nullValue());

        // The past EFFECTIVE history row now has entry_id NULL; it must not break the
        // overlay map of the freshly published baseline.
        as(token).when().get("/api/timetable/class/" + seeded.fixture().classId())
                .then().statusCode(200)
                .body("$", hasSize(greaterThan(0)));

        List<TimetablePublish> history = publishes.findBySemesterIdOrderByPublishedAtDesc(semesterId);
        assertThat(history).hasSize(2);
        assertThat(history).filteredOn(row -> TimetablePublish.ACTIVE.equals(row.getStatus())).hasSize(1);
        assertThat(history).filteredOn(row -> TimetablePublish.WITHDRAWN.equals(row.getStatus())).hasSize(1);
        assertThat(entries.countBySemesterId(semesterId)).isGreaterThan(0);

        // Old substitution rows are still there; re-publishing created new entry ids.
        List<Substitution> rows = substitutions.findBySemesterIdOrderByEffectiveDateDesc(semesterId);
        assertThat(rows).hasSize(3).allSatisfy(row -> assertThat(row.getEntryId()).isNull());
        Long activeEntry = entries.findBySemesterId(semesterId).get(0).getId();
        assertThat(activeEntry).isNotEqualTo(seeded.entryId());

        as(token).when().get("/api/foundation/audit-logs")
                .then().statusCode(200)
                .body("action", hasItem("PUBLISH_TIMETABLE"))
                .body("action", hasItem("WITHDRAW_TIMETABLE"));
    }

    @Test
    void withdrawRefusesUnpublishedDraftAndTeachers() {
        String token = adminToken();
        ScheduleFixture.Result draft = ScheduleFixture.solved(token, 5);

        as(token).when().post("/api/timetable-drafts/" + draft.draftId() + "/withdraw")
                .then().statusCode(409);

        long semesterId = draft.semesterId();
        ScheduleFixture.Result published = ScheduleFixture.publishedIn(token, 5, semesterId);
        String teacherUsername = "withdraw_" + TestData.suffix();
        as(token).body(Map.of(
                        "username", teacherUsername,
                        "password", "teacher123",
                        "displayName", "任课教师",
                        "roleCodes", List.of("teacher"),
                        "teacherId", published.teacherId()))
                .when().post("/api/users").then().statusCode(200);
        String teacherToken = login(teacherUsername, "teacher123");

        as(teacherToken).when()
                .post("/api/timetable-drafts/" + published.draftId() + "/withdraw")
                .then().statusCode(403);
        as(teacherToken).when().get("/api/timetable/withdraw-preview")
                .then().statusCode(403);
    }

    @Test
    void viewsAreEmptyRatherThanErrorsWithoutAnActivePublish() {
        String token = adminToken();
        ScheduleFixture.Result fixture = ScheduleFixture.published(token, 5);
        as(token).when().post("/api/timetable-drafts/" + fixture.draftId() + "/withdraw")
                .then().statusCode(200);

        as(token).when().get("/api/timetable/class/" + fixture.classId())
                .then().statusCode(200).body("$", empty());
        as(token).when().get("/api/timetable/teacher/" + fixture.teacherId())
                .then().statusCode(200).body("$", empty());
        as(token).when().get("/api/timetable/school")
                .then().statusCode(200)
                .body("entries", empty())
                .body("classIds", empty());

        as(token).when().get("/api/timetable/publish-info")
                .then().statusCode(200)
                .body("published", equalTo(false))
                .body("status", equalTo(TimetablePublish.WITHDRAWN))
                .body("withdrawnAt", notNullValue());

        as(token).queryParam("semesterId", fixture.semesterId())
                .when().get("/api/timetable/withdraw-preview")
                .then().statusCode(200)
                .body("published", equalTo(false))
                .body("futureSubstitutions", equalTo(0));
    }
}
