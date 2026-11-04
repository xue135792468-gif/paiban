package com.paiban.timetable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

import com.paiban.substitution.domain.Substitution;
import com.paiban.substitution.domain.SubstitutionHistoryRepository;
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
    private SubstitutionHistoryRepository substitutionHistory;

    @Autowired
    private TimetablePublishRepository publishes;

    @Autowired
    private TimetableEntryRepository entries;

    private record Seeded(ScheduleFixture.Result fixture, long entryId,
                          LocalDate pastDate, List<LocalDate> futureDates) {}

    /** The slot the seeded substitutions point at, copied out of a published entry. */
    private record Slot(long entryId, long classId, long courseId, long teacherId,
                        long periodId, int dayOfWeek, int periodNo) {}

    /**
     * A future semester with a published baseline and three effective substitutions:
     * one past row plus two future rows, all pointing at the same timetable entry.
     */
    private Seeded publishedWithSubstitutions(String token) {
        ScheduleFixture.Result fixture = publishedBaseline(token);
        Slot slot = slotOf(token, fixture);
        LocalDate monday = ScheduleFixture.nextMonday();
        LocalDate pastDate = LocalDate.now(com.paiban.substitution.service.LessonDeadline.SCHOOL_ZONE)
                .minusDays(5);
        List<LocalDate> futureDates = List.of(monday, monday.plusDays(1));
        for (LocalDate date : List.of(pastDate, monday, monday.plusDays(1))) {
            seedEffective(fixture.semesterId(), slot, fixture.otherTeacherId(), date, date);
        }
        return new Seeded(fixture, slot.entryId(), pastDate, futureDates);
    }

    /** A semester anchored on the next Monday with its baseline already published. */
    private ScheduleFixture.Result publishedBaseline(String token) {
        long semesterId = TestData.semesterStarting(token, ScheduleFixture.nextMonday());
        return ScheduleFixture.publishedIn(token, 5, semesterId);
    }

    private Slot slotOf(String token, ScheduleFixture.Result fixture) {
        Map<String, Object> entry = ScheduleFixture.classEntries(token, fixture.classId()).get(0);
        return new Slot(
                number(entry, "id"),
                number(entry, "classId"),
                number(entry, "courseId"),
                number(entry, "teacherId"),
                number(entry, "periodId"),
                ((Number) entry.get("dayOfWeek")).intValue(),
                ((Number) entry.get("periodNo")).intValue());
    }

    /** An EFFECTIVE request plus its EFFECTIVE overlay row for one instance date. */
    private SubstitutionRequest seedEffective(long semesterId, Slot slot, long substituteTeacherId,
                                              LocalDate effectiveDate, LocalDate lessonDate) {
        SubstitutionRequest request = seedRequest(
                semesterId, slot, effectiveDate, SubstitutionRequest.EFFECTIVE);
        Substitution substitution = new Substitution();
        substitution.setSemesterId(semesterId);
        substitution.setRequestId(request.getId());
        substitution.setEntryId(slot.entryId());
        substitution.setEffectiveDate(effectiveDate);
        substitution.setLessonDate(lessonDate);
        substitution.setOriginalTeacherId(slot.teacherId());
        substitution.setSubstituteTeacherId(substituteTeacherId);
        substitution.setType(Substitution.TYPE_COVER);
        substitution.setStatus(Substitution.EFFECTIVE);
        substitutions.save(substitution);
        return request;
    }

    /** An approved request that has not produced an overlay row yet. */
    private SubstitutionRequest seedApproved(long semesterId, Slot slot, LocalDate effectiveDate) {
        return seedRequest(semesterId, slot, effectiveDate, SubstitutionRequest.APPROVED);
    }

    private SubstitutionRequest seedRequest(long semesterId, Slot slot, LocalDate effectiveDate,
                                            String status) {
        SubstitutionRequest request = new SubstitutionRequest();
        request.setSemesterId(semesterId);
        request.setEntryId(slot.entryId());
        request.setAdminClassId(slot.classId());
        request.setCourseId(slot.courseId());
        request.setOriginalTeacherId(slot.teacherId());
        request.setEffectiveDate(effectiveDate);
        request.setPeriodId(slot.periodId());
        request.setDayOfWeek(slot.dayOfWeek());
        request.setPeriodNo(slot.periodNo());
        request.setType(Substitution.TYPE_COVER);
        request.setStatus(status);
        request.setApprovalMode("approval");
        substitutionRequests.save(request);
        return request;
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
                .body("allSubstitutions", equalTo(3));

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

        // The request must follow its cancelled rows: a cancelled arrangement that still
        // reads EFFECTIVE/APPROVED would tell the request page the lesson is covered.
        List<SubstitutionRequest> requestRows =
                substitutionRequests.findBySemesterIdOrderByCreatedAtDesc(semesterId);
        assertThat(requestRows).filteredOn(request -> request.getEffectiveDate().isAfter(seeded.pastDate()))
                .allSatisfy(request -> {
                    assertThat(request.getStatus()).isEqualTo(SubstitutionRequest.CANCELLED);
                    assertThat(substitutionHistory.findByRequestIdOrderByCreatedAtAsc(request.getId()))
                            .singleElement()
                            .satisfies(item -> {
                                assertThat(item.getFromStatus()).isEqualTo(SubstitutionRequest.EFFECTIVE);
                                assertThat(item.getToStatus()).isEqualTo(SubstitutionRequest.CANCELLED);
                                assertThat(item.getActor()).isEqualTo("admin");
                                assertThat(item.getComment()).contains("发布撤回");
                            });
                });
        assertThat(requestRows).filteredOn(
                        request -> request.getEffectiveDate().equals(seeded.pastDate()))
                .singleElement()
                .satisfies(request -> {
                    assertThat(request.getStatus()).isEqualTo(SubstitutionRequest.EFFECTIVE);
                    assertThat(substitutionHistory.findByRequestIdOrderByCreatedAtAsc(request.getId()))
                            .isEmpty();
                });

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

        // Publishing reports how many cancelled substitutions still cover future dates, so
        // the page can prompt for re-processing instead of staying silent.
        as(token).body(Map.of())
                .when().post("/api/timetable-drafts/" + draftId + "/publish")
                .then().statusCode(200)
                .body("entryCount", greaterThan(0))
                .body("cancelledSubstitutions", equalTo(2));

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

    /**
     * Legacy rows (no lesson_date) mean "from effective_date onward": one that still
     * starts today or later loses its anchor, one that started in the past is history.
     */
    @Test
    void legacyRowsWithoutLessonDateAreCancelledOnlyWhenTheyStillLieAhead() {
        String token = adminToken();
        ScheduleFixture.Result fixture = publishedBaseline(token);
        Slot slot = slotOf(token, fixture);
        LocalDate today = LocalDate.now(com.paiban.substitution.service.LessonDeadline.SCHOOL_ZONE);
        seedEffective(fixture.semesterId(), slot, fixture.otherTeacherId(), today, null);
        seedEffective(fixture.semesterId(), slot, fixture.otherTeacherId(), today.minusDays(9), null);

        as(token).queryParam("semesterId", fixture.semesterId())
                .when().get("/api/timetable/withdraw-preview")
                .then().statusCode(200)
                .body("futureSubstitutions", equalTo(1));

        as(token).when().post("/api/timetable-drafts/" + fixture.draftId() + "/withdraw")
                .then().statusCode(200)
                .body("cancelledSubstitutions", equalTo(1));

        assertThat(substitutions.findBySemesterIdOrderByEffectiveDateDesc(fixture.semesterId()))
                .filteredOn(row -> row.getEffectiveDate().equals(today))
                .singleElement()
                .satisfies(row -> assertThat(row.getStatus()).isEqualTo(Substitution.CANCELLED));
        assertThat(substitutions.findBySemesterIdOrderByEffectiveDateDesc(fixture.semesterId()))
                .filteredOn(row -> row.getEffectiveDate().isBefore(today))
                .singleElement()
                .satisfies(row -> assertThat(row.getStatus()).isEqualTo(Substitution.EFFECTIVE));
    }

    /**
     * An approved request for a future date has no row yet, but it still must not stay
     * APPROVED once the baseline it was approved against is gone.
     */
    @Test
    void approvedFutureRequestIsCountedAndCancelledWithItsHistory() {
        String token = adminToken();
        ScheduleFixture.Result fixture = publishedBaseline(token);
        Slot slot = slotOf(token, fixture);
        LocalDate monday = ScheduleFixture.nextMonday();
        SubstitutionRequest request = seedApproved(fixture.semesterId(), slot, monday);

        as(token).queryParam("semesterId", fixture.semesterId())
                .when().get("/api/timetable/withdraw-preview")
                .then().statusCode(200)
                .body("futureSubstitutions", equalTo(1));

        as(token).when().post("/api/timetable-drafts/" + fixture.draftId() + "/withdraw")
                .then().statusCode(200)
                .body("cancelledSubstitutions", equalTo(1));

        assertThat(substitutionRequests.findById(request.getId()))
                .hasValueSatisfying(row -> {
                    assertThat(row.getStatus()).isEqualTo(SubstitutionRequest.CANCELLED);
                    assertThat(row.getEntryId()).isNull();
                });
        assertThat(substitutionHistory.findByRequestIdOrderByCreatedAtAsc(request.getId()))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.getFromStatus()).isEqualTo(SubstitutionRequest.APPROVED);
                    assertThat(item.getToStatus()).isEqualTo(SubstitutionRequest.CANCELLED);
                    assertThat(item.getActor()).isEqualTo("admin");
                });
    }

    /**
     * A semester only ever has one live baseline, however many withdraw/publish cycles
     * run. The demotion inside publish() itself is pinned by
     * {@code TimetablePublishServiceTest}: through the API a second publish is refused
     * with SEMESTER_ALREADY_PUBLISHED long before that line can run.
     */
    @Test
    void repeatedWithdrawAndPublishKeepsExactlyOneActiveRow() {
        String token = adminToken();
        ScheduleFixture.Result fixture = publishedBaseline(token);
        long semesterId = fixture.semesterId();
        withdraw(token, fixture.draftId());

        as(token).body(Map.of())
                .when().post("/api/timetable-drafts/" + fixture.draftId() + "/publish")
                .then().statusCode(200);
        withdraw(token, fixture.draftId());
        as(token).body(Map.of())
                .when().post("/api/timetable-drafts/" + fixture.draftId() + "/publish")
                .then().statusCode(200);

        List<TimetablePublish> rows = publishes.findBySemesterIdOrderByPublishedAtDesc(semesterId);
        assertThat(rows).hasSize(3);
        assertThat(rows).filteredOn(row -> TimetablePublish.ACTIVE.equals(row.getStatus())).hasSize(1);
        assertThat(rows).filteredOn(row -> TimetablePublish.WITHDRAWN.equals(row.getStatus()))
                .hasSize(2)
                .allSatisfy(row -> {
                    assertThat(row.getWithdrawnAt()).isNotNull();
                    assertThat(row.getWithdrawnBy()).isEqualTo("admin");
                });
    }
}
