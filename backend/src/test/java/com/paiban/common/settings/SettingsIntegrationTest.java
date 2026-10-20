package com.paiban.common.settings;

import static org.assertj.core.api.Assertions.assertThat;

import com.paiban.support.ApiIntegrationTest;
import com.paiban.support.TestData;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SettingsIntegrationTest extends ApiIntegrationTest {

    @Test
    void schoolTypeDefaultsToJunior() {
        as(adminToken()).when().get("/api/settings")
                .then().statusCode(200)
                .body("'school.type'", org.hamcrest.Matchers.equalTo("junior"));

        as(adminToken()).when().get("/api/settings/public")
                .then().statusCode(200)
                .body("schoolType", org.hamcrest.Matchers.equalTo("junior"));
    }

    @Test
    void schoolTypeCanBeSwitchedBetweenJuniorAndSenior() {
        String token = adminToken();

        as(token).body(Map.of("school.type", "senior"))
                .when().put("/api/settings")
                .then().statusCode(200)
                .body("'school.type'", org.hamcrest.Matchers.equalTo("senior"));

        as(token).when().get("/api/settings")
                .then().statusCode(200)
                .body("'school.type'", org.hamcrest.Matchers.equalTo("senior"));

        as(token).when().get("/api/settings/public")
                .then().statusCode(200)
                .body("schoolType", org.hamcrest.Matchers.equalTo("senior"));

        as(token).body(Map.of("school.type", "junior"))
                .when().put("/api/settings")
                .then().statusCode(200)
                .body("'school.type'", org.hamcrest.Matchers.equalTo("junior"));
    }

    @Test
    void invalidSchoolTypeIsRejected() {
        as(adminToken()).body(Map.of("school.type", "university"))
                .when().put("/api/settings")
                .then().statusCode(400);
    }

    @Test
    void publicSettingsExposeOnlySchoolTypeAndNameToTeachers() {
        String token = adminToken();
        as(token).body(Map.of("school.name", "公开测试中学", "school.type", "senior"))
                .when().put("/api/settings").then().statusCode(200);

        String username = "setpub_" + TestData.suffix();
        createUser(token, username, "teacher123", "teacher");
        String teacherToken = login(username, "teacher123");

        Map<String, String> publicSettings = as(teacherToken)
                .when().get("/api/settings/public")
                .then().statusCode(200)
                .extract().jsonPath().getMap("$");

        assertThat(publicSettings)
                .containsOnlyKeys("schoolType", "schoolName")
                .containsEntry("schoolType", "senior")
                .containsEntry("schoolName", "公开测试中学");
    }

    @Test
    void teacherCannotReadFullSettings() {
        String username = "setfull_" + TestData.suffix();
        createUser(adminToken(), username, "teacher123", "teacher");
        String teacherToken = login(username, "teacher123");

        as(teacherToken).when().get("/api/settings")
                .then().statusCode(403);
    }
}
