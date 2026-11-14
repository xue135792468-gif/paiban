package com.paiban.identity;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.assertj.core.api.Assertions.assertThat;

import com.paiban.identity.domain.AppUserRepository;
import com.paiban.support.ApiIntegrationTest;
import com.paiban.support.TestData;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 票 02：每一次账号操作都在同一事务里留下逐目标账号的审计，超级管理员能在
 * {@code /api/account-audits} 按时间倒序、按动作与目标账号筛选查看。
 *
 * <p>本类用独立 H2 库：{@code app_user} 不在 {@code DatabaseResetExtension} 的清空名单里，
 * 与其它测试共用库会互相看到对方建的账号；这里仍在每个用例后删除自己建的账号并恢复预置 admin。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:paiban_account_audit;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
            + "DB_CLOSE_DELAY=-1"
})
class AccountAuditIntegrationTest extends ApiIntegrationTest {

    private static final String PASSWORD = "audit-pw-123";

    @Autowired
    private AppUserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private com.paiban.identity.service.UserService userService;

    private final List<String> createdUsernames = new ArrayList<>();

    @AfterEach
    void removeCreatedAccounts() {
        for (String username : createdUsernames) {
            userRepository.findByUsername(username).ifPresent(userRepository::delete);
        }
        createdUsernames.clear();
        userRepository.flush();
    }

    @Test
    void manualAccountCreationIsAuditedOnceWithTheTargetAccount() {
        String token = adminToken();
        String username = createTrackedUser(token, "teacher");
        long id = userId(token, username);

        as(token).when().get("/api/account-audits")
                .then().statusCode(200)
                .body("total", equalTo(1))
                .body("items", hasSize(1))
                .body("items[0].action", equalTo("ACCOUNT_CREATE"))
                .body("items[0].actor", equalTo("admin"))
                .body("items[0].targetUserId", equalTo((int) id))
                .body("items[0].targetUsername", equalTo(username))
                .body("items[0].before", nullValue())
                .body("items[0].after.enabled", equalTo(true))
                .body("items[0].after.roleCodes", contains("teacher"))
                .body("items[0].after.displayName", equalTo(username));
    }

    @Test
    void identityChangeIsItsOwnActionAndRecordsBeforeAndAfterOfTheTargetAccount() {
        String token = adminToken();
        String username = createTrackedUser(token, "teacher");
        long id = userId(token, username);

        as(token).body(Map.of("roleCodes", List.of("admin")))
                .when().put("/api/users/" + id)
                .then().statusCode(200);

        as(token).queryParam("action", "ACCOUNT_ROLE_CHANGE").when().get("/api/account-audits")
                .then().statusCode(200)
                .body("total", equalTo(1))
                .body("items[0].action", equalTo("ACCOUNT_ROLE_CHANGE"))
                .body("items[0].targetUserId", equalTo((int) id))
                .body("items[0].targetUsername", equalTo(username))
                .body("items[0].before.roleCodes", contains("teacher"))
                .body("items[0].after.roleCodes", contains("admin"))
                .body("items[0].before.enabled", equalTo(true))
                .body("items[0].after.enabled", equalTo(true));
        as(token).queryParam("action", "ACCOUNT_UPDATE").when().get("/api/account-audits")
                .then().statusCode(200)
                .body("total", equalTo(0));
    }

    /** 只改姓名 / 关联教师而身份不变，是「修改资料」；不得冒用「改身份」。 */
    @Test
    void profileOnlyChangeIsAnUpdateNotARoleChange() {
        String token = adminToken();
        String username = createTrackedUser(token, "teacher");
        long id = userId(token, username);

        as(token).body(Map.of("displayName", "改过资料"))
                .when().put("/api/users/" + id)
                .then().statusCode(200);

        as(token).queryParam("action", "ACCOUNT_UPDATE").when().get("/api/account-audits")
                .then().statusCode(200)
                .body("total", equalTo(1))
                .body("items[0].action", equalTo("ACCOUNT_UPDATE"))
                .body("items[0].before.displayName", equalTo(username))
                .body("items[0].after.displayName", equalTo("改过资料"))
                .body("items[0].after.roleCodes", contains("teacher"));
        as(token).queryParam("action", "ACCOUNT_ROLE_CHANGE").when().get("/api/account-audits")
                .then().statusCode(200)
                .body("total", equalTo(0));
    }

