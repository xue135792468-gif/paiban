package com.paiban.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import com.paiban.identity.domain.PermissionRepository;
import com.paiban.identity.domain.Permissions;
import com.paiban.identity.domain.Role;
import com.paiban.identity.domain.RoleRepository;
import com.paiban.scheduling.domain.DraftLesson;
import com.paiban.scheduling.domain.DraftLessonRepository;
import com.paiban.scheduling.domain.TimetableDraft;
import com.paiban.scheduling.domain.TimetableDraftRepository;
import com.paiban.support.ApiIntegrationTest;
import com.paiban.support.TestData;
import com.paiban.timetable.domain.TimetablePublish;
import com.paiban.timetable.domain.TimetablePublishRepository;
import io.restassured.response.Response;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Ticket 07: a draft imported from a real workbook has no rooms at all, so publishing
 * stops at "546 lessons have no room". The page offers one explicit action that gives
 * every class with missing rooms a fixed ordinary room, after a preview the user
 * confirms. Only empty room ids are filled, and the whole action is all-or-nothing.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class DraftRoomFillIntegrationTest extends ApiIntegrationTest {

    private static final String PLAN_PATH = "/api/timetable-drafts/%d/room-fill/plan";
    private static final String APPLY_PATH = "/api/timetable-drafts/%d/room-fill";

    @Autowired
    private TimetableDraftRepository drafts;

    @Autowired
    private DraftLessonRepository draftLessons;

    @Autowired
    private TimetablePublishRepository publishes;

    @Autowired
    private RoleRepository roles;

    @Autowired
    private PermissionRepository permissions;

    @Test
    void previewListsBothClassesAndApplyingCreatesOneRoomPerClassThenPublishes() {
        Fixture f = fixture(45, 38);
        long draftId = seedDraft(f, List.of(
                lesson(f.classA(), f.courseId(), f.teacherA(), 45, f.periodIds().get(0), null),
                lesson(f.classA(), f.courseId(), f.teacherA(), 45, f.periodIds().get(1), null),
                lesson(f.classB(), f.courseId(), f.teacherB(), 38, f.periodIds().get(0), null),
                lesson(f.classB(), f.courseId(), f.teacherB(), 38, f.periodIds().get(2), null)));

        Response preview = as(f.token())
                .when().get(PLAN_PATH.formatted(draftId))
                .then().statusCode(200).extract().response();

        assertThat(preview.jsonPath().getBoolean("applied")).isFalse();
        assertThat(preview.jsonPath().getBoolean("applicable")).isTrue();
        List<Map<String, Object>> planned = preview.jsonPath().getList("classes");
        assertThat(planned).hasSize(2);
        assertThat(preview.jsonPath().getList("manualItems")).isEmpty();
        // The derived name carries the grade as well as the class, so a class of another
        // grade with the same name can never adopt this room.
        assertThat(preview.jsonPath().getString("classes[0].roomName"))
                .isEqualTo(fixedRoomName(f, f.classNameA()));
        assertThat(preview.jsonPath().getString("classes[0].action")).isEqualTo("CREATE");
        assertThat(preview.jsonPath().getInt("classes[0].studentCount")).isEqualTo(45);
        assertThat(preview.jsonPath().getInt("classes[0].lessonCount")).isEqualTo(2);
        assertThat(preview.jsonPath().getString("classes[1].action")).isEqualTo("CREATE");
        // The preview is read-only: nothing is created before the user confirms.
        assertThat(rooms(f.token())).noneMatch(room -> isFixedRoomOf(room, f));

        Response applied = as(f.token())
                .when().post(APPLY_PATH.formatted(draftId))
                .then().statusCode(200).extract().response();

        assertThat(applied.jsonPath().getBoolean("applied")).isTrue();
        assertThat(applied.jsonPath().getInt("createdRooms")).isEqualTo(2);
        assertThat(applied.jsonPath().getInt("reusedRooms")).isZero();
        assertThat(applied.jsonPath().getInt("assignedLessons")).isEqualTo(4);
        assertThat(applied.jsonPath().getList("manualItems")).isEmpty();
        assertThat(applied.jsonPath().getList("blockers")).isEmpty();

        List<Map<String, Object>> rooms = rooms(f.token());
        assertThat(rooms).filteredOn(room -> isFixedRoomOf(room, f)).hasSize(2);
        assertThat(rooms).filteredOn(room -> isFixedRoomOf(room, f)).allSatisfy(room -> {
            assertThat(room.get("type")).isEqualTo("NORMAL");
            assertThat(((Number) room.get("capacity")).intValue()).isGreaterThanOrEqualTo(38);
        });
        assertThat(draftLessonRooms(draftId)).hasSize(4).allMatch(id -> id != null);

        as(f.token()).when().post("/api/timetable-drafts/" + draftId + "/publish")
                .then().statusCode(200);
    }

    @Test
    void aManuallyAssignedRoomIsLeftUntouched() {
        Fixture f = fixture(45, 38);
        long manualRoom = TestData.room(f.token(), "手工教室" + TestData.suffix());
        List<SeedLesson> seeds = new ArrayList<>(List.of(
                lesson(f.classA(), f.courseId(), f.teacherA(), 45, f.periodIds().get(0), null),
                lesson(f.classA(), f.courseId(), f.teacherA(), 45, f.periodIds().get(1), manualRoom)));
        long draftId = seedDraft(f, seeds);

        Response applied = as(f.token())
                .when().post(APPLY_PATH.formatted(draftId))
                .then().statusCode(200).extract().response();

        assertThat(applied.jsonPath().getInt("assignedLessons")).isEqualTo(1);
        List<Map<String, Object>> lessons = draftLessonsOf(f.token(), draftId);
        Map<String, Object> untouched = lessons.get(1);
        assertThat(((Number) untouched.get("roomId")).longValue()).isEqualTo(manualRoom);
        assertThat(((Number) lessons.get(0).get("roomId")).longValue())
                .isNotEqualTo(manualRoom)
                .isPositive();
    }

    @Test
    void applyingAgainReusesTheRoomItCreatedInsteadOfCreatingASecondOne() {
        Fixture f = fixture(45, 38);
        long draftId = seedDraft(f, List.of(
                lesson(f.classA(), f.courseId(), f.teacherA(), 45, f.periodIds().get(0), null),
                lesson(f.classB(), f.courseId(), f.teacherB(), 38, f.periodIds().get(0), null)));

        as(f.token()).when().post(APPLY_PATH.formatted(draftId)).then().statusCode(200);
        int roomsAfterFirst = rooms(f.token()).size();
        List<Long> lessonRoomsAfterFirst = draftLessonRooms(draftId);

        // A lesson arrives later for class A (as another import would add it): the same
        // class must get the room this action already created, not a second one.
        appendLesson(draftId, f.classA(), f.courseId(), f.teacherA(), 45, f.periodIds().get(1));

        Response second = as(f.token())
                .when().post(APPLY_PATH.formatted(draftId))
                .then().statusCode(200).extract().response();

        assertThat(second.jsonPath().getInt("createdRooms")).isZero();
        assertThat(second.jsonPath().getInt("reusedRooms")).isEqualTo(1);
        assertThat(second.jsonPath().getInt("assignedLessons")).isEqualTo(1);
        assertThat(second.jsonPath().getString("classes[0].action")).isEqualTo("REUSE");
        assertThat(rooms(f.token())).hasSize(roomsAfterFirst);
        List<Long> after = draftLessonRooms(draftId);
        assertThat(after.subList(0, lessonRoomsAfterFirst.size())).isEqualTo(lessonRoomsAfterFirst);
        assertThat(after.get(after.size() - 1)).isEqualTo(lessonRoomsAfterFirst.get(0));

        // A third call has nothing left to fill and still writes nothing.
        Response third = as(f.token())
                .when().post(APPLY_PATH.formatted(draftId))
                .then().statusCode(200).extract().response();
        assertThat(third.jsonPath().getInt("createdRooms")).isZero();
        assertThat(third.jsonPath().getInt("assignedLessons")).isZero();
        assertThat(rooms(f.token())).hasSize(roomsAfterFirst);
        assertThat(draftLessonRooms(draftId)).isEqualTo(after);
    }

    @Test
    void aLessonNeedingASpecialRoomRefusesTheWholeActionWithoutPartialWrites() {
        Fixture f = fixture(45, 38);
        long draftId = seedDraft(f, List.of(
                lesson(f.classA(), f.labCourseId(), f.teacherA(), 45, f.periodIds().get(0), null),
                lesson(f.classB(), f.courseId(), f.teacherB(), 38, f.periodIds().get(0), null)));
        int roomsBefore = rooms(f.token()).size();

        Response preview = as(f.token())
                .when().get(PLAN_PATH.formatted(draftId))
                .then().statusCode(200).extract().response();

        assertThat(preview.jsonPath().getBoolean("applicable")).isFalse();
        assertThat(preview.jsonPath().getList("classes")).hasSize(1);
        assertThat(preview.jsonPath().getList("manualItems.code"))
                .containsExactly("SPECIAL_ROOM_REQUIRED");

        Response refused = as(f.token())
                .when().post(APPLY_PATH.formatted(draftId))
                .then().statusCode(409).extract().response();

        assertThat(refused.jsonPath().getBoolean("applied")).isFalse();
        assertThat(refused.jsonPath().getList("manualItems.code")).contains("SPECIAL_ROOM_REQUIRED");
        // No room for the other class either, and no lesson picked up a room.
        assertThat(rooms(f.token())).hasSize(roomsBefore);
        assertThat(draftLessonRooms(draftId)).containsOnlyNulls();
    }

    @Test
    void aSameNamedRoomTooSmallForTheClassIsNeitherReusedNorExpanded() {
        Fixture f = fixture(45, 38);
        long draftId = seedDraft(f, List.of(
                lesson(f.classA(), f.courseId(), f.teacherA(), 45, f.periodIds().get(0), null),
                lesson(f.classB(), f.courseId(), f.teacherB(), 38, f.periodIds().get(0), null)));
        String clashingName = fixedRoomName(f, f.classNameA());
        long smallRoom = createRoom(f.token(), clashingName, 30, "NORMAL");
        int roomsBefore = rooms(f.token()).size();

        Response refused = as(f.token())
                .when().post(APPLY_PATH.formatted(draftId))
                .then().statusCode(409).extract().response();

        assertThat(refused.jsonPath().getBoolean("applied")).isFalse();
        assertThat(refused.jsonPath().getList("manualItems.code")).containsExactly("ROOM_NAME_CONFLICT");
        assertThat(rooms(f.token())).hasSize(roomsBefore);
        List<Map<String, Object>> rooms = rooms(f.token());
        assertThat(rooms).filteredOn(room -> clashingName.equals(room.get("name"))).hasSize(1);
        assertThat(rooms).filteredOn(room -> clashingName.equals(room.get("name")))
                .allSatisfy(room -> assertThat(((Number) room.get("capacity")).intValue()).isEqualTo(30));
        assertThat(((Number) rooms.stream()
                .filter(room -> clashingName.equals(room.get("name")))
                .findFirst().orElseThrow().get("id")).longValue()).isEqualTo(smallRoom);
        assertThat(draftLessonRooms(draftId)).containsOnlyNulls();
    }

    @Test
    void theActionRequiresBothTimetableEditAndFoundationManage() {
        Fixture f = fixture(45, 38);
        long draftId = seedDraft(f, List.of(
                lesson(f.classA(), f.courseId(), f.teacherA(), 45, f.periodIds().get(0), null)));
        int roomsBefore = rooms(f.token()).size();

        String editOnly = login(userWithRole(f.token(), "room_fill_edit_", Permissions.TIMETABLE_EDIT), "room12345");
        String foundationOnly = login(
                userWithRole(f.token(), "room_fill_foundation_", Permissions.FOUNDATION_MANAGE), "room12345");

        for (String token : List.of(editOnly, foundationOnly)) {
            as(token).when().get(PLAN_PATH.formatted(draftId)).then().statusCode(403);
            as(token).when().post(APPLY_PATH.formatted(draftId)).then().statusCode(403);
        }

        assertThat(rooms(f.token())).hasSize(roomsBefore);
        assertThat(draftLessonRooms(draftId)).containsOnlyNulls();
    }

    @Test
    void aSemesterWithAPublishedBaselineRefusesTheAction() {
        Fixture f = fixture(45, 38);
        long draftId = seedDraft(f, List.of(
                lesson(f.classA(), f.courseId(), f.teacherA(), 45, f.periodIds().get(0), null)));
        TimetablePublish publish = new TimetablePublish();
        publish.setSemesterId(f.semesterId());
        publish.setDraftId(draftId);
        publish.setPublishedBy("admin");
        publish.setPublishedAt(Instant.now());
        publishes.save(publish);
        int roomsBefore = rooms(f.token()).size();

        as(f.token()).when().post(APPLY_PATH.formatted(draftId))
                .then().statusCode(409)
                .body("applied", org.hamcrest.Matchers.equalTo(false));

        assertThat(rooms(f.token())).hasSize(roomsBefore);
        assertThat(draftLessonRooms(draftId)).containsOnlyNulls();
    }

    @Test
    void aSameNamedRoomAnotherClassAlreadyUsesThisSemesterIsNotReused() {
        Fixture f = fixture(45, 38);
        long roomId = createRoom(f.token(), fixedRoomName(f, f.classNameA()), 45, "NORMAL");
        // Another draft of the same semester already puts class B in that room.
        seedDraft(f, List.of(
                lesson(f.classB(), f.courseId(), f.teacherB(), 38, f.periodIds().get(0), roomId)));
        long draftId = seedDraft(f, List.of(
                lesson(f.classA(), f.courseId(), f.teacherA(), 45, f.periodIds().get(0), null)));
        int roomsBefore = rooms(f.token()).size();

        Response refused = as(f.token())
                .when().post(APPLY_PATH.formatted(draftId))
                .then().statusCode(409).extract().response();

        assertThat(refused.jsonPath().getBoolean("applied")).isFalse();
        assertThat(refused.jsonPath().getList("manualItems.code")).containsExactly("ROOM_IN_USE");
        assertThat(refused.jsonPath().getString("message")).contains("已被其他行政班使用");
        assertThat(rooms(f.token())).hasSize(roomsBefore);
        assertThat(draftLessonRooms(draftId)).containsOnlyNulls();
    }

    @Test
    void fillingRoomsAndPublishingRefuseTheSameDraftForTheSameReason() {
        Fixture f = fixture(45, 38);
        long roomId = createRoom(f.token(), "发布教室" + TestData.suffix(), 60, "NORMAL");
        long draftId = seedDraft(f, List.of(
                lesson(f.classA(), f.courseId(), f.teacherA(), 45, f.periodIds().get(0), roomId)));
        as(f.token()).when().post("/api/timetable-drafts/" + draftId + "/publish")
                .then().statusCode(200);

        // Published draft: the fill action must give the publishing judgement's own code and
        // wording, not a locally re-derived "published" rule.
        String publishedMessage = blockerMessage(f.token(), draftId, "DRAFT_PUBLISHED");
        Response plan = as(f.token())
                .when().get(PLAN_PATH.formatted(draftId))
                .then().statusCode(200).extract().response();
        assertThat(plan.jsonPath().getBoolean("applicable")).isFalse();
        assertThat(plan.jsonPath().getList("manualItems.code")).containsExactly("DRAFT_PUBLISHED");
        assertThat(plan.jsonPath().getString("manualItems[0].message")).isEqualTo(publishedMessage);

        Response refused = as(f.token())
                .when().post(APPLY_PATH.formatted(draftId))
                .then().statusCode(409).extract().response();
        assertThat(refused.jsonPath().getString("message")).isEqualTo(publishedMessage);
        assertThat(refused.jsonPath().getList("blockers.code")).contains("DRAFT_PUBLISHED");
        as(f.token()).when().post("/api/timetable-drafts/" + draftId + "/publish")
                .then().statusCode(409)
                .body("message", org.hamcrest.Matchers.equalTo(publishedMessage));

        // Same for the semester-wide terminal state: a fresh draft in the published semester.
        long secondDraftId = seedDraft(f, List.of(
                lesson(f.classB(), f.courseId(), f.teacherB(), 38, f.periodIds().get(1), roomId)));
        String semesterMessage = blockerMessage(f.token(), secondDraftId, "SEMESTER_ALREADY_PUBLISHED");
        Response secondPlan = as(f.token())
                .when().get(PLAN_PATH.formatted(secondDraftId))
                .then().statusCode(200).extract().response();
        assertThat(secondPlan.jsonPath().getList("manualItems.code"))
                .containsExactly("SEMESTER_ALREADY_PUBLISHED");
        assertThat(secondPlan.jsonPath().getString("manualItems[0].message")).isEqualTo(semesterMessage);

        as(f.token()).when().post(APPLY_PATH.formatted(secondDraftId))
                .then().statusCode(409)
                .body("manualItems[0].code", org.hamcrest.Matchers.equalTo("SEMESTER_ALREADY_PUBLISHED"))
                .body("message", org.hamcrest.Matchers.equalTo(semesterMessage));
        // Nothing was written for either draft.
        assertThat(draftLessonRooms(secondDraftId)).containsOnly(roomId);
    }

    /** The message the whole-draft check gives for one blocker code, the contract's own text. */
    private String blockerMessage(String token, long draftId, String code) {
        return as(token).when().get("/api/timetable-drafts/" + draftId + "/validation")
                .then().statusCode(200).extract().jsonPath()
                .getList("blockers").stream()
                .map(item -> (Map<?, ?>) item)
                .filter(item -> code.equals(item.get("code")))
                .map(item -> (String) item.get("message"))
                .findFirst()
                .orElseThrow();
    }

    // ---------- fixture ----------

    private record Fixture(
            String token,
            long semesterId,
            String semesterName,
            String gradeName,
            long classA,
            long classB,
            String classNameA,
            long courseId,
            long labCourseId,
            long teacherA,
            long teacherB,
            List<Long> periodIds) {}

    /** Two classes of different sizes, a normal course, a lab course and default periods. */
    private Fixture fixture(int sizeA, int sizeB) {
        String token = adminToken();
        long semesterId = TestData.semester(token);
        as(token).body(Map.of("morningCount", 4, "afternoonCount", 4, "eveningCount", 0))
                .when().post("/api/semesters/" + semesterId + "/periods/default")
                .then().statusCode(200);
        List<Long> periodIds = as(token)
                .when().get("/api/semesters/" + semesterId + "/periods")
                .then().statusCode(200).extract().jsonPath().getList("id", Long.class);
        String semesterName = as(token).when().get("/api/semesters/" + semesterId)
                .then().statusCode(200).extract().jsonPath().getString("name");
        String gradeName = "八年级" + TestData.suffix();
        long gradeId = TestData.grade(token, semesterId, gradeName);
        String classNameA = "八（1）班" + TestData.suffix();
        String classNameB = "八（2）班" + TestData.suffix();
        long classA = adminClass(token, semesterId, gradeId, classNameA, sizeA);
        long classB = adminClass(token, semesterId, gradeId, classNameB, sizeB);
        return new Fixture(
                token,
                semesterId,
                semesterName,
                gradeName,
                classA,
                classB,
                classNameA,
                TestData.course(token, "语文" + TestData.suffix()),
                createCourse(token, "实验化学" + TestData.suffix(), "LAB"),
                TestData.teacher(token, "语文老师" + TestData.suffix()),
                TestData.teacher(token, "数学老师" + TestData.suffix()),
                periodIds);
    }

    private record SeedLesson(
            long classId, long courseId, long teacherId, int studentCount, Long periodId, Long roomId) {}

    private SeedLesson lesson(
            long classId, long courseId, long teacherId, int studentCount, Long periodId, Long roomId) {
        return new SeedLesson(classId, courseId, teacherId, studentCount, periodId, roomId);
    }

    /** Seeds the imported draft directly: the import itself is out of scope for this action. */
    private long seedDraft(Fixture f, List<SeedLesson> seeds) {
        TimetableDraft draft = new TimetableDraft();
        draft.setSemesterId(f.semesterId());
        draft.setName("导入课表 " + TestData.suffix());
        draft.setStatus("DRAFT");
        draft.setCreatedBy("admin");
        TimetableDraft saved = drafts.save(draft);
        List<DraftLesson> rows = new ArrayList<>();
        int index = 0;
        for (SeedLesson seed : seeds) {
            DraftLesson row = new DraftLesson();
            row.setDraftId(saved.getId());
            row.setAssignmentId(0L);
            row.setLessonIndex(index++);
            row.setCourseId(seed.courseId());
            row.setTeacherId(seed.teacherId());
            row.setAdminClassId(seed.classId());
            row.setStudentCount(seed.studentCount());
            row.setPeriodId(seed.periodId());
            row.setRoomId(seed.roomId());
            rows.add(row);
        }
        draftLessons.saveAll(rows);
        return saved.getId();
    }

    /** Adds one more lesson to an existing draft, as a later import would. */
    private void appendLesson(
            long draftId, long classId, long courseId, long teacherId, int studentCount, Long periodId) {
        DraftLesson row = new DraftLesson();
        row.setDraftId(draftId);
        row.setAssignmentId(0L);
        row.setLessonIndex((int) draftLessons.countByDraftId(draftId));
        row.setCourseId(courseId);
        row.setTeacherId(teacherId);
        row.setAdminClassId(classId);
        row.setStudentCount(studentCount);
        row.setPeriodId(periodId);
        draftLessons.save(row);
    }

    private long adminClass(String token, long semesterId, long gradeId, String name, int studentCount) {
        Response response = as(token)
                .body(Map.of("gradeId", gradeId, "name", name, "studentCount", studentCount))
                .queryParam("semesterId", semesterId)
                .when().post("/api/classes");
        response.then().statusCode(200);
        return response.jsonPath().getLong("id");
    }

    private long createCourse(String token, String name, String requiredRoomType) {
        Response response = as(token)
                .body(Map.of("name", name, "subject", name, "requiredRoomType", requiredRoomType))
                .when().post("/api/courses");
        response.then().statusCode(200);
        return response.jsonPath().getLong("id");
    }

    private long createRoom(String token, String name, int capacity, String type) {
        Response response = as(token)
                .body(Map.of("name", name, "capacity", capacity, "type", type))
                .when().post("/api/rooms");
        response.then().statusCode(200);
        return response.jsonPath().getLong("id");
    }

    /** A user holding exactly one permission, so the two-permission guard is isolated. */
    private String userWithRole(String adminToken, String prefix, String permission) {
        Role role = new Role(prefix + TestData.suffix(), "仅一种权限");
        role.setPermissions(Set.of(permissions.findByCode(permission).orElseThrow()));
        roles.save(role);
        String username = prefix + TestData.suffix();
        createUser(adminToken, username, "room12345", role.getCode());
        return username;
    }

    private List<Map<String, Object>> rooms(String token) {
        return as(token).when().get("/api/rooms")
                .then().statusCode(200).extract().jsonPath().getList("$");
    }

    /** The name this action derives for one of the fixture's classes. */
    private String fixedRoomName(Fixture f, String className) {
        return f.semesterName() + "-" + f.gradeName() + className + "-固定教室";
    }

    private boolean isFixedRoomOf(Map<String, Object> room, Fixture f) {
        return String.valueOf(room.get("name")).startsWith(f.semesterName() + "-")
                && String.valueOf(room.get("name")).endsWith("-固定教室");
    }

    private List<Map<String, Object>> draftLessonsOf(String token, long draftId) {
        return as(token).when().get("/api/timetable-drafts/" + draftId)
                .then().statusCode(200).extract().jsonPath().getList("lessons");
    }

    private List<Long> draftLessonRooms(long draftId) {
        return draftLessons.findByDraftIdOrderByIdAsc(draftId).stream()
                .map(DraftLesson::getRoomId)
                .toList();
    }

}
