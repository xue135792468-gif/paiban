package com.paiban.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

import com.paiban.identity.domain.AppUserRepository;
import com.paiban.integration.dingtalk.FakeDingTalkClient;
import com.paiban.integration.domain.DingUser;
import com.paiban.support.ApiIntegrationTest;
import com.paiban.support.TestData;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/** Public first-login tracer; all identity information comes from the fake vendor. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:paiban_dingtalk_provision;MODE=PostgreSQL;"
            + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
class DingTalkAutoProvisionIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private FakeDingTalkClient fakeDingTalk;

    @Autowired
    private AppUserRepository accounts;

    private final List<Long> ownedTeachers = new ArrayList<>();

    @BeforeEach
    void configureFakeTenant() {
        resetIntegrationSettings();
        fakeDingTalk.clearDirectory();
        as(adminToken()).body(Map.of(
                        "enabled", true, "appKey", "provision-key", "appSecret", "provision-secret",
                        "agentId", "1000", "corpId", "provision-corp"))
                .when().put("/api/integrations/dingtalk/config").then().statusCode(200);
    }

    @AfterEach
    void removeOnlyAccountsCreatedForThisFixture() {
        for (long teacherId : ownedTeachers) {
            accounts.deleteAll(accounts.findAllByTeacherId(teacherId));
        }
        accounts.flush();
        ownedTeachers.clear();
        resetIntegrationSettings();
        fakeDingTalk.resetToDefault();
    }

    @Test
    void firstLoginPairsTheUniqueUnboundTeacherAndCreatesOnePasswordlessTeacherAccount() {
        String admin = adminToken();
        String suffix = TestData.suffix();
        String name = "首登可信姓名" + suffix;
        String externalId = "verified-" + suffix;
        String jobNumber = "FIRST-" + suffix;
        long teacherId = as(admin).body(Map.of("name", name))
                .when().post("/api/teachers").then().statusCode(200)
                .extract().jsonPath().getLong("id");
        ownedTeachers.add(teacherId);
        fakeDingTalk.addUser(1, new DingUser(externalId, name, null, null, jobNumber));

        Response first = RestAssured.given().contentType(ContentType.JSON)
                .body(Map.of("authCode", "fake:" + jobNumber))
                .when().post("/api/auth/dingtalk");
        first.then().statusCode(200)
                .body("user.teacherId", equalTo((int) teacherId))
                .body("user.loginType", equalTo("DINGTALK"))
                .body("user.roles", equalTo(List.of("teacher")));
        long accountId = first.jsonPath().getLong("user.id");
        String session = first.jsonPath().getString("token");
        as(session).when().get("/api/auth/me").then().statusCode(200)
                .body("id", equalTo((int) accountId)).body("loginType", equalTo("DINGTALK"));
        as(admin).when().get("/api/teachers").then().statusCode(200)
                .body("find { it.id == " + teacherId + " }.dingtalkUserId", equalTo(externalId));
        assertThat(accounts.findById(accountId).orElseThrow().getPasswordHash()).isNull();

        RestAssured.given().contentType(ContentType.JSON)
                .body(Map.of("authCode", "fake:" + jobNumber))
                .when().post("/api/auth/dingtalk").then().statusCode(200)
                .body("user.id", equalTo((int) accountId));
        as(admin).when().get("/api/users").then().statusCode(200)
                .body("findAll { it.teacherId == " + teacherId + " }", hasSize(1));
        Response audits = as(admin).queryParam("targetUserId", accountId)
                .when().get("/api/account-audits");
        audits.then().statusCode(200).body("total", equalTo(2));
        assertThat(audits.jsonPath().getList("items.action", String.class)).containsExactlyInAnyOrder(
                "ACCOUNT_DINGTALK_AUTO_PROVISION", "ACCOUNT_DINGTALK_LINK");
        assertThat(audits.jsonPath().getList("items.actor", String.class))
                .containsOnly("系统（钉钉免登）");
        audits.then().body("items.find { it.action == 'ACCOUNT_DINGTALK_LINK' }.after.dingtalkUserId",
                equalTo(externalId));
    }
}