    /**
     * 复合编辑（改名 + 改身份 + 停用）必须形成两条连续、不重叠的事件：
     * 第一条从旧资料到新资料（启用状态仍是旧值），第二条从新资料 + 旧启用状态到新资料 + 新启用状态。
     * 第二条不能把整单初始快照再传一次，否则身份变化被记两遍、前后口径重叠。
     */
    @Test
    void compositeEditRecordsConsecutiveNonOverlappingEvents() {
        String token = adminToken();
        String username = createTrackedUser(token, "teacher");
        long id = userId(token, username);

        as(token).body(Map.of(
                        "displayName", "复核改名",
                        "roleCodes", List.of("admin"),
                        "enabled", false))
                .when().put("/api/users/" + id)
                .then().statusCode(200);

        as(token).when().queryParam("targetUserId", id).get("/api/account-audits")
                .then().statusCode(200)
                .body("total", equalTo(3))
                .body("items[0].action", equalTo("ACCOUNT_DISABLE"))
                .body("items[0].before.displayName", equalTo("复核改名"))
                .body("items[0].before.roleCodes", contains("admin"))
                .body("items[0].before.enabled", equalTo(true))
                .body("items[0].after.displayName", equalTo("复核改名"))
                .body("items[0].after.roleCodes", contains("admin"))
                .body("items[0].after.enabled", equalTo(false))
                .body("items[1].action", equalTo("ACCOUNT_ROLE_CHANGE"))
                .body("items[1].before.displayName", equalTo(username))
                .body("items[1].before.roleCodes", contains("teacher"))
                .body("items[1].before.enabled", equalTo(true))
                .body("items[1].after.displayName", equalTo("复核改名"))
                .body("items[1].after.roleCodes", contains("admin"))
                .body("items[1].after.enabled", equalTo(true))
                .body("items[2].action", equalTo("ACCOUNT_CREATE"));

        as(token).queryParam("action", "ACCOUNT_DISABLE").queryParam("targetUserId", id)
                .when().get("/api/account-audits")
                .then().statusCode(200)
                .body("total", equalTo(1));
        as(token).queryParam("action", "ACCOUNT_ROLE_CHANGE").queryParam("targetUserId", id)
                .when().get("/api/account-audits")
                .then().statusCode(200)
                .body("total", equalTo(1));
    }

    @Test
    void passwordResetIsAuditedAndNeverStoresThePasswordOrHash() {
        String token = adminToken();
        String username = createTrackedUser(token, "teacher");
        long id = userId(token, username);
        String newPassword = "ResetPw9871";

        as(token).body(Map.of("newPassword", newPassword))
                .when().post("/api/users/" + id + "/reset-password")
                .then().statusCode(200);

        String body = as(token).when().get("/api/account-audits")
                .then().statusCode(200)
                .body("total", equalTo(2))
                .body("items[0].action", equalTo("ACCOUNT_RESET_PASSWORD"))
                .body("items[0].targetUserId", equalTo((int) id))
                .body("items[0].detail", containsString("重置密码"))
                .extract().asString();
        assertThat(body).doesNotContain(newPassword, "password", "passwordHash", "$2a$", "$2b$");

        Integer leaked = jdbc.queryForObject(
                "select count(*) from audit_log where detail like ? or before_json like ? or after_json like ?",
                Integer.class, "%" + newPassword + "%", "%" + newPassword + "%", "%" + newPassword + "%");
        assertThat(leaked).as("密码不得进入审计表").isZero();
    }

    @Test
    void pausingAndResumingEachLeaveOneAuditEventWithTheStateChange() {
        String token = adminToken();
        String username = createTrackedUser(token, "teacher");
        long id = userId(token, username);

        as(token).when().post("/api/users/" + id + "/disable").then().statusCode(200);
        as(token).when().post("/api/users/" + id + "/enable").then().statusCode(200);

        as(token).queryParam("action", "ACCOUNT_DISABLE").when().get("/api/account-audits")
                .then().statusCode(200)
                .body("total", equalTo(1))
                .body("items[0].before.enabled", equalTo(true))
                .body("items[0].after.enabled", equalTo(false));
        as(token).queryParam("action", "ACCOUNT_ENABLE").when().get("/api/account-audits")
                .then().statusCode(200)
                .body("total", equalTo(1))
                .body("items[0].before.enabled", equalTo(false))
                .body("items[0].after.enabled", equalTo(true));
    }

