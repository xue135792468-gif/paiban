package com.paiban.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

import com.paiban.scheduling.domain.ConstraintType;
import com.paiban.scheduling.service.ConstraintService;
import com.paiban.scheduling.solver.ConstraintSettings;
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
 * Ticket 14/05: the global default and each object exception carry their own pause /
 * must-prefer / weight. An active exception replaces both the number and the strength;
 * pausing it falls back to the global, pausing the global leaves the exception alone.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ConstraintThresholdControlsIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private ConstraintService constraintService;

    @Test
    void globalAndExceptionKeepTheirOwnControlsAcrossReload() {
        String token = adminToken();
        long semesterId = semesterWithPeriods(token);
        long teacher = TestData.teacher(token, "上限例外" + TestData.suffix());

        save(token, semesterId, List.of(teacherMax(
                true, scope(null, 4, true, null),
                scope(teacher, 6, false, 30)))).then().statusCode(200)
                .body("find { it.type == 'TEACHER_MAX_PER_DAY' }.entries", hasSize(2));

        List<Map<String, Object>> entries = reload(token, semesterId);
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).get("hard")).isEqualTo(true);
        assertThat(entries.get(1).get("hard")).isEqualTo(false);
        assertThat(((Number) entries.get(1).get("weight")).intValue()).isEqualTo(30);
        assertThat(entries).allMatch(row -> row.get("groupKey") == null);
    }

    @Test
    void theActiveExceptionReplacesTheGlobalNumberAndStrength() {
        String token = adminToken();
        long semesterId = semesterWithPeriods(token);
        long teacher = TestData.teacher(token, "上限例外" + TestData.suffix());
        save(token, semesterId, List.of(teacherMax(
                true, scope(null, 4, true, null),
                scope(teacher, 6, false, 30)))).then().statusCode(200);

        ConstraintSettings settings = constraintService.settings(semesterId);
        assertThat(settings.limitOrNull(ConstraintType.TEACHER_MAX_PER_DAY, teacher))
                .isEqualTo(6);
        assertThat(settings.thresholdControl(ConstraintType.TEACHER_MAX_PER_DAY, teacher))
                .extracting(ConstraintSettings.Control::hard)
                .isEqualTo(false);
        assertThat(settings.thresholdControl(ConstraintType.TEACHER_MAX_PER_DAY, teacher))
                .extracting(ConstraintSettings.Control::weight)
                .isEqualTo(30);
        // An object without an exception keeps the hard global.
        long other = TestData.teacher(token, "上限普通" + TestData.suffix());
        assertThat(settings.limitOrNull(ConstraintType.TEACHER_MAX_PER_DAY, other))
                .isEqualTo(4);
        assertThat(settings.thresholdControl(ConstraintType.TEACHER_MAX_PER_DAY, other))
                .extracting(ConstraintSettings.Control::hard)
                .isEqualTo(true);
    }

    @Test
    void pausingTheExceptionFallsBackToTheGlobalAndPausingTheGlobalKeepsTheException() {
        String token = adminToken();
        long semesterId = semesterWithPeriods(token);
        long teacher = TestData.teacher(token, "上限例外" + TestData.suffix());

        save(token, semesterId, List.of(teacherMax(
                true, scope(null, 4, true, null),
                scope(teacher, 6, false, 30, false)))).then().statusCode(200);
        ConstraintSettings settings = constraintService.settings(semesterId);
        assertThat(settings.limitOrNull(ConstraintType.TEACHER_MAX_PER_DAY, teacher)).isEqualTo(4);
        assertThat(settings.thresholdControl(ConstraintType.TEACHER_MAX_PER_DAY, teacher))
                .extracting(ConstraintSettings.Control::hard)
                .isEqualTo(true);

        save(token, semesterId, List.of(teacherMax(
                true, scope(null, 4, true, null, false),
                scope(teacher, 6, false, 30)))).then().statusCode(200);
        settings = constraintService.settings(semesterId);
        assertThat(settings.limitOrNull(ConstraintType.TEACHER_MAX_PER_DAY, teacher)).isEqualTo(6);
        assertThat(settings.thresholdControl(ConstraintType.TEACHER_MAX_PER_DAY, teacher))
                .extracting(ConstraintSettings.Control::hard)
                .isEqualTo(false);
    }

    @Test
    void templateRoundTripKeepsBothScopesAndTheirControls() {
        String token = adminToken();
        long source = semesterWithPeriods(token);
        long teacher = TestData.teacher(token, "上限模板" + TestData.suffix());
        save(token, source, List.of(teacherMax(
                true, scope(null, 4, true, null),
                scope(teacher, 6, false, 30)))).then().statusCode(200);

        long templateId = as(token).queryParam("semesterId", source)
                .body(Map.of("name", "阈值控制-" + TestData.suffix()))
                .when().post("/api/constraint-templates")
                .then().statusCode(200)
                .body("entryCount", equalTo(2))
                .extract().jsonPath().getLong("id");

        long target = semesterWithPeriods(token);
        as(token).queryParam("semesterId", target)
                .when().post("/api/constraint-templates/" + templateId + "/apply")
                .then().statusCode(200)
                .body("find { it.type == 'TEACHER_MAX_PER_DAY' }.entries", hasSize(2));

        List<Map<String, Object>> restored = reload(token, target);
        assertThat(restored).hasSize(2);
        Map<?, ?> globalParams = (Map<?, ?>) restored.get(0).get("params");
        assertThat(((Number) globalParams.get("maxPerDay")).intValue()).isEqualTo(4);
        assertThat(restored.get(0).get("hard")).isEqualTo(true);
        assertThat(restored.get(1).get("hard")).isEqualTo(false);
        assertThat(((Number) restored.get(1).get("weight")).intValue()).isEqualTo(30);
        assertThat(restored.get(1).get("id")).isNotNull();
    }

    // ------------------------------------------------------------------ helpers

    private long semesterWithPeriods(String token) {
        long semesterId = TestData.semester(token);
        as(token).body(Map.of("morningCount", 4, "afternoonCount", 4, "eveningCount", 0))
                .when().post("/api/semesters/" + semesterId + "/periods/default")
                .then().statusCode(200);
        return semesterId;
    }

    private Response save(String token, long semesterId, List<Map<String, Object>> configs) {
        long version = as(token).queryParam("semesterId", semesterId)
                .when().get("/api/constraints/state")
                .then().statusCode(200).extract().jsonPath().getLong("version");
        return as(token).queryParam("semesterId", semesterId)
                .queryParam("baseVersion", version)
                .body(Map.of("configs", configs))
                .when().put("/api/constraints");
    }

    private Map<String, Object> teacherMax(boolean enabled, Map<String, Object>... entries) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("type", "TEACHER_MAX_PER_DAY");
        config.put("weight", 60);
        config.put("hard", true);
        config.put("enabled", enabled);
        config.put("entries", List.of(entries));
        return config;
    }

    private Map<String, Object> scope(Long teacherId, int maxPerDay, boolean hard, Integer weight) {
        return scope(teacherId, maxPerDay, hard, weight, true);
    }

    private Map<String, Object> scope(
            Long teacherId, int maxPerDay, boolean hard, Integer weight, boolean enabled) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (teacherId != null) {
            params.put("teacherId", teacherId);
        }
        params.put("maxPerDay", maxPerDay);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("params", params);
        row.put("hard", hard);
        row.put("enabled", enabled);
        if (weight != null) {
            row.put("weight", weight);
        }
        return row;
    }

    private List<Map<String, Object>> reload(String token, long semesterId) {
        return as(token).queryParam("semesterId", semesterId)
                .when().get("/api/constraints")
                .then().statusCode(200).extract().jsonPath()
                .getList("find { it.type == 'TEACHER_MAX_PER_DAY' }.entries");
    }
}
