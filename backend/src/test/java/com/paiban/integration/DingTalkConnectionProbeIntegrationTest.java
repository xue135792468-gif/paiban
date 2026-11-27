package com.paiban.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;

import com.paiban.integration.dingtalk.FakeDingTalkProbeClient;
import com.paiban.integration.dingtalk.DingTalkProbeCode;
import com.paiban.foundation.domain.AuditLogRepository;
import com.paiban.notification.domain.NotificationLogRepository;
import com.paiban.support.ApiIntegrationTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class DingTalkConnectionProbeIntegrationTest extends ApiIntegrationTest {

    private static final String USER_PASSWORD = "probe-user-pass";

    @Autowired
    private FakeDingTalkProbeClient fakeProbe;

    @Autowired
    private AuditLogRepository auditLogs;

    @Autowired
    private NotificationLogRepository notificationLogs;

    @BeforeEach
    void resetFake() {
        fakeProbe.resetToDefault();
        resetIntegrationSettings();
    }

    @Test
    void unsavedCredentialsCanBeTestedWithoutSavingThem() {
        String token = adminToken();

        as(token).body(Map.of(
                        "appKey", "unsaved-key",
                        "appSecret", "unsaved-secret",
                        "useSavedSecret", false))
                .when().post("/api/integrations/dingtalk/test-connection")
                .then()
                .statusCode(200)
                .body("success", equalTo(true))
                .body("code", equalTo("OK"));

        as(token).when().get("/api/integrations/dingtalk/config")
                .then()
                .statusCode(200)
                .body("enabled", equalTo(false))
                .body("appKey", equalTo(""))
                .body("hasSecret", equalTo(false));
        List<FakeDingTalkProbeClient.ProbeCall> calls = fakeProbe.probeCalls();
        assertThat(calls).singleElement().satisfies(call -> {
            assertThat(call.appKey()).isEqualTo("unsaved-key");
            assertThat(call.appSecret()).isEqualTo("unsaved-secret");
        });
    }

    @Test
    void invalidSyncStrategyLeavesEveryExistingConfigValueUntouched() {
        String token = adminToken();
        as(token).body(Map.of(
                        "enabled", true,
                        "appKey", "old-key",
                        "appSecret", "old-secret",
                        "agentId", "old-agent",
                        "corpId", "old-corp",
                        "syncStrategy", "local"))
                .when().put("/api/integrations/dingtalk/config")
                .then()
                .statusCode(200);

        as(token).body(Map.of(
                        "enabled", false,
                        "appKey", "changed-key",
                        "appSecret", "changed-secret",
                        "agentId", "changed-agent",
                        "corpId", "changed-corp",
                        "syncStrategy", "unknown"))
                .when().put("/api/integrations/dingtalk/config")
                .then()
                .statusCode(400);

        as(token).when().get("/api/integrations/dingtalk/config")
                .then()
                .statusCode(200)
                .body("enabled", equalTo(true))
                .body("appKey", equalTo("old-key"))
                .body("hasSecret", equalTo(true))
                .body("agentId", equalTo("old-agent"))
                .body("corpId", equalTo("old-corp"))
                .body("syncStrategy", equalTo("local"));

        as(token).body(Map.of(
                        "appKey", "old-key",
                        "appSecret", "",
                        "useSavedSecret", true))
                .when().post("/api/integrations/dingtalk/test-connection")
                .then()
                .statusCode(200)
                .body("success", equalTo(true));
        List<FakeDingTalkProbeClient.ProbeCall> calls = fakeProbe.probeCalls();
        org.assertj.core.api.Assertions.assertThat(calls).hasSize(1);
        org.assertj.core.api.Assertions.assertThat(calls.get(0).appSecret()).isEqualTo("old-secret");
    }

    @Test
    void savedSecretIsReusedOnlyForTheExactSavedKeyAndExistingSecret() {
        String token = adminToken();
        saveConfig(token, true, "saved-key", "saved-secret");

        // A non-empty submitted secret always wins, even when reuse was requested.
        probe(token, "new-key", "new-secret", true).statusCode(200)
                .body("success", equalTo(true));
        assertThat(lastProbeCall()).isEqualTo(
                new FakeDingTalkProbeClient.ProbeCall("new-key", "new-secret"));

        // Empty secret without explicit reuse is a parameter error.
        probe(token, "saved-key", "", false).statusCode(400);
        probe(token, "saved-key", "  ", null).statusCode(400);

        // A changed key, including a non-identical whitespace variant, cannot borrow it.
        probe(token, "changed-key", "", true).statusCode(400);
        probe(token, "saved-key ", "", true).statusCode(400);
        assertThat(fakeProbe.probeCalls()).hasSize(1);

        // The exact saved pair is the only reuse path.
        probe(token, "saved-key", "", true).statusCode(200)
                .body("success", equalTo(true));
        assertThat(lastProbeCall()).isEqualTo(
                new FakeDingTalkProbeClient.ProbeCall("saved-key", "saved-secret"));
    }

    @Test
    void anEmptySavedSecretCannotBeReused() {
        String token = adminToken();
        as(token).body(Map.of("appKey", "saved-key"))
                .when().put("/api/integrations/dingtalk/config")
                .then()
                .statusCode(200);

        probe(token, "saved-key", "", true).statusCode(400);
        assertThat(fakeProbe.probeCalls()).isEmpty();
    }

    @Test
    void superAdminCanProbeAndAcademicAndTeacherAreForbidden() {
        String superToken = adminToken();
        createUser(superToken, "academic-probe", USER_PASSWORD, "admin");
        createUser(superToken, "teacher-probe", USER_PASSWORD, "teacher");
        String academicToken = login("academic-probe", USER_PASSWORD);
        String teacherToken = login("teacher-probe", USER_PASSWORD);

        probe(academicToken, "academic-key", "academic-secret", false)
                .statusCode(403);
        probe(teacherToken, "teacher-key", "teacher-secret", false)
                .statusCode(403);
        probe(superToken, "super-key", "super-secret", false)
                .statusCode(200)
                .body("success", equalTo(true));

        assertThat(fakeProbe.probeCalls()).containsExactly(
                new FakeDingTalkProbeClient.ProbeCall("super-key", "super-secret"));
    }

    @Test
    void failedProbeCodesAreSafeTwoHundredResponses() {
        String token = adminToken();
        for (DingTalkProbeCode code : List.of(
                DingTalkProbeCode.TOKEN_REJECTED,
                DingTalkProbeCode.DEPARTMENT_REJECTED,
                DingTalkProbeCode.NETWORK_ERROR,
                DingTalkProbeCode.INVALID_RESPONSE)) {
            fakeProbe.alwaysReturnProbe(code);
            probe(token, "unsaved-key", "unsaved-secret", false).statusCode(200)
                    .body("success", equalTo(false))
                    .body("code", equalTo(code.name()))
                    .body("message", equalTo(code.message()))
                    .body("message", not(containsString("unsaved-secret")));
        }
    }

    @Test
    void fakeProbeCanBeDrivenFromE2eFormInput() {
        probe(adminToken(), "e2e-key", "fake-probe:TOKEN_REJECTED", false)
                .statusCode(200)
                .body("success", equalTo(false))
                .body("code", equalTo("TOKEN_REJECTED"));
        assertThat(lastProbeCall()).isEqualTo(
                new FakeDingTalkProbeClient.ProbeCall("e2e-key", "fake-probe:TOKEN_REJECTED"));
    }

    @Test
    void probeDoesNotWriteConfigAuditUsersTeachersOrNotifications() {
        String token = adminToken();
        saveConfig(token, true, "saved-key", "saved-secret");
        long auditsBefore = auditLogs.count();
        long notificationLogsBefore = notificationLogs.count();
        int usersBefore = as(token).when().get("/api/users")
                .then()
                .statusCode(200).extract().jsonPath().getList("$").size();
        int teachersBefore = as(token).when().get("/api/teachers")
                .then()
                .statusCode(200).extract().jsonPath().getList("$").size();

        probe(token, "unsaved-key", "unsaved-secret", false).statusCode(200)
                .body("success", equalTo(true));

        as(token).when().get("/api/integrations/dingtalk/config")
                .then()
                .statusCode(200)
                .body("enabled", equalTo(true))
                .body("appKey", equalTo("saved-key"))
                .body("hasSecret", equalTo(true));
        assertThat(auditLogs.count()).isEqualTo(auditsBefore);
        assertThat(notificationLogs.count()).isEqualTo(notificationLogsBefore);
        assertThat(as(token).when().get("/api/users")
                .then()
                .statusCode(200).extract().jsonPath().getList("$").size())
                .isEqualTo(usersBefore);
        assertThat(as(token).when().get("/api/teachers")
                .then()
                .statusCode(200).extract().jsonPath().getList("$").size())
                .isEqualTo(teachersBefore);
    }

    private io.restassured.response.ValidatableResponse probe(
            String token, String appKey, String appSecret, Boolean useSavedSecret) {
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("appKey", appKey);
        body.put("appSecret", appSecret);
        if (useSavedSecret != null) {
            body.put("useSavedSecret", useSavedSecret);
        }
        return as(token).body(body)
                .when().post("/api/integrations/dingtalk/test-connection")
                .then();
    }

    private void saveConfig(String token, boolean enabled, String appKey, String appSecret) {
        as(token).body(Map.of(
                        "enabled", enabled,
                        "appKey", appKey,
                        "appSecret", appSecret,
                        "agentId", "agent",
                        "corpId", "corp",
                        "syncStrategy", "dingtalk"))
                .when().put("/api/integrations/dingtalk/config")
                .then()
                .statusCode(200);
    }

    private FakeDingTalkProbeClient.ProbeCall lastProbeCall() {
        List<FakeDingTalkProbeClient.ProbeCall> calls = fakeProbe.probeCalls();
        return calls.get(calls.size() - 1);
    }
}