    @Test
    void listIsNewestFirstAndFiltersByActionAndTargetAtTheDatabaseLayer() {
        String token = adminToken();
        String first = createTrackedUser(token, "teacher");
        String second = createTrackedUser(token, "teacher");
        createTrackedUser(token, "teacher");
        long secondId = userId(token, second);
        as(token).when().post("/api/users/" + secondId + "/disable").then().statusCode(200);

        // 四类事件：3 条建号 + 1 条停用；最新的一条（停用）排在最前。
        as(token).when().get("/api/account-audits")
                .then().statusCode(200)
                .body("total", equalTo(4))
                .body("items", hasSize(4))
                .body("items[0].action", equalTo("ACCOUNT_DISABLE"))
                .body("items[1].action", equalTo("ACCOUNT_CREATE"))
                .body("items[2].action", equalTo("ACCOUNT_CREATE"))
                .body("items[3].action", equalTo("ACCOUNT_CREATE"));

        as(token).queryParam("action", "ACCOUNT_CREATE").when().get("/api/account-audits")
                .then().statusCode(200)
                .body("total", equalTo(3))
                .body("items.action", everyItem(equalTo("ACCOUNT_CREATE")));

        as(token).queryParam("targetUserId", secondId).when().get("/api/account-audits")
                .then().statusCode(200)
                .body("total", equalTo(2))
                .body("items.targetUserId", everyItem(equalTo((int) secondId)))
                .body("items.targetUsername", everyItem(equalTo(second)));

        as(token).queryParam("action", "ACCOUNT_CREATE").queryParam("targetUserId", secondId)
                .when().get("/api/account-audits")
                .then().statusCode(200)
                .body("total", equalTo(1))
                .body("items[0].after.roleCodes", contains("teacher"));

        // 分页在数据库层：第一页给 2 条、第二页给剩下的 2 条，总数不随页变化。
        as(token).queryParam("page", 1).queryParam("size", 2).when().get("/api/account-audits")
                .then().statusCode(200)
                .body("page", equalTo(1))
                .body("size", equalTo(2))
                .body("total", equalTo(4))
                .body("items", hasSize(2));
        as(token).queryParam("page", 2).queryParam("size", 2).when().get("/api/account-audits")
                .then().statusCode(200)
                .body("total", equalTo(4))
                .body("items", hasSize(2));

        as(token).queryParam("action", "NOT_A_REAL_ACTION").when().get("/api/account-audits")
                .then().statusCode(400);
        assertThat(first).isNotBlank();
    }

    @Test
    void academicAdminAndTeacherCannotReadAccountAudits() {
        String token = adminToken();
        createTrackedUser(token, "teacher");
        String academic = createTrackedUser(token, "admin");
        String teacher = createTrackedUser(token, "teacher");
        String academicToken = login(academic, PASSWORD);
        String teacherToken = login(teacher, PASSWORD);

        for (String caller : List.of(academicToken, teacherToken)) {
            as(caller).when().get("/api/account-audits").then().statusCode(403);
            as(caller).when().get("/api/account-audits/actions").then().statusCode(403);
        }
        as(token).when().get("/api/account-audits/actions")
                .then().statusCode(200)
                .body("size()", is(10))
                .body("find { it.code == 'ACCOUNT_CREATE' }.label", equalTo("手工创建账号"))
                .body("find { it.code == 'ACCOUNT_ROLE_CHANGE' }.label", equalTo("改身份"))
                .body("find { it.code == 'ACCOUNT_UPDATE' }.label", equalTo("修改资料"))
                // 后续票据的写入入口已预留：只出现在动作目录，不产生假实现。
                .body("collect { it.code }", org.hamcrest.Matchers.hasItems(
                        "ACCOUNT_DINGTALK_AUTO_PROVISION",
                        "ACCOUNT_DINGTALK_LINK",
                        "ACCOUNT_SYNC_DISABLE"));
    }

