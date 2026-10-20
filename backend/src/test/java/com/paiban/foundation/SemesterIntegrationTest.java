package com.paiban.foundation;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

import com.paiban.support.ApiIntegrationTest;
import io.restassured.response.Response;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SemesterIntegrationTest extends ApiIntegrationTest {

    private String suffix() {
        return UUID.randomUUID().toString().substring(0, 6);
    }

    private long createSemester(String token, String name) {
        Response response = as(token)
                .body(Map.of("name", name, "startDate", "2025-09-01", "endDate", "2026-01-20"))
                .when().post("/api/semesters");
        response.then().statusCode(200);
        return response.jsonPath().getLong("id");
    }

    @Test
    void createSemesterConfigurePeriodsAndSetCurrent() {
        String token = adminToken();
        String name = "2025秋季-" + suffix();
        long id = createSemester(token, name);

        as(token).body(Map.of("morningCount", 4, "afternoonCount", 4, "eveningCount", 2))
                .when().post("/api/semesters/" + id + "/periods/default")
                .then().statusCode(200)
                .body("$", hasSize(50));

        as(token).when().post("/api/semesters/" + id + "/set-current")
                .then().statusCode(200)
                .body("current", equalTo(true));

        as(token).when().get("/api/semesters/current")
                .then().statusCode(200)
                .body("id", equalTo((int) id))
                .body("name", equalTo(name));

        as(token).when().get("/api/semesters/" + id + "/periods")
                .then().statusCode(200)
                .body("$", hasSize(50))
                .body("findAll { it.dayOfWeek == 1 }.size()", equalTo(10));
    }

    @Test
    void onlyOneSemesterCanBeCurrent() {
        String token = adminToken();
        long first = createSemester(token, "甲学期-" + suffix());
        long second = createSemester(token, "乙学期-" + suffix());

        as(token).when().post("/api/semesters/" + first + "/set-current").then().statusCode(200);
        as(token).when().post("/api/semesters/" + second + "/set-current").then().statusCode(200);

        as(token).when().get("/api/semesters")
                .then().statusCode(200)
                .body("findAll { it.current == true }.size()", equalTo(1))
                .body("find { it.current == true }.id", equalTo((int) second));
    }

    @Test
    void customPeriodSlotsAreStoredPerDay() {
        String token = adminToken();
        long id = createSemester(token, "自定义作息-" + suffix());

        Map<String, Object> slot = Map.of(
                "periodNo", 1,
                "label", "第一节",
                "section", "MORNING",
                "startTime", "08:00",
                "endTime", "08:45",
                "days", List.of(1, 2, 3));
        as(token).body(Map.of("slots", List.of(slot)))
                .when().put("/api/semesters/" + id + "/periods")
                .then().statusCode(200)
                .body("$", hasSize(3));

        as(token).when().get("/api/semesters/" + id + "/periods")
                .then().statusCode(200)
                .body("$", hasSize(3))
                .body("[0].label", equalTo("第一节"))
                .body("[0].startTime", notNullValue());
    }

    @Test
    void invalidSemesterDatesAreRejected() {
        as(adminToken())
                .body(Map.of("name", "非法学期-" + suffix(), "startDate", "2026-01-01", "endDate", "2025-01-01"))
                .when().post("/api/semesters")
                .then().statusCode(400);
    }

    @Test
    void duplicateSemesterNameIsRejected() {
        String token = adminToken();
        String name = "重复学期-" + suffix();
        createSemester(token, name);

        as(token)
                .body(Map.of("name", name, "startDate", "2025-09-01", "endDate", "2026-01-20"))
                .when().post("/api/semesters")
                .then().statusCode(409);
    }

    @Test
    void teacherCannotCreateSemester() {
        String name = "t_" + suffix();
        createUser(adminToken(), name, "teacher123", "teacher");
        String teacherToken = login(name, "teacher123");

        as(teacherToken)
                .body(Map.of("name", "越权学期-" + suffix(), "startDate", "2025-09-01", "endDate", "2026-01-20"))
                .when().post("/api/semesters")
                .then().statusCode(403);
    }
}
