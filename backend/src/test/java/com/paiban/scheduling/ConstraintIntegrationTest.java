package com.paiban.scheduling;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;

import com.paiban.support.ApiIntegrationTest;
import com.paiban.support.TestData;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ConstraintIntegrationTest extends ApiIntegrationTest {

    @Test
    void newSemesterGetsDefaultHardConstraints() {
        String token = adminToken();
        long semesterId = TestData.semester(token);

        as(token).queryParam("semesterId", semesterId)
                .when().get("/api/constraints")
                .then().statusCode(200)
                .body("$", hasSize(3))
                .body("type", hasItem("TEACHER_CONFLICT"))
                .body("type", hasItem("CLASS_CONFLICT"))
                .body("type", hasItem("ROOM_CONFLICT"))
                .body("type", not(hasItem("ROOM_CAPACITY_FIT")))
                .body("find { it.type == 'TEACHER_CONFLICT' }.hard", equalTo(true))
                .body("find { it.type == 'TEACHER_CONFLICT' }.enabled", equalTo(true));
    }

    @Test
    void presetCatalogueHasAtLeastTwentyConstraintsWithSchemas() {
        as(adminToken()).when().get("/api/constraint-types")
                .then().statusCode(200)
                .body("size()", greaterThanOrEqualTo(20))
                .body("find { it.code == 'TEACHER_MAX_PER_DAY' }.params[0].name", equalTo("maxPerDay"))
                .body("find { it.code == 'TEACHER_UNAVAILABLE' }.params.size()", equalTo(3))
                .body("categoryLabel", hasItem("冲突类"));
    }

    @Test
    void saveConstraintsPersistsParamsWeightAndMode() {
        String token = adminToken();
        long semesterId = TestData.semester(token);

        List<Map<String, Object>> configs = List.of(
                Map.of("type", "TEACHER_CONFLICT", "params", Map.of(), "weight", 100, "hard", true, "enabled", true),
                Map.of("type", "TEACHER_MAX_PER_DAY", "params", Map.of("maxPerDay", 4), "weight", 70,
                        "hard", false, "enabled", true),
                Map.of("type", "SPORT_NOT_FIRST_AFTERNOON", "params", Map.of(), "weight", 80,
                        "hard", false, "enabled", true));

        as(token).queryParam("semesterId", semesterId)
                .body(Map.of("configs", configs))
                .when().put("/api/constraints")
                .then().statusCode(200)
                .body("$", hasSize(3));

        as(token).queryParam("semesterId", semesterId)
                .when().get("/api/constraints")
                .then().statusCode(200)
                .body("find { it.type == 'TEACHER_MAX_PER_DAY' }.params.maxPerDay", equalTo(4))
                .body("find { it.type == 'TEACHER_MAX_PER_DAY' }.weight", equalTo(70))
                .body("find { it.type == 'TEACHER_MAX_PER_DAY' }.hard", equalTo(false));
    }

    @Test
    void invalidConstraintParamsAreRejected() {
        String token = adminToken();
        long semesterId = TestData.semester(token);

        as(token).queryParam("semesterId", semesterId)
                .body(Map.of("configs", List.of(Map.of(
                        "type", "TEACHER_UNAVAILABLE",
                        "params", Map.of("dayOfWeek", 3)))))
                .when().put("/api/constraints")
                .then().statusCode(400);

        as(token).queryParam("semesterId", semesterId)
                .body(Map.of("configs", List.of(Map.of(
                        "type", "TEACHER_MAX_PER_DAY",
                        "params", Map.of("maxPerDay", 99)))))
                .when().put("/api/constraints")
                .then().statusCode(400);

        as(token).queryParam("semesterId", semesterId)
                .body(Map.of("configs", List.of(Map.of("type", "NOT_A_CONSTRAINT", "params", Map.of()))))
                .when().put("/api/constraints")
                .then().statusCode(400);
    }

    @Test
    void templateCanBeSavedAndAppliedToAnotherSemester() {
        String token = adminToken();
        long source = TestData.semester(token);
        String templateName = "复用模板-" + TestData.suffix();

        as(token).queryParam("semesterId", source)
                .body(Map.of("configs", List.of(
                        Map.of("type", "TEACHER_CONFLICT", "params", Map.of(), "weight", 100, "hard", true),
                        Map.of("type", "CLASS_MAX_PER_DAY", "params", Map.of("maxPerDay", 7),
                                "weight", 65, "hard", false))))
                .when().put("/api/constraints").then().statusCode(200);

        long templateId = as(token).queryParam("semesterId", source)
                .body(Map.of("name", templateName))
                .when().post("/api/constraint-templates")
                .then().statusCode(200)
                .body("configCount", equalTo(2))
                .extract().jsonPath().getLong("id");

        long target = TestData.semester(token);
        as(token).queryParam("semesterId", target)
                .when().post("/api/constraint-templates/" + templateId + "/apply")
                .then().statusCode(200)
                .body("$", hasSize(2));

        as(token).queryParam("semesterId", target)
                .when().get("/api/constraints")
                .then().statusCode(200)
                .body("find { it.type == 'CLASS_MAX_PER_DAY' }.params.maxPerDay", equalTo(7));

        as(token).when().get("/api/constraint-templates")
                .then().statusCode(200)
                .body("name", hasItem(templateName));
    }

    @Test
    void teacherCannotModifyConstraints() {
        String username = "ct_" + TestData.suffix();
        createUser(adminToken(), username, "teacher123", "teacher");
        String teacherToken = login(username, "teacher123");
        long semesterId = TestData.semester(adminToken());

        as(teacherToken).queryParam("semesterId", semesterId)
                .body(Map.of("configs", List.of()))
                .when().put("/api/constraints")
                .then().statusCode(403);
    }
}
