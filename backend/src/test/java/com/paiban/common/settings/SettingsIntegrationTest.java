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
    void invalidSettingsKeyIsRejected() {
        as(adminToken()).body(Map.of("school.type", "university"))
                .when().put("/api/settings")
                .then().statusCode(400);
    }

    /** The public surface exists only so the UI can render the school name. */
    @Test
    void publicSettingsExposeOnlySchoolNameToTeachers() {
        String token = adminToken();
        as(token).body(Map.of("school.name", "公开测试中学"))
                .when().put("/api/settings").then().statusCode(200);

        String username = "setpub_" + TestData.suffix();
        createUser(token, username, "teacher123", "teacher");
        String teacherToken = login(username, "teacher123");

        Map<String, String> publicSettings = as(teacherToken)
                .when().get("/api/settings/public")
                .then().statusCode(200)
                .extract().jsonPath().getMap("$");

        assertThat(publicSettings)
                .containsOnlyKeys("schoolName")
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
