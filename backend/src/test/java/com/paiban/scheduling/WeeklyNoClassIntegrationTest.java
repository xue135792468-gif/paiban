package com.paiban.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

import com.paiban.scheduling.domain.DraftLesson;
import com.paiban.scheduling.domain.DraftLessonRepository;
import com.paiban.scheduling.domain.TimetableDraft;
import com.paiban.scheduling.domain.TimetableDraftRepository;
import com.paiban.support.ApiIntegrationTest;
import com.paiban.support.ScheduleFixture;
import com.paiban.support.TestData;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** Weekly availability observed through the same HTTP boundary used by the editor. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class WeeklyNoClassIntegrationTest extends ApiIntegrationTest {
    @Autowired private TimetableDraftRepository drafts;
    @Autowired private DraftLessonRepository lessons;

    @Test
    void hardOnlyAndInvalidSlotsAreRejectedWithoutAdvancingTheSavedVersion() {
        Fixture f = fixture(false);
        saveBan(f);
        long version = version(f);
        Map<String, Object> typeSoft = banConfig(f, true);
        typeSoft.put("hard", false);
        Map<String, Object> entrySoft = banConfig(f, true);
        entrySoft.put("entries", List.of(Map.of("params", Map.of("classId", f.classId(),
                "dayOfWeek", 1, "periodNo", 1), "hard", false)));
        Map<String, Object> invalidSlot = banConfig(f, true);
        invalidSlot.put("entries", List.of(Map.of("params", Map.of("classId", f.classId(),
                "dayOfWeek", 7, "periodNo", 20), "hard", true)));
        for (Map<String, Object> invalid : List.of(typeSoft, entrySoft, invalidSlot)) {
            as(f.token()).queryParam("semesterId", f.semesterId()).queryParam("baseVersion", version)
                    .body(Map.of("configs", List.of(invalid))).put("/api/constraints")
                    .then().statusCode(400);
            assertThat(version(f)).isEqualTo(version);
            as(f.token()).get("/api/timetable-drafts/" + f.draftId()).then().statusCode(200)
                    .body("blockedSlots", hasSize(1));
        }
    }

    @Test
    void pausingTheRequirementRestoresTheSlotWithoutRemovingItsStoredCondition() {
        Fixture f = fixture(false);
        saveBan(f);
        as(f.token()).queryParam("semesterId", f.semesterId()).queryParam("baseVersion", version(f))
                .body(Map.of("configs", List.of(banConfig(f, false)))).put("/api/constraints")
                .then().statusCode(200).body("[0].entries", hasSize(1))
                .body("[0].entries[0].enabled", equalTo(false));
        as(f.token()).get("/api/timetable-drafts/" + f.draftId()).then().statusCode(200)
                .body("blockedSlots", hasSize(0));
        as(f.token()).body(Map.of("periodId", f.blockedPeriod(), "roomId", f.roomId()))
                .patch("/api/timetable-drafts/" + f.draftId() + "/lessons/" + f.lessonId())
                .then().statusCode(200).body("saved", equalTo(true));
    }

    @Test
    void unavailableTargetsAreVisibleAndCannotBeForcedIntoTheDraft() {
        Fixture f = fixture(false);
        saveBan(f);
        as(f.token()).get("/api/timetable-drafts/" + f.draftId()).then().statusCode(200)
                .body("blockedSlots", hasSize(1))
                .body("blockedSlots[0].classId", equalTo((int) f.classId()))
                .body("blockedSlots[0].periodId", equalTo((int) f.blockedPeriod()))
                .body("blockedSlots[0].reason", equalTo("指定班级规则（本班）"));
        for (boolean force : List.of(false, true)) {
            as(f.token()).body(Map.of("periodId", f.blockedPeriod(), "roomId", f.roomId(), "force", force))
                    .patch("/api/timetable-drafts/" + f.draftId() + "/lessons/" + f.lessonId())
                    .then().statusCode(200).body("saved", equalTo(false));
        }
        as(f.token()).get("/api/timetable-drafts/" + f.draftId()).then().statusCode(200)
                .body("lessons", hasSize(1)).body("lessons[0].periodId", equalTo((int) f.freePeriod()));
    }

    @Test
    void existingOccupancyIsKeptAndMustBeRepairedBeforePublishing() {
        Fixture f = fixture(true);
        saveBan(f);
        as(f.token()).get("/api/timetable-drafts/" + f.draftId()).then().statusCode(200)
                .body("lessons", hasSize(1)).body("lessons[0].periodId", equalTo((int) f.blockedPeriod()));
        as(f.token()).get("/api/timetable-drafts/" + f.draftId() + "/validation")
                .then().statusCode(200).body("publishable", equalTo(false));
        as(f.token()).post("/api/timetable-drafts/" + f.draftId() + "/publish")
                .then().statusCode(409);
        as(f.token()).body(Map.of("periodId", f.freePeriod(), "roomId", f.roomId()))
                .patch("/api/timetable-drafts/" + f.draftId() + "/lessons/" + f.lessonId())
                .then().statusCode(200).body("saved", equalTo(true));
        as(f.token()).get("/api/timetable-drafts/" + f.draftId() + "/validation")
                .then().statusCode(200).body("publishable", equalTo(true));
        as(f.token()).post("/api/timetable-drafts/" + f.draftId() + "/publish")
                .then().statusCode(200).body("entryCount", equalTo(1));
    }

    @Test
    void solvingRepairsTheOldSeedWithoutDeletingItsLessonOrChangingTheSource() throws Exception {
        Fixture f = fixture(true);
        saveBan(f);
        long jobId = as(f.token()).queryParam("semesterId", f.semesterId())
                .body(Map.of("maxSeconds", 2)).post("/api/scheduling-jobs")
                .then().statusCode(200).extract().jsonPath().getLong("id");
        Map<String, Object> job = Map.of();
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(45).toNanos();
        while (System.nanoTime() < deadline) {
            job = as(f.token()).get("/api/scheduling-jobs/" + jobId)
                    .then().statusCode(200).extract().jsonPath().getMap("$");
            if (List.of("COMPLETED", "FAILED", "CANCELLED").contains(job.get("status"))) break;
            Thread.sleep(100);
        }
        assertThat(job.get("status")).isEqualTo("COMPLETED");
        assertThat(job.get("warmStartSource")).isEqualTo("DRAFT");
        long newDraft = ((Number) job.get("draftId")).longValue();
        long placed = as(f.token()).get("/api/timetable-drafts/" + newDraft)
                .then().statusCode(200).body("lessons", hasSize(1))
                .extract().jsonPath().getLong("lessons[0].periodId");
        assertThat(placed).isNotEqualTo(f.blockedPeriod());
        as(f.token()).get("/api/timetable-drafts/" + newDraft + "/validation")
                .then().statusCode(200).body("valid", equalTo(true));
        as(f.token()).get("/api/timetable-drafts/" + f.draftId()).then().statusCode(200)
                .body("lessons", hasSize(1)).body("lessons[0].periodId", equalTo((int) f.blockedPeriod()));
    }

    private void saveBan(Fixture f) {
        List<Map<String, Object>> configs = new ArrayList<>();
        for (String type : List.of("TEACHER_CONFLICT", "CLASS_CONFLICT", "ROOM_CONFLICT")) {
            configs.add(Map.of("type", type, "params", Map.of(), "enabled", true, "hard", true, "weight", 100));
        }
        configs.add(banConfig(f, true));
        as(f.token()).queryParam("semesterId", f.semesterId()).queryParam("baseVersion", version(f))
                .body(Map.of("configs", configs)).put("/api/constraints").then().statusCode(200);
    }

    private long version(Fixture f) {
        return as(f.token()).queryParam("semesterId", f.semesterId()).get("/api/constraints/state")
                .then().statusCode(200).extract().jsonPath().getLong("version");
    }

    private Map<String, Object> banConfig(Fixture f, boolean enabled) {
        return new LinkedHashMap<>(Map.of("type", "CLASS_UNAVAILABLE", "params", Map.of(),
                "enabled", true, "hard", true, "weight", 100, "entries", List.of(Map.of(
                        "params", Map.of("classId", f.classId(), "dayOfWeek", 1, "periodNo", 1),
                        "enabled", enabled, "hard", true, "groupKey", "weekly-no-class"))));
    }

    private Fixture fixture(boolean initiallyBlocked) {
        String token = adminToken();
        String suffix = TestData.suffix();
        long semester = TestData.semesterStarting(token, ScheduleFixture.nextMonday());
        as(token).body(Map.of("morningCount", 3, "afternoonCount", 0, "eveningCount", 0,
                        "days", List.of(1, 2)))
                .post("/api/semesters/" + semester + "/periods/default").then().statusCode(200);
        List<Map<String, Object>> slots = as(token).get("/api/semesters/" + semester + "/periods")
                .then().statusCode(200).extract().jsonPath().getList("$");
        long blocked = id(slots.stream().filter(p -> ((Number) p.get("dayOfWeek")).intValue() == 1
                && ((Number) p.get("periodNo")).intValue() == 1).findFirst().orElseThrow());
        long free = id(slots.stream().filter(p -> ((Number) p.get("dayOfWeek")).intValue() == 1
                && ((Number) p.get("periodNo")).intValue() == 2).findFirst().orElseThrow());
        long grade = TestData.grade(token, semester, "留空级" + suffix);
        long adminClass = TestData.adminClass(token, semester, grade, "留空班" + suffix);
        long teacher = TestData.teacher(token, "留空教师" + suffix);
        long course = TestData.course(token, "留空课程" + suffix);
        long room = TestData.room(token, "留空教室" + suffix);
        long assignment = as(token).body(Map.of("classId", adminClass, "teacherId", teacher,
                        "courseId", course, "weeklyLessons", 1))
                .post("/api/assignments").then().statusCode(200).extract().jsonPath().getLong("id");
        TimetableDraft draft = new TimetableDraft();
        draft.setSemesterId(semester);
        draft.setName("导入形状留空草稿" + suffix);
        draft = drafts.save(draft);
        DraftLesson lesson = new DraftLesson();
        lesson.setDraftId(draft.getId());
        lesson.setAssignmentId(assignment);
        lesson.setAdminClassId(adminClass);
        lesson.setTeacherId(teacher);
        lesson.setCourseId(course);
        lesson.setRoomId(room);
        lesson.setStudentCount(45);
        lesson.setPeriodId(initiallyBlocked ? blocked : free);
        lesson = lessons.save(lesson);
        return new Fixture(token, semester, adminClass, room, draft.getId(), lesson.getId(), blocked, free);
    }

    private static long id(Map<String, Object> row) { return ((Number) row.get("id")).longValue(); }
    private record Fixture(String token, long semesterId, long classId, long roomId,
                           long draftId, long lessonId, long blockedPeriod, long freePeriod) {}
}
