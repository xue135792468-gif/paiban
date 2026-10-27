package com.paiban.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

import com.paiban.scheduling.domain.ConstraintEntry;
import com.paiban.scheduling.domain.ConstraintEntryRepository;
import com.paiban.scheduling.domain.ConstraintConfigRepository;
import com.paiban.scheduling.domain.ConstraintTemplate;
import com.paiban.scheduling.domain.ConstraintTemplateRepository;
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
 * Ticket 10/05: a template carries *every* entry and every type-level control, old
 * single-params templates keep their meaning, the stored text is large enough for a
 * real entry list without silent truncation, and a class reference from another
 * semester is either rebound explicitly or refused with the offending rows listed.
 *
 * <p>Everything here goes through the public template REST surface; entry rows and
 * legacy template rows are seeded directly only where the current writer could not
 * express the state under test (an old-format template, an oversized snapshot).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ConstraintTemplateCompatibilityTest extends ApiIntegrationTest {

    @Autowired
    private ConstraintEntryRepository entryRepository;

    @Autowired
    private ConstraintConfigRepository configRepository;

    @Autowired
    private ConstraintTemplateRepository templateRepository;

    @Test
    void templateRestoresEveryEntryAndTypeControl() {
        String token = adminToken();
        long source = semesterWithPeriods(token);
        long first = TestData.teacher(token, "模板甲" + TestData.suffix());
        long second = TestData.teacher(token, "模板乙" + TestData.suffix());
        long chinese = TestData.course(token, "语文" + TestData.suffix());
        long math = TestData.course(token, "数学" + TestData.suffix());
        long klass = adminClass(token, source, "模板班" + TestData.suffix());

        save(token, source, List.of(
                unavailable(entry(first, 1, 1), entry(first, 1, 2), entry(second, 2, 4)),
                Map.of("type", "COURSE_REQUIRED_ROOM_TYPE", "weight", 70, "hard", false,
                        "enabled", true,
                        "entries", List.of(
                                entry(Map.of("courseId", chinese, "roomType", "LAB")),
                                entry(Map.of("courseId", math, "roomType", "NORMAL")))),
                Map.of("type", "TEACHER_MAX_PER_DAY", "weight", 55, "hard", false,
                        "enabled", true,
                        "entries", List.of(
                                entry(Map.of("maxPerDay", 6)),
                                entry(Map.of("teacherId", first, "maxPerDay", 3)))),
                Map.of("type", "CLASS_MAX_PER_DAY", "weight", 90, "hard", true,
                        "enabled", true,
                        "entries", List.of(
                                entry(Map.of("maxPerDay", 8)),
                                entry(Map.of("classId", klass, "maxPerDay", 5)))),
                // A switched-off single-switch row: the template must carry the switch too.
                Map.of("type", "ROOM_CONFLICT", "weight", 100, "hard", true, "enabled", false)))
                .then().statusCode(200);

        long templateId = saveTemplate(token, source, "完整往返-" + TestData.suffix())
                .then().statusCode(200)
                .body("configCount", equalTo(5))
                .body("entryCount", equalTo(9))
                .extract().jsonPath().getLong("id");

        // Wipe the semester so the template is the only source of the configuration.
        save(token, source, List.of()).then().statusCode(200);
        assertThat(configs(token, source)).isEmpty();

        as(token).queryParam("semesterId", source)
                .when().post("/api/constraint-templates/" + templateId + "/apply")
                .then().statusCode(200);

        List<Map<String, Object>> restored = configs(token, source);
        assertThat(restored).hasSize(5);

        Map<String, Object> unavailable = byType(restored, "TEACHER_UNAVAILABLE");
        assertParams(unavailable, List.of(
                Map.of("teacherId", first, "dayOfWeek", 1, "periodNo", 1),
                Map.of("teacherId", first, "dayOfWeek", 1, "periodNo", 2),
                Map.of("teacherId", second, "dayOfWeek", 2, "periodNo", 4)));
        assertThat(unavailable.get("weight")).isEqualTo(100);
        assertThat(unavailable.get("hard")).isEqualTo(true);
        assertThat(unavailable.get("enabled")).isEqualTo(true);

        Map<String, Object> roomType = byType(restored, "COURSE_REQUIRED_ROOM_TYPE");
        assertParams(roomType, List.of(
                Map.of("courseId", chinese, "roomType", "LAB"),
                Map.of("courseId", math, "roomType", "NORMAL")));
        assertThat(roomType.get("weight")).isEqualTo(70);
        assertThat(roomType.get("hard")).isEqualTo(false);

        Map<String, Object> teacherMax = byType(restored, "TEACHER_MAX_PER_DAY");
        assertParams(teacherMax, List.of(
                Map.of("maxPerDay", 6),
                Map.of("teacherId", first, "maxPerDay", 3)));
        assertThat(teacherMax.get("weight")).isEqualTo(55);

        Map<String, Object> classMax = byType(restored, "CLASS_MAX_PER_DAY");
        assertParams(classMax, List.of(
                Map.of("maxPerDay", 8),
                Map.of("classId", klass, "maxPerDay", 5)));
        assertThat(classMax.get("hard")).isEqualTo(true);

        Map<String, Object> roomConflict = byType(restored, "ROOM_CONFLICT");
        assertThat(roomConflict.get("enabled")).isEqualTo(false);
        assertThat(entryRows(source, "ROOM_CONFLICT")).isZero();
    }

    @Test
    void legacyTemplateWithoutEntriesKeepsItsSingleEntryMeaning() {
        String token = adminToken();
        long semesterId = semesterWithPeriods(token);
        long teacher = TestData.teacher(token, "老模板" + TestData.suffix());
        long course = TestData.course(token, "老模板课" + TestData.suffix());
        // The pre-V26 shape: one params map per type, no entries array anywhere.
        String legacyJson = "["
                + "{\"type\":\"TEACHER_UNAVAILABLE\",\"params\":{\"teacherId\":" + teacher
                + ",\"dayOfWeek\":1,\"periodNo\":3},\"weight\":100,\"hard\":true,\"enabled\":true},"
                + "{\"type\":\"TEACHER_MAX_PER_DAY\",\"params\":{\"maxPerDay\":5},"
                + "\"weight\":60,\"hard\":false,\"enabled\":true},"
                + "{\"type\":\"COURSE_NOT_IN_EVENING\",\"params\":{\"courseId\":" + course
                + "},\"weight\":50,\"hard\":false,\"enabled\":true}]";
        long templateId = seedTemplate("老模板-" + TestData.suffix(), legacyJson);

        as(token).queryParam("semesterId", semesterId)
                .when().post("/api/constraint-templates/" + templateId + "/apply")
                .then().statusCode(200);

        List<Map<String, Object>> restored = configs(token, semesterId);
        assertParams(byType(restored, "TEACHER_UNAVAILABLE"), List.of(
                Map.of("teacherId", teacher, "dayOfWeek", 1, "periodNo", 3)));
        assertParams(byType(restored, "TEACHER_MAX_PER_DAY"), List.of(Map.of("maxPerDay", 5)));
        assertParams(byType(restored, "COURSE_NOT_IN_EVENING"), List.of(
                Map.of("courseId", course)));
        assertThat(byType(restored, "TEACHER_MAX_PER_DAY").get("weight")).isEqualTo(60);

        // Re-saving the applied configuration round-trips the same meaning.
        long reSaved = saveTemplate(token, semesterId, "老模板升级-" + TestData.suffix())
                .then().statusCode(200)
                .body("entryCount", equalTo(3))
                .extract().jsonPath().getLong("id");
        long second = semesterWithPeriods(token);
        as(token).queryParam("semesterId", second)
                .when().post("/api/constraint-templates/" + reSaved + "/apply")
                .then().statusCode(200);
        assertParams(byType(configs(token, second), "TEACHER_UNAVAILABLE"), List.of(
                Map.of("teacherId", teacher, "dayOfWeek", 1, "periodNo", 3)));
        assertThat(entryRows(second, "TEACHER_UNAVAILABLE")).isEqualTo(1);
    }

    @Test
    void twoHundredEntriesFitAndAnOversizedSnapshotIsRefused() {
        String token = adminToken();
        long source = semesterWithPeriods(token);
        // 5 days x 8 periods x 5 teachers = 200 distinct, legal unavailable slots.
        List<Map<String, Object>> entries = new ArrayList<>();
        for (int teacherIndex = 0; teacherIndex < 5; teacherIndex++) {
            long teacher = TestData.teacher(token, "容量师" + teacherIndex + TestData.suffix());
            for (int day = 1; day <= 5; day++) {
                for (int period = 1; period <= 8; period++) {
                    entries.add(entry(teacher, day, period));
                }
            }
        }
        assertThat(entries).hasSize(200);
        save(token, source, List.of(unavailable(entries.toArray(new Map[0]))))
                .then().statusCode(200);

        long templateId = saveTemplate(token, source, "两百条-" + TestData.suffix())
                .then().statusCode(200)
                .body("configCount", equalTo(1))
                .body("entryCount", equalTo(200))
                .extract().jsonPath().getLong("id");
        assertThat(templateRepository.findById(templateId).orElseThrow().getConfigsJson().length())
                .isGreaterThan(8000);

        long target = semesterWithPeriods(token);
        as(token).queryParam("semesterId", target)
                .when().post("/api/constraint-templates/" + templateId + "/apply")
                .then().statusCode(200);
        assertThat(entryRows(target, "TEACHER_UNAVAILABLE")).isEqualTo(200);

        // Beyond the column there is still an explicit refusal, never a truncation.
        long overflowed = semesterWithPeriods(token);
        long filler = TestData.teacher(token, "溢出师" + TestData.suffix());
        save(token, overflowed, List.of(unavailable(entry(filler, 1, 1)))).then().statusCode(200);
        List<ConstraintEntry> extra = new ArrayList<>();
        for (int index = 0; index < 1600; index++) {
            ConstraintEntry row = new ConstraintEntry();
            row.setSemesterId(overflowed);
            row.setType("TEACHER_UNAVAILABLE");
            row.setParamsJson("{\"teacherId\":" + filler + ",\"dayOfWeek\":1,\"periodNo\":1,"
                    + "\"pad\":" + index + "}");
            row.setCreatedAt(Instant.now());
            extra.add(row);
        }
        entryRepository.saveAll(extra);
        String overflowName = "超容量-" + TestData.suffix();
        saveTemplate(token, overflowed, overflowName)
                .then().statusCode(400)
                .body("message", containsString("超过"));
        assertThat(as(token).when().get("/api/constraint-templates").then().statusCode(200)
                .extract().jsonPath().getList("name")).doesNotContain(overflowName);
    }

    @Test
    void crossSemesterClassReferenceIsRefusedUntilExplicitlyRebound() {
        String token = adminToken();
        long source = semesterWithPeriods(token);
        long teacher = TestData.teacher(token, "跨学期师" + TestData.suffix());
        long course = TestData.course(token, "跨学期课" + TestData.suffix());
        String className = "跨学期班" + TestData.suffix();
        long sourceClass = adminClass(token, source, className);
        save(token, source, List.of(
                unavailable(entry(teacher, 1, 1)),
                Map.of("type", "COURSE_REQUIRED_ROOM_TYPE", "weight", 70, "hard", false,
                        "enabled", true,
                        "entries", List.of(entry(Map.of("courseId", course, "roomType", "LAB")))),
                Map.of("type", "CLASS_MAX_PER_DAY", "weight", 80, "hard", false, "enabled", true,
                        "entries", List.of(
                                entry(Map.of("maxPerDay", 8)),
                                entry(Map.of("classId", sourceClass, "maxPerDay", 5))))))
                .then().statusCode(200);
        long templateId = saveTemplate(token, source, "跨学期-" + TestData.suffix())
                .then().statusCode(200)
                .extract().jsonPath().getLong("id");

        long target = semesterWithPeriods(token);
        long targetClass = adminClass(token, target, className);
        String before = rawConstraints(token, target);

        Response refused = as(token).queryParam("semesterId", target)
                .when().post("/api/constraint-templates/" + templateId + "/apply")
                .then().statusCode(409)
                .body("errors", hasSize(0))
                .body("invalidReferences", hasSize(1))
                .body("invalidReferences[0].type", equalTo("CLASS_MAX_PER_DAY"))
                .body("invalidReferences[0].field", equalTo("classId"))
                .body("invalidReferences[0].objectId", equalTo((int) sourceClass))
                .body("invalidReferences[0].name", equalTo(className))
                .body("invalidReferences[0].position", equalTo(2))
                .extract().response();
        assertThat(rawConstraints(token, target)).isEqualTo(before);
        assertThat(entryRows(target, "CLASS_MAX_PER_DAY")).isZero();

        // The preview reports the same list without writing anything — same body shape,
        // same rows: 200 + errors/invalidReferences here, 409 + the same two lists there.
        Response preview = as(token).queryParam("semesterId", target)
                .when().post("/api/constraint-templates/" + templateId + "/preview")
                .then().statusCode(200)
                .body("configCount", equalTo(3))
                .body("entryCount", equalTo(4))
                .body("invalidReferences", hasSize(1))
                .body("errors", hasSize(0))
                .extract().response();
        assertThat(preview.jsonPath().getList("invalidReferences").toString())
                .isEqualTo(refused.jsonPath().getList("invalidReferences").toString());
        assertThat(rawConstraints(token, target)).isEqualTo(before);

        // An explicit mapping rebinds the class; teachers and courses stay global.
        as(token).queryParam("semesterId", target)
                .body(Map.of("classMapping", Map.of(String.valueOf(sourceClass), targetClass)))
                .when().post("/api/constraint-templates/" + templateId + "/apply")
                .then().statusCode(200);

        List<Map<String, Object>> applied = configs(token, target);
        assertParams(byType(applied, "CLASS_MAX_PER_DAY"), List.of(
                Map.of("maxPerDay", 8),
                Map.of("classId", targetClass, "maxPerDay", 5)));
        assertParams(byType(applied, "TEACHER_UNAVAILABLE"), List.of(
                Map.of("teacherId", teacher, "dayOfWeek", 1, "periodNo", 1)));
        assertParams(byType(applied, "COURSE_REQUIRED_ROOM_TYPE"), List.of(
                Map.of("courseId", course, "roomType", "LAB")));
        assertThat(entryRepository.findBySemesterId(target).stream()
                .noneMatch(row -> row.getParamsJson().contains(
                        "\"classId\":" + sourceClass + "}")))
                .isTrue();
    }

    @Test
    void applyingTheSameTemplateTwiceDoesNotDuplicateEntries() {
        String token = adminToken();
        long source = semesterWithPeriods(token);
        long teacher = TestData.teacher(token, "重复套用" + TestData.suffix());
        save(token, source, List.of(
                unavailable(entry(teacher, 1, 1), entry(teacher, 1, 2))))
                .then().statusCode(200);
        long templateId = saveTemplate(token, source, "重复套用-" + TestData.suffix())
                .then().statusCode(200).extract().jsonPath().getLong("id");

        long target = semesterWithPeriods(token);
        for (int round = 0; round < 3; round++) {
            as(token).queryParam("semesterId", target)
                    .when().post("/api/constraint-templates/" + templateId + "/apply")
                    .then().statusCode(200);
            assertThat(entryRows(target, "TEACHER_UNAVAILABLE")).isEqualTo(2);
            assertThat(configRepository.findBySemesterId(target)).hasSize(1);
        }
    }

    @Test
    void aFailedApplyLeavesTheConfigurationByteForByteUntouched() {
        String token = adminToken();
        long semesterId = semesterWithPeriods(token);
        long teacher = TestData.teacher(token, "原子性" + TestData.suffix());
        save(token, semesterId, List.of(unavailable(entry(teacher, 1, 1))))
                .then().statusCode(200);
        String before = rawConstraints(token, semesterId);

        long unknownTeacher = seedTemplate("未知教师-" + TestData.suffix(),
                "[{\"type\":\"TEACHER_UNAVAILABLE\",\"params\":{\"teacherId\":999999,"
                        + "\"dayOfWeek\":1,\"periodNo\":2},\"weight\":100,\"hard\":true,"
                        + "\"enabled\":true}]");
        as(token).queryParam("semesterId", semesterId)
                .when().post("/api/constraint-templates/" + unknownTeacher + "/apply")
                .then().statusCode(409)
                .body("invalidReferences[0].field", equalTo("teacherId"));
        assertThat(rawConstraints(token, semesterId)).isEqualTo(before);

        long outsideTimetable = seedTemplate("超作息-" + TestData.suffix(),
                "[{\"type\":\"TEACHER_UNAVAILABLE\",\"params\":{\"teacherId\":" + teacher
                        + ",\"dayOfWeek\":6,\"periodNo\":2},\"weight\":100,\"hard\":true,"
                        + "\"enabled\":true}]");
        // The apply reports every refusal in the same body shape the preview returns:
        // 409 with `errors` (a slot the timetable does not have) and an empty
        // `invalidReferences` (no object is missing).
        as(token).queryParam("semesterId", semesterId)
                .when().post("/api/constraint-templates/" + outsideTimetable + "/apply")
                .then().statusCode(409)
                .body("errors", hasSize(1))
                .body("invalidReferences", hasSize(0));
        assertThat(rawConstraints(token, semesterId)).isEqualTo(before);
        assertThat(entryRows(semesterId, "TEACHER_UNAVAILABLE")).isEqualTo(1);
    }

    @Test
    void theWholeCatalogueRoundTripsWithPerRequirementControlsAndGroups() {
        String token = adminToken();
        long source = semesterWithPeriods(token);
        long target = semesterWithPeriods(token);
        long teacher = TestData.teacher(token, "全目录师" + TestData.suffix());
        String courseName = "语文" + TestData.suffix();
        long course = TestData.course(token, courseName);
        String className = "全目录班" + TestData.suffix();
        long sourceClass = adminClass(token, source, className);
        long targetClass = adminClass(token, target, className);

        List<Map<String, Object>> configs = new ArrayList<>();
        // 19 entry-backed types, one grouped requirement each with its own soft weight.
        configs.add(controlled("TEACHER_UNAVAILABLE", 44,
                Map.of("teacherId", teacher, "dayOfWeek", 1, "periodNo", 1)));
        configs.add(controlled("TEACHER_MEETING_BLOCKED", 44,
                Map.of("subject", courseName, "dayOfWeek", 1, "periodNo", 2)));
        configs.add(controlled("COURSE_REQUIRED_ROOM_TYPE", 44,
                Map.of("courseId", course, "roomType", "LAB")));
        configs.add(controlled("COURSE_NOT_IN_EVENING", 44, Map.of("courseId", course)));
        configs.add(controlled("COURSE_UNAVAILABLE", 44,
                Map.of("courseId", course, "dayOfWeek", 2, "periodNo", 1)));
        configs.add(controlled("SAME_COURSE_ONCE_PER_DAY", 44, Map.of()));
        configs.add(controlled("SPORT_NOT_FIRST_AFTERNOON", 44, Map.of()));
        configs.add(controlled("CONSECUTIVE_LESSONS", 44,
                Map.of("courseId", course, "minConsecutive", 2)));
        configs.add(controlled("COURSE_SPREAD", 44, Map.of()));
        configs.add(controlled("MAIN_SUBJECT_MORNING", 44,
                Map.of("courseId", course, "beforePeriodNo", 4)));
        configs.add(controlled("TEACHER_PREFERRED_SLOT", 44,
                Map.of("teacherId", teacher, "dayOfWeek", 3, "periodNo", 1)));
        configs.add(controlled("TEACHER_NO_FIRST_PERIOD", 44, Map.of("teacherId", teacher)));
        configs.add(controlled("TEACHER_DAY_OFF", 44,
                Map.of("teacherId", teacher, "dayOfWeek", 5)));
        configs.add(controlled("PE_LAST_PERIOD_AVOID", 44, Map.of()));
        configs.add(controlled("TEACHER_MAX_PER_DAY", 44, Map.of("maxPerDay", 4)));
        configs.add(controlled("CLASS_MAX_PER_DAY", 44,
                Map.of("classId", sourceClass, "maxPerDay", 8)));
        configs.add(controlled("COURSE_MAX_PER_DAY", 44,
                Map.of("courseId", course, "maxPerDay", 2)));
        configs.add(controlled("EVENING_MAX", 44,
                Map.of("classId", sourceClass, "maxPerDay", 2)));
        configs.add(controlled("TEACHER_MAX_CONSECUTIVE", 44,
                Map.of("teacherId", teacher, "maxConsecutive", 3)));
        // Six single-switch types: global controls only, no entries.
        for (String type : List.of("TEACHER_CONFLICT", "CLASS_CONFLICT", "ROOM_CONFLICT",
                "ROOM_CAPACITY_FIT", "CLASS_GAP_MINIMIZED", "HEAD_TEACHER_FIRST_PERIOD")) {
            configs.add(Map.of("type", type, "params", Map.of(), "weight", 100,
                    "hard", true, "enabled", true));
        }
        assertThat(configs).hasSize(25);
        save(token, source, configs).then().statusCode(200);

        long templateId = saveTemplate(token, source, "全目录-" + TestData.suffix())
                .then().statusCode(200)
                .body("configCount", equalTo(25))
                .body("entryCount", equalTo(19))
                .extract().jsonPath().getLong("id");

        as(token).queryParam("semesterId", target)
                .body(Map.of("classMapping", Map.of(String.valueOf(sourceClass), targetClass)))
                .when().post("/api/constraint-templates/" + templateId + "/apply")
                .then().statusCode(200);

        List<Map<String, Object>> restored = configs(token, target);
        assertThat(restored).hasSize(25);
        List<Map<String, Object>> controlledRows = restored.stream()
                .filter(row -> ((List<?>) row.get("entries")).size() == 1)
                .filter(row -> !"TEACHER_CONFLICT".equals(row.get("type")))
                .toList();
        assertThat(controlledRows).hasSize(19);
        controlledRows.forEach(row -> {
            Map<?, ?> entry = (Map<?, ?>) ((List<?>) row.get("entries")).get(0);
            assertThat(entry.get("groupKey")).as("group key of %s", row.get("type"))
                    .isEqualTo("g-" + row.get("type"));
            assertThat(entry.get("hard")).isEqualTo(false);
            assertThat(((Number) entry.get("weight")).intValue()).isEqualTo(44);
            assertThat(entry.get("enabled")).isEqualTo(true);
        });
        // The class reference was explicitly rebound; the source id never leaked.
        Map<String, Object> classMax = byType(restored, "CLASS_MAX_PER_DAY");
        Map<?, ?> classEntry = (Map<?, ?>) ((List<?>) classMax.get("entries")).get(0);
        assertThat(((Number) ((Map<?, ?>) classEntry.get("params")).get("classId")).longValue())
                .isEqualTo(targetClass);
        // Six switches keep being switches: no entries were invented for them.
        for (String type : List.of("TEACHER_CONFLICT", "CLASS_CONFLICT", "ROOM_CONFLICT",
                "ROOM_CAPACITY_FIT", "CLASS_GAP_MINIMIZED", "HEAD_TEACHER_FIRST_PERIOD")) {
            assertThat(byType(restored, type).get("entries")).isEqualTo(List.of());
        }
    }

    @Test
    void aTemplateWithoutControlFieldsInheritsTheTypeDefaults() {
        String token = adminToken();
        long semesterId = semesterWithPeriods(token);
        long teacher = TestData.teacher(token, "旧字段师" + TestData.suffix());
        long templateId = seedTemplate("旧字段-" + TestData.suffix(),
                "[{\"type\":\"TEACHER_UNAVAILABLE\",\"weight\":100,\"hard\":true,"
                        + "\"enabled\":true,\"entries\":[{\"params\":{\"teacherId\":" + teacher
                        + ",\"dayOfWeek\":1,\"periodNo\":1}}]}]");

        as(token).queryParam("semesterId", semesterId)
                .when().post("/api/constraint-templates/" + templateId + "/apply")
                .then().statusCode(200);

        Map<String, Object> restored = byType(configs(token, semesterId), "TEACHER_UNAVAILABLE");
        Map<?, ?> entry = (Map<?, ?>) ((List<?>) restored.get("entries")).get(0);
        // Null overrides are omitted from JSON: the entry inherits the type row.
        assertThat(entry.get("enabled")).isEqualTo(true);
        assertThat(entry.get("hard")).isNull();
        assertThat(entry.get("weight")).isNull();
        assertThat(entry.get("groupKey")).isNull();
        assertThat(restored.get("hard")).isEqualTo(true);
    }

    // ------------------------------------------------------------------ helpers

    private long semesterWithPeriods(String token) {
        long semesterId = TestData.semester(token);
        as(token).body(Map.of("morningCount", 4, "afternoonCount", 4, "eveningCount", 0))
                .when().post("/api/semesters/" + semesterId + "/periods/default")
                .then().statusCode(200);
        return semesterId;
    }

    private long adminClass(String token, long semesterId, String name) {
        long gradeId = as(token).body(Map.of("name", "年级" + TestData.suffix(), "sortOrder", 1))
                .queryParam("semesterId", semesterId)
                .when().post("/api/grades")
                .then().statusCode(200).extract().jsonPath().getLong("id");
        return TestData.adminClass(token, semesterId, gradeId, name);
    }

    private Response save(String token, long semesterId, List<Map<String, Object>> configs) {
        return as(token).queryParam("semesterId", semesterId)
                .body(Map.of("configs", configs))
                .when().put("/api/constraints");
    }

    private Response saveTemplate(String token, long semesterId, String name) {
        return as(token).queryParam("semesterId", semesterId)
                .body(Map.of("name", name))
                .when().post("/api/constraint-templates");
    }

    @SafeVarargs
    private Map<String, Object> unavailable(Map<String, Object>... entries) {
        return Map.of(
                "type", "TEACHER_UNAVAILABLE",
                "weight", 100,
                "hard", true,
                "enabled", true,
                "entries", List.of(entries));
    }

    private Map<String, Object> entry(long teacherId, int dayOfWeek, int periodNo) {
        return entry(Map.of("teacherId", teacherId, "dayOfWeek", dayOfWeek, "periodNo", periodNo));
    }

    private Map<String, Object> entry(Map<String, Object> params) {
        return Map.of("params", params);
    }

    /** One entry-backed type whose single requirement carries a group and controls. */
    private Map<String, Object> controlled(
            String type, int weight, Map<String, Object> params) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("params", params);
        entry.put("groupKey", "g-" + type);
        entry.put("hard", false);
        entry.put("weight", weight);
        entry.put("enabled", true);
        return Map.of(
                "type", type,
                "weight", weight,
                "hard", true,
                "enabled", true,
                "entries", List.of(entry));
    }

    private List<Map<String, Object>> configs(String token, long semesterId) {
        return as(token).queryParam("semesterId", semesterId)
                .when().get("/api/constraints")
                .then().statusCode(200)
                .extract().jsonPath().getList("$");
    }

    private String rawConstraints(String token, long semesterId) {
        return as(token).queryParam("semesterId", semesterId)
                .when().get("/api/constraints")
                .then().statusCode(200)
                .extract().asString();
    }

    private Map<String, Object> byType(List<Map<String, Object>> configs, String type) {
        return configs.stream()
                .filter(row -> type.equals(row.get("type")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing config " + type));
    }

    /** Compares the entries of one config by value, ignoring the JSON number width. */
    private void assertParams(Map<String, Object> config, List<Map<String, Object>> expected) {
        List<Map<String, Object>> actual = new ArrayList<>();
        for (Object row : (List<?>) config.get("entries")) {
            Map<?, ?> params = (Map<?, ?>) ((Map<?, ?>) row).get("params");
            Map<String, Object> normalized = new LinkedHashMap<>();
            params.forEach((key, value) -> normalized.put(
                    String.valueOf(key), value instanceof Number number
                            ? (Object) number.longValue() : value));
            actual.add(normalized);
        }
        List<Map<String, Object>> wanted = new ArrayList<>();
        for (Map<String, Object> params : expected) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            params.forEach((key, value) -> normalized.put(
                    key, value instanceof Number number ? (Object) number.longValue() : value));
            wanted.add(normalized);
        }
        assertThat(actual).containsExactlyElementsOf(wanted);
    }

    private long entryRows(long semesterId, String type) {
        return entryRepository.findBySemesterId(semesterId).stream()
                .filter(row -> type.equals(row.getType()))
                .count();
    }

    private long seedTemplate(String name, String configsJson) {
        ConstraintTemplate template = new ConstraintTemplate();
        template.setName(name);
        template.setConfigsJson(configsJson);
        template.setCreatedAt(Instant.now());
        return templateRepository.save(template).getId();
    }
}
