package com.paiban.integration;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.containsString;

import com.paiban.identity.domain.AppUserRepository;
import com.paiban.integration.dingtalk.FakeDingTalkClient;
import com.paiban.integration.dingtalk.DingTalkApiException;
import com.paiban.support.ApiIntegrationTest;
import com.paiban.support.TestData;
import io.restassured.response.Response;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/** Public confirmation contract: explicit selection, immediate revocation and one account audit. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:paiban_sync_deactivation;MODE=PostgreSQL;"
            + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
class DingTalkSyncDeactivationIntegrationTest extends ApiIntegrationTest {

    private static final String PASSWORD = "Sync04-Test-Password9!";

    @Autowired
    private FakeDingTalkClient fakeDingTalk;

    @Autowired
    private AppUserRepository accounts;

    private final List<Long> createdAccountIds = new ArrayList<>();

    @BeforeEach
    void resetExternalDirectoryAndSettings() {
        resetIntegrationSettings();
        fakeDingTalk.clearDirectory();
        configureIntegration();
    }

    @AfterEach
    void cleanOwnedAccountsAndExternalState() {
        for (Long accountId : createdAccountIds) {
            accounts.findById(accountId).ifPresent(accounts::delete);
        }
        accounts.flush();
        createdAccountIds.clear();
        resetIntegrationSettings();
        fakeDingTalk.resetToDefault();
    }

    @Test
    void confirmationDisablesOnlySelectedAccountAndRevokesItsExistingSession() {
        String admin = adminToken();
        long selectedTeacher = teacher(admin, "待停用教师", "sync04-selected");
        long excludedTeacher = teacher(admin, "本次排除教师", "sync04-excluded");
        String selectedName = "sync04-selected-" + TestData.suffix();
        String excludedName = "sync04-excluded-" + TestData.suffix();
        long selectedAccount = linkedAccount(admin, selectedName, selectedTeacher);
        long excludedAccount = linkedAccount(admin, excludedName, excludedTeacher);
        String selectedSession = login(selectedName, PASSWORD);
        String excludedSession = login(excludedName, PASSWORD);
        as(selectedSession).when().get("/api/auth/me").then().statusCode(200);

        // Both teachers are missing from the complete empty directory, but the
        // administrator explicitly selects only the first account for this run.
        Response confirmation = as(admin).body(Map.of(
                        "excludedDepartments", List.of(),
                        "excludedUserIds", List.of(),
                        "suspectedDecisions", List.of(),
                        "deactivationSelections", List.of(Map.of(
                                "accountId", selectedAccount,
                                "teacherId", selectedTeacher,
                                "dingtalkUserId", "sync04-selected"))))
                .when().post("/api/integrations/dingtalk/sync/confirm");
        confirmation.then().statusCode(200);

        as(admin).when().get("/api/users")
                .then().statusCode(200)
                .body("find { it.id == " + selectedAccount + " }.enabled", equalTo(false))
                .body("find { it.id == " + excludedAccount + " }.enabled", equalTo(true));
        confirmation.then().body("accountDisabled", equalTo(1));
        as(selectedSession).when().get("/api/auth/me").then().statusCode(401);
        as(excludedSession).when().get("/api/auth/me").then().statusCode(200);

        as(admin).when().get("/api/teachers")
                .then().statusCode(200)
                .body("find { it.id == " + selectedTeacher + " }.dingtalkUserId",
                        equalTo("sync04-selected"))
                .body("find { it.id == " + excludedTeacher + " }.dingtalkUserId",
                        equalTo("sync04-excluded"));
        as(admin).queryParam("action", "ACCOUNT_SYNC_DISABLE")
                .when().get("/api/account-audits")
                .then().statusCode(200)
                .body("total", equalTo(1))
                .body("items", hasSize(1))
                .body("items[0].targetUserId", equalTo((int) selectedAccount))
                .body("items[0].actor", equalTo("admin"))
                .body("items[0].before.enabled", equalTo(true))
                .body("items[0].after.enabled", equalTo(false));
    }

    @Test
    void incompleteRescanRejectsAnOldSelectionWithoutStoppingTheAccount() {
        String admin = adminToken();
        long teacherId = teacher(admin, "不完整扫描保护教师", "sync04-partial");
        long accountId = linkedAccount(admin, "sync04-partial-" + TestData.suffix(), teacherId);
        fakeDingTalk.failUserList(1L,
                new DingTalkApiException("读取测试部门", 41, 0, "部门暂时不可读"));

        as(admin).body(Map.of("deactivationSelections", List.of(Map.of(
                        "accountId", accountId,
                        "teacherId", teacherId,
                        "dingtalkUserId", "sync04-partial"))))
                .when().post("/api/integrations/dingtalk/sync/confirm")
                .then().statusCode(409)
                .body("message", containsString("扫描不完整"));

        as(admin).when().get("/api/users")
                .then().statusCode(200)
                .body("find { it.id == " + accountId + " }.enabled", equalTo(true));
        as(admin).queryParam("action", "ACCOUNT_SYNC_DISABLE")
                .when().get("/api/account-audits")
                .then().statusCode(200).body("total", equalTo(0));
    }

    @ParameterizedTest(name = "旧选择遇到{0}时须重新预演")
    @EnumSource(BindingChange.class)
    void changedBindingCannotReuseTheOldAccountSelection(BindingChange change) {
        String admin = adminToken();
        String oldUid = "sync04-binding-original";
        String newUid = "sync04-binding-new";
        String teacherName = "绑定变更原教师";
        long teacherId = teacher(admin, teacherName, oldUid);
        long accountId = linkedAccount(admin, "sync04-binding-" + TestData.suffix(), teacherId);
        Map<String, Object> oldSelection = Map.of(
                "accountId", accountId, "teacherId", teacherId, "dingtalkUserId", oldUid);
        as(admin).when().post("/api/integrations/dingtalk/sync/preview")
                .then().statusCode(200)
                .body("deactivations.find { it.accountId == " + accountId + " }.teacherId",
                        equalTo((int) teacherId))
                .body("deactivations.find { it.accountId == " + accountId + " }.dingtalkUserId",
                        equalTo(oldUid));

        long currentTeacherId = teacherId;
        if (change == BindingChange.ACCOUNT_TEACHER) {
            currentTeacherId = teacher(admin, "新关联教师", newUid);
            as(admin).body(Map.of("teacherId", currentTeacherId))
                    .when().put("/api/users/" + accountId).then().statusCode(200);
        } else {
            as(admin).body(Map.of("name", teacherName, "dingtalkUserId", newUid))
                    .when().put("/api/teachers/" + teacherId).then().statusCode(200);
        }

        // The new binding is also absent from the directory. Checking only the
        // account ID would wrongly accept the old administrator confirmation.
        as(admin).body(Map.of("deactivationSelections", List.of(oldSelection)))
                .when().post("/api/integrations/dingtalk/sync/confirm")
                .then().statusCode(409).body("message", containsString("重新预演"));
        as(admin).when().get("/api/users")
                .then().statusCode(200)
                .body("find { it.id == " + accountId + " }.enabled", equalTo(true))
                .body("find { it.id == " + accountId + " }.teacherId", equalTo((int) currentTeacherId));
        as(admin).queryParam("action", "ACCOUNT_SYNC_DISABLE")
                .when().get("/api/account-audits")
                .then().statusCode(200).body("total", equalTo(0));
    }

    private enum BindingChange { ACCOUNT_TEACHER, TEACHER_UID }

    @ParameterizedTest(name = "非法停用选择 {0} 不得产生写入")
    @EnumSource(InvalidSelection.class)
    void malformedOrRepeatedSelectionsAreRejectedBeforeWriting(InvalidSelection invalid) {
        String admin = adminToken();
        long teacherId = teacher(admin, "非法选择保护教师", "sync04-invalid");
        long accountId = linkedAccount(admin, "sync04-invalid-" + TestData.suffix(), teacherId);
        Map<String, Object> selection = new java.util.HashMap<>(Map.of(
                "accountId", accountId, "teacherId", teacherId, "dingtalkUserId", "sync04-invalid"));
        List<Map<String, Object>> selections;
        switch (invalid) {
            case NULL_ITEM -> selections = java.util.Collections.singletonList(null);
            case DUPLICATE -> selections = List.of(selection, selection);
            default -> {
                switch (invalid) {
                    case MISSING_ACCOUNT -> selection.remove("accountId");
                    case NEGATIVE_ACCOUNT -> selection.put("accountId", -1);
                    case MISSING_TEACHER -> selection.remove("teacherId");
                    case MISSING_UID -> selection.remove("dingtalkUserId");
                    case BLANK_UID -> selection.put("dingtalkUserId", " ");
                    default -> throw new IllegalArgumentException("Unhandled case: " + invalid);
                }
                selections = List.of(selection);
            }
        }
        as(admin).body(Map.of("deactivationSelections", selections))
                .when().post("/api/integrations/dingtalk/sync/confirm")
                .then().statusCode(400);
        as(admin).when().get("/api/users").then().statusCode(200)
                .body("find { it.id == " + accountId + " }.enabled", equalTo(true));
        as(admin).queryParam("action", "ACCOUNT_SYNC_DISABLE")
                .when().get("/api/account-audits").then().statusCode(200).body("total", equalTo(0));
    }

    private enum InvalidSelection {
        NULL_ITEM, DUPLICATE, MISSING_ACCOUNT, NEGATIVE_ACCOUNT, MISSING_TEACHER, MISSING_UID, BLANK_UID
    }

    private void configureIntegration() {
        as(adminToken()).body(Map.of(
                        "enabled", true,
                        "appKey", "sync04-test-key",
                        "appSecret", "sync04-test-secret",
                        "agentId", "1000"))
                .when().put("/api/integrations/dingtalk/config")
                .then().statusCode(200);
    }

    private long teacher(String token, String name, String externalId) {
        return as(token).body(Map.of("name", name, "dingtalkUserId", externalId))
                .when().post("/api/teachers")
                .then().statusCode(200).extract().jsonPath().getLong("id");
    }

    private long linkedAccount(String token, String accountName, long teacherId) {
        long accountId = createUser(token, accountName, PASSWORD, "teacher");
        createdAccountIds.add(accountId);
        as(token).body(Map.of("teacherId", teacherId))
                .when().put("/api/users/" + accountId)
                .then().statusCode(200);
        return accountId;
    }
}
