package com.paiban.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

import com.paiban.scheduling.domain.ConstraintEntry;
import com.paiban.scheduling.domain.ConstraintEntryRepository;
import com.paiban.scheduling.domain.ConstraintTemplate;
import com.paiban.scheduling.domain.ConstraintTemplateRepository;
import com.paiban.scheduling.domain.DraftLesson;
import com.paiban.scheduling.domain.DraftLessonRepository;
import com.paiban.scheduling.domain.TimetableDraft;
import com.paiban.scheduling.domain.TimetableDraftRepository;
import com.paiban.support.ApiIntegrationTest;
import com.paiban.support.TestData;
import io.restassured.response.Response;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * The new multi-entry contract for {@code TEACHER_UNAVAILABLE}, exercised only
 * through the public REST surface plus a storage assertion. Legacy requests keep
 * their old single-params meaning (also covered by {@link ConstraintIntegrationTest}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ConstraintEntriesIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private ConstraintEntryRepository entryRepository;

    @Autowired
    private ConstraintTemplateRepository templateRepository;

    @Autowired
    private TimetableDraftRepository draftRepository;

    @Autowired
    private DraftLessonRepository draftLessonRepository;

    @Test
    void twoTeachersKeepBothUnavailableEntriesAndTheirIds() {
        String token = adminToken();
        long semesterId = semesterWithPeriods(token);
        long first = TestData.teacher(token, "禁排甲" + TestData.suffix());
        long second = TestData.teacher(token, "禁排乙" + TestData.suffix());

        Response saved = save(token, semesterId, List.of(unavailable(
                entry(first, 1, 1), entry(second, 2, 2))));

        saved.then().statusCode(200)
                .body("find { it.type == 'TEACHER_UNAVAILABLE' }.entries", hasSize(2))
                .body("find { it.type == 'TEACHER_UNAVAILABLE' }.entries[0].id", notNullValue())
                .body("find { it.type == 'TEACHER_UNAVAILABLE' }.entries[1].id", notNullValue());

        Response reloaded = as(token).queryParam("semesterId", semesterId)
                .when().get("/api/constraints").then().statusCode(200).extract().response();
        List<Map<String, Object>> entries =
                reloaded.jsonPath().getList("find { it.type == 'TEACHER_UNAVAILABLE' }.entries");
        assertThat(entries).hasSize(2);
        assertThat(((Number) ((Map<?, ?>) entries.get(0).get("params")).get("teacherId")).longValue())
                .isEqualTo(first);
        assertThat(((Number) ((Map<?, ?>) entries.get(1).get("params")).get("teacherId")).longValue())
                .isEqualTo(second);
        // The legacy single-params view stays populated with the first entry.
        assertThat(reloaded.jsonPath()
                .getLong("find { it.type == 'TEACHER_UNAVAILABLE' }.params.teacherId"))
                .isEqualTo(first);

        assertThat(unavailableEntries(semesterId)).hasSize(2);
    }

    @Test
    void twoSlotsOfTheSameTeacherAreStoredAsTwoEntries() {
        String token = adminToken();
        long semesterId = semesterWithPeriods(token);
        long teacher = TestData.teacher(token, "连排禁排" + TestData.suffix());

        save(token, semesterId, List.of(unavailable(
                entry(teacher, 1, 1), entry(teacher, 1, 3)))).then().statusCode(200);

        assertThat(unavailableEntries(semesterId)).hasSize(2);
        assertThat(unavailableEntries(semesterId))
                .allMatch(entry -> entry.getParamsJson().contains("\"teacherId\":" + teacher));
    }

    @Test
    void duplicateEntryIsRejectedAndNothingChanges() {
        String token = adminToken();
        long semesterId = semesterWithPeriods(token);
        long teacher = TestData.teacher(token, "重复禁排" + TestData.suffix());
        save(token, semesterId, List.of(unavailable(entry(teacher, 3, 2)))).then().statusCode(200);
        String before = as(token).queryParam("semesterId", semesterId)
                .when().get("/api/constraints").then().statusCode(200).extract().asString();

        Response rejected = save(token, semesterId, List.of(unavailable(
                entry(teacher, 3, 2), entry(teacher, 3, 2))));

        rejected.then().statusCode(400);
        assertThat(rejected.asString()).contains("TEACHER_UNAVAILABLE").contains("重复");
        String after = as(token).queryParam("semesterId", semesterId)
                .when().get("/api/constraints").then().statusCode(200).extract().asString();
        assertThat(after).isEqualTo(before);
        assertThat(unavailableEntries(semesterId)).hasSize(1);
    }

    @Test
    void invalidEntriesAreRejectedAndNothingChanges() {
        String token = adminToken();
        long semesterId = semesterWithPeriods(token);
        long teacher = TestData.teacher(token, "非法禁排" + TestData.suffix());
        save(token, semesterId, List.of(unavailable(entry(teacher, 1, 1)))).then().statusCode(200);
        String before = as(token).queryParam("semesterId", semesterId)
                .when().get("/api/constraints").then().statusCode(200).extract().asString();

        // A teacher that does not exist.
        save(token, semesterId, List.of(unavailable(entry(9_999_999L, 1, 1))))
                .then().statusCode(400);
        // dayOfWeek outside 1-7.
        save(token, semesterId, List.of(unavailable(entry(teacher, 8, 1))))
                .then().statusCode(400);
        // A period the semester timetable does not have (8 periods per day by default).
        Response outOfRange = save(token, semesterId, List.of(unavailable(entry(teacher, 1, 9))));
        outOfRange.then().statusCode(400);
        assertThat(outOfRange.asString()).contains("作息");

        String after = as(token).queryParam("semesterId", semesterId)
                .when().get("/api/constraints").then().statusCode(200).extract().asString();
        assertThat(after).isEqualTo(before);
        assertThat(unavailableEntries(semesterId)).hasSize(1);
    }

    @Test
    void legacyRequestWithoutEntriesBecomesOneEntry() {
        String token = adminToken();
        long semesterId = semesterWithPeriods(token);
        long teacher = TestData.teacher(token, "旧请求" + TestData.suffix());

        Map<String, Object> legacy = new LinkedHashMap<>();
        legacy.put("type", "TEACHER_UNAVAILABLE");
        legacy.put("params", Map.of("teacherId", teacher, "dayOfWeek", 2, "periodNo", 3));
        legacy.put("weight", 100);
        legacy.put("hard", true);
        legacy.put("enabled", true);
        as(token).queryParam("semesterId", semesterId)
                .body(Map.of("configs", List.of(legacy)))
                .when().put("/api/constraints")
                .then().statusCode(200)
                .body("find { it.type == 'TEACHER_UNAVAILABLE' }.entries", hasSize(1))
                .body("find { it.type == 'TEACHER_UNAVAILABLE' }.entries[0].params.dayOfWeek",
                        equalTo(2));

        assertThat(unavailableEntries(semesterId)).hasSize(1);
    }

    @Test
    void savingAgainUpdatesInPlaceInsertsAndDeletesByEntryId() {
        String token = adminToken();
        long semesterId = semesterWithPeriods(token);
        long first = TestData.teacher(token, "稳定甲" + TestData.suffix());
        long second = TestData.teacher(token, "稳定乙" + TestData.suffix());
        long third = TestData.teacher(token, "稳定丙" + TestData.suffix());

        Response initial = save(token, semesterId, List.of(unavailable(
                entry(first, 1, 1), entry(second, 2, 2)))).then().statusCode(200).extract().response();
        List<Long> ids = entryIds(initial, "TEACHER_UNAVAILABLE");
        assertThat(ids).hasSize(2);

        // Second save: change one entry's parameters, add one, drop one.
        Response second2 = save(token, semesterId, List.of(unavailable(
                entry(ids.get(0), first, 1, 3), entry(null, third, 3, 4))))
                .then().statusCode(200).extract().response();
        List<Map<String, Object>> saved =
                second2.jsonPath().getList("find { it.type == 'TEACHER_UNAVAILABLE' }.entries");
        assertThat(saved).hasSize(2);
        assertThat(number(saved.get(0).get("id"))).as("the edited entry keeps its id")
                .isEqualTo(ids.get(0));
        assertThat(paramsOf(saved.get(0)).get("periodNo")).isEqualTo(3);
        Long insertedId = number(saved.get(1).get("id"));
        assertThat(insertedId).isNotNull().isNotEqualTo(ids.get(0)).isNotEqualTo(ids.get(1));
        assertThat(number(paramsOf(saved.get(1)).get("teacherId"))).isEqualTo(third);

        // Storage agrees: the dropped row is gone, the kept one was updated in place.
        List<ConstraintEntry> rows = unavailableEntries(semesterId);
        assertThat(rows).extracting(ConstraintEntry::getId)
                .containsExactlyInAnyOrder(ids.get(0), insertedId);
        assertThat(rows.stream().filter(row -> row.getId().equals(ids.get(0))).findFirst().orElseThrow()
                .getParamsJson()).contains("\"periodNo\":3");

        List<Map<String, Object>> reloaded = as(token).queryParam("semesterId", semesterId)
                .when().get("/api/constraints").then().statusCode(200).extract().jsonPath()
                .getList("find { it.type == 'TEACHER_UNAVAILABLE' }.entries");
        assertThat(reloaded).extracting(row -> number(row.get("id")))
                .containsExactly(ids.get(0), insertedId);
    }

    @Test
    void anEntryIdFromAnotherSemesterOrTypeIsRejectedWithoutWriting() {
        String token = adminToken();
        long source = semesterWithPeriods(token);
        long teacher = TestData.teacher(token, "跨学期条目" + TestData.suffix());
        // One request carries both types, because a save replaces the whole semester.
        Response seeded = save(token, source, List.of(
                unavailable(entry(teacher, 1, 1)),
                Map.of("type", "TEACHER_MAX_PER_DAY", "weight", 60, "hard", false, "enabled", true,
                        "entries", List.of(Map.of("params", Map.of("maxPerDay", 6))))))
                .then().statusCode(200).extract().response();
        Long seededEntryId = entryIds(seeded, "TEACHER_UNAVAILABLE").get(0);
        Long thresholdEntryId = entryRepository.findBySemesterIdAndTypeOrderByIdAsc(
                source, "TEACHER_MAX_PER_DAY").get(0).getId();
        String sourceBefore = as(token).queryParam("semesterId", source)
                .when().get("/api/constraints").then().statusCode(200).extract().asString();

        // Same id, different semester: the id names no row of the target semester.
        long target = semesterWithPeriods(token);
        Response foreignSemester = save(token, target, List.of(unavailable(
                entry(seededEntryId, teacher, 1, 1))));
        foreignSemester.then().statusCode(400);
        assertThat(foreignSemester.asString()).contains("不属于当前学期");
        assertThat(unavailableEntries(target)).isEmpty();
        assertThat(entryRowCount(target)).isEqualTo(0);

        // Same semester, another type: a threshold id is not an unavailable-entry id.
        Response foreignType = save(token, source, List.of(unavailable(
                entry(thresholdEntryId, teacher, 1, 1))));
        foreignType.then().statusCode(400);
        assertThat(foreignType.asString()).contains("TEACHER_MAX_PER_DAY");

        // Zero writes: both types keep their rows, values and ids.
        assertThat(as(token).queryParam("semesterId", source)
                .when().get("/api/constraints").then().statusCode(200).extract().asString())
                .isEqualTo(sourceBefore);
        assertThat(entryRepository.findBySemesterIdAndTypeOrderByIdAsc(source, "TEACHER_MAX_PER_DAY"))
                .hasSize(1)
                .allMatch(row -> row.getParamsJson().contains("\"maxPerDay\":6"));
        assertThat(unavailableEntries(source)).hasSize(1);
        assertThat(unavailableEntries(source).get(0).getId()).isEqualTo(seededEntryId);
    }

    @Test
    void otherTypesKeepTheirLegacyParamsMeaning() {
        String token = adminToken();
        long semesterId = semesterWithPeriods(token);
        long course = TestData.course(token, "晚自习课程" + TestData.suffix());

        as(token).queryParam("semesterId", semesterId)
                .body(Map.of("configs", List.of(
                        Map.of("type", "COURSE_NOT_IN_EVENING",
                                "params", Map.of("courseId", course),
                                "weight", 100, "hard", true, "enabled", true),
                        Map.of("type", "TEACHER_MAX_PER_DAY",
                                "params", Map.of("maxPerDay", 5),
                                "weight", 60, "hard", false, "enabled", true))))
                .when().put("/api/constraints")
                .then().statusCode(200);

        as(token).queryParam("semesterId", semesterId)
                .when().get("/api/constraints")
                .then().statusCode(200)
                .body("find { it.type == 'COURSE_NOT_IN_EVENING' }.params.courseId", equalTo((int) course))
                .body("find { it.type == 'COURSE_NOT_IN_EVENING' }.entries", hasSize(1))
                .body("find { it.type == 'COURSE_NOT_IN_EVENING' }.entries[0].params.courseId",
                        equalTo((int) course))
                .body("find { it.type == 'TEACHER_MAX_PER_DAY' }.params.maxPerDay", equalTo(5))
                .body("find { it.type == 'TEACHER_MAX_PER_DAY' }.entries", hasSize(1));
    }

    @Test
    void templateRoundTripRestoresEveryEntry() {
        String token = adminToken();
        long source = semesterWithPeriods(token);
        long first = TestData.teacher(token, "模板甲" + TestData.suffix());
        long second = TestData.teacher(token, "模板乙" + TestData.suffix());
        save(token, source, List.of(
                unavailable(entry(first, 1, 1), entry(first, 1, 2), entry(second, 2, 4)),
                Map.of("type", "CLASS_MAX_PER_DAY", "params", Map.of("maxPerDay", 7),
                        "weight", 65, "hard", false, "enabled", true))).then().statusCode(200);

        long templateId = as(token).queryParam("semesterId", source)
                .body(Map.of("name", "多条教师禁排-" + TestData.suffix()))
                .when().post("/api/constraint-templates")
                .then().statusCode(200)
                .body("configCount", equalTo(2))
                .extract().jsonPath().getLong("id");

        long target = semesterWithPeriods(token);
        as(token).queryParam("semesterId", target)
                .when().post("/api/constraint-templates/" + templateId + "/apply")
                .then().statusCode(200)
                .body("find { it.type == 'TEACHER_UNAVAILABLE' }.entries", hasSize(3));

        List<Map<String, Object>> restored =
                as(token).queryParam("semesterId", target).when().get("/api/constraints")
                        .then().statusCode(200)
                        .extract().jsonPath()
                        .getList("find { it.type == 'TEACHER_UNAVAILABLE' }.entries");
        assertThat(restored).hasSize(3);
        assertThat(restored.stream()
                .map(row -> ((Number) ((Map<?, ?>) row.get("params")).get("teacherId")).longValue())
                .toList()).containsExactly(first, first, second);
        assertThat(unavailableEntries(target)).hasSize(3);
    }

    @Test
    void legacyTemplateWithoutEntriesStillApplies() {
        String token = adminToken();
        long semesterId = semesterWithPeriods(token);
        long teacher = TestData.teacher(token, "老模板" + TestData.suffix());
        // The pre-V26 template shape: one params map per type, no entries array.
        String legacyJson = "[{\"type\":\"TEACHER_UNAVAILABLE\",\"params\":{\"teacherId\":" + teacher
                + ",\"dayOfWeek\":1,\"periodNo\":3},\"weight\":100,\"hard\":true,\"enabled\":true}]";
        long templateId = seedLegacyTemplate("老模板-" + TestData.suffix(), legacyJson);

        as(token).queryParam("semesterId", semesterId)
                .when().post("/api/constraint-templates/" + templateId + "/apply")
                .then().statusCode(200)
                .body("find { it.type == 'TEACHER_UNAVAILABLE' }.entries", hasSize(1))
                .body("find { it.type == 'TEACHER_UNAVAILABLE' }.entries[0].params.periodNo",
                        equalTo(3));
        assertThat(unavailableEntries(semesterId)).hasSize(1);
    }

    @Test
    void draftCheckAndPublishRefuseBothUnavailableSlots() {
        String token = adminToken();
        long semesterId = semesterWithPeriods(token);
        long gradeId = TestData.grade(token, semesterId, "高一" + TestData.suffix());
        long classId = TestData.adminClass(token, semesterId, gradeId, "高一1班" + TestData.suffix());
        long first = TestData.teacher(token, "草稿甲" + TestData.suffix());
        long second = TestData.teacher(token, "草稿乙" + TestData.suffix());
        long chinese = TestData.course(token, "语文" + TestData.suffix());
        long math = TestData.course(token, "数学" + TestData.suffix());
        long roomOne = TestData.room(token, "教室1" + TestData.suffix());
        long roomTwo = TestData.room(token, "教室2" + TestData.suffix());
        save(token, semesterId, List.of(unavailable(
                entry(first, 1, 1), entry(second, 1, 2), entry(first, 2, 1)))).then().statusCode(200);
        Map<Integer, Long> periods = periodIds(token, semesterId);

        TimetableDraft draft = new TimetableDraft();
        draft.setSemesterId(semesterId);
        draft.setName("禁排草稿" + TestData.suffix());
        draft.setStatus("DRAFT");
        draft.setCreatedBy("test");
        draft.setCreatedAt(java.time.Instant.now());
        long draftId = draftRepository.save(draft).getId();
        // Both teachers sit in a slot they are unavailable for; the third lesson is fine.
        placeLesson(draftId, classId, chinese, first, periods.get(1), roomOne, 1);
        placeLesson(draftId, classId, math, second, periods.get(2), roomTwo, 2);
        placeLesson(draftId, classId, chinese, second, periods.get(3), roomOne, 3);

        Response validation = as(token)
                .when().get("/api/timetable-drafts/" + draftId + "/validation")
                .then().statusCode(200).extract().response();
        assertThat(validation.jsonPath().getBoolean("valid")).isFalse();
        assertThat(validation.jsonPath().getList("conflicts.type", String.class))
                .filteredOn(type -> type.equals("TEACHER_UNAVAILABLE"))
                .hasSize(2);

        as(token).when().post("/api/timetable-drafts/" + draftId + "/publish")
                .then().statusCode(409);
    }

    // ------------------------------------------------------------------ helpers

    private Map<Integer, Long> periodIds(String token, long semesterId) {
        List<Map<String, Object>> rows = as(token)
                .when().get("/api/semesters/" + semesterId + "/periods")
                .then().statusCode(200).extract().jsonPath().getList("$");
        Map<Integer, Long> byNo = new LinkedHashMap<>();
        rows.stream()
                .filter(row -> ((Number) row.get("dayOfWeek")).intValue() == 1)
                .forEach(row -> byNo.put(((Number) row.get("periodNo")).intValue(),
                        ((Number) row.get("id")).longValue()));
        return byNo;
    }

    private void placeLesson(long draftId, long classId, long courseId, long teacherId,
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

    private Map<String, Object> unavailable(Map<String, Object>... entries) {
        return Map.of(
                "type", "TEACHER_UNAVAILABLE",
                "weight", 100,
                "hard", true,
                "enabled", true,
                "entries", List.of(entries));
    }

    private Map<String, Object> entry(long teacherId, int dayOfWeek, int periodNo) {
        return Map.of("params", Map.of(
                "teacherId", teacherId, "dayOfWeek", dayOfWeek, "periodNo", periodNo));
    }

    /** An entry that asks to update one existing row (`id` null means "insert"). */
    private Map<String, Object> entry(Long id, long teacherId, int dayOfWeek, int periodNo) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("params", Map.of(
                "teacherId", teacherId, "dayOfWeek", dayOfWeek, "periodNo", periodNo));
        return row;
    }

    private List<Long> entryIds(Response response, String type) {
        List<Map<String, Object>> rows = response.jsonPath()
                .getList("find { it.type == '" + type + "' }.entries");
        return rows.stream().map(row -> number(row.get("id"))).toList();
    }

    private Map<String, Object> paramsOf(Map<String, Object> entry) {
        Object params = entry.get("params");
        return params instanceof Map<?, ?> map ? castParams(map) : Map.of();
    }

    private Map<String, Object> castParams(Map<?, ?> params) {
        Map<String, Object> copy = new LinkedHashMap<>();
        params.forEach((key, value) -> copy.put(String.valueOf(key), value));
        return copy;
    }

    private Long number(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private int entryRowCount(long semesterId) {
        return entryRepository.findBySemesterId(semesterId).size();
    }

    private List<ConstraintEntry> unavailableEntries(long semesterId) {
        return entryRepository.findBySemesterId(semesterId).stream()
                .filter(entry -> "TEACHER_UNAVAILABLE".equals(entry.getType()))
                .toList();
    }

    /** Seeds a template row in the pre-V26 JSON shape, bypassing the current writer. */
    private long seedLegacyTemplate(String name, String configsJson) {
        ConstraintTemplate template = new ConstraintTemplate();
        template.setName(name);
        template.setConfigsJson(configsJson);
        template.setCreatedAt(java.time.Instant.now());
        return templateRepository.save(template).getId();
    }
}
