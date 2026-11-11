package com.paiban.identity;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;

import com.paiban.identity.domain.AppUserRepository;
import com.paiban.identity.domain.RoleRepository;
import com.paiban.integration.dingtalk.FakeDingTalkClient;
import com.paiban.integration.domain.DingUser;
import com.paiban.support.ApiIntegrationTest;
import com.paiban.support.TestData;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/** A DingTalk super administrator must not remove the school's password recovery route. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:paiban_password_fallback;MODE=PostgreSQL;"
            + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
class PasswordFallbackIntegrationTest extends ApiIntegrationTest {
    @Autowired
    private AppUserRepository accounts;
    @Autowired
    private RoleRepository roles;
    @Autowired
    private FakeDingTalkClient fakeDingTalk;

    private final List<Long> ownedTeachers = new ArrayList<>();

    @BeforeEach
    void configureFakeTenant() {
        resetIntegrationSettings();
        fakeDingTalk.clearDirectory();
        as(adminToken()).body(Map.of("enabled", true, "appKey", "fallback-key",
                        "appSecret", "fallback-secret", "agentId", "1000"))
                .when().put("/api/integrations/dingtalk/config").then().statusCode(200);
    }

    @AfterEach
    void restoreThisDatabaseSeedAndRemoveOnlyOwnedAccounts() {
        // The red run can demote/disable the seed. Restore this isolated fixture
        // before removing the extra super administrator; never touch another DB.
        var root = accounts.findByUsername("admin").orElseThrow();
        root.setEnabled(true);
        root.setTeacherId(null);
        root.setRoles(new HashSet<>(Set.of(roles.findByCode("super_admin").orElseThrow())));
        accounts.saveAndFlush(root);
        for (long teacherId : ownedTeachers) {
            accounts.deleteAll(accounts.findAllByTeacherId(teacherId));
        }
        accounts.flush();
        ownedTeachers.clear();
        resetIntegrationSettings();
        fakeDingTalk.resetToDefault();
    }

    @Test
    void aDingTalkSuperAdministratorCannotDemoteTheLastEnabledPasswordSuperAdministrator() {
        String admin = adminToken();
        long rootId = as(admin).when().get("/api/auth/me").then().statusCode(200)
                .body("loginType", equalTo("PASSWORD")).extract().jsonPath().getLong("id");
        String dingAdministrator = provisionDingTalkSuperAdministrator(admin);

        as(dingAdministrator).body(Map.of("roleCodes", List.of("teacher")))
                .when().put("/api/users/" + rootId).then().statusCode(409)
                .body("message", containsString("密码"));
        as(dingAdministrator).when().get("/api/users").then().statusCode(200)
                .body("find { it.id == " + rootId + " }.enabled", equalTo(true))
                .body("find { it.id == " + rootId + " }.roles", hasItem("super_admin"));
        as(adminToken()).when().get("/api/auth/me").then().statusCode(200)
                .body("roles", hasItem("super_admin")).body("loginType", equalTo("PASSWORD"));
    }

    private String provisionDingTalkSuperAdministrator(String admin) {
        String suffix = TestData.suffix();
        String name = "密码总钥匙验证" + suffix;
        String jobNumber = "RECOVERY-" + suffix;
        long teacherId = as(admin).body(Map.of("name", name)).when().post("/api/teachers")
                .then().statusCode(200).extract().jsonPath().getLong("id");
        ownedTeachers.add(teacherId);
        fakeDingTalk.addUser(1, new DingUser("recovery-" + suffix, name, null, null, jobNumber));
        long accountId = dingTalkLogin(jobNumber).then().statusCode(200)
                .body("user.loginType", equalTo("DINGTALK")).extract().jsonPath().getLong("user.id");
        as(admin).body(Map.of("roleCodes", List.of("super_admin")))
                .when().put("/api/users/" + accountId).then().statusCode(200)
                .body("loginType", equalTo("DINGTALK"));
        return dingTalkLogin(jobNumber).then().statusCode(200)
                .body("user.id", equalTo((int) accountId))
                .body("user.roles", hasItem("super_admin"))
                .extract().jsonPath().getString("token");
    }

    private Response dingTalkLogin(String jobNumber) {
        return RestAssured.given().contentType(ContentType.JSON)
                .body(Map.of("authCode", "fake:" + jobNumber)).when().post("/api/auth/dingtalk");
    }
}
