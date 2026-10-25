package com.paiban.foundation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

import com.paiban.ai.imports.ImportDtos.RowDto;
import com.paiban.ai.imports.TimetableImportService;
import com.paiban.scheduling.domain.DraftLesson;
import com.paiban.scheduling.domain.DraftLessonRepository;
import com.paiban.support.ApiIntegrationTest;
import com.paiban.support.TestData;
import io.restassured.response.Response;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

/**
 * Ticket 11: saving the period table used to delete and recreate every row, which
 * silently orphaned {@code draft_lesson.period_id}. These tests pin the user-visible
 * behaviour of the fix: identity is kept per (day, periodNo), removing a slot that a
 * draft still uses is refused whole, and a published semester stays read-only.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class PeriodReferenceIntegrityTest extends ApiIntegrationTest {

    private static final String FIRST_LESSON_SLOT_LABEL = "第一节（调整时间）";

    @Autowired
    private TimetableImportService importService;

    @Autowired
    private DraftLessonRepository draftLessons;

    private record Fixture(String token, long semesterId, long draftId, long periodId) {}

    /** One semester with two morning periods (10 slots) and one imported lesson at 周一第 1 节. */
    private Fixture fixture() {
        String token = adminToken();
        long semesterId = TestData.semester(token);
        as(token).body(Map.of("morningCount", 2, "afternoonCount", 0, "eveningCount", 0))
                .when().post("/api/semesters/" + semesterId + "/periods/default")
                .then().statusCode(200).body("$", hasSize(10));
        long gradeId = TestData.grade(token, semesterId, "一年级");
        TestData.adminClass(token, semesterId, gradeId, "一年级1班");
        TestData.course(token, "语文");
        TestData.teacher(token, "张三");
        TestData.room(token, "101");

        TimetableImportService.Result imported = importService.importRows(
                List.of(row("一年级1班", "周一", "第1节", "语文", "张三", "101")), null, "admin");
        assertThat(imported.errors()).isEmpty();
        long draftId = imported.draftId();
        long periodId = as(token).get("/api/timetable-drafts/" + draftId)
                .then().statusCode(200)
                .extract().jsonPath().getLong("lessons[0].periodId");
        return new Fixture(token, semesterId, draftId, periodId);
    }

    private static RowDto row(String className, String day, String period, String course, String teacher, String room) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("className", className);
        values.put("dayOfWeek", day);
        values.put("periodNo", period);
        values.put("courseName", course);
        values.put("teacherName", teacher);
        values.put("roomName", room);
        return new RowDto(values, Map.of(), List.of());
    }

    private static Map<String, Object> slot(int periodNo, String label, String start, String end) {
        return Map.of(
                "periodNo", periodNo,
                "label", label,
                "section", "MORNING",
                "startTime", start,
                "endTime", end,
                "days", List.of(1, 2, 3, 4, 5));
    }

    private Map<String, Object> body(List<Map<String, Object>> slots) {
        return Map.of("slots", slots);
    }

    private Response savePeriods(String token, long semesterId, List<Map<String, Object>> slots) {
        return as(token).body(body(slots))
                .when().put("/api/semesters/" + semesterId + "/periods");
    }

    @Test
    void savingTheSameSlotKeepsItsIdentityAndTheDraftStaysValid() {
        Fixture f = fixture();

        Response response = savePeriods(f.token(), f.semesterId(), List.of(
                slot(1, FIRST_LESSON_SLOT_LABEL, "08:10", "08:55"),
                slot(2, "上午第2节", "09:05", "09:50")));

        response.then().statusCode(200)
                .body("$", hasSize(10))
                .body("[0].id", equalTo((int) f.periodId()))
                .body("[0].label", equalTo(FIRST_LESSON_SLOT_LABEL))
                .body("[0].startTime", equalTo("08:10:00"));

        as(f.token()).get("/api/semesters/" + f.semesterId() + "/periods")
                .then().statusCode(200)
                .body("find { it.dayOfWeek == 1 && it.periodNo == 1 }.id", equalTo((int) f.periodId()));

        as(f.token()).get("/api/timetable-drafts/" + f.draftId() + "/validation")
                .then().statusCode(200).body("valid", equalTo(true));
        as(f.token()).get("/api/timetable-drafts/" + f.draftId())
                .then().statusCode(200)
                .body("lessons[0].periodId", equalTo((int) f.periodId()));
    }

    @Test
    void removingASlotStillUsedByADraftIsRejectedAndNothingChanges() {
        Fixture f = fixture();

        // A valid, non-empty request: 上午第2节 stays, so only the occupied 第1节 would go.
        savePeriods(f.token(), f.semesterId(), List.of(slot(2, "上午第2节", "09:05", "09:50")))
                .then().statusCode(409);

        as(f.token()).get("/api/semesters/" + f.semesterId() + "/periods")
                .then().statusCode(200)
                .body("$", hasSize(10))
                .body("find { it.dayOfWeek == 1 && it.periodNo == 1 }.id", equalTo((int) f.periodId()));

        as(f.token()).get("/api/timetable-drafts/" + f.draftId())
                .then().statusCode(200)
                .body("lessons", hasSize(1))
                .body("lessons[0].periodId", equalTo((int) f.periodId()));
        as(f.token()).get("/api/timetable-drafts/" + f.draftId() + "/validation")
                .then().statusCode(200).body("valid", equalTo(true));
    }

    @Test
    void removingAnUnusedSlotSucceeds() {
        Fixture f = fixture();

        savePeriods(f.token(), f.semesterId(), List.of(slot(1, "第一节", "08:00", "08:45")))
                .then().statusCode(200)
                .body("$", hasSize(5))
                .body("findAll { it.periodNo == 2 }", hasSize(0));

        as(f.token()).get("/api/semesters/" + f.semesterId() + "/periods")
                .then().statusCode(200)
                .body("$", hasSize(5))
                .body("findAll { it.periodNo == 2 }", hasSize(0))
                .body("find { it.dayOfWeek == 1 }.id", equalTo((int) f.periodId()));

        as(f.token()).get("/api/timetable-drafts/" + f.draftId() + "/validation")
                .then().statusCode(200).body("valid", equalTo(true));
    }

    @Test
    void publishedSemesterStillRefusesPeriodChanges() {
        Fixture f = fixture();
        as(f.token()).when().post("/api/timetable-drafts/" + f.draftId() + "/publish")
                .then().statusCode(200);

        savePeriods(f.token(), f.semesterId(), List.of(
                slot(1, FIRST_LESSON_SLOT_LABEL, "08:10", "08:55"),
                slot(2, "上午第2节", "09:05", "09:50")))
                .then().statusCode(409);

        as(f.token()).body(Map.of("morningCount", 2, "afternoonCount", 0, "eveningCount", 0))
                .when().post("/api/semesters/" + f.semesterId() + "/periods/default")
                .then().statusCode(409);
    }

    @Test
    void databaseRejectsADraftLessonPointingAtAMissingPeriod() {
        Fixture f = fixture();
        DraftLesson lesson = draftLessons.findByDraftIdOrderByIdAsc(f.draftId()).get(0);

        lesson.setPeriodId(f.periodId() + 1000);
        assertThatThrownBy(() -> draftLessons.saveAndFlush(lesson))
                .isInstanceOf(DataIntegrityViolationException.class);

        DraftLesson reloaded = draftLessons.findByDraftIdOrderByIdAsc(f.draftId()).get(0);
        assertThat(reloaded.getPeriodId()).isEqualTo(f.periodId());
    }

    @Test
    void aForcedMoveToAMissingPeriodIsRefusedWithoutTouchingTheLesson() {
        Fixture f = fixture();
        long lessonId = as(f.token()).get("/api/timetable-drafts/" + f.draftId())
                .then().statusCode(200)
                .extract().jsonPath().getLong("lessons[0].id");

        as(f.token())
                .body(Map.of("periodId", f.periodId() + 1000, "force", true))
                .when().patch("/api/timetable-drafts/" + f.draftId() + "/lessons/" + lessonId)
                .then().statusCode(400);

        as(f.token()).get("/api/timetable-drafts/" + f.draftId())
                .then().statusCode(200)
                .body("lessons[0].periodId", equalTo((int) f.periodId()));
    }

    @Test
    void deletingASemesterStillRemovesItsDraftsAndPeriods() {
        Fixture f = fixture();
        TestData.semester(f.token()); // makes another semester current, so the fixture one may be deleted
        assertThat(draftLessons.count()).isEqualTo(1);

        as(f.token()).when().delete("/api/semesters/" + f.semesterId()).then().statusCode(200);

        assertThat(draftLessons.count()).isZero();
        as(f.token()).get("/api/semesters/" + f.semesterId()).then().statusCode(404);
    }
}