    @Test
    void academicAdminCannotReachAccountEventsThroughTheGenericFoundationLog() throws Exception {
        String token = adminToken();
        String username = createTrackedUser(token, "teacher");
        // 一条真实的非账号基础日志：教师 Excel 导入。
        byte[] file = com.paiban.support.ExcelFixtures.workbook(
                List.of("姓名"), List.of(List.of("审计基础数据" + TestData.suffix())));
        io.restassured.RestAssured.given()
                .header("Authorization", "Bearer " + token)
                .multiPart("file", "teachers.xlsx", file,
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                .when().post("/api/import/teachers/commit")
                .then().statusCode(200);

        String academic = createTrackedUser(token, "admin");
        String academicToken = login(academic, PASSWORD);
        List<String> actions = as(academicToken).when().get("/api/foundation/audit-logs")
                .then().statusCode(200)
                .extract().jsonPath().getList("action");
        assertThat(actions)
                .as("教务有基础数据权限，但账号事件不能从旧接口绕过账号审计权限")
                .contains("IMPORT")
                .noneMatch(action -> action.startsWith("ACCOUNT_"))
                .doesNotContain("DISABLE_USER", "ENABLE_USER");

        // 超级管理员的账号审计页照常可查同一条建号事件。
        as(token).queryParam("action", "ACCOUNT_CREATE").when().get("/api/account-audits")
                .then().statusCode(200)
                .body("items.targetUsername", org.hamcrest.Matchers.hasItem(username));
    }

    /**
     * 升级前写入的账号事件用的是旧动作码（V60 及以前）：DISABLE_USER / ENABLE_USER，以及批量导入的
     * IMPORT。它们没有目标账号 ID 与快照，但旧描述必须原样可读；动作筛选要按规范码命中这些历史行，
     * DTO 也只返回规范码，让页面能翻译成中文。
     */
    @Test
    void legacyAccountCodesAreReadOnlyAliasesOfTheirCanonicalActions() {
        String token = adminToken();
        insertLegacyAccountEvent("DISABLE_USER", "停用用户「旧存档」(legacy_zhang)");
        insertLegacyAccountEvent("ENABLE_USER", "启用用户「旧存档」(legacy_zhang)");
        insertLegacyAccountEvent("IMPORT", "文件: users.xlsx, 成功 2 行");
        insertLegacyAccountEvent("LEGACY_UNKNOWN", "升级前的未知动作");

        as(token).queryParam("action", "ACCOUNT_DISABLE").when().get("/api/account-audits")
                .then().statusCode(200)
                .body("total", equalTo(1))
                .body("items[0].action", equalTo("ACCOUNT_DISABLE"))
                .body("items[0].detail", equalTo("停用用户「旧存档」(legacy_zhang)"))
                .body("items[0].successRows", equalTo(1))
                .body("items[0].failedRows", equalTo(0))
                // 历史行没有记录目标账号：保持 null，不能从文本猜人，也不能用当前账号伪造快照。
                .body("items[0].targetUserId", nullValue())
                .body("items[0].targetUsername", nullValue())
                .body("items[0].before", nullValue())
                .body("items[0].after", nullValue());

        as(token).queryParam("action", "ACCOUNT_ENABLE").when().get("/api/account-audits")
                .then().statusCode(200)
                .body("total", equalTo(1))
                .body("items[0].action", equalTo("ACCOUNT_ENABLE"));
        as(token).queryParam("action", "ACCOUNT_IMPORT").when().get("/api/account-audits")
                .then().statusCode(200)
                .body("total", equalTo(1))
                .body("items[0].action", equalTo("ACCOUNT_IMPORT"))
                .body("items[0].successRows", equalTo(2));

        // 整页不因未知历史码报错；已知旧码一律以规范码出现。
        List<String> actions = as(token).when().get("/api/account-audits")
                .then().statusCode(200)
                .body("total", equalTo(4))
                .extract().jsonPath().getList("items.action");
        assertThat(actions).containsExactly(
                "LEGACY_UNKNOWN", "ACCOUNT_IMPORT", "ACCOUNT_ENABLE", "ACCOUNT_DISABLE");
        assertThat(actions).doesNotContain("DISABLE_USER", "ENABLE_USER", "IMPORT");
    }

    @Test
    void failedAccountOperationsDoNotLeaveASuccessEvent() {
        String token = adminToken();
        String username = createTrackedUser(token, "teacher");
        long id = userId(token, username);

        // 重名建号 409、目标不存在 404、停用自己 409：三种失败都不得留下「成功」事件。
        as(token).body(Map.of(
                        "username", username,
                        "password", PASSWORD,
                        "displayName", "重名",
                        "roleCodes", List.of("teacher")))
                .when().post("/api/users").then().statusCode(409);
        as(token).body(Map.of("newPassword", PASSWORD))
                .when().post("/api/users/999999/reset-password").then().statusCode(404);
        as(token).when().post("/api/users/" + userId(token, "admin") + "/disable").then().statusCode(409);

        as(token).when().get("/api/account-audits")
                .then().statusCode(200)
                .body("total", equalTo(1))
                .body("items[0].action", equalTo("ACCOUNT_CREATE"))
                .body("items[0].targetUserId", equalTo((int) id));
    }

    @Test
    void batchImportAuditsEveryCreatedAccountAsImportNotManualCreation() throws Exception {
        String token = adminToken();
        String suffix = TestData.suffix();
        String first = "imp_aud_" + suffix + "_a";
        String second = "imp_aud_" + suffix + "_b";
        createdUsernames.add(first);
        createdUsernames.add(second);
        byte[] file = com.paiban.support.ExcelFixtures.workbook(
                List.of("用户名", "姓名", "初始密码", "角色"),
                List.of(
                        List.of(first, "导入甲", PASSWORD, "teacher"),
                        List.of(second, "导入乙", PASSWORD, "teacher")));

        io.restassured.RestAssured.given()
                .header("Authorization", "Bearer " + token)
                .multiPart("file", "users.xlsx", file,
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                .when().post("/api/users/import/commit")
                .then().statusCode(200)
                .body("importedRows", equalTo(2));

        // 每个成功账号一条可追溯事件，全部是「批量导入」，不会被记成手工建号。
        as(token).queryParam("action", "ACCOUNT_IMPORT").when().get("/api/account-audits")
                .then().statusCode(200)
                .body("total", equalTo(3))
                .body("items.findAll { it.targetUserId != null }.targetUsername",
                        org.hamcrest.Matchers.containsInAnyOrder(first, second))
                .body("items.findAll { it.targetUserId != null }", hasSize(2));
        as(token).queryParam("action", "ACCOUNT_CREATE").when().get("/api/account-audits")
                .then().statusCode(200)
                .body("total", equalTo(0));

        // 整批摘要只保留汇总信息，不会冒充某个账号的事件。
        List<Map<String, Object>> summary = as(token).queryParam("action", "ACCOUNT_IMPORT")
                .when().get("/api/account-audits")
                .then().statusCode(200).extract().jsonPath().getList("items");
        Map<String, Object> withoutTarget = summary.stream()
                .filter(item -> item.get("targetUserId") == null)
                .findFirst().orElseThrow();
        assertThat(withoutTarget.get("actor")).isEqualTo("admin");

        // 导入摘要也不能从旧通用日志接口漏给教务。
        List<String> actions = as(token).when().get("/api/foundation/audit-logs")
                .then().statusCode(200).extract().jsonPath().getList("action");
        assertThat(actions).noneMatch(action -> action.startsWith("ACCOUNT_"));
        assertThat(actions).as("账号批量导入的旧摘要也不再出现在基础日志里").doesNotContain("IMPORT");
    }

    @Test
    void disabledAccountIsRefusedOnItsNextRequest() {
        String token = adminToken();
        String username = createTrackedUser(token, "teacher");
        long id = userId(token, username);
        String userToken = login(username, PASSWORD);

        as(userToken).when().get("/api/auth/me").then().statusCode(200);
        as(token).when().post("/api/users/" + id + "/disable").then().statusCode(200);
        as(userToken).when().get("/api/auth/me").then().statusCode(401);
    }

    @Test
    void resettingPasswordCannotResurrectAnAccountDisabledConcurrently() throws Exception {
        String token = adminToken();
        String username = createTrackedUser(token, "teacher");
        long id = userId(token, username);

        CountDownLatch preloaded = new CountDownLatch(1);
        CountDownLatch disabled = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> staleReset = pool.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                // 旧快照：此刻 enabled = true。
                userService.get(id);
                preloaded.countDown();
                await(disabled, "等待另一个事务停用账号超时");
                userService.resetPassword(id, "{{SECRET_jw7sg2fy}}");
            }));

