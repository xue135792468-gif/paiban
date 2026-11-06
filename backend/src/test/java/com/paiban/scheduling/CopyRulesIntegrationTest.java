package com.paiban.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;

import com.paiban.support.ApiIntegrationTest;
import com.paiban.support.TestData;
import io.restassured.response.Response;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Ticket 08 backend contract: copy one semester's whole non-system rule set onto
 * another. Teachers and courses keep their global ids, time groups map by name,
 * slots map by (weekday, period number), and an unresolvable card is still copied
 * but paused with a readable reason.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class CopyRulesIntegrationTest extends ApiIntegrationTest {

    // ------------------------------------------------------------ happy path

    @Test
    void copiesTheWholeRuleSetAndReplacesTheTargetsOldRules() {
        String token = adminToken();
        long source = semesterWithPeriods(token);
        long target = semesterWithPeriods(token);
        long teacherA = TestData.teacher(token, "复制甲" + TestData.suffix());
        long teacherB = TestData.teacher(token, "复制乙" + TestData.suffix());

        save(token, source, null, List.of(
                config("TEACHER_UNAVAILABLE",
                        List.of(entry(Map.of(
                                "teacherId", teacherA, "dayOfWeek", 1, "periodNo", 1), "src-card"))),
                config("CLASS_MAX_PER_DAY",
                        List.of(entry(Map.of("maxPerDay", 8), "src-card-2")))))
                .then().statusCode(200);
        save(token, target, null, List.of(
                config("TEACHER_UNAVAILABLE",
                        List.of(entry(Map.of(
                                "teacherId", teacherB, "dayOfWeek", 1, "periodNo", 2),
                                "old-card"))),
                legacyConfig("TEACHER_CONFLICT", true, true),
                legacyConfig("CLASS_CONFLICT", true, true)))
                .then().statusCode(200);
        long base = stateVersion(token, target);

        copy(token, source, target, base).then().statusCode(200)
                .body("copiedCards", equalTo(2))
                .body("disabledCards", equalTo(0))
                .body("issues", hasSize(0));

        List<Map<String, Object>> copied = configs(token, target);
        assertThat(copied).extracting(row -> row.get("type"))
                .containsExactlyInAnyOrder(
                        "TEACHER_CONFLICT", "CLASS_CONFLICT",
                        "TEACHER_UNAVAILABLE", "CLASS_MAX_PER_DAY");
        Map<String, Object> teacherRule = configOf(copied, "TEACHER_UNAVAILABLE");
        Map<String, Object> params = paramsOfFirstEntry(teacherRule);
        assertThat(number(params.get("teacherId"))).isEqualTo(teacherA);
        assertThat(number(params.get("periodNo"))).isEqualTo(1);
        assertThat(paramsOfFirstEntry(configOf(copied, "CLASS_MAX_PER_DAY")).get("maxPerDay"))
                .isEqualTo(8);
        assertThat(copied.stream()
                .flatMap(row -> entriesOf(row).stream())
                .map(row -> (Map<?, ?>) row.get("params"))
                .map(row -> row.get("teacherId"))
                .filter(java.util.Objects::nonNull)
                .map(this::number))
                .doesNotContain(teacherB);
        assertThat(stateVersion(token, target)).isEqualTo(base + 1);
    }

    // ------------------------------------------------------- unusable cards

    @Test
    void pausesACardWhoseTeacherWasDeleted() {
        String token = adminToken();
        long source = semesterWithPeriods(token);
        long target = semesterWithPeriods(token);
        long teacher = TestData.teacher(token, "离职教师" + TestData.suffix());
        save(token, source, null, List.of(
                config("TEACHER_UNAVAILABLE",
                        List.of(entry(Map.of(
                                "teacherId", teacher, "dayOfWeek", 1, "periodNo", 1), "gone")))))
                .then().statusCode(200);
        as(token).queryParam("mode", "DELETE")
                .when().delete("/api/teachers/" + teacher)
                .then().statusCode(200);

        copy(token, source, target, stateVersion(token, target)).then().statusCode(200)
                .body("copiedCards", equalTo(1))
                .body("disabledCards", equalTo(1))
                .body("issues[0].reason", containsString("教师已删除"));

        as(token).queryParam("semesterId", target)
                .when().get("/api/constraints")
                .then().statusCode(200)
                .body("find { it.type == 'TEACHER_UNAVAILABLE' }.entries[0].enabled",
                        equalTo(false))
                .body("find { it.type == 'TEACHER_UNAVAILABLE' }.invalidReferences",
                        hasItem(containsString("教师已停用")));
    }

    @Test
    void pausesACardWhoseClassBelongsToTheSourceSemester() {
        String token = adminToken();
        long source = semesterWithPeriods(token);
        long target = semesterWithPeriods(token);
        long grade = TestData.grade(token, source, "复制年级" + TestData.suffix());
        long sourceClass = TestData.adminClass(
                token, source, grade, "复制班" + TestData.suffix());
        save(token, source, null, List.of(
                config("CLASS_MAX_PER_DAY",
                        List.of(entry(Map.of(
                                "classId", sourceClass, "maxPerDay", 6), "class-card")))))
                .then().statusCode(200);

        copy(token, source, target, stateVersion(token, target)).then().statusCode(200)
                .body("copiedCards", equalTo(1))
                .body("disabledCards", equalTo(1))
                .body("issues[0].reason", containsString("行政班不在本学期"));

        as(token).queryParam("semesterId", target)
                .when().get("/api/constraints")
                .then().statusCode(200)
                .body("find { it.type == 'CLASS_MAX_PER_DAY' }.entries[0].enabled",
                        equalTo(false))
                .body("find { it.type == 'CLASS_MAX_PER_DAY' }.invalidReferences",
                        hasItem(containsString("行政班")));
    }

    @Test
    void pausesACardWhosePeriodDoesNotExistInTheTargetTimetable() {
        String token = adminToken();
        long source = semesterWithPeriods(token);      // 9 periods on each weekday
        long target = TestData.semester(token);        // 8 periods on Monday only
        putMondayPeriods(token, target, List.of(1, 2, 3, 4, 5, 6, 7, 8));
        long teacher = TestData.teacher(token, "第九节教师" + TestData.suffix());
        save(token, source, null, List.of(
                config("TEACHER_UNAVAILABLE",
                        List.of(entry(Map.of(
                                "teacherId", teacher, "dayOfWeek", 1, "periodNo", 9),
                                "ninth")))))
                .then().statusCode(200);

        copy(token, source, target, stateVersion(token, target)).then().statusCode(200)
                .body("copiedCards", equalTo(1))
                .body("disabledCards", equalTo(1))
                .body("issues[0].reason", containsString("第9节在本学期作息中不存在"));

        as(token).queryParam("semesterId", target)
                .when().get("/api/constraints")
                .then().statusCode(200)
                .body("find { it.type == 'TEACHER_UNAVAILABLE' }.entries[0].enabled",
                        equalTo(false))
                .body("find { it.type == 'TEACHER_UNAVAILABLE' }.invalidReferences",
                        hasItem(containsString("第9节在本学期作息中不存在")));
    }

    @Test
    void pausesACardWhoseTimeGroupHasNoSameNameTarget() {
        String token = adminToken();
        long source = semesterWithPeriods(token);
        long target = semesterWithPeriods(token);
        String name = "早读" + TestData.suffix();
        long groupId = createTimeGroup(token, source, name, 1, 1);
        long teacher = TestData.teacher(token, "时间组教师" + TestData.suffix());
        save(token, source, null, List.of(
                config("TEACHER_UNAVAILABLE",
                        List.of(entry(Map.of(
                                "teacherId", teacher, "timeGroupId", groupId), "tg")))))
                .then().statusCode(200);

        copy(token, source, target, stateVersion(token, target)).then().statusCode(200)
                .body("copiedCards", equalTo(1))
                .body("disabledCards", equalTo(1))
                .body("issues[0].reason", containsString("本学期没有时间组：" + name));

        Map<String, Object> rule = configOf(configs(token, target), "TEACHER_UNAVAILABLE");
        Map<String, Object> params = paramsOfFirstEntry(rule);
        assertThat(params).doesNotContainKey("timeGroupId");
        assertThat(((Map<?, ?>) ((List<?>) rule.get("entries")).get(0)).get("enabled"))
                .isEqualTo(false);
    }

    @Test
    void mapsATimeGroupWithTheSameNameToTheTargetSemesterGroup() {
        String token = adminToken();
        long source = semesterWithPeriods(token);
        long target = semesterWithPeriods(token);
        long sourceMorning = timeGroupId(token, source, "上午");
        long targetMorning = timeGroupId(token, target, "上午");
        assertThat(targetMorning).isNotEqualTo(sourceMorning);
        long teacher = TestData.teacher(token, "同名组教师" + TestData.suffix());
        save(token, source, null, List.of(
                config("TEACHER_UNAVAILABLE",
                        List.of(entry(Map.of(
                                "teacherId", teacher, "timeGroupId", sourceMorning),
                                "morning")))))
                .then().statusCode(200);

        copy(token, source, target, stateVersion(token, target)).then().statusCode(200)
                .body("disabledCards", equalTo(0))
                .body("issues", hasSize(0));

        Map<String, Object> params =
                paramsOfFirstEntry(configOf(configs(token, target), "TEACHER_UNAVAILABLE"));
        assertThat(number(params.get("timeGroupId"))).isEqualTo(targetMorning);
    }

    @Test
    void oneUnusableRowPausesTheWholeCard() {
        String token = adminToken();
        long source = semesterWithPeriods(token);
        long target = semesterWithPeriods(token);
        long remains = TestData.teacher(token, "在岗教师" + TestData.suffix());
        long leaves = TestData.teacher(token, "离职教师" + TestData.suffix());
        save(token, source, null, List.of(
                config("TEACHER_UNAVAILABLE", List.of(
                        entry(Map.of("teacherId", remains, "dayOfWeek", 1, "periodNo", 1),
                                "one-card"),
                        entry(Map.of("teacherId", leaves, "dayOfWeek", 1, "periodNo", 2),
                                "one-card")))))
                .then().statusCode(200);
        as(token).queryParam("mode", "DELETE")
                .when().delete("/api/teachers/" + leaves)
                .then().statusCode(200);

        copy(token, source, target, stateVersion(token, target)).then().statusCode(200)
                .body("copiedCards", equalTo(1))
                .body("disabledCards", equalTo(1));

        List<Map<String, Object>> entries =
                entriesOf(configOf(configs(token, target), "TEACHER_UNAVAILABLE"));
        assertThat(entries).hasSize(2);
        assertThat(entries).allMatch(row -> Boolean.FALSE.equals(row.get("enabled")));
        Object firstKey = entries.get(0).get("groupKey");
        assertThat(firstKey).isNotNull();
        assertThat(String.valueOf(firstKey)).startsWith("copy-");
        assertThat(entries.get(1).get("groupKey")).isEqualTo(firstKey);
        // The usable row survives with its params; only the switch is turned off.
        assertThat(entries).extracting(row -> number(((Map<?, ?>) row.get("params")).get("teacherId")))
                .containsExactlyInAnyOrder(remains, leaves);
    }

    // ---------------------------------------------------------- guard rails

    @Test
    void refusesAStaleBaseVersionAndLeavesTheTargetUntouched() {
        String token = adminToken();
        long source = semesterWithPeriods(token);
        long target = semesterWithPeriods(token);
        long teacher = TestData.teacher(token, "版本教师" + TestData.suffix());
        save(token, source, null, List.of(
                config("TEACHER_UNAVAILABLE",
                        List.of(entry(Map.of(
                                "teacherId", teacher, "dayOfWeek", 1, "periodNo", 1), "v")))))
                .then().statusCode(200);
        save(token, target, null, List.of(
                config("CLASS_GAP_MINIMIZED", List.of()))).then().statusCode(200);
        long base = stateVersion(token, target);
        String before = stateBody(token, target);

        copy(token, source, target, base + 100).then().statusCode(409)
                .body("message", containsString("请刷新"));

        assertThat(stateBody(token, target)).isEqualTo(before);
        assertThat(stateVersion(token, target)).isEqualTo(base);
    }

    @Test
    void keepsTheTargetsSystemBaselinesUntouched() {
        String token = adminToken();
        long source = semesterWithPeriods(token);
        long target = semesterWithPeriods(token);
        long teacher = TestData.teacher(token, "底线教师" + TestData.suffix());
        save(token, source, null, List.of(
                config("TEACHER_UNAVAILABLE",
                        List.of(entry(Map.of(
                                "teacherId", teacher, "dayOfWeek", 1, "periodNo", 1), "base")))))
                .then().statusCode(200);
        // Pause the target's own teacher-conflict baseline: the copy must leave it as is.
        save(token, target, null, List.of(
                legacyConfig("TEACHER_CONFLICT", true, false),
                legacyConfig("CLASS_CONFLICT", true, true))).then().statusCode(200);

        copy(token, source, target, stateVersion(token, target)).then().statusCode(200)
                .body("copiedCards", equalTo(1));

        List<Map<String, Object>> copied = configs(token, target);
        assertThat(copied.stream().filter(row -> "TEACHER_CONFLICT".equals(row.get("type"))))
                .hasSize(1);
        assertThat(copied.stream().filter(row -> "CLASS_CONFLICT".equals(row.get("type"))))
                .hasSize(1);
        assertThat(configOf(copied, "TEACHER_CONFLICT").get("enabled")).isEqualTo(false);
        assertThat(configOf(copied, "CLASS_CONFLICT").get("enabled")).isEqualTo(true);
    }

    @Test
    void recordsAnAuditRowForTheCopy() {
        String token = adminToken();
        long source = semesterWithPeriods(token);
        long target = semesterWithPeriods(token);
        long teacher = TestData.teacher(token, "审计教师" + TestData.suffix());
        save(token, source, null, List.of(
                config("TEACHER_UNAVAILABLE",
                        List.of(entry(Map.of(
                                "teacherId", teacher, "dayOfWeek", 1, "periodNo", 1), "audit")))))
                .then().statusCode(200);

        copy(token, source, target, stateVersion(token, target)).then().statusCode(200);

        as(token).queryParam("semesterId", target)
                .when().get("/api/constraint-audits")
                .then().statusCode(200)
                .body("size()", org.hamcrest.Matchers.greaterThanOrEqualTo(1))
                .body("finalConfigs.type.flatten()", hasItem("TEACHER_UNAVAILABLE"));
    }

    // -------------------------------------------------------------- fixtures

    private long semesterWithPeriods(String token) {
        long semesterId = TestData.semester(token);
        as(token).body(Map.of("morningCount", 5, "afternoonCount", 4, "eveningCount", 0))
                .when().post("/api/semesters/" + semesterId + "/periods/default")
                .then().statusCode(200);
        return semesterId;
    }

    private void putMondayPeriods(String token, long semesterId, List<Integer> periodNos) {
        List<Map<String, Object>> slots = new ArrayList<>();
        for (int periodNo : periodNos) {
            LocalTime start = LocalTime.of(8, 0).plusMinutes(55L * (periodNo - 1));
            Map<String, Object> slot = new LinkedHashMap<>();
            slot.put("periodNo", periodNo);
            slot.put("label", "第" + periodNo + "节");
            slot.put("startTime", start.toString());
            slot.put("endTime", start.plusMinutes(45).toString());
            slot.put("days", List.of(1));
            slots.add(slot);
        }
        as(token).body(Map.of("slots", slots))
                .when().put("/api/semesters/" + semesterId + "/periods")
                .then().statusCode(200);
    }

    private Response save(
            String token, long semesterId, Long baseVersion,
            List<Map<String, Object>> configs) {
        var spec = as(token).queryParam("semesterId", semesterId);
        if (baseVersion != null) {
            spec = spec.queryParam("baseVersion", baseVersion);
        }
        return spec.body(Map.of("configs", configs)).when().put("/api/constraints");
    }

    private Response copy(String token, long source, long target, Long baseVersion) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sourceSemesterId", source);
        body.put("targetSemesterId", target);
        if (baseVersion != null) {
            body.put("baseVersion", baseVersion);
        }
        return as(token).body(body).when().post("/api/constraints/copy-rules");
    }

    private Map<String, Object> config(String type, List<Map<String, Object>> entries) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("type", type);
        config.put("weight", 100);
        config.put("hard", true);
        config.put("enabled", true);
        config.put("entries", entries);
        return config;
    }

    private Map<String, Object> legacyConfig(String type, boolean hard, boolean enabled) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("type", type);
        config.put("params", Map.of());
        config.put("weight", 100);
        config.put("hard", hard);
        config.put("enabled", enabled);
        return config;
    }

    private Map<String, Object> entry(Map<String, Object> params, String groupKey) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("params", params);
        entry.put("enabled", true);
        entry.put("hard", true);
        entry.put("weight", 100);
        entry.put("groupKey", groupKey);
        return entry;
    }

    private long createTimeGroup(
            String token, long semesterId, String name, int dayOfWeek, int periodNo) {
        Response created = as(token).body(Map.of(
                        "type", "TIME", "name", name, "semesterId", semesterId,
                        "members", List.of(Map.of("dayOfWeek", dayOfWeek, "periodNo", periodNo))))
                .when().post("/api/rule-groups");
        created.then().statusCode(200);
        return created.jsonPath().getLong("id");
    }

    private long timeGroupId(String token, long semesterId, String name) {
        List<Map<String, Object>> groups = as(token)
                .queryParam("type", "TIME")
                .queryParam("semesterId", semesterId)
                .when().get("/api/rule-groups")
                .then().statusCode(200).extract().jsonPath().getList("$");
        return groups.stream()
                .filter(group -> name.equals(group.get("name")))
                .map(group -> number(group.get("id")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少时间组：" + name));
    }

    private List<Map<String, Object>> configs(String token, long semesterId) {
        return as(token).queryParam("semesterId", semesterId)
                .when().get("/api/constraints")
                .then().statusCode(200).extract().jsonPath().getList("$");
    }

    private Map<String, Object> configOf(
            List<Map<String, Object>> configs, String type) {
        return configs.stream()
                .filter(row -> type.equals(row.get("type")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少规则类型：" + type));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> entriesOf(Map<String, Object> config) {
        return (List<Map<String, Object>>) config.get("entries");
    }

    private Map<String, Object> paramsOfFirstEntry(Map<String, Object> config) {
        Object params = entriesOf(config).get(0).get("params");
        @SuppressWarnings("unchecked")
        Map<String, Object> typed = (Map<String, Object>) params;
        return typed;
    }

    private long stateVersion(String token, long semesterId) {
        return as(token).queryParam("semesterId", semesterId)
                .when().get("/api/constraints/state")
                .then().statusCode(200).extract().jsonPath().getLong("version");
    }

    private String stateBody(String token, long semesterId) {
        return as(token).queryParam("semesterId", semesterId)
                .when().get("/api/constraints/state")
                .then().statusCode(200).extract().asString();
    }

    private long number(Object value) {
        return ((Number) value).longValue();
    }
}
