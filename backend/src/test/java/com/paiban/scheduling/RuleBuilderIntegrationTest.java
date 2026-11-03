package com.paiban.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

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
 * 票 08 的后端契约：句子积木只生成现有的 {@code constraint_entry}，草稿校验
 * 通过同一个 {@code ConstraintChecker} 立即看到新规则；停用或删除一条要求
 * 只影响它自己，其它类型仍然生效。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RuleBuilderIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private ConstraintEntryRepository entryRepository;

    @Autowired
    private TimetableDraftRepository draftRepository;

    @Autowired
    private DraftLessonRepository draftLessonRepository;

    @Test
    void schoolAndTeacherSentencesCompileAndFlagDraftConflicts() {
        String token = adminToken();
        long semesterId = semesterWithPeriods(token);
        long ruleTeacher = TestData.teacher(token, "周五下午禁排" + TestData.suffix());
        long otherTeacher = TestData.teacher(token, "对照教师" + TestData.suffix());
        long course = TestData.course(token, "规则课" + TestData.suffix());
        long room = TestData.room(token, "规则教室" + TestData.suffix());

        save(token, semesterId, null, ruleTeacher, true, true, course).then().statusCode(200);

        List<ConstraintEntry> school = entries(semesterId, "CLASS_UNAVAILABLE");
        assertThat(school).hasSize(1);
        assertThat(school.get(0).getGroupKey()).isEqualTo("pt-school-fri-7");
        assertThat(school.get(0).getParamsJson())
                .contains("\"dayOfWeek\":5")
                .contains("\"periodNo\":7");
        assertThat(school.get(0).getHard()).isTrue();

        List<ConstraintEntry> teacher = entries(semesterId, "TEACHER_UNAVAILABLE");
        assertThat(teacher).hasSize(4);
        assertThat(teacher).allMatch(row -> "pt-teacher-fri-pm".equals(row.getGroupKey()));
        assertThat(teacher).allMatch(row -> row.getParamsJson().contains("\"teacherId\":" + ruleTeacher));
        for (int periodNo = 5; periodNo <= 8; periodNo++) {
            String expected = "\"periodNo\":" + periodNo;
            assertThat(teacher).anyMatch(row -> row.getParamsJson().contains(expected));
        }

        // Another type survives the sentence save, so the rule page never erases
        // what was configured in the advanced page.
        assertThat(entries(semesterId, "COURSE_NOT_IN_EVENING")).hasSize(1);

        Map<String, Long> periods = periodIds(token, semesterId);
        long firstClass = TestData.grade(token, semesterId, "高一" + TestData.suffix());
        long classA = TestData.adminClass(token, semesterId, firstClass, "高一1班" + TestData.suffix());
        long classB = TestData.adminClass(token, semesterId, firstClass, "高一2班" + TestData.suffix());
        long draftId = draft(token, semesterId, classA, course, otherTeacher, periods, 5, 7, room, classB, ruleTeacher);
        assertThat(draftLessonRepository.findByDraftIdOrderByIdAsc(draftId)).hasSize(2);

        Response validation = validate(token, draftId);
        assertThat(validation.jsonPath().getBoolean("valid")).isFalse();
        List<String> types = validation.jsonPath().getList("conflicts.type", String.class);
        assertThat(types).contains("CLASS_UNAVAILABLE", "TEACHER_UNAVAILABLE");
    }

    @Test
    void disablingOrDeletingOneGroupLeavesTheOtherRulesAlone() {
        String token = adminToken();
        long semesterId = semesterWithPeriods(token);
        long ruleTeacher = TestData.teacher(token, "停用禁排" + TestData.suffix());
        long otherTeacher = TestData.teacher(token, "停用对照" + TestData.suffix());
        long course = TestData.course(token, "停用规则课" + TestData.suffix());
        long room = TestData.room(token, "停用规则教室" + TestData.suffix());

        save(token, semesterId, null, ruleTeacher, true, true, course).then().statusCode(200);
        Map<String, Long> periods = periodIds(token, semesterId);
        long gradeId = TestData.grade(token, semesterId, "高一" + TestData.suffix());
        long classA = TestData.adminClass(token, semesterId, gradeId, "高一1班" + TestData.suffix());
        long classB = TestData.adminClass(token, semesterId, gradeId, "高一2班" + TestData.suffix());
        long draftId = draft(token, semesterId, classA, course, otherTeacher, periods, 5, 7, room, classB, ruleTeacher);

        // 停用教师卡片：只把这一组的 entry 变成 enabled=false。
        save(token, semesterId, version(token, semesterId), ruleTeacher, false, true, course)
                .then().statusCode(200);
        assertThat(entries(semesterId, "TEACHER_UNAVAILABLE"))
                .hasSize(4)
                .allMatch(row -> !row.isEnabled());
        assertThat(entries(semesterId, "CLASS_UNAVAILABLE"))
                .hasSize(1)
                .allMatch(ConstraintEntry::isEnabled);

        Response afterDisable = validate(token, draftId);
        List<String> disabledTypes = afterDisable.jsonPath().getList("conflicts.type", String.class);
        assertThat(disabledTypes).contains("CLASS_UNAVAILABLE");
        assertThat(disabledTypes).doesNotContain("TEACHER_UNAVAILABLE");

        // 删除教师组：整条要求从请求里消失，班级规则和其它类型原样保留。
        save(token, semesterId, version(token, semesterId), ruleTeacher, false, false, course)
                .then().statusCode(200);
        assertThat(entries(semesterId, "TEACHER_UNAVAILABLE")).isEmpty();
        assertThat(entries(semesterId, "CLASS_UNAVAILABLE")).hasSize(1);
        assertThat(entries(semesterId, "COURSE_NOT_IN_EVENING")).hasSize(1);

        Response afterDelete = validate(token, draftId);
        List<String> deletedTypes = afterDelete.jsonPath().getList("conflicts.type", String.class);
        assertThat(deletedTypes).contains("CLASS_UNAVAILABLE");
        assertThat(deletedTypes).doesNotContain("TEACHER_UNAVAILABLE");
    }

    @Test
    void aSoftTeacherSentenceIsAPreferenceAndDoesNotBlockTheDraft() {
        String token = adminToken();
        long semesterId = semesterWithPeriods(token);
        long ruleTeacher = TestData.teacher(token, "尽量禁排" + TestData.suffix());
        long course = TestData.course(token, "尽量规则课" + TestData.suffix());
        long room = TestData.room(token, "尽量规则教室" + TestData.suffix());

        List<Map<String, Object>> rows = new ArrayList<>();
        for (int periodNo = 5; periodNo <= 8; periodNo++) {
            rows.add(entry(Map.of(
                    "teacherId", ruleTeacher,
                    "dayOfWeek", 5,
                    "periodNo", periodNo), true, "pt-soft-teacher", false));
        }
        as(token).queryParam("semesterId", semesterId)
                .body(Map.of("configs", List.of(Map.of(
                        "type", "TEACHER_UNAVAILABLE",
                        "weight", 100,
                        "hard", true,
                        "enabled", true,
                        "entries", rows))))
                .when().put("/api/constraints")
                .then().statusCode(200);
        assertThat(entries(semesterId, "TEACHER_UNAVAILABLE"))
                .hasSize(4)
                .allMatch(row -> Boolean.FALSE.equals(row.getHard()));

        Map<String, Long> periods = periodIds(token, semesterId);
        long gradeId = TestData.grade(token, semesterId, "高一" + TestData.suffix());
        long classId = TestData.adminClass(token, semesterId, gradeId, "高一1班" + TestData.suffix());
        TimetableDraft draft = new TimetableDraft();
        draft.setSemesterId(semesterId);
        draft.setName("尽量规则草稿" + TestData.suffix());
        draft.setStatus("DRAFT");
        draft.setCreatedBy("test");
        draft.setCreatedAt(Instant.now());
        long draftId = draftRepository.save(draft).getId();
        placeLesson(draftId, classId, course, ruleTeacher, periods.get("5-5"), room, 1);

        Response validation = validate(token, draftId);
        assertThat(validation.jsonPath().getList("conflicts.type", String.class))
                .doesNotContain("TEACHER_UNAVAILABLE");
    }

    // ------------------------------------------------------------------ helpers

    private Response save(
            String token,
            long semesterId,
            Long baseVersion,
            long ruleTeacherId,
            boolean teacherEnabled,
            boolean includeTeacher,
            long courseId) {
        List<Map<String, Object>> configs = new ArrayList<>();
        configs.add(classConfig());
        if (includeTeacher) {
            configs.add(teacherConfig(ruleTeacherId, teacherEnabled));
        }
        configs.add(Map.of(
                "type", "COURSE_NOT_IN_EVENING",
                "params", Map.of("courseId", courseId),
                "weight", 100,
                "hard", true,
                "enabled", true));
        var request = as(token).queryParam("semesterId", semesterId);
        if (baseVersion != null) {
            request = request.queryParam("baseVersion", baseVersion);
        }
        return request.body(Map.of("configs", configs)).when().put("/api/constraints");
    }

    private Map<String, Object> classConfig() {
        return Map.of(
                "type", "CLASS_UNAVAILABLE",
                "weight", 100,
                "hard", true,
                "enabled", true,
                "entries", List.of(entry(Map.of("dayOfWeek", 5, "periodNo", 7),
                        true, "pt-school-fri-7")));
    }

    private Map<String, Object> teacherConfig(long ruleTeacherId, boolean enabled) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int periodNo = 5; periodNo <= 8; periodNo++) {
            rows.add(entry(Map.of(
                    "teacherId", ruleTeacherId,
                    "dayOfWeek", 5,
                    "periodNo", periodNo), enabled, "pt-teacher-fri-pm"));
        }
        return Map.of(
                "type", "TEACHER_UNAVAILABLE",
                "weight", 100,
                "hard", true,
                "enabled", true,
                "entries", rows);
    }

    private Map<String, Object> entry(Map<String, Object> params, boolean enabled, String groupKey) {
        return entry(params, enabled, groupKey, true);
    }

    private Map<String, Object> entry(
            Map<String, Object> params, boolean enabled, String groupKey, boolean hard) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("params", params);
        row.put("enabled", enabled);
        row.put("hard", hard);
        row.put("groupKey", groupKey);
        return row;
    }

    private long draft(
            String token,
            long semesterId,
            long classA,
            long courseId,
            long otherTeacher,
            Map<String, Long> periods,
            int rulePeriod,
            int classRulePeriod,
            long roomId,
            long classB,
            long ruleTeacherId) {
        TimetableDraft draft = new TimetableDraft();
        draft.setSemesterId(semesterId);
        draft.setName("规则草稿" + TestData.suffix());
        draft.setStatus("DRAFT");
        draft.setCreatedBy("test");
        draft.setCreatedAt(Instant.now());
        long draftId = draftRepository.save(draft).getId();
        // 周五下午：ruleTeacher 在上规则的那一节；另一个班落在全校不上课的周五第 7 节。
        placeLesson(draftId, classB, courseId, ruleTeacherId, periods.get("5-" + rulePeriod), roomId, 1);
        placeLesson(draftId, classA, courseId, otherTeacher, periods.get("5-" + classRulePeriod), roomId, 2);
        return draftId;
    }

    private void placeLesson(
            long draftId, long classId, long courseId, long teacherId,
            long periodId, long roomId, int index) {
        DraftLesson lesson = new DraftLesson();
        lesson.setDraftId(draftId);
        lesson.setAssignmentId(0L);
        lesson.setLessonIndex(index);
        lesson.setCourseId(courseId);
        lesson.setTeacherId(teacherId);
        lesson.setAdminClassId(classId);
        lesson.setStudentCount(45);
        lesson.setPeriodId(periodId);
        lesson.setRoomId(roomId);
        draftLessonRepository.save(lesson);
    }

    private Response validate(String token, long draftId) {
        return as(token)
                .when().get("/api/timetable-drafts/" + draftId + "/validation")
                .then().statusCode(200).extract().response();
    }

    private long version(String token, long semesterId) {
        return as(token).queryParam("semesterId", semesterId)
                .when().get("/api/constraints/state")
                .then().statusCode(200)
                .extract().jsonPath().getLong("version");
    }

    private List<ConstraintEntry> entries(long semesterId, String type) {
        return entryRepository.findBySemesterIdAndTypeOrderByIdAsc(semesterId, type);
    }

    private long semesterWithPeriods(String token) {
        long semesterId = TestData.semester(token);
        as(token).body(Map.of("morningCount", 4, "afternoonCount", 4, "eveningCount", 0))
                .when().post("/api/semesters/" + semesterId + "/periods/default")
                .then().statusCode(200);
        return semesterId;
    }

    private Map<String, Long> periodIds(String token, long semesterId) {
        List<Map<String, Object>> rows = as(token)
                .when().get("/api/semesters/" + semesterId + "/periods")
                .then().statusCode(200).extract().jsonPath().getList("$");
        Map<String, Long> bySlot = new LinkedHashMap<>();
        rows.forEach(row -> bySlot.put(
                ((Number) row.get("dayOfWeek")).intValue() + "-"
                        + ((Number) row.get("periodNo")).intValue(),
                ((Number) row.get("id")).longValue()));
        return bySlot;
    }
}