            assertThat(preloaded.await(15, TimeUnit.SECONDS)).isTrue();
            transactionTemplate.executeWithoutResult(status -> userService.disable(id));
            disabled.countDown();
            staleReset.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }

        assertThat(userRepository.findById(id).orElseThrow().isEnabled())
                .as("重置密码只改哈希，不能把并发停用的账号写回启用")
                .isFalse();
        as(token).queryParam("action", "ACCOUNT_ENABLE").when().get("/api/account-audits")
                .then().statusCode(200)
                .body("total", equalTo(0));
    }

    private void await(CountDownLatch latch, String message) {
        try {
            if (!latch.await(15, TimeUnit.SECONDS)) {
                throw new IllegalStateException(message);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(message, ex);
        }
    }

    private String createTrackedUser(String adminToken, String role) {
        String username = "aud_" + TestData.suffix();
        createUser(adminToken, username, PASSWORD, role);
        createdUsernames.add(username);
        return username;
    }

    private void insertLegacyAccountEvent(String action, String detail) {
        jdbc.update("insert into audit_log"
                        + "(actor, action, entity_type, detail, success_rows, failed_rows, created_at, category)"
                        + " values (?, ?, 'APP_USER', ?, ?, 0, current_timestamp, 'ACCOUNT')",
                "admin", action, detail, "IMPORT".equals(action) ? 2 : 1);
    }

    private long userId(String token, String username) {
        List<Map<String, Object>> users = as(token).when().get("/api/users")
                .then().statusCode(200).extract().jsonPath().getList("$");
        return users.stream()
                .filter(user -> username.equals(user.get("username")))
                .map(user -> ((Number) user.get("id")).longValue())
                .findFirst()
                .orElseThrow();
    }
}
