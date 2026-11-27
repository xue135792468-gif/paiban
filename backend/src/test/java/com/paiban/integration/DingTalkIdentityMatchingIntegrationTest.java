package com.paiban.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

import com.paiban.identity.domain.AppUser;
import com.paiban.identity.domain.AppUserRepository;
import com.paiban.identity.domain.LoginType;
import com.paiban.integration.dingtalk.FakeDingTalkClient;
import com.paiban.integration.domain.DingUser;
import com.paiban.support.ApiIntegrationTest;
import com.paiban.support.TestData;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * 03-A3 有界回归：免登「谁是谁」的六条既有规则，全部从公开 HTTP 与假外部 Adapter 进入。
 *
 * <p>身份来自 {@code FakeDingTalkClient.addUser} 与 {@code fake:<jobNumber>}，前端不自报姓名；
 * 不调用 provisioner 的私有配对方法，不 mock 内部 Service，也不断言调用次数。真实数据库只在
 * 「用户名被占用时原账号哈希未变」这一处被直接读取——那是浏览器里看不到的既有账号事实。
 *
 * <p>每个用例都重置集成设置与假通讯录；账号表不随业务表清空（{@code DatabaseResetExtension}
 * 保留 {@code app_user}），所以清理只删本用例拥有的教师关联账号和自建的占位账号，不动 admin。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:paiban_identity_matching;MODE=PostgreSQL;"
            + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
class DingTalkIdentityMatchingIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private FakeDingTalkClient fakeDingTalk;

    @Autowired
    private AppUserRepository accounts;

    private final List<Long> ownedTeachers = new ArrayList<>();

    private final List<Long> ownedAccounts = new ArrayList<>();

    @BeforeEach
    void configureFakeTenant() {
        resetIntegrationSettings();
        fakeDingTalk.clearDirectory();
        as(adminToken()).body(Map.of(
                        "enabled", true, "appKey", "matching-key", "appSecret", "matching-secret",
                        "agentId", "1000", "corpId", "matching-corp"))
                .when().put("/api/integrations/dingtalk/config").then().statusCode(200);
    }

    @AfterEach
    void removeOnlyWhatThisFixtureOwns() {
        for (long teacherId : ownedTeachers) {
            accounts.deleteAll(accounts.findAllByTeacherId(teacherId));
        }
        for (long accountId : ownedAccounts) {
            accounts.findById(accountId).ifPresent(accounts::delete);
        }
        accounts.flush();
        ownedTeachers.clear();
        ownedAccounts.clear();
        resetIntegrationSettings();
        fakeDingTalk.resetToDefault();
    }

    /**
     * 规则 1：同一 UID 挂在两份未软删档案上（历史脏数据，root 接口不设唯一约束）时，
     * 免登必须按 UID 判歧义，而不是任选一份绑定。
     */
    @Test
    void duplicateUidOnTwoLiveTeachersIsRejectedWithoutCreatingAnAccount() {
        String suffix = TestData.suffix();
        String duplicatedUid = "dup-uid-" + suffix;
        String firstName = "重复绑定甲" + suffix;
        String secondName = "重复绑定乙" + suffix;
        long first = createTeacher(firstName, duplicatedUid, null);
        long second = createTeacher(secondName, duplicatedUid, null);
        String session = adminToken();
        addFakeUser(duplicatedUid, firstName, "DUP-" + suffix);

        dingTalkLogin("DUP-" + suffix).then().statusCode(401)
                .body("status", equalTo(401))
                .body("code", equalTo("AMBIGUOUS_IDENTITY"));

        // 拒绝发生在开号与绑定之前：两份档案仍然各自挂着原 UID，也没有任何账号被建出来。
        as(session).when().get("/api/teachers").then().statusCode(200)
                .body("find { it.id == " + first + " }.dingtalkUserId", equalTo(duplicatedUid))
                .body("find { it.id == " + second + " }.dingtalkUserId", equalTo(duplicatedUid));
        as(session).when().get("/api/users").then().statusCode(200)
                .body("findAll { it.teacherId == " + first + " || it.teacherId == " + second + " }",
                        hasSize(0));
        expectNoAutomaticDingTalkAudit(session);
    }

    /**
     * 规则 2：同名两人中一人已绑定另一个 UID（且挂着管理账号），另一人未绑定。
     * 按原规格「未绑定 UID 且未软删」的同名集合只有一人，普通老师自动配对，
     * 绝不把已绑定的管理员档案当成候选。
     */
    @Test
    void sameNameWithOneAlreadyBoundPicksTheUnboundOrdinaryTeacher() {
        String suffix = TestData.suffix();
        String name = "同名已绑定" + suffix;
        String boundUid = "bound-uid-" + suffix;
        String targetUid = "target-uid-" + suffix;
        long boundTeacher = createTeacher(name, boundUid, null);
        long boundAdminAccount = createAccount(
                "bound_admin_" + suffix, "admin-pass-" + suffix, "已绑定管理员", List.of("admin"), boundTeacher);
        long ordinaryTeacher = createTeacher(name, null, null);
        String session = adminToken();
        addFakeUser(targetUid, name, "SAME-" + suffix);

        Response login = dingTalkLogin("SAME-" + suffix).then().statusCode(200)
                .body("user.teacherId", equalTo((int) ordinaryTeacher))
                .body("user.loginType", equalTo("DINGTALK"))
                .body("user.roles", equalTo(List.of("teacher")))
                .extract().response();
        long provisioned = login.jsonPath().getLong("user.id");
        assertThat(provisioned).isNotEqualTo(boundAdminAccount);

        // UID 只落到未绑定的普通老师身上；已绑定的管理员档案与它的账号都不动。
        as(session).when().get("/api/teachers").then().statusCode(200)
                .body("find { it.id == " + ordinaryTeacher + " }.dingtalkUserId", equalTo(targetUid))
                .body("find { it.id == " + boundTeacher + " }.dingtalkUserId", equalTo(boundUid));
        as(session).when().get("/api/users").then().statusCode(200)
                .body("findAll { it.teacherId == " + ordinaryTeacher + " }", hasSize(1))
                .body("find { it.id == " + boundAdminAccount + " }.enabled", equalTo(true))
                .body("find { it.id == " + boundAdminAccount + " }.loginType", equalTo("PASSWORD"))
                .body("find { it.id == " + boundAdminAccount + " }.teacherId", equalTo((int) boundTeacher));
    }

    /**
     * 规则 3：同名两人都未绑定，其中一人关联管理账号。歧义判定必须先按规格计数，
     * 不能先把受保护档案过滤掉再声称「唯一」。
     */
    @Test
    void sameNameWhereOnlyOneCandidateIsProtectedStillReportsAmbiguity() {
        String suffix = TestData.suffix();
        String name = "同名含管理员" + suffix;
        String uid = "protected-uid-" + suffix;
        long protectedTeacher = createTeacher(name, null, null);
        long adminAccount = createAccount(
                "protected_admin_" + suffix, "admin-pass-" + suffix, "受保护管理员", List.of("admin"), protectedTeacher);
        long ordinaryTeacher = createTeacher(name, null, null);
        String session = adminToken();
        addFakeUser(uid, name, "PROT-" + suffix);

        dingTalkLogin("PROT-" + suffix).then().statusCode(401)
                .body("code", equalTo("AMBIGUOUS_IDENTITY"));

        as(session).when().get("/api/teachers").then().statusCode(200)
                .body("find { it.id == " + protectedTeacher + " }.dingtalkUserId", equalTo(null))
                .body("find { it.id == " + ordinaryTeacher + " }.dingtalkUserId", equalTo(null));
        as(session).when().get("/api/users").then().statusCode(200)
                .body("findAll { it.teacherId == " + ordinaryTeacher + " }", hasSize(0))
                .body("findAll { it.teacherId == " + protectedTeacher + " }", hasSize(1))
                .body("find { it.id == " + adminAccount + " }.loginType", equalTo("PASSWORD"));
        expectNoAutomaticDingTalkAudit(session);
    }

    /**
     * 规则 4：教师未启用只影响排课资格，不是账号启用状态。唯一未绑定的停用教师
     * 仍按 account.enabled 逻辑开号并可登录，档案本身保持停用。
     */
    @Test
    void disabledTeacherStillGetsAnEnabledAccountAndCanSignIn() {
        String suffix = TestData.suffix();
        String name = "停用教师" + suffix;
        String uid = "inactive-uid-" + suffix;
        long teacher = createTeacher(name, null, false);
        String session = adminToken();
        addFakeUser(uid, name, "INACT-" + suffix);

        Response login = dingTalkLogin("INACT-" + suffix).then().statusCode(200)
                .body("user.teacherId", equalTo((int) teacher))
                .body("user.enabled", equalTo(true))
                .body("user.loginType", equalTo("DINGTALK"))
                .extract().response();
        String token = login.jsonPath().getString("token");

        // 排课资格仍是停用，账号却是启用的：两者不是同一个开关。
        as(session).when().get("/api/teachers").then().statusCode(200)
                .body("find { it.id == " + teacher + " }.active", equalTo(false))
                .body("find { it.id == " + teacher + " }.dingtalkUserId", equalTo(uid));
        as(session).when().get("/api/users").then().statusCode(200)
                .body("findAll { it.teacherId == " + teacher + " }", hasSize(1))
                .body("findAll { it.teacherId == " + teacher + " }[0].enabled", equalTo(true))
                .body("findAll { it.teacherId == " + teacher + " }[0].loginType", equalTo("DINGTALK"));
        as(token).when().get("/api/auth/me").then().statusCode(200)
                .body("teacherId", equalTo((int) teacher))
                .body("loginType", equalTo("DINGTALK"));
    }

    /**
     * 规则 5：目标账号名已被一个无关的手工密码账号占用时，不得复用那个账号。
     * 新账号换名（仍在 64 字符内）、指向正确教师与 DINGTALK 类型，原账号原封不动。
     */
    @Test
    void occupiedAccountNameIsNotReused() {
        String suffix = TestData.suffix();
        String name = "账号名被占用" + suffix;
        String uid = "collision-uid-" + suffix;
        long teacher = createTeacher(name, null, null);
        String occupiedUsername = "dt_" + teacher;
        String occupiedPassword = "occupied-pass-" + suffix;
        long occupiedAccount = createAccount(occupiedUsername, occupiedPassword, "无关手工账号",
                List.of("teacher"), null);
        String occupiedHash = accounts.findById(occupiedAccount).orElseThrow().getPasswordHash();
        String session = adminToken();
        addFakeUser(uid, name, "COLL-" + suffix);

        Response login = dingTalkLogin("COLL-" + suffix).then().statusCode(200)
                .body("user.loginType", equalTo("DINGTALK"))
                .body("user.teacherId", equalTo((int) teacher))
                .extract().response();
        long provisioned = login.jsonPath().getLong("user.id");
        String provisionedUsername = login.jsonPath().getString("user.username");

        assertThat(provisioned).isNotEqualTo(occupiedAccount);
        assertThat(provisionedUsername).isNotEqualTo(occupiedUsername);
        assertThat(provisionedUsername).hasSizeLessThanOrEqualTo(64);

        // 原账号在库里没被改名、没被改成免登、没被挂到这位老师身上；哈希逐字不变。
        AppUser untouched = accounts.findById(occupiedAccount).orElseThrow();
        assertThat(untouched.getPasswordHash()).isEqualTo(occupiedHash);
        assertThat(untouched.getLoginType()).isEqualTo(LoginType.PASSWORD);
        assertThat(untouched.getTeacherId()).isNull();
        assertThat(untouched.getUsername()).isEqualTo(occupiedUsername);

        // 它仍然是一个可用的密码账号（公开入口再确认一次没有被免登流程接管）。
        RestAssured.given().contentType(ContentType.JSON)
                .body(Map.of("username", occupiedUsername, "password", occupiedPassword))
                .when().post("/api/auth/login").then().statusCode(200)
                .body("user.id", equalTo((int) occupiedAccount))
                .body("user.teacherId", equalTo(null))
                .body("user.loginType", equalTo("PASSWORD"));
        as(session).when().get("/api/users").then().statusCode(200)
                .body("findAll { it.teacherId == " + teacher + " }", hasSize(1))
                .body("findAll { it.teacherId == " + teacher + " }[0].id", equalTo((int) provisioned));
    }

    /**
     * 规则 6：教师已由超级管理员显式绑定 UID，且有两个启用中的历史密码账号时，
     * 稳定复用最小 ID；停用它之后复用一个启用账号，而不是再开一个新号。
     * 这种复用不是「配对」，因此没有伪 LINK，也不改变登录类型。
     */
    @Test
    void boundTeacherReusesTheLowestAccountIdAndFallsBackWhenItIsDisabled() {
        String suffix = TestData.suffix();
        String name = "历史双账号" + suffix;
        String uid = "stable-uid-" + suffix;
        long teacher = createTeacher(name, uid, null);
        long firstAccount = createAccount(
                "hist_one_" + suffix, "first-pass-" + suffix, name, List.of("teacher"), teacher);
        long secondAccount = createAccount(
                "hist_two_" + suffix, "second-pass-" + suffix, name, List.of("teacher"), teacher);
        long lowest = Math.min(firstAccount, secondAccount);
        long highest = Math.max(firstAccount, secondAccount);
        String session = adminToken();
        addFakeUser(uid, name, "HIST-" + suffix);

        dingTalkLogin("HIST-" + suffix).then().statusCode(200)
                .body("user.id", equalTo((int) lowest))
                .body("user.teacherId", equalTo((int) teacher))
                .body("user.loginType", equalTo("PASSWORD"));

        as(session).when().get("/api/users").then().statusCode(200)
                .body("findAll { it.teacherId == " + teacher + " }", hasSize(2));
        expectNoAutomaticDingTalkAudit(session);

        // 停用被选中的那个账号后，复用的是另一个启用账号，账号总数不增加。
        as(session).body(Map.of("enabled", false))
                .when().put("/api/users/" + lowest).then().statusCode(200)
                .body("enabled", equalTo(false));

        dingTalkLogin("HIST-" + suffix).then().statusCode(200)
                .body("user.id", equalTo((int) highest))
                .body("user.teacherId", equalTo((int) teacher))
                .body("user.loginType", equalTo("PASSWORD"));

        as(session).when().get("/api/users").then().statusCode(200)
                .body("findAll { it.teacherId == " + teacher + " }", hasSize(2))
                .body("find { it.id == " + highest + " }.enabled", equalTo(true))
                .body("find { it.id == " + highest + " }.loginType", equalTo("PASSWORD"))
                .body("find { it.id == " + lowest + " }.loginType", equalTo("PASSWORD"));
        as(session).when().get("/api/teachers").then().statusCode(200)
                .body("find { it.id == " + teacher + " }.dingtalkUserId", equalTo(uid));
        expectNoAutomaticDingTalkAudit(session);
    }

    // ---------- fixtures ----------

    /** 管理端建档案；{@code dingtalkUserId} 为 null 表示保持未绑定。 */
    private long createTeacher(String name, String dingtalkUserId, Boolean active) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        if (dingtalkUserId != null) {
            body.put("dingtalkUserId", dingtalkUserId);
        }
        if (active != null) {
            body.put("active", active);
        }
        Response response = as(adminToken()).body(body)
                .when().post("/api/teachers").then().statusCode(200).extract().response();
        long id = response.jsonPath().getLong("id");
        ownedTeachers.add(id);
        return id;
    }

    /** 手工密码账号，可显式关联教师，也可与任何教师无关。 */
    private long createAccount(
            String username, String password, String displayName, List<String> roleCodes, Long teacherId) {
        Map<String, Object> body = new HashMap<>();
        body.put("username", username);
        body.put("password", password);
        body.put("displayName", displayName);
        body.put("roleCodes", roleCodes);
        if (teacherId != null) {
            body.put("teacherId", teacherId);
        }
        Response response = as(adminToken()).body(body)
                .when().post("/api/users").then().statusCode(200).extract().response();
        long id = response.jsonPath().getLong("id");
        // 关联本用例教师的账号由教师清理带走；无关账号单独记账。
        if (teacherId == null) {
            ownedAccounts.add(id);
        }
        return id;
    }

    /** 假通讯录里的真实身份；authCode 用它自带的工号进入免登。 */
    private void addFakeUser(String userId, String name, String jobNumber) {
        fakeDingTalk.addUser(FakeDingTalkClient.ROOT_DEPARTMENT_ID,
                new DingUser(userId, name, null, null, jobNumber));
    }

    private Response dingTalkLogin(String jobNumber) {
        return RestAssured.given().contentType(ContentType.JSON)
                .body(Map.of("authCode", "fake:" + jobNumber))
                .when().post("/api/auth/dingtalk");
    }

    /** 免登若没写账号，就不该留下自动开号或配对事件。 */
    private void expectNoAutomaticDingTalkAudit(String session) {
        as(session).queryParam("action", "ACCOUNT_DINGTALK_AUTO_PROVISION")
                .when().get("/api/account-audits").then().statusCode(200)
                .body("total", equalTo(0));
        as(session).queryParam("action", "ACCOUNT_DINGTALK_LINK")
                .when().get("/api/account-audits").then().statusCode(200)
                .body("total", equalTo(0));
    }
}
