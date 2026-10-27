package com.paiban.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import com.paiban.support.ApiIntegrationTest;
import com.paiban.support.TestData;
import io.restassured.response.Response;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Ticket 14/07: the effective configuration carries one version. A save based on an
 * older version is refused with the latest configuration (no silent overwrite), and two
 * genuinely concurrent saves cannot both win.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ConstraintVersionIntegrationTest extends ApiIntegrationTest {

    @Test
    void versionStartsAtZeroAndAdvancesOnEveryWrite() {
        String token = adminToken();
        long semesterId = semesterWithPeriods(token);

        assertThat(stateVersion(token, semesterId)).isZero();

        // The first write creates the row at revision 1; every later write increments it.
        save(token, semesterId, null, List.of(switchConfig("TEACHER_CONFLICT"))).then().statusCode(200);
        assertThat(stateVersion(token, semesterId)).isEqualTo(1);

        save(token, semesterId, 1L, List.of(switchConfig("TEACHER_CONFLICT"))).then().statusCode(200);
        assertThat(stateVersion(token, semesterId)).isEqualTo(2);
    }

    @Test
    void aStaleBaseVersionIsRefusedWithTheLatestConfigurationAndWritesNothing() {
        String token = adminToken();
        long semesterId = semesterWithPeriods(token);
        save(token, semesterId, null, List.of(switchConfig("TEACHER_CONFLICT"))).then().statusCode(200);
        save(token, semesterId, 1L, List.of(switchConfig("CLASS_CONFLICT"))).then().statusCode(200);
        String before = stateBody(token, semesterId);

        Response refused = save(token, semesterId, 1L, List.of(switchConfig("ROOM_CONFLICT")));

        refused.then().statusCode(409)
                .body("version", equalTo(2))
                .body("message", org.hamcrest.Matchers.containsString("已被他人修改"));
        assertThat(refused.jsonPath().getList("configs.type", String.class))
                .contains("CLASS_CONFLICT");
        assertThat(stateBody(token, semesterId)).isEqualTo(before);
    }

    @Test
    void aLegacyRequestCannotErasePerRequirementData() {
        String token = adminToken();
        long semesterId = semesterWithPeriods(token);
        long teacher = TestData.teacher(token, "版本教师" + TestData.suffix());
        Map<String, Object> grouped = new LinkedHashMap<>();
        grouped.put("type", "TEACHER_UNAVAILABLE");
        grouped.put("weight", 100);
        grouped.put("hard", true);
        grouped.put("enabled", true);
        grouped.put("entries", List.of(
                Map.of("params", Map.of("teacherId", teacher, "dayOfWeek", 1, "periodNo", 1),
                        "groupKey", "g1", "hard", false, "weight", 30)));
        save(token, semesterId, null, List.of(grouped)).then().statusCode(200);
        String before = stateBody(token, semesterId);

        // No baseVersion: a legacy client cannot represent the group/override, so it is
        // told to refresh instead of silently deleting them.
        Response legacy = as(token).queryParam("semesterId", semesterId)
                .body(Map.of("configs", List.of(switchConfig("TEACHER_CONFLICT"))))
                .when().put("/api/constraints");

        legacy.then().statusCode(400);
        assertThat(legacy.asString()).contains("配置版本");
        assertThat(stateBody(token, semesterId)).isEqualTo(before);
    }

    @Test
    void twoConcurrentSavesFromTheSameVersionHaveExactlyOneWinner() throws Exception {
        String token = adminToken();
        long semesterId = semesterWithPeriods(token);
        save(token, semesterId, null, List.of(switchConfig("TEACHER_CONFLICT"))).then().statusCode(200);
        long base = stateVersion(token, semesterId);

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> first = concurrentSave(
                    token, semesterId, base, barrier, "CLASS_CONFLICT");
            Callable<Integer> second = concurrentSave(
                    token, semesterId, base, barrier, "ROOM_CONFLICT");
            Future<Integer> a = pool.submit(first);
            Future<Integer> b = pool.submit(second);
            List<Integer> statuses = List.of(
                    a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));

            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
            assertThat(stateVersion(token, semesterId)).isEqualTo(base + 1);
            List<String> types = as(token).queryParam("semesterId", semesterId)
                    .when().get("/api/constraints/state")
                    .then().statusCode(200).extract().jsonPath().getList("configs.type", String.class);
            // Exactly one configuration survived; the loser wrote nothing.
            assertThat(types).hasSize(1);
            assertThat(types.get(0)).isIn("CLASS_CONFLICT", "ROOM_CONFLICT");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aTemplateApplyHonoursTheSameVersion() {
        String token = adminToken();
        long semesterId = semesterWithPeriods(token);
        save(token, semesterId, null, List.of(switchConfig("TEACHER_CONFLICT"))).then().statusCode(200);
        long templateId = as(token).queryParam("semesterId", semesterId)
                .body(Map.of("name", "版本模板-" + TestData.suffix()))
                .when().post("/api/constraint-templates")
                .then().statusCode(200).extract().jsonPath().getLong("id");
        save(token, semesterId, 1L, List.of(switchConfig("CLASS_CONFLICT"))).then().statusCode(200);

        // Based on version 1 while the semester is at 2: refused, nothing changes.
        as(token).queryParam("semesterId", semesterId).queryParam("baseVersion", 1L)
                .when().post("/api/constraint-templates/" + templateId + "/apply")
                .then().statusCode(409);

        as(token).queryParam("semesterId", semesterId).queryParam("baseVersion", 2L)
                .when().post("/api/constraint-templates/" + templateId + "/apply")
                .then().statusCode(200);
        assertThat(stateVersion(token, semesterId)).isEqualTo(3);
    }

    // ------------------------------------------------------------------ helpers

    private Callable<Integer> concurrentSave(
            String token, long semesterId, long baseVersion,
            CyclicBarrier barrier, String type) {
        return () -> {
            barrier.await(10, TimeUnit.SECONDS);
            return save(token, semesterId, baseVersion, List.of(switchConfig(type)))
                    .then().extract().statusCode();
        };
    }

    private long semesterWithPeriods(String token) {
        long semesterId = TestData.semester(token);
        as(token).body(Map.of("morningCount", 4, "afternoonCount", 4, "eveningCount", 0))
                .when().post("/api/semesters/" + semesterId + "/periods/default")
                .then().statusCode(200);
        return semesterId;
    }

    private Response save(
            String token, long semesterId, Long baseVersion, List<Map<String, Object>> configs) {
        io.restassured.specification.RequestSpecification request = as(token)
                .queryParam("semesterId", semesterId);
        if (baseVersion != null) {
            request = request.queryParam("baseVersion", baseVersion);
        }
        return request.body(Map.of("configs", configs))
                .when().put("/api/constraints");
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

    private Map<String, Object> switchConfig(String type) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("type", type);
        config.put("params", Map.of());
        config.put("weight", 100);
        config.put("hard", true);
        config.put("enabled", true);
        return config;
    }
}
