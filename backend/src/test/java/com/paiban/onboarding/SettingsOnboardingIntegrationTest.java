package com.paiban.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;

import com.paiban.support.ApiIntegrationTest;
import io.restassured.http.ContentType;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SettingsOnboardingIntegrationTest extends ApiIntegrationTest {

    @Test
    void settingsCanBeReadAndUpdated() {
        String token = adminToken();
        Map<String, String> initial = as(token).when().get("/api/settings")
                .then().statusCode(200).extract().jsonPath().getMap("$");
        assertThat(initial).containsKeys(
                "substitution.approval-mode", "notification.scope", "ai.features.substitute-recommendation");
        assertThat(initial.get("ai.features.substitute-recommendation")).isEqualTo("false");

        Map<String, String> updated = as(token).body(Map.of(
                        "school.name", "测试中学",
                        "notification.scope", "minimal",
                        "ai.features.substitute-recommendation", "true"))
                .when().put("/api/settings")
                .then().statusCode(200).extract().jsonPath().getMap("$");
        assertThat(updated)
                .containsEntry("school.name", "测试中学")
                .containsEntry("notification.scope", "minimal")
                .containsEntry("ai.features.substitute-recommendation", "true");
    }

    @Test
    void invalidSettingsAreRejected() {
        String token = adminToken();
        as(token).body(Map.of("substitution.approval-mode", "sometimes"))
                .when().put("/api/settings").then().statusCode(400);
        as(token).body(Map.of("unknown.key", "x"))
                .when().put("/api/settings").then().statusCode(400);
    }

    @Test
    void teacherCannotChangeSettings() {
        String username = "set_" + com.paiban.support.TestData.suffix();
        createUser(adminToken(), username, "teacher123", "teacher");
        String teacherToken = login(username, "teacher123");
        as(teacherToken).body(Map.of("school.name", "越权"))
                .when().put("/api/settings").then().statusCode(403);
    }

    @Test
    void logoCanBeUploaded() throws Exception {
        BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);

        var response = io.restassured.RestAssured.given()
                .header("Authorization", "Bearer " + adminToken())
                .multiPart("file", "logo.png", out.toByteArray(), "image/png")
                .when().post("/api/settings/logo");
        response.then().statusCode(200);
        Map<String, String> settings = response.then().extract().jsonPath().getMap("$");
        assertThat(settings.get("school.logo-path")).contains("logo-");
    }

    @Test
    void onboardingProgressCanBeSavedSkippedAndReset() {
        String username = "onb_" + com.paiban.support.TestData.suffix();
        createUser(adminToken(), username, "teacher123", "admin");
        String token = login(username, "teacher123");

        as(token).when().get("/api/onboarding")
                .then().statusCode(200)
                .body("currentStep", equalTo(0))
                .body("skipped", equalTo(false));

        as(token).body(Map.of(
                        "currentStep", 3,
                        "completedSteps", List.of(0, 1, 2),
                        "data", Map.of("schoolName", "测试中学"),
                        "skipped", false))
                .when().put("/api/onboarding")
                .then().statusCode(200)
                .body("currentStep", equalTo(3))
                .body("completedSteps", hasSize(3));

        as(token).body(Map.of("skipped", true))
                .when().put("/api/onboarding")
                .then().statusCode(200)
                .body("skipped", equalTo(true));

        as(token).when().get("/api/onboarding/status")
                .then().statusCode(200)
                .body("skipped", equalTo(true))
                .body("hasProgress", equalTo(true))
                .body("showWizard", equalTo(false));

        as(token).when().post("/api/onboarding/reset").then().statusCode(200);
        as(token).when().get("/api/onboarding")
                .then().statusCode(200)
                .body("currentStep", equalTo(0))
                .body("skipped", equalTo(false));
    }

    @Test
    void onboardingStatusListsSetupHints() {
        String token = adminToken();
        as(token).when().get("/api/onboarding/status")
                .then().statusCode(200)
                .body("hints", hasSize(9))
                .body("hints.key", hasItem("semester"))
                .body("hints.key", hasItem("periods"))
                .body("hints.key", hasItem("classes"))
                .body("hints.key", hasItem("published"));
    }

    @Test
    void onboardingStatusIsAvailableToTeachers() {
        String username = "onbt_" + com.paiban.support.TestData.suffix();
        createUser(adminToken(), username, "teacher123", "teacher");
        String teacherToken = login(username, "teacher123");
        as(teacherToken).when().get("/api/onboarding/status")
                .then().statusCode(200);
    }

    @Test
    void settingsRequireAuthentication() {
        io.restassured.RestAssured.given()
                .contentType(ContentType.JSON)
                .when().get("/api/settings")
                .then().statusCode(401);
    }
}
