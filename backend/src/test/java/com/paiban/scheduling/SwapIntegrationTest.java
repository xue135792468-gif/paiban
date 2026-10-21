package com.paiban.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

import com.paiban.scheduling.domain.DraftLesson;
import com.paiban.scheduling.domain.DraftLessonRepository;
import com.paiban.scheduling.domain.TimetableDraft;
import com.paiban.scheduling.domain.TimetableDraftRepository;
import com.paiban.support.ApiIntegrationTest;
import com.paiban.support.TestData;
import io.restassured.response.Response;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SwapIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private TimetableDraftRepository drafts;

    @Autowired
    private DraftLessonRepository draftLessons;

    private record Base(long semesterId, long draftId, long classA, long classB,
                        long courseA, long courseB, long teacherA, long teacherB,
                        long roomA, long roomB, long period1, long period2) {}

    @Test
    void intraClassSwapExchangesPeriodsAndOptionallyRooms() {
        String token = adminToken();
        Base base = base(token, "语文", "语文");
        long sourceId = lesson(base, base.courseA(), base.teacherA(), base.classA(),
                base.period1(), base.roomA());
        long targetId = lesson(base, base.courseB(), base.teacherB(), base.classA(),
                base.period2(), base.roomB());
        TimetableDraft draft = drafts.findById(base.draftId()).orElseThrow();
        draft.setScoreHard(3);
        drafts.save(draft);

        as(token).body(Map.of(
                        "lessonId", sourceId,
                        "targetLessonId", targetId,
                        "type", "CROSS_CLASS"))
                .when().post("/api/timetable-drafts/" + base.draftId() + "/swap")
                .then().statusCode(200)
                .body("saved", equalTo(true))
                .body("valid", equalTo(true))
                .body("hardConflicts", equalTo(0))
                .body("type", equalTo("INTRA_CLASS"))
                .body("conflicts", hasSize(0));

        DraftLesson afterSource = draftLessons.findById(sourceId).orElseThrow();
        DraftLesson afterTarget = draftLessons.findById(targetId).orElseThrow();
        assertThat(afterSource.getPeriodId()).isEqualTo(base.period2());
        assertThat(afterTarget.getPeriodId()).isEqualTo(base.period1());
        assertThat(afterSource.getRoomId()).isEqualTo(base.roomA());
        assertThat(afterTarget.getRoomId()).isEqualTo(base.roomB());
        assertThat(drafts.findById(base.draftId()).orElseThrow().getScoreHard()).isZero();

        as(token).body(Map.of(
                        "lessonId", sourceId,
                        "targetLessonId", targetId,
                        "swapRooms", true))
                .when().post("/api/timetable-drafts/" + base.draftId() + "/swap")
                .then().statusCode(200)
                .body("saved", equalTo(true));

        afterSource = draftLessons.findById(sourceId).orElseThrow();
        afterTarget = draftLessons.findById(targetId).orElseThrow();
        assertThat(afterSource.getPeriodId()).isEqualTo(base.period1());
        assertThat(afterTarget.getPeriodId()).isEqualTo(base.period2());
        assertThat(afterSource.getRoomId()).isEqualTo(base.roomB());
        assertThat(afterTarget.getRoomId()).isEqualTo(base.roomA());
    }

    @Test
    void crossClassSwapExchangesTeachersOnly() {
        String token = adminToken();
        Base base = base(token, "语文", "语文");
        long sourceId = lesson(base, base.courseA(), base.teacherA(), base.classA(),
                base.period1(), base.roomA());
        long targetId = lesson(base, base.courseB(), base.teacherB(), base.classB(),
                base.period2(), base.roomB());

        as(token).body(Map.of("lessonId", sourceId, "targetLessonId", targetId))
                .when().post("/api/timetable-drafts/" + base.draftId() + "/swap")
                .then().statusCode(200)
                .body("saved", equalTo(true))
                .body("valid", equalTo(true))
                .body("hardConflicts", equalTo(0))
                .body("type", equalTo("CROSS_CLASS"))
                .body("warnings", hasSize(0));

        DraftLesson afterSource = draftLessons.findById(sourceId).orElseThrow();
        DraftLesson afterTarget = draftLessons.findById(targetId).orElseThrow();
        assertThat(afterSource.getTeacherId()).isEqualTo(base.teacherB());
        assertThat(afterSource.getPeriodId()).isEqualTo(base.period1());
        assertThat(afterSource.getRoomId()).isEqualTo(base.roomA());
        assertThat(afterSource.getCourseId()).isEqualTo(base.courseA());
        assertThat(afterSource.getAdminClassId()).isEqualTo(base.classA());
        assertThat(afterSource.getStudentCount()).isEqualTo(45);
        assertThat(afterTarget.getTeacherId()).isEqualTo(base.teacherA());
        assertThat(afterTarget.getPeriodId()).isEqualTo(base.period2());
        assertThat(afterTarget.getRoomId()).isEqualTo(base.roomB());
        assertThat(afterTarget.getCourseId()).isEqualTo(base.courseB());
        assertThat(afterTarget.getAdminClassId()).isEqualTo(base.classB());
        assertThat(afterTarget.getStudentCount()).isEqualTo(45);
    }

    @Test
    void hardConflictLeavesBothLessonsUnchanged() {
        String token = adminToken();
        Base base = base(token, "语文", "语文");
        long sourceId = lesson(base, base.courseA(), base.teacherA(), base.classA(),
                base.period1(), base.roomA());
        long targetId = lesson(base, base.courseB(), base.teacherB(), base.classA(),
                base.period2(), base.roomB());
        long roomC = TestData.room(token, "教室C" + TestData.suffix());
        long blockerId = lesson(base, base.courseB(), base.teacherA(), base.classB(),
                base.period2(), roomC);

        Response response = as(token)
                .body(Map.of("lessonId", sourceId, "targetLessonId", targetId))
                .when().post("/api/timetable-drafts/" + base.draftId() + "/swap")
                .then().statusCode(200).extract().response();

        assertThat(response.jsonPath().getBoolean("saved")).isFalse();
        assertThat(response.jsonPath().getBoolean("valid")).isFalse();
        assertThat(response.jsonPath().getInt("hardConflicts")).isGreaterThanOrEqualTo(1);
        List<Map<String, Object>> conflicts = response.jsonPath().getList("conflicts");
        assertThat(conflicts).anySatisfy(conflict ->
                assertThat(conflict.get("type")).isEqualTo("TEACHER_CONFLICT"));

        assertThat(draftLessons.findById(sourceId).orElseThrow().getPeriodId())
                .isEqualTo(base.period1());
        assertThat(draftLessons.findById(targetId).orElseThrow().getPeriodId())
                .isEqualTo(base.period2());
        assertThat(draftLessons.findById(blockerId).orElseThrow().getPeriodId())
                .isEqualTo(base.period2());
    }

    @Test
    void crossClassSwapRejectsDifferentCourseSubjects() {
        String token = adminToken();
        Base base = base(token, "数学", "数学");
        long sourceId = lesson(base, base.courseA(), base.teacherA(), base.classA(),
                base.period1(), base.roomA());
        long targetId = lesson(base, base.courseB(), base.teacherB(), base.classB(),
                base.period2(), base.roomB());

        Response response = as(token)
                .body(Map.of("lessonId", sourceId, "targetLessonId", targetId))
                .when().post("/api/timetable-drafts/" + base.draftId() + "/swap")
                .then().statusCode(200).extract().response();

        assertThat(response.jsonPath().getBoolean("saved")).isFalse();
        assertThat(response.jsonPath().getBoolean("valid")).isFalse();
        List<Map<String, Object>> conflicts = response.jsonPath().getList("conflicts");
        assertThat(conflicts).anySatisfy(conflict ->
                assertThat(conflict.get("type")).isEqualTo("SUBJECT_MISMATCH"));
        assertThat(draftLessons.findById(sourceId).orElseThrow().getTeacherId())
                .isEqualTo(base.teacherA());
        assertThat(draftLessons.findById(targetId).orElseThrow().getTeacherId())
                .isEqualTo(base.teacherB());
    }

    @Test
    void crossClassSwapRejectsTeacherSubjectMismatch() {
        String token = adminToken();
        Base base = base(token, "语文", "数学");
        long sourceId = lesson(base, base.courseA(), base.teacherA(), base.classA(),
                base.period1(), base.roomA());
        long targetId = lesson(base, base.courseB(), base.teacherB(), base.classB(),
                base.period2(), base.roomB());

        Response response = as(token)
                .body(Map.of("lessonId", sourceId, "targetLessonId", targetId))
                .when().post("/api/timetable-drafts/" + base.draftId() + "/swap")
                .then().statusCode(200).extract().response();

        assertThat(response.jsonPath().getBoolean("saved")).isFalse();
        List<Map<String, Object>> conflicts = response.jsonPath().getList("conflicts");
        assertThat(conflicts).anySatisfy(conflict ->
                assertThat(conflict.get("type")).isEqualTo("TEACHER_SUBJECT_MISMATCH"));
        assertThat(draftLessons.findById(sourceId).orElseThrow().getTeacherId())
                .isEqualTo(base.teacherA());
        assertThat(draftLessons.findById(targetId).orElseThrow().getTeacherId())
                .isEqualTo(base.teacherB());
    }

    @Test
    void crossClassSwapWarnsWhenTeacherSubjectIsMissing() {
        String token = adminToken();
        Base base = base(token, "语文", null);
        long sourceId = lesson(base, base.courseA(), base.teacherA(), base.classA(),
                base.period1(), base.roomA());
        long targetId = lesson(base, base.courseB(), base.teacherB(), base.classB(),
                base.period2(), base.roomB());

        Response response = as(token)
                .body(Map.of("lessonId", sourceId, "targetLessonId", targetId))
                .when().post("/api/timetable-drafts/" + base.draftId() + "/swap")
                .then().statusCode(200)
                .body("saved", equalTo(true))
                .body("warnings", hasSize(1))
                .extract().response();

        assertThat(response.jsonPath().getString("warnings[0]")).contains("学科未维护");
        assertThat(draftLessons.findById(sourceId).orElseThrow().getTeacherId())
                .isEqualTo(base.teacherB());
        assertThat(draftLessons.findById(targetId).orElseThrow().getTeacherId())
                .isEqualTo(base.teacherA());
    }

    @Test
    void swapRequiresTimetableEditPermission() {
        String token = adminToken();
        Base base = base(token, "语文", "语文");
        long sourceId = lesson(base, base.courseA(), base.teacherA(), base.classA(),
                base.period1(), base.roomA());
        long targetId = lesson(base, base.courseB(), base.teacherB(), base.classA(),
                base.period2(), base.roomB());
        String username = "swap_" + TestData.suffix();
        createUser(token, username, "swap123", "teacher");
        String teacherToken = login(username, "swap123");

        as(teacherToken).body(Map.of("lessonId", sourceId, "targetLessonId", targetId))
                .when().post("/api/timetable-drafts/" + base.draftId() + "/swap")
                .then().statusCode(403);

        as(token).body(Map.of("lessonId", sourceId, "targetLessonId", targetId))
                .when().post("/api/timetable-drafts/" + base.draftId() + "/swap")
                .then().statusCode(200)
                .body("saved", equalTo(true));
    }

    @Test
    void publishedTimetableUsesSwappedTeacher() {
        String token = adminToken();
        Base base = base(token, "语文", "语文");
        long sourceId = lesson(base, base.courseA(), base.teacherA(), base.classA(),
                base.period1(), base.roomA());
        long targetId = lesson(base, base.courseB(), base.teacherB(), base.classB(),
                base.period2(), base.roomB());

        as(token).body(Map.of("lessonId", sourceId, "targetLessonId", targetId))
                .when().post("/api/timetable-drafts/" + base.draftId() + "/swap")
                .then().statusCode(200)
                .body("saved", equalTo(true));

        as(token).when().post("/api/timetable-drafts/" + base.draftId() + "/publish")
                .then().statusCode(200);

        List<Map<String, Object>> entries = as(token)
                .when().get("/api/timetable/class/" + base.classA())
                .then().statusCode(200).extract().jsonPath().getList("$");
        Map<String, Object> sourceEntry = entries.stream()
                .filter(entry -> ((Number) entry.get("periodId")).longValue() == base.period1())
                .findFirst()
                .orElseThrow();
        assertThat(((Number) sourceEntry.get("teacherId")).longValue()).isEqualTo(base.teacherB());
        assertThat(((Number) sourceEntry.get("courseId")).longValue()).isEqualTo(base.courseA());
    }

    @Test
    void swapRoomsExchangesAnUnassignedRoom() {
        String token = adminToken();
        Base base = base(token, "语文", "语文");
        long sourceId = lesson(base, base.courseA(), base.teacherA(), base.classA(),
                base.period1(), base.roomA());
        long targetId = lesson(base, base.courseB(), base.teacherB(), base.classA(),
                base.period2(), null);

        as(token).body(Map.of(
                        "lessonId", sourceId,
                        "targetLessonId", targetId,
                        "swapRooms", true))
                .when().post("/api/timetable-drafts/" + base.draftId() + "/swap")
                .then().statusCode(200)
                .body("saved", equalTo(true));

        assertThat(draftLessons.findById(sourceId).orElseThrow().getRoomId()).isNull();
        assertThat(draftLessons.findById(targetId).orElseThrow().getRoomId()).isEqualTo(base.roomA());
        assertThat(draftLessons.findById(sourceId).orElseThrow().getPeriodId()).isEqualTo(base.period2());
        assertThat(draftLessons.findById(targetId).orElseThrow().getPeriodId()).isEqualTo(base.period1());
    }

    @Test
    void swapRoomsDoesNotReportAConflictForTheRoomBeingGivenUp() {
        String token = adminToken();
        Base base = base(token, "语文", "语文");
        long sourceId = lesson(base, base.courseA(), base.teacherA(), base.classA(),
                base.period1(), base.roomA());
        long targetId = lesson(base, base.courseB(), base.teacherB(), base.classA(),
                base.period2(), null);
        long otherTeacher = teacher(token, "王老师" + TestData.suffix(), "语文");
        lesson(base, base.courseB(), otherTeacher, base.classB(), base.period2(), base.roomA());

        as(token).body(Map.of(
                        "lessonId", sourceId,
                        "targetLessonId", targetId,
                        "swapRooms", true))
                .when().post("/api/timetable-drafts/" + base.draftId() + "/swap")
                .then().statusCode(200)
                .body("saved", equalTo(true))
                .body("conflicts", hasSize(0));

        assertThat(draftLessons.findById(sourceId).orElseThrow().getRoomId()).isNull();
        assertThat(draftLessons.findById(targetId).orElseThrow().getRoomId()).isEqualTo(base.roomA());
    }

    private Base base(String token, String courseBSubject, String teacherBSubject) {
        String suffix = TestData.suffix();
        long semesterId = TestData.semester(token);
        Response periodResponse = as(token)
                .body(Map.of("morningCount", 2, "afternoonCount", 0, "eveningCount", 0,
                        "days", List.of(1)))
                .when().post("/api/semesters/" + semesterId + "/periods/default")
                .then().statusCode(200).extract().response();
        List<Integer> periodIds = periodResponse.jsonPath().getList("id");

        long gradeId = TestData.grade(token, semesterId, "高一" + suffix);
        long classA = TestData.adminClass(token, semesterId, gradeId, "一班" + suffix);
        long classB = TestData.adminClass(token, semesterId, gradeId, "二班" + suffix);
        long courseA = course(token, "语文" + suffix, "语文");
        long courseB = course(token, "阅读" + suffix, courseBSubject);
        long teacherA = teacher(token, "张老师" + suffix, "语文");
        long teacherB = teacher(token, "李老师" + suffix, teacherBSubject);
        long roomA = TestData.room(token, "教室A" + suffix);
        long roomB = TestData.room(token, "教室B" + suffix);

        TimetableDraft draft = new TimetableDraft();
        draft.setSemesterId(semesterId);
        draft.setName("换课测试" + suffix);
        draft.setStatus("DRAFT");
        long draftId = drafts.save(draft).getId();

        return new Base(semesterId, draftId, classA, classB, courseA, courseB,
                teacherA, teacherB, roomA, roomB,
                periodIds.get(0).longValue(), periodIds.get(1).longValue());
    }

    private long lesson(
            Base base, long courseId, long teacherId, long classId, long periodId, Long roomId) {
        DraftLesson lesson = new DraftLesson();
        lesson.setDraftId(base.draftId());
        lesson.setAssignmentId(1L);
        lesson.setLessonIndex(1);
        lesson.setCourseId(courseId);
        lesson.setTeacherId(teacherId);
        lesson.setAdminClassId(classId);
        lesson.setStudentCount(45);
        lesson.setPeriodId(periodId);
        lesson.setRoomId(roomId);
        return draftLessons.save(lesson).getId();
    }

    private long course(String token, String name, String subject) {
        Response response = as(token)
                .body(Map.of("name", name, "subject", subject, "allowConsecutive", false))
                .when().post("/api/courses");
        response.then().statusCode(200);
        return response.jsonPath().getLong("id");
    }

    private long teacher(String token, String name, String subject) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("employeeNo", "T" + TestData.suffix());
        if (subject != null) {
            body.put("subject", subject);
        }
        Response response = as(token).body(body).when().post("/api/teachers");
        response.then().statusCode(200);
        return response.jsonPath().getLong("id");
    }
}
