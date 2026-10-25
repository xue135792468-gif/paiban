package com.paiban.timetable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;

import com.paiban.scheduling.domain.DraftLesson;
import com.paiban.scheduling.domain.DraftLessonRepository;
import com.paiban.scheduling.domain.TimetableDraft;
import com.paiban.scheduling.domain.TimetableDraftRepository;
import com.paiban.support.ApiIntegrationTest;
import com.paiban.support.ScheduleFixture;
import io.restassured.response.Response;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * The whole-draft check and publishing must answer the same question the same way.
 * Ticket 13: a draft whose lessons all lack a room used to report {@code valid=true}
 * and then fail publishing with 400.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class DraftPublishReadinessIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private TimetableDraftRepository drafts;

    @Autowired
    private DraftLessonRepository draftLessons;

    @Test
    void draftWithoutRoomsIsNeitherValidNorPublishable() {
        String token = adminToken();
        long draftId = ScheduleFixture.solved(token, 5).draftId();
        List<Map<String, Object>> lessons = ScheduleFixture.lessons(token, draftId);
        clearRooms(token, draftId, lessons);

        Response validation = validation(token, draftId);
        assertThat(validation.jsonPath().getBoolean("valid")).isFalse();
        assertThat(validation.jsonPath().getBoolean("publishable")).isFalse();
        // Missing rooms are a resource gap, not a scheduling conflict.
        assertThat(validation.jsonPath().getInt("hardConflicts")).isZero();
        assertThat(validation.jsonPath().getList("conflicts")).isEmpty();

        Map<String, Object> missingRooms = blocker(validation, "MISSING_ROOMS");
        assertThat(count(missingRooms)).isEqualTo(lessons.size());
        assertThat((String) missingRooms.get("message")).contains(String.valueOf(lessons.size()));
        assertThat((String) missingRooms.get("action")).isNotBlank();
        assertThat(blocker(validation, "UNSCHEDULED_LESSONS")).isNull();

        as(token).when().post("/api/timetable-drafts/" + draftId + "/publish")
                .then().statusCode(400)
                .body("message", containsString(String.valueOf(lessons.size())));
    }

    @Test
    void assigningRoomsMakesTheDraftValidAndPublishable()  {
        String token = adminToken();
        long draftId = ScheduleFixture.solved(token, 5).draftId();
        List<Map<String, Object>> lessons = ScheduleFixture.lessons(token, draftId);
        clearRooms(token, draftId, lessons);

        for (Map<String, Object> lesson : lessons) {
            Map<String, Object> move = new HashMap<>();
            move.put("periodId", lesson.get("periodId"));
            move.put("roomId", lesson.get("roomId"));
            as(token).body(move)
                    .when().patch("/api/timetable-drafts/" + draftId + "/lessons/" + lesson.get("id"))
                    .then().statusCode(200);
        }
        // The lesson maps above still hold the original room ids; re-read to confirm.
        List<Map<String, Object>> restored = ScheduleFixture.lessons(token, draftId);
        assertThat(restored).allSatisfy(lesson -> assertThat(lesson.get("roomId")).isNotNull());

        Response validation = validation(token, draftId);
        assertThat(validation.jsonPath().getBoolean("valid")).isTrue();
        assertThat(validation.jsonPath().getBoolean("publishable")).isTrue();
        assertThat(validation.jsonPath().getList("blockers")).isEmpty();
        assertThat(validation.jsonPath().getInt("hardConflicts")).isZero();

        as(token).when().post("/api/timetable-drafts/" + draftId + "/publish")
                .then().statusCode(200)
                .body("entryCount", greaterThan(0));
    }

    @Test
    void localMoveValidationIgnoresLessonsStillMissingRooms() {
        String token = adminToken();
        long draftId = ScheduleFixture.solved(token, 5).draftId();
        List<Map<String, Object>> lessons = ScheduleFixture.lessons(token, draftId);

        // One lesson loses its room; a different lesson is still validated exactly as before.
        Map<String, Object> broken = lessons.get(0);
        Map<String, Object> move = new HashMap<>();
        move.put("periodId", broken.get("periodId"));
        move.put("roomId", null);
        as(token).body(move)
                .when().patch("/api/timetable-drafts/" + draftId + "/lessons/" + broken.get("id"))
                .then().statusCode(200);

        Map<String, Object> untouched = lessons.get(lessons.size() - 1);
        as(token).body(Map.of(
                        "lessonId", untouched.get("id"),
                        "periodId", untouched.get("periodId"),
                        "roomId", untouched.get("roomId")))
                .when().post("/api/timetable-drafts/" + draftId + "/validate-move")
                .then().statusCode(200)
                .body("valid", equalTo(true))
                .body("hardConflicts", equalTo(0))
                .body("conflicts", org.hamcrest.Matchers.empty());
    }

    @Test
    void emptyDraftStillRefusesToPublishWithBadRequest() {
        String token = adminToken();
        long semesterId = com.paiban.support.TestData.semester(token);
        long draftId = seedDraft(semesterId, "空草稿", List.of());

        as(token).when().post("/api/timetable-drafts/" + draftId + "/publish")
                .then().statusCode(400);

        Response validation = validation(token, draftId);
        assertThat(validation.jsonPath().getBoolean("valid")).isFalse();
        assertThat(validation.jsonPath().getBoolean("publishable")).isFalse();
        assertThat(blocker(validation, "DRAFT_EMPTY")).isNotNull();
    }

    @Test
    void semesterWithAPublishedBaselineStillRefusesASecondPublish() {
        String token = adminToken();
        ScheduleFixture.Result fixture = ScheduleFixture.solved(token, 5);
        as(token).when().post("/api/timetable-drafts/" + fixture.draftId() + "/publish")
                .then().statusCode(200);

        long secondDraftId = seedDraft(
                fixture.semesterId(), "第二份草稿",
                draftLessons.findByDraftIdOrderByIdAsc(fixture.draftId()));

        Response validation = validation(token, secondDraftId);
        // The content is fine; only the already-published semester stands in the way, and a
        // terminal state is not what `valid` reports (it stays backward compatible).
        assertThat(validation.jsonPath().getBoolean("valid")).isTrue();
        assertThat(validation.jsonPath().getBoolean("publishable")).isFalse();
        assertThat(blocker(validation, "SEMESTER_ALREADY_PUBLISHED")).isNotNull();

        as(token).when().post("/api/timetable-drafts/" + secondDraftId + "/publish")
                .then().statusCode(409);
    }

    @Test
    void aPublishedDraftReadsBackAsValidButNotPublishable() {
        String token = adminToken();
        long draftId = ScheduleFixture.solved(token, 5).draftId();
        as(token).when().post("/api/timetable-drafts/" + draftId + "/publish")
                .then().statusCode(200);

        Response validation = validation(token, draftId);
        // Ticket 13 promised the response stays compatible: publishing a draft does not make
        // its own content invalid, it only stops a second publish.
        assertThat(validation.jsonPath().getBoolean("valid")).isTrue();
        assertThat(validation.jsonPath().getBoolean("publishable")).isFalse();
        assertThat(validation.jsonPath().getInt("hardConflicts")).isZero();
        assertThat(blocker(validation, "DRAFT_PUBLISHED")).isNotNull();
    }

    private Response validation(String token, long draftId) {
        return as(token).when().get("/api/timetable-drafts/" + draftId + "/validation")
                .then().statusCode(200).extract().response();
    }

    /** Clears every lesson's room through the public move endpoint. */
    private void clearRooms(String token, long draftId, List<Map<String, Object>> lessons) {
        for (Map<String, Object> lesson : lessons) {
            Map<String, Object> move = new HashMap<>();
            move.put("periodId", lesson.get("periodId"));
            move.put("roomId", null);
            as(token).body(move)
                    .when().patch("/api/timetable-drafts/" + draftId + "/lessons/" + lesson.get("id"))
                    .then().statusCode(200);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> blocker(Response validation, String code) {
        List<Map<String, Object>> blockers = validation.jsonPath().getList("blockers");
        if (blockers == null) {
            return null;
        }
        return blockers.stream()
                .filter(item -> code.equals(item.get("code")))
                .findFirst()
                .orElse(null);
    }

    private static int count(Map<String, Object> blocker) {
        assertThat(blocker).as("blocker present").isNotNull();
        return ((Number) blocker.get("count")).intValue();
    }

    /** Seeds a draft (and optionally copies lessons) without going through the solver. */
    private long seedDraft(long semesterId, String name, List<DraftLesson> sourceLessons) {
        TimetableDraft draft = new TimetableDraft();
        draft.setSemesterId(semesterId);
        draft.setName(name);
        draft.setCreatedBy("admin");
        TimetableDraft saved = drafts.save(draft);
        List<DraftLesson> copies = new ArrayList<>();
        for (DraftLesson source : sourceLessons) {
            DraftLesson copy = new DraftLesson();
            copy.setDraftId(saved.getId());
            copy.setAssignmentId(source.getAssignmentId());
            copy.setLessonIndex(source.getLessonIndex());
            copy.setCourseId(source.getCourseId());
            copy.setTeacherId(source.getTeacherId());
            copy.setAdminClassId(source.getAdminClassId());
            copy.setStudentCount(source.getStudentCount());
            copy.setPeriodId(source.getPeriodId());
            copy.setRoomId(source.getRoomId());
            copies.add(copy);
        }
        draftLessons.saveAll(copies);
        return saved.getId();
    }
}
