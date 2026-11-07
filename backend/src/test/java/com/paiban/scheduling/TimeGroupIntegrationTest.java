package com.paiban.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import com.paiban.scheduling.domain.ConstraintType;
import com.paiban.scheduling.service.ConstraintConfigService;
import com.paiban.scheduling.solver.ConstraintSettings;
import com.paiban.support.ApiIntegrationTest;
import com.paiban.support.TestData;
import io.restassured.response.Response;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Ticket 06 backend contract: every semester owns two editable time groups (「上午」/
 * 「下午」). A new semester seeds them empty through {@code SemesterCreatedEvent}; a
 * timetable save recomputes a group that is still auto-filled and only prunes removed
 * slots from a group the user edited. Rules reference the group id and the shared
 * snapshot expands it into concrete weekday+period rows.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class TimeGroupIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private ConstraintConfigService configService;

    // ------------------------------------------------------------- prebuilt groups

    @Test
    void aNewSemesterStartsWithEmptyAutoFilledMorningAndAfternoonGroups() {
        String token = adminToken();
        long semesterId = TestData.semester(token);

        List<Map<String, Object>> groups = timeGroups(token, semesterId);
        assertThat(groups).hasSize(2);

        Map<String, Object> morning = groupNamed(groups, "上午");
        Map<String, Object> afternoon = groupNamed(groups, "下午");
        assertThat(morning.get("autoFilled")).isEqualTo(true);
        assertThat(afternoon.get("autoFilled")).isEqualTo(true);
        assertThat(((Number) morning.get("semesterId")).longValue()).isEqualTo(semesterId);
        assertThat((List<?>) morning.get("members")).isEmpty();
        assertThat((List<?>) afternoon.get("members")).isEmpty();
    }

    @Test
    void savingPeriodsFillsThePrebuiltGroupsByStartTime() {
        String token = adminToken();
        long semesterId = newSemester(token);
        putMondayPeriods(token, semesterId, List.of(1, 2, 3, 4, 5, 6, 7, 8));

        List<Map<String, Object>> groups = timeGroups(token, semesterId);
        assertThat(slotsOf(groupNamed(groups, "上午")))
                .containsExactlyInAnyOrder("1-1", "1-2", "1-3", "1-4");
        assertThat(slotsOf(groupNamed(groups, "下午")))
                .containsExactlyInAnyOrder("1-5", "1-6", "1-7", "1-8");
    }

    // ------------------------------------------------------------ snapshot rules

    @Test
    void aTimeGroupRuleExpandsIntoConcreteSlots() {
        String token = adminToken();
        long semesterId = newSemester(token);
        putMondayPeriods(token, semesterId, List.of(1, 2, 3, 4, 5, 6, 7, 8));
        long morningId = groupId(token, semesterId, "上午");
        long teacher = TestData.teacher(token, "时段教师" + TestData.suffix());

        saveRule(token, semesterId, "TEACHER_UNAVAILABLE",
                Map.of("teacherId", teacher, "timeGroupId", morningId))
                .then().statusCode(200);

        ConstraintSettings settings = configService.settings(semesterId);
        assertThat(settings.requirements(ConstraintType.TEACHER_UNAVAILABLE)).hasSize(1);
        List<Map<String, Object>> rows = settings.entries(ConstraintType.TEACHER_UNAVAILABLE);
        assertThat(rows).hasSize(4);
        assertThat(rows).allSatisfy(row -> {
            assertThat(ConstraintSettings.longValue(row.get("teacherId"))).isEqualTo(teacher);
            assertThat(ConstraintSettings.longValue(row.get("dayOfWeek"))).isEqualTo(1L);
            assertThat(row).doesNotContainKey("timeGroupId");
        });
        assertThat(rows).extracting(row -> ConstraintSettings.longValue(row.get("periodNo")))
                .containsExactlyInAnyOrder(1L, 2L, 3L, 4L);
    }

    /** 「技能课 不排在 上午」: a course group times a time group is a cartesian product. */
    @Test
    void aCourseGroupAndATimeGroupExpandAsACartesianProduct() {
        String token = adminToken();
        long semesterId = newSemester(token);
        putMondayPeriods(token, semesterId, List.of(1, 2, 3, 4, 5, 6, 7, 8));
        long morningId = groupId(token, semesterId, "上午");
        long first = TestData.course(token, "体育" + TestData.suffix());
        long second = TestData.course(token, "美术" + TestData.suffix());
        long courseGroup = createCourseGroup(token, "技能课" + TestData.suffix(), first, second);

        saveRule(token, semesterId, "COURSE_UNAVAILABLE",
                Map.of("courseGroupId", courseGroup, "timeGroupId", morningId))
                .then().statusCode(200);

        ConstraintSettings settings = configService.settings(semesterId);
        assertThat(settings.requirements(ConstraintType.COURSE_UNAVAILABLE)).hasSize(1);
        List<Map<String, Object>> rows = settings.entries(ConstraintType.COURSE_UNAVAILABLE);
        assertThat(rows).hasSize(8);
        assertThat(rows).extracting(row -> ConstraintSettings.longValue(row.get("courseId")))
                .containsExactlyInAnyOrder(
                        first, first, first, first, second, second, second, second);
        assertThat(rows).extracting(row -> ConstraintSettings.longValue(row.get("periodNo")))
                .containsOnly(1L, 2L, 3L, 4L);
        assertThat(rows).allSatisfy(row -> {
            assertThat(ConstraintSettings.longValue(row.get("dayOfWeek"))).isEqualTo(1L);
            assertThat(row).doesNotContainKeys("courseGroupId", "timeGroupId");
        });
    }

    @Test
    void editingATimeGroupTakesEffectImmediatelyAndStopsAutoFilling() {
        String token = adminToken();
        long semesterId = newSemester(token);
        putMondayPeriods(token, semesterId, List.of(1, 2, 3, 4, 5, 6, 7, 8));
        long morningId = groupId(token, semesterId, "上午");
        long teacher = TestData.teacher(token, "改组成员教师" + TestData.suffix());
        saveRule(token, semesterId, "TEACHER_UNAVAILABLE",
                Map.of("teacherId", teacher, "timeGroupId", morningId))
                .then().statusCode(200);
        assertThat(configService.settings(semesterId)
                .entries(ConstraintType.TEACHER_UNAVAILABLE)).hasSize(4);

        Response updated = as(token).body(Map.of(
                        "type", "TIME",
                        "name", "上午",
                        "semesterId", semesterId,
                        "members", List.of(
                                Map.of("dayOfWeek", 1, "periodNo", 1),
                                Map.of("dayOfWeek", 1, "periodNo", 2))))
                .when().put("/api/rule-groups/" + morningId);
        updated.then().statusCode(200)
                .body("autoFilled", org.hamcrest.Matchers.equalTo(false));

        List<Map<String, Object>> rows = configService.settings(semesterId)
                .entries(ConstraintType.TEACHER_UNAVAILABLE);
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(row -> ConstraintSettings.longValue(row.get("periodNo")))
                .containsExactlyInAnyOrder(1L, 2L);
    }

    // --------------------------------------------------------- period lifecycle

    @Test
    void removingAPeriodPrunesAutoAndUserGroups() {
        String token = adminToken();
        long semesterId = newSemester(token);
        putMondayPeriods(token, semesterId, List.of(1, 2, 3, 4, 5, 6, 7, 8));
        String userName = "自习" + TestData.suffix();
        long userGroup = createTimeGroup(token, semesterId, userName,
                List.of(Map.of("dayOfWeek", 1, "periodNo", 1),
                        Map.of("dayOfWeek", 1, "periodNo", 7)));
        assertThat(slotsOf(groupNamed(timeGroups(token, semesterId), userName)))
                .containsExactlyInAnyOrder("1-1", "1-7");

        // Drop a period that only the afternoon auto group and the user group used.
        putMondayPeriods(token, semesterId, List.of(1, 2, 3, 4, 5, 6, 8));

        List<Map<String, Object>> groups = timeGroups(token, semesterId);
        assertThat(slotsOf(groupNamed(groups, "上午")))
                .containsExactlyInAnyOrder("1-1", "1-2", "1-3", "1-4");
        // The auto group was recomputed even though no slot looks "stale" by name.
        assertThat(slotsOf(groupNamed(groups, "下午")))
                .containsExactlyInAnyOrder("1-5", "1-6", "1-8");
        // A user group only loses the vanished coordinate and keeps the rest.
        Map<String, Object> user = groups.stream()
                .filter(group -> ((Number) group.get("id")).longValue() == userGroup)
                .findFirst().orElseThrow();
        assertThat(user.get("autoFilled")).isEqualTo(false);
        assertThat(slotsOf(user)).containsExactly("1-1");
    }

    @Test
    void aTimeGroupRuleOfAnotherSemesterIsRefused() {
        String token = adminToken();
        long semesterA = newSemester(token);
        putMondayPeriods(token, semesterA, List.of(1, 2, 3, 4));
        long semesterB = newSemester(token);
        putMondayPeriods(token, semesterB, List.of(1, 2, 3, 4));
        long morningOfA = groupId(token, semesterA, "上午");
        long teacher = TestData.teacher(token, "跨学期教师" + TestData.suffix());

        Response refused = saveRule(token, semesterB, "TEACHER_UNAVAILABLE",
                Map.of("teacherId", teacher, "timeGroupId", morningOfA));
        refused.then().statusCode(400);
        assertThat(refused.asString()).contains("不属于当前学期");
        assertThat(configService.settings(semesterB)
                .requirements(ConstraintType.TEACHER_UNAVAILABLE)).isEmpty();
    }

    @Test
    void aSecondMorningGroupInTheSameSemesterIsRefused() {
        String token = adminToken();
        long semesterId = TestData.semester(token);

        as(token).body(Map.of(
                        "type", "TIME",
                        "name", "上午",
                        "semesterId", semesterId,
                        "members", List.of()))
                .when().post("/api/rule-groups")
                .then().statusCode(409);

        // A differently named user group is accepted and is not auto-filled.
        as(token).body(Map.of(
                        "type", "TIME",
                        "name", "早晨" + TestData.suffix(),
                        "semesterId", semesterId,
                        "members", List.of()))
                .when().post("/api/rule-groups")
                .then().statusCode(200)
                .body("autoFilled", org.hamcrest.Matchers.equalTo(false));
    }

    // ------------------------------------------------------------------ helpers

    private long newSemester(String token) {
        return TestData.semester(token);
    }

    /**
     * Eight periods on Monday only, so a "morning" expansion is exactly the four slots
     * the ticket's sentences talk about. Periods 1-4 start before noon, 5-8 after.
     */
    private void putMondayPeriods(String token, long semesterId, List<Integer> periodNos) {
        List<Map<String, Object>> slots = new ArrayList<>();
        for (int periodNo : periodNos) {
            LocalTime start = periodNo <= 4
                    ? LocalTime.of(8, 0).plusMinutes(55L * (periodNo - 1))
                    : LocalTime.of(14, 0).plusMinutes(55L * (periodNo - 5));
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

    private List<Map<String, Object>> timeGroups(String token, long semesterId) {
        return as(token)
                .queryParam("type", "TIME")
                .queryParam("semesterId", semesterId)
                .when().get("/api/rule-groups")
                .then().statusCode(200).extract().jsonPath().getList("$");
    }

    private Map<String, Object> groupNamed(List<Map<String, Object>> groups, String name) {
        return groups.stream()
                .filter(group -> name.equals(group.get("name")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少时间组：" + name));
    }

    private long groupId(String token, long semesterId, String name) {
        return ((Number) groupNamed(timeGroups(token, semesterId), name).get("id")).longValue();
    }

    private Set<String> slotsOf(Map<String, Object> group) {
        Set<String> slots = new LinkedHashSet<>();
        for (Map<String, Object> member : membersOf(group)) {
            slots.add(((Number) member.get("dayOfWeek")).intValue() + "-"
                    + ((Number) member.get("periodNo")).intValue());
        }
        return slots;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> membersOf(Map<String, Object> group) {
        return (List<Map<String, Object>>) group.get("members");
    }

    private long createTimeGroup(String token, long semesterId, String name,
                                 List<Map<String, Object>> members) {
        Response response = as(token).body(Map.of(
                        "type", "TIME", "name", name, "semesterId", semesterId,
                        "members", members))
                .when().post("/api/rule-groups");
        response.then().statusCode(200);
        return response.jsonPath().getLong("id");
    }

    private long createCourseGroup(String token, String name, long... courseIds) {
        List<Map<String, Object>> members = new ArrayList<>();
        for (long id : courseIds) {
            members.add(Map.of("courseId", id));
        }
        Response response = as(token).body(Map.of(
                        "type", "COURSE", "name", name, "members", members))
                .when().post("/api/rule-groups");
        response.then().statusCode(200);
        return response.jsonPath().getLong("id");
    }

    private Response saveRule(String token, long semesterId, String type,
                              Map<String, Object> params) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("params", params);
        entry.put("hard", true);
        entry.put("groupKey", "time-group-" + semesterId + "-" + type);
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("type", type);
        config.put("weight", 100);
        config.put("hard", true);
        config.put("enabled", true);
        config.put("entries", List.of(entry));
        return as(token).queryParam("semesterId", semesterId)
                .body(Map.of("configs", List.of(config)))
                .when().put("/api/constraints");
    }
}
