package com.paiban.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import com.paiban.scheduling.domain.ConstraintConfig;
import com.paiban.scheduling.domain.ConstraintConfigRepository;
import com.paiban.scheduling.domain.ConstraintEntry;
import com.paiban.scheduling.domain.ConstraintEntryRepository;
import com.paiban.scheduling.domain.ConstraintType;
import com.paiban.scheduling.service.ConstraintService;
import com.paiban.scheduling.solver.ConstraintSettings;
import com.paiban.support.ApiIntegrationTest;
import com.paiban.support.TestData;
import io.restassured.response.Response;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 票 09/15：目录收敛，以及数量型/形态型/预设规则写入后与求解器、
 * 草稿校验、调代共用同一份 {@link ConstraintSettings} 快照。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RuleTypesIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private ConstraintService constraintService;

    @Autowired
    private ConstraintConfigRepository configRepository;

    @Autowired
    private ConstraintEntryRepository entryRepository;

    @Test
    void theCatalogueReturnsTheEighteenCurrentTypes() {
        String token = adminToken();

        List<Map<String, Object>> types = as(token)
                .when().get("/api/constraint-types")
                .then().statusCode(200)
                .extract().jsonPath().getList("$");

        List<String> codes = types.stream().map(row -> String.valueOf(row.get("code"))).toList();
        assertThat(codes).hasSize(18);
        assertThat(codes).doesNotContain(
                "ROOM_CONFLICT", "ROOM_CAPACITY_FIT", "COURSE_REQUIRED_ROOM_TYPE",
                "COURSE_NOT_IN_EVENING", "EVENING_MAX", "TEACHER_MEETING_BLOCKED",
                "HEAD_TEACHER_FIRST_PERIOD", "SPORT_NOT_FIRST_AFTERNOON");
        assertThat(codes).contains(
                "TEACHER_CONFLICT", "CLASS_CONFLICT", "CLASS_UNAVAILABLE",
                "TEACHER_UNAVAILABLE", "COURSE_UNAVAILABLE", "COURSE_MAX_PER_DAY",
                "TEACHER_MAX_PER_DAY", "CLASS_MAX_PER_DAY", "SAME_COURSE_ONCE_PER_DAY",
                "CONSECUTIVE_LESSONS", "COURSE_SPREAD", "CLASS_GAP_MINIMIZED",
                "TEACHER_MAX_CONSECUTIVE",
                "PE_LAST_PERIOD_AVOID", "MAIN_SUBJECT_MORNING",
                "TEACHER_NO_FIRST_PERIOD", "TEACHER_DAY_OFF", "TEACHER_PREFERRED_SLOT");
    }

    @Test
    void unknownConfigRowsStayReadableButNeverEnterTheSharedSnapshot() {
        String token = adminToken();
        long semesterId = semesterWithPeriods(token);
        saveRetiredRows(semesterId);

        // Reading the state must not throw: the old rows still deserialize.
        as(token).queryParam("semesterId", semesterId)
                .when().get("/api/constraints/state")
                .then().statusCode(200);

        ConstraintSettings settings = constraintService.settings(semesterId);
        // A code the catalogue no longer knows cannot change the fingerprint used by
        // the preview guard.
        ConstraintSettings clean = constraintService.settings(TestData.semester(token));
        assertThat(settings.fingerprint()).isEqualTo(clean.fingerprint());
    }

    @Test
    void quantityShapeAndPresetRulesAllLandInTheSameSnapshot() {
        String token = adminToken();
        long semesterId = semesterWithPeriods(token);
        String suffix = TestData.suffix();
        long gradeId = TestData.grade(token, semesterId, "高一" + suffix);
        long classId = TestData.adminClass(token, semesterId, gradeId, "高一1班" + suffix);
        long teacherId = TestData.teacher(token, "规则教师" + suffix);
        long courseId = TestData.course(token, "规则课程" + suffix);

        List<Map<String, Object>> configs = new ArrayList<>();
        configs.add(entryConfig("COURSE_MAX_PER_DAY", 50,
                Map.of("courseId", courseId, "maxPerDay", 1)));
        configs.add(entryConfig("TEACHER_MAX_PER_DAY", 60,
                Map.of("teacherId", teacherId, "maxPerDay", 4)));
        configs.add(entryConfig("CLASS_MAX_PER_DAY", 50,
                Map.of("classId", classId, "maxPerDay", 8)));
        configs.add(entryConfig("SAME_COURSE_ONCE_PER_DAY", 100, Map.of("courseId", courseId)));
        configs.add(entryConfig("CONSECUTIVE_LESSONS", 60,
                Map.of("courseId", courseId, "minConsecutive", 2)));
        configs.add(entryConfig("COURSE_SPREAD", 50, Map.of("courseId", courseId)));
        configs.add(Map.of("type", "CLASS_GAP_MINIMIZED", "params", Map.of(),
                "weight", 30, "hard", true, "enabled", true));
        configs.add(entryConfig("TEACHER_MAX_CONSECUTIVE", 45,
                Map.of("teacherId", teacherId, "maxConsecutive", 3)));
        configs.add(entryConfig("PE_LAST_PERIOD_AVOID", 35, Map.of("courseId", courseId)));
        configs.add(entryConfig("MAIN_SUBJECT_MORNING", 55,
                Map.of("courseId", courseId, "beforePeriodNo", 4)));
        configs.add(entryConfig("TEACHER_NO_FIRST_PERIOD", 35, Map.of("teacherId", teacherId)));
        configs.add(entryConfig("TEACHER_DAY_OFF", 30,
                Map.of("teacherId", teacherId, "dayOfWeek", 1)));
        configs.add(entryConfig("TEACHER_PREFERRED_SLOT", 40,
                Map.of("teacherId", teacherId, "dayOfWeek", 1, "periodNo", 1)));

        as(token).queryParam("semesterId", semesterId)
                .body(Map.of("configs", configs))
                .when().put("/api/constraints")
                .then().statusCode(200);

        ConstraintSettings settings = constraintService.settings(semesterId);
        for (String code : List.of(
                "COURSE_MAX_PER_DAY", "TEACHER_MAX_PER_DAY", "CLASS_MAX_PER_DAY",
                "SAME_COURSE_ONCE_PER_DAY", "CONSECUTIVE_LESSONS", "COURSE_SPREAD",
                "CLASS_GAP_MINIMIZED", "TEACHER_MAX_CONSECUTIVE",
                "PE_LAST_PERIOD_AVOID",
                "MAIN_SUBJECT_MORNING", "TEACHER_NO_FIRST_PERIOD",
                "TEACHER_DAY_OFF", "TEACHER_PREFERRED_SLOT")) {
            ConstraintType type = ConstraintType.valueOf(code);
            assertThat(settings.enabled(type)).as(code).isTrue();
            if (type.isEntryBacked()) {
                assertThat(settings.entries(type)).as(code).isNotEmpty();
            }
        }
    }

    private Map<String, Object> entryConfig(
            String type, int weight, Map<String, Object> params) {
        return Map.of(
                "type", type,
                "weight", weight,
                "hard", true,
                "enabled", true,
                "entries", List.of(Map.of("params", params, "enabled", true, "hard", true)));
    }

    private void saveRetiredRows(long semesterId) {
        // Old rows with a code the product removed: they must stay readable and inert.
        ConstraintConfig config = new ConstraintConfig();
        config.setSemesterId(semesterId);
        config.setType("ROOM_CONFLICT");
        config.setParamsJson("{}");
        config.setWeight(100);
        config.setHard(true);
        config.setEnabled(true);
        configRepository.save(config);

        ConstraintEntry entry = new ConstraintEntry();
        entry.setSemesterId(semesterId);
        entry.setType("ROOM_CONFLICT");
        entry.setParamsJson("{}");
        entry.setEnabled(true);
        entry.setHard(true);
        entry.setGroupKey("retired-ROOM_CONFLICT");
        entryRepository.save(entry);
    }

    private long semesterWithPeriods(String token) {
        long semesterId = TestData.semester(token);
        as(token).body(Map.of("morningCount", 4, "afternoonCount", 4, "eveningCount", 0))
                .when().post("/api/semesters/" + semesterId + "/periods/default")
                .then().statusCode(200);
        return semesterId;
    }
}
