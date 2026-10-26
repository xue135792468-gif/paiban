package com.paiban.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

import com.paiban.scheduling.domain.ConstraintConfig;
import com.paiban.scheduling.domain.ConstraintConfigRepository;
import com.paiban.scheduling.domain.ConstraintEntryRepository;
import com.paiban.scheduling.domain.ConstraintType;
import com.paiban.scheduling.service.ConstraintService;
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
 * Ticket 10/03 through the public configuration API: the twelve remaining target
 * types accept several entries that do not overwrite each other, object/scalar
 * conflicts and illegal targets are rejected before anything is written, and the
 * pre-existing single-`params` requests keep working (the front-end of ticket 02
 * still sends them until the next ticket).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ConstraintTargetEntriesIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private ConstraintEntryRepository entryRepository;

    @Autowired
    private ConstraintConfigRepository configRepository;

    @Autowired
    private ConstraintService constraintService;

    @Test
    void everyRemainingTargetTypeStoresTwoEntriesWithoutOverwriting() {
        String token = adminToken();
        long semesterId = semesterWithPeriods(token);
        World world = world(token);

        Response saved = save(token, semesterId, twoEntriesForEachType(world));

        saved.then().statusCode(200);
        List<Map<String, Object>> configs = saved.jsonPath().getList("$");
        assertThat(configs).hasSize(12);
        for (Map<String, Object> config : configs) {
            assertThat(config.get("type")).as("every target type answered").isNotNull();
            assertThat((List<?>) config.get("entries"))
                    .as("two entries for " + config.get("type"))
                    .hasSize(2);
        }

        // The compatible `params` field still answers with the first entry.
        as(token).queryParam("semesterId", semesterId)
                .when().get("/api/constraints")
                .then().statusCode(200)
                .body("find { it.type == 'TEACHER_NO_FIRST_PERIOD' }.params.teacherId",
                        equalTo((int) world.firstTeacher()))
                .body("find { it.type == 'TEACHER_NO_FIRST_PERIOD' }.entries", hasSize(2));
        assertThat(entryRepository.findBySemesterId(semesterId))
                .filteredOn(entry -> !"TEACHER_CONFLICT".equals(entry.getType()))
                .hasSize(24);
    }

    @Test
    void sameCourseWithTwoRoomTypesIsRejectedAndNothingChanges() {
        String token = adminToken();
        long semesterId = semesterWithPeriods(token);
        long playground = TestData.course(token, "体育" + TestData.suffix());
        save(token, semesterId, List.of(config("COURSE_REQUIRED_ROOM_TYPE", true, 100,
                List.of(Map.of("courseId", playground, "roomType", "PLAYGROUND"))))).then().statusCode(200);
        String before = constraintsJson(token, semesterId);

        Response rejected = save(token, semesterId, List.of(config("COURSE_REQUIRED_ROOM_TYPE", true, 100,
                List.of(Map.of("courseId", playground, "roomType", "PLAYGROUND"),
                        Map.of("courseId", playground, "roomType", "LAB")))));

        rejected.then().statusCode(400);
        assertThat(rejected.asString())
                .contains("COURSE_REQUIRED_ROOM_TYPE")
                .contains("第2 条")
                .contains("教室类型")
                .contains("不一致");
        assertThat(constraintsJson(token, semesterId)).isEqualTo(before);
        assertThat(entryRepository.findBySemesterId(semesterId)).hasSize(1);
    }

    @Test
    void contradictoryScalarEntriesForTheSameCourseAreRejected() {
        String token = adminToken();
        long semesterId = semesterWithPeriods(token);
        long course = TestData.course(token, "连堂" + TestData.suffix());

        Response rejected = save(token, semesterId, List.of(config("CONSECUTIVE_LESSONS", false, 60,
                List.of(Map.of("courseId", course, "minConsecutive", 2),
                        Map.of("courseId", course, "minConsecutive", 3)))));

        rejected.then().statusCode(400);
        assertThat(rejected.asString())
                .contains("CONSECUTIVE_LESSONS")
                .contains("第2 条")
                .contains("连排节数");
        assertThat(entryRepository.findBySemesterId(semesterId)).isEmpty();
    }

    @Test
    void unknownOrOutOfRangeTargetsAreRejected() {
        String token = adminToken();
        long semesterId = semesterWithPeriods(token);
        World world = world(token);

        save(token, semesterId, List.of(config("COURSE_NOT_IN_EVENING", true, 100,
                List.of(Map.of("courseId", 9_999_999L))))).then().statusCode(400);
        save(token, semesterId, List.of(config("TEACHER_PREFERRED_SLOT", false, 40,
                List.of(Map.of("teacherId", 9_999_999L, "dayOfWeek", 1, "periodNo", 1)))))
                .then().statusCode(400);
        save(token, semesterId, List.of(config("TEACHER_MEETING_BLOCKED", true, 100,
                List.of(Map.of("subject", "不存在的学科" + TestData.suffix(),
                        "dayOfWeek", 1, "periodNo", 1))))).then().statusCode(400);
        // Day 1 only has the eight periods the semester was created with.
        Response outOfTimetable = save(token, semesterId, List.of(config("TEACHER_PREFERRED_SLOT", false, 40,
                List.of(Map.of("teacherId", world.firstTeacher(), "dayOfWeek", 1, "periodNo", 9)))));
        outOfTimetable.then().statusCode(400);
        assertThat(outOfTimetable.asString()).contains("作息");

        assertThat(entryRepository.findBySemesterId(semesterId)).isEmpty();
    }

    @Test
    void anEmptyEntryListMeansNotConfigured() {
        String token = adminToken();
        long semesterId = semesterWithPeriods(token);

        save(token, semesterId, List.of(config("TEACHER_DAY_OFF", true, 30, List.of())))
                .then().statusCode(200)
                .body("find { it.type == 'TEACHER_DAY_OFF' }.entries", hasSize(0));

        // A legacy row that only has params_json (what a pre-V26 database would hold
        // when nothing migrated it) is not evaluated and reads back with no entries.
        long teacher = TestData.teacher(token, "旧行" + TestData.suffix());
        seedLegacyRow(semesterId, "TEACHER_NO_FIRST_PERIOD",
                "{\"teacherId\":" + teacher + "}");

        as(token).queryParam("semesterId", semesterId)
                .when().get("/api/constraints")
                .then().statusCode(200)
                .body("find { it.type == 'TEACHER_NO_FIRST_PERIOD' }.entries", hasSize(0));
        assertThat(constraintService.settings(semesterId)
                .entries(ConstraintType.TEACHER_NO_FIRST_PERIOD)).isEmpty();

        // Threshold types joined the same registry in ticket 10/04: their legacy single
        // value is what V28 migrated into one global entry, so an unmigrated row is
        // equally "not configured".
        seedLegacyRow(semesterId, "TEACHER_MAX_PER_DAY", "{\"maxPerDay\":3}");
        assertThat(constraintService.settings(semesterId)
                .entries(ConstraintType.TEACHER_MAX_PER_DAY)).isEmpty();
    }

    @Test
    void legacyParamsRequestsStillBecomeOneEntry() {
        String token = adminToken();
        long semesterId = semesterWithPeriods(token);
        World world = world(token);

        save(token, semesterId, List.of(
                legacy("COURSE_NOT_IN_EVENING", true, 100, Map.of("courseId", world.firstCourse())),
                legacy("SPORT_NOT_FIRST_AFTERNOON", false, 70, Map.of()),
                legacy("COURSE_SPREAD", false, 50, Map.of()),
                legacy("SAME_COURSE_ONCE_PER_DAY", true, 100, Map.of()),
                legacy("PE_LAST_PERIOD_AVOID", false, 35, Map.of())))
                .then().statusCode(200);

        Response reloaded = as(token).queryParam("semesterId", semesterId)
                .when().get("/api/constraints").then().statusCode(200).extract().response();
        for (String type : List.of("COURSE_NOT_IN_EVENING", "SPORT_NOT_FIRST_AFTERNOON",
                "COURSE_SPREAD", "SAME_COURSE_ONCE_PER_DAY", "PE_LAST_PERIOD_AVOID")) {
            List<Map<String, Object>> entries =
                    reloaded.jsonPath().getList("find { it.type == '" + type + "' }.entries");
            assertThat(entries).as(type + " keeps one entry").hasSize(1);
        }
        // The blank target is the explicit "every course / sport class" entry, not a
        // dropped configuration: the pre-existing front-end sends `params: {}` for it.
        assertThat(reloaded.jsonPath()
                .getMap("find { it.type == 'SPORT_NOT_FIRST_AFTERNOON' }.entries[0].params"))
                .isEmpty();
        assertThat(reloaded.jsonPath()
                .getLong("find { it.type == 'COURSE_NOT_IN_EVENING' }.entries[0].params.courseId"))
                .isEqualTo(world.firstCourse());
        assertThat(constraintService.settings(semesterId)
                .entries(ConstraintType.SPORT_NOT_FIRST_AFTERNOON)).hasSize(1);
    }

    @Test
    void templateKeepsEveryEntryOfEveryTargetType() {
        String token = adminToken();
        long source = semesterWithPeriods(token);
        World world = world(token);
        save(token, source, twoEntriesForEachType(world)).then().statusCode(200);

        long templateId = as(token).queryParam("semesterId", source)
                .body(Map.of("name", "目标型条目-" + TestData.suffix()))
                .when().post("/api/constraint-templates")
                .then().statusCode(200)
                .body("configCount", equalTo(12))
                .extract().jsonPath().getLong("id");

        long target = semesterWithPeriods(token);
        Response applied = as(token).queryParam("semesterId", target)
                .when().post("/api/constraint-templates/" + templateId + "/apply")
                .then().statusCode(200).extract().response();

        List<Map<String, Object>> configs = applied.jsonPath().getList("$");
        assertThat(configs).hasSize(12);
        for (Map<String, Object> config : configs) {
            assertThat((List<?>) config.get("entries"))
                    .as("template restores both entries of " + config.get("type"))
                    .hasSize(2);
        }
    }

    // ------------------------------------------------------------------ helpers

    private record World(long firstTeacher, long secondTeacher, long firstCourse, long secondCourse) {}

    private World world(String token) {
        String suffix = TestData.suffix();
        return new World(
                TestData.teacher(token, "目标甲" + suffix),
                TestData.teacher(token, "目标乙" + suffix),
                TestData.course(token, "语文" + suffix),
                TestData.course(token, "数学" + suffix));
    }

    /** One request carrying every remaining target type, each with two entries. */
    private List<Map<String, Object>> twoEntriesForEachType(World world) {
        long teacher1 = world.firstTeacher();
        long teacher2 = world.secondTeacher();
        long course1 = world.firstCourse();
        long course2 = world.secondCourse();
        List<Map<String, Object>> configs = new ArrayList<>();
        configs.add(config("TEACHER_MEETING_BLOCKED", true, 100, List.of(
                Map.of("dayOfWeek", 1, "periodNo", 1),
                Map.of("dayOfWeek", 1, "periodNo", 2))));
        configs.add(config("COURSE_REQUIRED_ROOM_TYPE", true, 100, List.of(
                Map.of("courseId", course1, "roomType", "LAB"),
                Map.of("courseId", course2, "roomType", "PLAYGROUND"))));
        configs.add(config("COURSE_NOT_IN_EVENING", true, 100, List.of(
                Map.of("courseId", course1), Map.of("courseId", course2))));
        configs.add(config("SAME_COURSE_ONCE_PER_DAY", true, 100, List.of(
                Map.of(), Map.of("courseId", course1))));
        configs.add(config("SPORT_NOT_FIRST_AFTERNOON", false, 70, List.of(
                Map.of(), Map.of("courseId", course1))));
        configs.add(config("CONSECUTIVE_LESSONS", false, 60, List.of(
                Map.of("courseId", course1, "minConsecutive", 2),
                Map.of("courseId", course2, "minConsecutive", 3))));
        configs.add(config("COURSE_SPREAD", false, 50, List.of(
                Map.of(), Map.of("courseId", course1))));
        configs.add(config("MAIN_SUBJECT_MORNING", false, 55, List.of(
                Map.of("courseId", course1, "beforePeriodNo", 4),
                Map.of("courseId", course2, "beforePeriodNo", 6))));
        configs.add(config("TEACHER_PREFERRED_SLOT", false, 40, List.of(
                Map.of("teacherId", teacher1, "dayOfWeek", 1, "periodNo", 1),
                Map.of("teacherId", teacher1, "dayOfWeek", 1, "periodNo", 2))));
        configs.add(config("TEACHER_NO_FIRST_PERIOD", false, 35, List.of(
                Map.of("teacherId", teacher1), Map.of("teacherId", teacher2))));
        configs.add(config("TEACHER_DAY_OFF", false, 30, List.of(
                Map.of("teacherId", teacher1, "dayOfWeek", 2),
                Map.of("teacherId", teacher2, "dayOfWeek", 3))));
        configs.add(config("PE_LAST_PERIOD_AVOID", false, 35, List.of(
                Map.of(), Map.of("courseId", course1))));
        return configs;
    }

    private Map<String, Object> config(String type, boolean hard, int weight,
                                       List<Map<String, Object>> entries) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("type", type);
        // Each entry is `{id?, params}` on the wire; the tests spell out the params.
        config.put("entries", entries.stream().map(params -> Map.of("params", params)).toList());
        config.put("weight", weight);
        config.put("hard", hard);
        config.put("enabled", true);
        return config;
    }

    private Map<String, Object> legacy(String type, boolean hard, int weight,
                                       Map<String, Object> params) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("type", type);
        config.put("params", params);
        config.put("weight", weight);
        config.put("hard", hard);
        config.put("enabled", true);
        return config;
    }

    private Response save(String token, long semesterId, List<Map<String, Object>> configs) {
        return as(token).queryParam("semesterId", semesterId)
                .body(Map.of("configs", configs))
                .when().put("/api/constraints");
    }

    private String constraintsJson(String token, long semesterId) {
        return as(token).queryParam("semesterId", semesterId)
                .when().get("/api/constraints").then().statusCode(200).extract().asString();
    }

    private void seedLegacyRow(long semesterId, String type, String paramsJson) {
        ConstraintConfig config = new ConstraintConfig();
        config.setSemesterId(semesterId);
        config.setType(type);
        config.setParamsJson(paramsJson);
        config.setWeight(50);
        config.setHard(false);
        config.setEnabled(true);
        configRepository.save(config);
    }

    private long semesterWithPeriods(String token) {
        long semesterId = TestData.semester(token);
        as(token).body(Map.of("morningCount", 4, "afternoonCount", 4, "eveningCount", 0))
                .when().post("/api/semesters/" + semesterId + "/periods/default")
                .then().statusCode(200);
        return semesterId;
    }
}
