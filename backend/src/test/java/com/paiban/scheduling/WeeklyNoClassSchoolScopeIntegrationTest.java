package com.paiban.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;

import com.paiban.scheduling.domain.TimetableDraft;
import com.paiban.scheduling.domain.TimetableDraftRepository;
import com.paiban.support.ApiIntegrationTest;
import com.paiban.support.ScheduleFixture;
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
 * 票05：全校/多班范围。全部通过编辑器使用的 HTTP 边界观察，草稿详情是规则的可见结果。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class WeeklyNoClassSchoolScopeIntegrationTest extends ApiIntegrationTest {

    @Autowired private TimetableDraftRepository drafts;

    @Test
    void wholeSchoolBanCoversClassesCreatedLater() {
        String token = adminToken();
        long semester = semesterWithPeriods(token, 2, List.of(1, 2));
        long first = adminClass(token, semester, "全校甲");

        save(token, semester, List.of(banConfig(List.of(
                entry(null, 1, 1, "all-school"))))).then().statusCode(200);

        long second = adminClass(token, semester, "全校乙");
        List<Map<String, Object>> blocked = blockedSlots(token, draftFor(semester));

        assertThat(blocked).extracting(row -> ((Number) row.get("classId")).longValue())
                .containsExactlyInAnyOrder(first, second);
    }

    @Test
    void selectedClassesOnlyBanTheirOwnSlots() {
        String token = adminToken();
        long semester = semesterWithPeriods(token, 2, List.of(1, 2));
        long banned = adminClass(token, semester, "指定甲");
        long otherBanned = adminClass(token, semester, "指定乙");
        long untouched = adminClass(token, semester, "指定丙");

        save(token, semester, List.of(banConfig(List.of(
                entry(banned, 1, 1, "two-classes"),
                entry(otherBanned, 1, 1, "two-classes"))))).then().statusCode(200);

        List<Map<String, Object>> blocked = blockedSlots(token, draftFor(semester));
        assertThat(blocked).hasSize(2);
        assertThat(blocked).extracting(row -> ((Number) row.get("classId")).longValue())
                .containsExactlyInAnyOrder(banned, otherBanned)
                .doesNotContain(untouched);
    }

    @Test
    void invalidClassReferencesAreRejectedAndNeverBecomeWholeSchool() {
        String token = adminToken();
        long semester = semesterWithPeriods(token, 2, List.of(1, 2));
        long current = adminClass(token, semester, "非法甲");
        long otherSemester = semesterWithPeriods(token, 2, List.of(1, 2));
        long foreign = adminClass(token, otherSemester, "外学期");
        long draft = draftFor(semester);

        Response foreignRejected = save(token, semester, List.of(banConfig(List.of(
                entry(foreign, 1, 1, "foreign")))));
        foreignRejected.then().statusCode(400);
        assertThat(foreignRejected.asString()).contains("其他学期");

        save(token, semester, List.of(banConfig(List.of(
                entry(9_999_999L, 1, 1, "missing"))))).then().statusCode(400);

        assertThat(blockedSlots(token, draft)).isEmpty();
        assertThat(as(token).queryParam("semesterId", semester).get("/api/constraints")
                .then().statusCode(200).extract().jsonPath()
                .getList("findAll { it.type == 'CLASS_UNAVAILABLE' }.entries")).isEmpty();
        assertThat(current).isPositive();
    }

    @Test
    void overlappingGroupsUnionAndPausingOneKeepsTheOther() {
        String token = adminToken();
        long semester = semesterWithPeriods(token, 2, List.of(1, 2));
        long classId = adminClass(token, semester, "重叠甲");
        long draft = draftFor(semester);

        save(token, semester, List.of(banConfig(List.of(
                entry(classId, 1, 1, "group-a"),
                entry(classId, 1, 2, "group-a"),
                entry(classId, 1, 1, "group-b"))))).then().statusCode(200);
        assertThat(blockedSlots(token, draft)).hasSize(2);

        save(token, semester, List.of(banConfig(List.of(
                paused(classId, 1, 1, "group-a"),
                paused(classId, 1, 2, "group-a"),
                entry(classId, 1, 1, "group-b"))))).then().statusCode(200);
        assertThat(blockedSlots(token, draft)).hasSize(1);

        save(token, semester, List.of(banConfig(List.of(
                paused(classId, 1, 1, "group-a"),
                paused(classId, 1, 2, "group-a"),
                paused(classId, 1, 1, "group-b"))))).then().statusCode(200);
        assertThat(blockedSlots(token, draft)).isEmpty();
    }

    @Test
    void duplicateInsideOneGroupIsRejectedWhileSiblingGroupsMayOverlap() {
        String token = adminToken();
        long semester = semesterWithPeriods(token, 2, List.of(1, 2));
        long classId = adminClass(token, semester, "去重甲");

        Response duplicate = save(token, semester, List.of(banConfig(List.of(
                entry(classId, 1, 1, "same"), entry(classId, 1, 1, "same")))));
        duplicate.then().statusCode(400);
        assertThat(duplicate.asString()).contains("重复");

        save(token, semester, List.of(banConfig(List.of(
                entry(classId, 1, 1, "left"), entry(classId, 1, 1, "right")))))
                .then().statusCode(200);
    }

    @Test
    void oneGroupMayNotMixWholeSchoolWithSelectedClasses() {
        String token = adminToken();
        long semester = semesterWithPeriods(token, 2, List.of(1, 2));
        long classId = adminClass(token, semester, "混合甲");

        Response rejected = save(token, semester, List.of(banConfig(List.of(
                entry(null, 1, 1, "mixed"), entry(classId, 1, 2, "mixed")))));

        rejected.then().statusCode(400);
        assertThat(rejected.asString()).contains("全校").contains("指定班级");
    }

    @Test
    void explicitBlankClassIdIsRejectedWhileAnOmittedOneIsWholeSchool() {
        String token = adminToken();
        long semester = semesterWithPeriods(token, 2, List.of(1, 2));
        long classId = adminClass(token, semester, "空白班");

        Response blank = save(token, semester, List.of(banConfig(List.of(
                entry("  ", 1, 1, "blank")))));
        blank.then().statusCode(400);
        assertThat(blank.asString()).contains("空白字符串");

        save(token, semester, List.of(banConfig(List.of(entry(null, 1, 1, "school")))))
                .then().statusCode(200);
        assertThat(blockedSlots(token, draftFor(semester)))
                .extracting(row -> ((Number) row.get("classId")).longValue())
                .containsExactly(classId);
    }

    @Test
    void otherSlotGroupedTypesKeepTheirPreTicketDeduplication() {
        String token = adminToken();
        long semester = semesterWithPeriods(token, 2, List.of(1, 2));
        long teacher = TestData.teacher(token, "旧去重" + TestData.suffix());

        Response rejected = save(token, semester, List.of(
                teacherUnavailable(teacher, 1, 1, "left", "right")));

        rejected.then().statusCode(400);
        assertThat(rejected.asString()).contains("TEACHER_UNAVAILABLE").contains("重复");
    }

    @Test
    void oneGroupMustCarryTheCompleteClassTimesSlotRectangle() {
        String token = adminToken();
        long semester = semesterWithPeriods(token, 2, List.of(1, 2));
        long first = adminClass(token, semester, "矩形甲");
        long second = adminClass(token, semester, "矩形乙");

        Response rejected = save(token, semester, List.of(banConfig(List.of(
                entry(first, 1, 1, "rect"),
                entry(first, 1, 2, "rect"),
                entry(second, 1, 1, "rect")))));

        rejected.then().statusCode(400);
        assertThat(rejected.asString()).contains("完整");
    }

    @Test
    void templateKeepsWholeSchoolScopeAndMapsSelectedClasses() {
        String token = adminToken();
        long source = semesterWithPeriods(token, 2, List.of(1, 2));
        long sourceClass = adminClass(token, source, "模板源班");
        long target = semesterWithPeriods(token, 2, List.of(1, 2));
        long targetClass = adminClass(token, target, "模板目标班");

        save(token, source, List.of(banConfig(List.of(
                entry(null, 1, 1, "all-school"),
                entry(sourceClass, 1, 2, "selected"))))).then().statusCode(200);
        long template = as(token).queryParam("semesterId", source)
                .body(Map.of("name", "全校模板" + TestData.suffix()))
                .post("/api/constraint-templates").then().statusCode(200)
                .extract().jsonPath().getLong("id");

        as(token).queryParam("semesterId", target).queryParam("baseVersion", version(token, target))
                .body(Map.of()).post("/api/constraint-templates/" + template + "/apply")
                .then().statusCode(409);

        as(token).queryParam("semesterId", target).queryParam("baseVersion", version(token, target))
                .body(Map.of("classMapping", Map.of(sourceClass, targetClass)))
                .post("/api/constraint-templates/" + template + "/apply")
                .then().statusCode(200);

        List<Map<String, Object>> entries = as(token).queryParam("semesterId", target)
                .get("/api/constraints").then().statusCode(200).extract().jsonPath()
                .getList("find { it.type == 'CLASS_UNAVAILABLE' }.entries");
        assertThat(entries).hasSize(2);
        assertThat(entries.stream().anyMatch(row -> {
            Map<?, ?> params = (Map<?, ?>) row.get("params");
            return params.get("classId") == null
                    && ((Number) params.get("periodNo")).intValue() == 1;
        })).isTrue();
        assertThat(entries.stream().anyMatch(row -> {
            Map<?, ?> params = (Map<?, ?>) row.get("params");
            return params.get("classId") != null
                    && ((Number) params.get("classId")).longValue() == targetClass
                    && ((Number) params.get("periodNo")).intValue() == 2;
        })).isTrue();
    }

    /**
     * 未保存工作区保存的是编辑器真正会提交的展开结果：两个班 × 两个时段 = 四条带
     * groupKey 与控制的条目，而不是一个额外的范围字段。恢复后服务器必须原样认得这四条，
     * 并给出空的 errors/invalidReferences。
     */
    @Test
    void workspaceRoundTripKeepsEveryExpandedClassSlotEntry() {
        String token = adminToken();
        long semester = semesterWithPeriods(token, 2, List.of(1, 2));
        long first = adminClass(token, semester, "工作区甲");
        long second = adminClass(token, semester, "工作区乙");

        List<Map<String, Object>> entries = new ArrayList<>();
        for (long classId : List.of(first, second)) {
            entries.add(entry(classId, 1, 1, "weekly-scope"));
            entries.add(entry(classId, 1, 2, "weekly-scope"));
        }
        Map<String, Object> payload = Map.of("configs", List.of(banConfig(entries)));

        as(token).queryParam("semesterId", semester)
                .body(Map.of("baseRevision", 0, "baseVersion", version(token, semester),
                        "payload", payload))
                .put("/api/constraint-workspace").then().statusCode(200)
                .body("errors", hasSize(0))
                .body("invalidReferences", hasSize(0))
                .body("payload.configs[0].entries", hasSize(4));

        List<Map<String, Object>> restored = as(token).queryParam("semesterId", semester)
                .get("/api/constraint-workspace").then().statusCode(200)
                .body("errors", hasSize(0))
                .body("invalidReferences", hasSize(0))
                .extract().jsonPath().getList("payload.configs[0].entries");

        assertThat(restored).hasSize(4);
        assertThat(restored).allSatisfy(row -> {
            assertThat(row.get("groupKey")).isEqualTo("weekly-scope");
            assertThat(row.get("enabled")).isEqualTo(true);
            assertThat(row.get("hard")).isEqualTo(true);
        });
        for (long classId : List.of(first, second)) {
            for (int periodNo : List.of(1, 2)) {
                assertThat(restored.stream().anyMatch(row -> {
                    Map<?, ?> params = (Map<?, ?>) row.get("params");
                    return ((Number) params.get("classId")).longValue() == classId
                            && ((Number) params.get("dayOfWeek")).intValue() == 1
                            && ((Number) params.get("periodNo")).intValue() == periodNo;
                })).as("class %s slot %s", classId, periodNo).isTrue();
            }
        }
    }

    @Test
    void wholeSchoolSaveIsAuditedWithItsFinalScope() {
        String token = adminToken();
        long semester = semesterWithPeriods(token, 2, List.of(1, 2));
        save(token, semester, List.of(banConfig(List.of(
                entry(null, 1, 1, "audit-school"))))).then().statusCode(200);

        List<Map<String, Object>> audits = as(token).queryParam("semesterId", semester)
                .get("/api/constraint-audits").then().statusCode(200)
                .extract().jsonPath().getList("$");

        assertThat(audits).hasSize(1);
        Map<?, ?> finalScope = (Map<?, ?>) ((List<?>) audits.get(0).get("finalConfigs")).stream()
                .map(config -> (Map<?, ?>) config)
                .filter(config -> "CLASS_UNAVAILABLE".equals(config.get("type")))
                .findFirst().orElseThrow();
        List<?> entries = (List<?>) finalScope.get("entries");
        assertThat(entries).hasSize(1);
        assertThat(((Map<?, ?>) ((Map<?, ?>) entries.get(0)).get("params")).get("classId")).isNull();
    }

    // ------------------------------------------------------------------ helpers

    private long semesterWithPeriods(String token, int periodsPerDay, List<Integer> days) {
        long semester = TestData.semesterStarting(token, ScheduleFixture.nextMonday());
        as(token).body(Map.of("morningCount", periodsPerDay, "afternoonCount", 0,
                        "eveningCount", 0, "days", days))
                .post("/api/semesters/" + semester + "/periods/default").then().statusCode(200);
        return semester;
    }

    private long adminClass(String token, long semester, String name) {
        long grade = TestData.grade(token, semester, "年级-" + name + TestData.suffix());
        return TestData.adminClass(token, semester, grade, name + TestData.suffix());
    }

    private long draftFor(long semester) {
        TimetableDraft draft = new TimetableDraft();
        draft.setSemesterId(semester);
        draft.setName("范围草稿" + TestData.suffix());
        return drafts.save(draft).getId();
    }

    private List<Map<String, Object>> blockedSlots(String token, long draftId) {
        return as(token).get("/api/timetable-drafts/" + draftId)
                .then().statusCode(200).extract().jsonPath().getList("blockedSlots");
    }

    private long version(String token, long semester) {
        return as(token).queryParam("semesterId", semester).get("/api/constraints/state")
                .then().statusCode(200).extract().jsonPath().getLong("version");
    }

    private Response save(String token, long semester, List<Map<String, Object>> configs) {
        List<Map<String, Object>> all = new ArrayList<>();
        for (String type : List.of("TEACHER_CONFLICT", "CLASS_CONFLICT", "ROOM_CONFLICT")) {
            all.add(Map.of("type", type, "params", Map.of(), "enabled", true,
                    "hard", true, "weight", 100));
        }
        all.addAll(configs);
        return as(token).queryParam("semesterId", semester)
                .queryParam("baseVersion", version(token, semester))
                .body(Map.of("configs", all)).put("/api/constraints");
    }

    private Map<String, Object> banConfig(List<Map<String, Object>> entries) {
        return new LinkedHashMap<>(Map.of("type", "CLASS_UNAVAILABLE", "params", Map.of(),
                "enabled", true, "hard", true, "weight", 100, "entries", entries));
    }

    private Map<String, Object> entry(Object classId, int dayOfWeek, int periodNo, String groupKey) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (classId != null) {
            params.put("classId", classId);
        }
        params.put("dayOfWeek", dayOfWeek);
        params.put("periodNo", periodNo);
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("params", params);
        entry.put("enabled", true);
        entry.put("hard", true);
        entry.put("groupKey", groupKey);
        return entry;
    }

    private Map<String, Object> paused(Long classId, int dayOfWeek, int periodNo, String groupKey) {
        Map<String, Object> entry = entry(classId, dayOfWeek, periodNo, groupKey);
        entry.put("enabled", false);
        return entry;
    }

    private Map<String, Object> teacherUnavailable(
            long teacherId, int dayOfWeek, int periodNo, String... groupKeys) {
        Map<String, Object> config = new LinkedHashMap<>(Map.of(
                "type", "TEACHER_UNAVAILABLE", "params", Map.of(),
                "enabled", true, "hard", true, "weight", 100));
        List<Map<String, Object>> entries = new ArrayList<>();
        for (String groupKey : groupKeys) {
            entries.add(Map.of(
                    "params", Map.of("teacherId", teacherId, "dayOfWeek", dayOfWeek,
                            "periodNo", periodNo),
                    "enabled", true, "hard", true, "groupKey", groupKey));
        }
        config.put("entries", entries);
        return config;
    }
}
