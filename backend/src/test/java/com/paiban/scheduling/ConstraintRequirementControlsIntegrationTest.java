package com.paiban.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

import com.paiban.scheduling.domain.ConstraintEntry;
import com.paiban.scheduling.domain.ConstraintEntryRepository;
import com.paiban.scheduling.domain.DraftLesson;
import com.paiban.scheduling.domain.DraftLessonRepository;
import com.paiban.scheduling.domain.TimetableDraft;
import com.paiban.scheduling.domain.TimetableDraftRepository;
import com.paiban.support.ApiIntegrationTest;
import com.paiban.support.TestData;
import io.restassured.response.Response;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Ticket 14/01 through the public REST surface: the new {@code COURSE_UNAVAILABLE}
 * type, the per-requirement controls that travel with it, the group identity that
 * keeps the expanded slots one requirement, and the template round-trip that must not
 * drop any of them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ConstraintRequirementControlsIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private ConstraintEntryRepository entryRepository;

    @Autowired
    private TimetableDraftRepository draftRepository;

    @Autowired
    private DraftLessonRepository draftLessonRepository;

    @Test
    void groupedCourseUnavailableKeepsItsIdentityAndControlsAcrossReload() {
        String token = adminToken();
        long semesterId = semesterWithPeriods(token);
        long course = TestData.course(token, "体育" + TestData.suffix());

        save(token, semesterId, List.of(courseUnavailable(true, List.of(
                slot("g1", true, null, course, 1, 1),
                slot("g1", true, null, course, 1, 2),
                slot("g2", false, 70, course, 2, 1)))))
                .then().statusCode(200)
                .body("find { it.type == 'COURSE_UNAVAILABLE' }.entries", hasSize(3));

        List<Map<String, Object>> entries = reloadEntries(token, semesterId, "COURSE_UNAVAILABLE");
        assertThat(entries).hasSize(3);
        assertThat(entries).extracting(row -> row.get("groupKey"))
                .containsExactly("g1", "g1", "g2");
        assertThat(entries.get(0).get("hard")).isEqualTo(true);
        assertThat(entries.get(2).get("hard")).isEqualTo(false);
        assertThat(((Number) entries.get(2).get("weight")).intValue()).isEqualTo(70);
        assertThat(entries).allMatch(row -> Boolean.TRUE.equals(row.get("enabled")));

        List<ConstraintEntry> rows = entryRepository.findBySemesterIdAndTypeOrderByIdAsc(
                semesterId, "COURSE_UNAVAILABLE");
        assertThat(rows).hasSize(3);
        assertThat(rows).extracting(ConstraintEntry::getGroupKey)
                .containsExactly("g1", "g1", "g2");
        assertThat(rows.get(2).getHard()).isFalse();
        assertThat(rows.get(2).getWeight()).isEqualTo(70);
    }

    @Test
    void mixedControlsInsideOneGroupAreRejected() {
        String token = adminToken();
        long semesterId = semesterWithPeriods(token);
        long course = TestData.course(token, "化学" + TestData.suffix());

        Response rejected = save(token, semesterId, List.of(courseUnavailable(true, List.of(
                slot("same", true, null, course, 1, 1),
                slot("same", false, 40, course, 1, 2)))));

        rejected.then().statusCode(400);
        assertThat(rejected.asString()).contains("同一条要求").contains("共用");
        assertThat(entryRepository.findBySemesterIdAndTypeOrderByIdAsc(
                semesterId, "COURSE_UNAVAILABLE")).isEmpty();
    }

    @Test
    void sameSlotWithDifferentControlsIsAllowedButAnIdenticalDuplicateIsNot() {
        String token = adminToken();
        long semesterId = semesterWithPeriods(token);
        long course = TestData.course(token, "体育" + TestData.suffix());

        // Overlapping requirements: one must, one prefer. Both stay; evaluation counts
        // the fact once and lets hard win.
        save(token, semesterId, List.of(courseUnavailable(true, List.of(
                slot("must", true, null, course, 1, 1),
                slot("prefer", false, 60, course, 1, 1)))))
                .then().statusCode(200)
                .body("find { it.type == 'COURSE_UNAVAILABLE' }.entries", hasSize(2));

        Response duplicate = save(token, semesterId, List.of(courseUnavailable(true, List.of(
                slot("a", true, null, course, 1, 1),
                slot("b", true, null, course, 1, 1)))));
        duplicate.then().statusCode(400);
        assertThat(duplicate.asString()).contains("完全相同");
    }

    @Test
    void aPausedRequirementKeepsItsContentAndStopsBlocking() {
        String token = adminToken();
        long semesterId = semesterWithPeriods(token);
        long course = TestData.course(token, "体育" + TestData.suffix());

        save(token, semesterId, List.of(courseUnavailable(true, List.of(
                slot("g1", true, null, false, course, 1, 1)))))
                .then().statusCode(200)
                .body("find { it.type == 'COURSE_UNAVAILABLE' }.entries[0].enabled",
                        equalTo(false))
                .body("find { it.type == 'COURSE_UNAVAILABLE' }.entries[0].params.courseId",
                        notNullValue());

        long draftId = draftWithLesson(token, semesterId, course, 1, 1);
        assertThat(conflictCodes(token, draftId)).doesNotContain("COURSE_UNAVAILABLE");
    }

    @Test
    void hardCourseUnavailableBlocksDraftAndPublishWhileSoftDoesNotBlock() {
        String token = adminToken();
        long semesterId = semesterWithPeriods(token);
        long course = TestData.course(token, "体育" + TestData.suffix());

        save(token, semesterId, List.of(courseUnavailable(true, List.of(
                slot("hard", true, null, course, 1, 1),
                slot("soft", false, 65, course, 1, 2)))))
                .then().statusCode(200);

        long draftId = draftWithLesson(token, semesterId, course, 1, 1);
        assertThat(conflictCodes(token, draftId)).contains("COURSE_UNAVAILABLE");
        as(token).when().post("/api/timetable-drafts/" + draftId + "/publish")
                .then().statusCode(409);

        // The soft slot does not block: a draft that only breaks the soft requirement
        // has no conflict and keeps a positive independent soft score.
        long softOnlyDraft = draftWithLesson(token, semesterId, course, 1, 2);
        assertThat(conflictCodes(token, softOnlyDraft)).doesNotContain("COURSE_UNAVAILABLE");
    }

    @Test
    void courseUnavailableDoesNotRestrictTheTeachersOtherCourse() {
        String token = adminToken();
        long semesterId = semesterWithPeriods(token);
        long blockedCourse = TestData.course(token, "体育" + TestData.suffix());
        long otherCourse = TestData.course(token, "数学" + TestData.suffix());
        save(token, semesterId, List.of(courseUnavailable(true, List.of(
                slot("g1", true, null, blockedCourse, 1, 1)))))
                .then().statusCode(200);

        long draftId = draftWithLesson(token, semesterId, otherCourse, 1, 1);
        assertThat(conflictCodes(token, draftId)).doesNotContain("COURSE_UNAVAILABLE");
    }

    @Test
    void switchingTheTypeOffKeepsEveryPerRequirementState() {
        String token = adminToken();
        long semesterId = semesterWithPeriods(token);
        long course = TestData.course(token, "体育" + TestData.suffix());

        Response saved = save(token, semesterId, List.of(courseUnavailable(false, List.of(
                slot("g1", true, null, course, 1, 1),
                slot("g2", true, null, false, course, 1, 2)))))
                .then().statusCode(200).extract().response();
        List<Long> ids = entryIds(saved, "COURSE_UNAVAILABLE");

        List<Map<String, Object>> reloaded = reloadEntries(token, semesterId, "COURSE_UNAVAILABLE");
        assertThat(reloaded).hasSize(2);
        assertThat(reloaded.get(0).get("hard")).isEqualTo(true);
        assertThat(reloaded.get(1).get("enabled")).isEqualTo(false);

        // Turning the type back on does not reset the per-entry pause.
        Map<String, Object> entryOne = new LinkedHashMap<>();
        entryOne.put("id", ids.get(0));
        entryOne.put("params", Map.of("courseId", course, "dayOfWeek", 1, "periodNo", 1));
        entryOne.put("groupKey", "g1");
        entryOne.put("hard", true);
        Map<String, Object> entryTwo = new LinkedHashMap<>();
        entryTwo.put("id", ids.get(1));
        entryTwo.put("params", Map.of("courseId", course, "dayOfWeek", 1, "periodNo", 2));
        entryTwo.put("groupKey", "g2");
        entryTwo.put("hard", true);
        entryTwo.put("enabled", false);
        save(token, semesterId, List.of(Map.of(
                "type", "COURSE_UNAVAILABLE",
                "weight", 100,
                "hard", true,
                "enabled", true,
                "entries", List.of(entryOne, entryTwo)))).then().statusCode(200);

        List<Map<String, Object>> afterReenable = reloadEntries(
                token, semesterId, "COURSE_UNAVAILABLE");
        assertThat(afterReenable.stream()
                .map(row -> ((Number) row.get("id")).longValue()).toList())
                .containsExactly(ids.get(0), ids.get(1));
        assertThat(afterReenable.get(1).get("enabled")).isEqualTo(false);
    }

    @Test
    void templateRoundTripKeepsGroupsAndPerRequirementControls() {
        String token = adminToken();
        long source = semesterWithPeriods(token);
        long course = TestData.course(token, "体育" + TestData.suffix());
        save(token, source, List.of(courseUnavailable(true, List.of(
                slot("g1", true, null, course, 1, 1),
                slot("g1", true, null, course, 1, 2),
                slot("g2", false, 70, course, 2, 1)))))
                .then().statusCode(200);

        long templateId = as(token).queryParam("semesterId", source)
                .body(Map.of("name", "课程禁排-" + TestData.suffix()))
                .when().post("/api/constraint-templates")
                .then().statusCode(200)
                .body("entryCount", equalTo(3))
                .extract().jsonPath().getLong("id");

        long target = semesterWithPeriods(token);
        as(token).queryParam("semesterId", target)
                .when().post("/api/constraint-templates/" + templateId + "/apply")
                .then().statusCode(200)
                .body("find { it.type == 'COURSE_UNAVAILABLE' }.entries", hasSize(3));

        List<Map<String, Object>> restored = reloadEntries(
                token, target, "COURSE_UNAVAILABLE");
        assertThat(restored).extracting(row -> row.get("groupKey"))
                .containsExactly("g1", "g1", "g2");
        assertThat(restored.get(0).get("hard")).isEqualTo(true);
        assertThat(restored.get(2).get("hard")).isEqualTo(false);
        assertThat(((Number) restored.get(2).get("weight")).intValue()).isEqualTo(70);
    }

    // ------------------------------------------------------------------ helpers

    private long draftWithLesson(
            String token, long semesterId, long courseId, int dayOfWeek, int periodNo) {
        long gradeId = TestData.grade(token, semesterId, "高一" + TestData.suffix());
        long classId = TestData.adminClass(
                token, semesterId, gradeId, "高一1班" + TestData.suffix());
        long teacher = TestData.teacher(token, "任课" + TestData.suffix());
        long room = TestData.room(token, "教室" + TestData.suffix());
        Map<Integer, Long> periods = periodIds(token, semesterId);

        TimetableDraft draft = new TimetableDraft();
        draft.setSemesterId(semesterId);
        draft.setName("课程禁排草稿" + TestData.suffix());
        draft.setStatus("DRAFT");
        draft.setCreatedBy("test");
        draft.setCreatedAt(Instant.now());
        long draftId = draftRepository.save(draft).getId();

        DraftLesson lesson = new DraftLesson();
        lesson.setDraftId(draftId);
        lesson.setAssignmentId(0L);
        lesson.setLessonIndex(1);
        lesson.setCourseId(courseId);
        lesson.setTeacherId(teacher);
        lesson.setAdminClassId(classId);
        lesson.setStudentCount(45);
        lesson.setPeriodId(periods.get(dayOfWeek * 100 + periodNo));
        lesson.setRoomId(room);
        draftLessonRepository.save(lesson);
        return draftId;
    }

    private List<String> conflictCodes(String token, long draftId) {
        List<Map<String, Object>> rows = as(token)
                .when().get("/api/timetable-drafts/" + draftId + "/validation")
                .then().statusCode(200).extract().jsonPath().getList("conflicts");
        List<String> codes = new ArrayList<>();
        rows.forEach(row -> codes.add(String.valueOf(row.get("type"))));
        return codes;
    }

    private Map<Integer, Long> periodIds(String token, long semesterId) {
        List<Map<String, Object>> rows = as(token)
                .when().get("/api/semesters/" + semesterId + "/periods")
                .then().statusCode(200).extract().jsonPath().getList("$");
        Map<Integer, Long> bySlot = new LinkedHashMap<>();
        rows.forEach(row -> bySlot.put(
                ((Number) row.get("dayOfWeek")).intValue() * 100
                        + ((Number) row.get("periodNo")).intValue(),
                ((Number) row.get("id")).longValue()));
        return bySlot;
    }

    private long semesterWithPeriods(String token) {
        long semesterId = TestData.semester(token);
        as(token).body(Map.of("morningCount", 4, "afternoonCount", 4, "eveningCount", 0))
                .when().post("/api/semesters/" + semesterId + "/periods/default")
                .then().statusCode(200);
        return semesterId;
    }

    private Response save(String token, long semesterId, List<Map<String, Object>> configs) {
        return as(token).queryParam("semesterId", semesterId)
                .body(Map.of("configs", configs))
                .when().put("/api/constraints");
    }

    private Map<String, Object> courseUnavailable(
            boolean enabled, List<Map<String, Object>> entries) {
        return Map.of(
                "type", "COURSE_UNAVAILABLE",
                "weight", 100,
                "hard", true,
                "enabled", enabled,
                "entries", entries);
    }

    private Map<String, Object> slot(
            String group, boolean hard, Integer weight, long courseId,
            int dayOfWeek, int periodNo) {
        return slot(group, hard, weight, true, courseId, dayOfWeek, periodNo);
    }

    private Map<String, Object> slot(
            String group, boolean hard, Integer weight, boolean enabled, long courseId,
            int dayOfWeek, int periodNo) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("params", Map.of(
                "courseId", courseId, "dayOfWeek", dayOfWeek, "periodNo", periodNo));
        row.put("groupKey", group);
        row.put("hard", hard);
        row.put("enabled", enabled);
        if (weight != null) {
            row.put("weight", weight);
        }
        return row;
    }

    private List<Map<String, Object>> reloadEntries(String token, long semesterId, String type) {
        return as(token).queryParam("semesterId", semesterId)
                .when().get("/api/constraints")
                .then().statusCode(200).extract().jsonPath()
                .getList("find { it.type == '" + type + "' }.entries");
    }

    private List<Long> entryIds(Response response, String type) {
        List<Map<String, Object>> rows = response.jsonPath()
                .getList("find { it.type == '" + type + "' }.entries");
        return rows.stream().map(row -> ((Number) row.get("id")).longValue()).toList();
    }
}
