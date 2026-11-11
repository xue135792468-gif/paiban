package com.paiban.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.paiban.identity.domain.AppUserRepository;
import com.paiban.integration.dingtalk.DingTalkApiException;
import com.paiban.integration.dingtalk.FakeDingTalkClient;
import com.paiban.integration.domain.DingUser;
import com.paiban.support.ApiIntegrationTest;
import com.paiban.support.ScheduleFixture;
import com.paiban.support.TestData;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * 票 04 停用链路的回归网：钉钉同步确认只能在「显式选中 + 声明未变化 + 扫描完整」时停用账号。
 *
 * <p>这里断言的全部是<b>当前候选已实现</b>的行为（缺省选择不停用、旧选择在绑定/可见性变化后拒绝、
 * 批量停用原子回滚、停用不影响已发布课表与教师档案、重新启用后同一个钉钉身份登回同一账号、
 * 教务管理员与老师无权预演或确认），因此本类不制造 red，只钉住既有语义，防止后续改动悄悄放松守卫。
 *
 * <p>与同目录 {@code DingTalkSyncDeactivationIntegrationTest} 的既有用例互补：那边盯「选中后停用 +
 * 旧选择拒绝 + 不完整扫描」，本类补 HTTP 侧的空选择矩阵、可见性回摆、批量原子性、课表副作用与
 * 权限矩阵。全部通过公开 HTTP 行为断言，不调用私有方法、不数内部调用次数。
 *
 * <p>本类使用独立 H2 库：{@code app_user} 不在 {@code DatabaseResetExtension} 的清空名单里，
 * 共用库会看到别的测试建的账号；即便如此仍在每个用例后只删除本类自己创建的账号。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:paiban_sync_safety;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
            + "DB_CLOSE_DELAY=-1"
})
class DingTalkSyncSafetyIntegrationTest extends ApiIntegrationTest {

    private static final String ACCOUNT_PASSWORD = "Sync04-Test-Password9!";
    private static final String PREVIEW = "/api/integrations/dingtalk/sync/preview";
    private static final String CONFIRM = "/api/integrations/dingtalk/sync/confirm";
    private static final String LEGACY_SYNC = "/api/integrations/dingtalk/sync";

    @Autowired
    private FakeDingTalkClient fakeDingTalk;

    @Autowired
    private AppUserRepository accounts;

    @Autowired
    private JdbcTemplate jdbc;

    /** 只装本类创建的账号；seed 的 admin 不动。 */
    private final List<Long> createdAccountIds = new ArrayList<>();

    @BeforeEach
    void resetExternalDirectoryAndSettings() {
        resetIntegrationSettings();
        fakeDingTalk.resetToDefault();
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

    // ---------- 1. 缺省（缺失 / null / 空）选择与旧 /sync 接口都不自动停账号 ----------

    @ParameterizedTest(name = "停用选择未提交（{0}）时已绑定账号保持启用")
    @EnumSource(SelectionForm.class)
    void confirmWithoutSelectionsKeepsBoundAccountsEnabled(SelectionForm form) {
        String admin = adminToken();
        String uid = "safety-default-uid";
        long teacherId = createTeacher(admin, "缺省选择教师", uid);
        long accountId = linkedAccount(admin, "safety-default-" + TestData.suffix(), teacherId);
        // 教师绑定的钉钉身份在本次完整扫描里完全缺失：这是停用候选，但没有选择就不能停。
        fakeDingTalk.clearDirectory();

        Response confirmation = confirm(admin, form.body());

        confirmation.then().statusCode(200);
        assertThat(confirmation.jsonPath().getBoolean("success")).isTrue();
        assertThat(confirmation.jsonPath().getInt("accountDisabled"))
                .as("未选择任何账号时不能自动停用")
                .isZero();
        assertAccountKept(admin, accountId, teacherId, uid);
    }

    @Test
    void legacySyncEndpointNeverStopsMissingAccounts() {
        String admin = adminToken();
        String uid = "safety-legacy-uid";
        long teacherId = createTeacher(admin, "旧接口教师", uid);
        long accountId = linkedAccount(admin, "safety-legacy-" + TestData.suffix(), teacherId);
        fakeDingTalk.clearDirectory();

        Response sync = as(admin).when().post(LEGACY_SYNC);

        sync.then().statusCode(200);
        assertThat(sync.jsonPath().getBoolean("success")).isTrue();
        assertThat(sync.jsonPath().getInt("accountDisabled")).isZero();
        assertAccountKept(admin, accountId, teacherId, uid);
    }

    @Test
    void incompleteScanWithoutSelectionStopsNoAccount() {
        String admin = adminToken();
        String uid = "safety-partial-uid";
        long teacherId = createTeacher(admin, "不完整扫描教师", uid);
        long accountId = linkedAccount(admin, "safety-partial-" + TestData.suffix(), teacherId);
        fakeDingTalk.clearDirectory();
        fakeDingTalk.failUserList(FakeDingTalkClient.ROOT_DEPARTMENT_ID,
                new DingTalkApiException("读取测试部门", 41, 0, "部门暂时不可读"));

        // 扫描不完整只挡「带选择的停用」；没带选择时必须正常同步，且仍然不停用任何人。
        Response confirmation = confirm(admin, Map.of("deactivationSelections", List.of()));

        confirmation.then().statusCode(200);
        assertThat(confirmation.jsonPath().getBoolean("success")).isTrue();
        assertThat(confirmation.jsonPath().getInt("accountDisabled")).isZero();
        assertAccountKept(admin, accountId, teacherId, uid);
    }

    private enum SelectionForm {
        /** 请求体里根本没有 deactivationSelections 字段。 */
        NO_SELECTIONS_FIELD,
        /** 字段存在但显式是 null。 */
        NULL_SELECTIONS,
        /** 字段是空数组。 */
        EMPTY_SELECTIONS;

        Map<String, Object> body() {
            return switch (this) {
                case NO_SELECTIONS_FIELD -> Map.of();
                case NULL_SELECTIONS -> {
                    Map<String, Object> payload = new HashMap<>();
                    payload.put("deactivationSelections", null);
                    yield payload;
                }
                case EMPTY_SELECTIONS -> Map.of("deactivationSelections", List.of());
            };
        }
    }

    // ---------- 2. 预览后 UID 重新可见：旧选择被拒绝，账号/教师/审计原样 ----------

    @Test
    void anOldSelectionIsRefusedWhenTheUidReappearsAfterPreview() {
        String admin = adminToken();
        String uid = "safety-reappear-uid";
        long teacherId = createTeacher(admin, "重新可见教师", uid);
        long accountId = linkedAccount(admin, "safety-reappear-" + TestData.suffix(), teacherId);
        fakeDingTalk.clearDirectory();

        Map<String, Object> preview = as(admin).when().post(PREVIEW)
                .then().statusCode(200).extract().jsonPath().getMap("$");
        assertThat(candidates(preview)).hasSize(1);
        assertThat(((Number) candidates(preview).get(0).get("accountId")).longValue())
                .isEqualTo(accountId);

        // 管理员看预览的同时，这个 UID 在钉钉里重新出现：缺失理由消失，旧选择必须作废。
        fakeDingTalk.addUser(FakeDingTalkClient.ROOT_DEPARTMENT_ID,
                new DingUser(uid, "重新可见教师", null, null, "E-REAPPEAR"));

        Response confirmation = confirm(admin, Map.of(
                "deactivationSelections", List.of(selection(accountId, teacherId, uid))));

        confirmation.then().statusCode(409);
        assertThat(confirmation.jsonPath().getString("message")).contains("重新预演");
        assertAccountKept(admin, accountId, teacherId, uid);
        assertThat(teacherView(admin, teacherId).get("name")).isEqualTo("重新可见教师");
        assertThat(syncAuditEntries(admin)).as("拒绝路径不写同步审计").isEmpty();
    }

    // ---------- 3. 批量停用原子性：操作者在名单里 -> 409 且整批回滚 ----------

    @Test
    void batchStopRollsBackWhenTheOperatorIsInTheSelection() {
        String admin = adminToken();
        String normalUid = "safety-batch-normal-uid";
        String operatorUid = "safety-batch-operator-uid";
        long normalTeacher = createTeacher(admin, "批量普通教师", normalUid);
        long operatorTeacher = createTeacher(admin, "批量操作者教师", operatorUid);
        long normalAccount = linkedAccount(admin, "safety-batch-normal-" + TestData.suffix(), normalTeacher);

        // 额外的超级管理员（不动 seed 的 admin），同样关联一个缺失教师，让它成为停用候选。
        String operatorName = "safety-batch-operator-" + TestData.suffix();
        long operatorAccount = createUser(admin, operatorName, ACCOUNT_PASSWORD, "super_admin");
        createdAccountIds.add(operatorAccount);
        as(admin).body(Map.of("teacherId", operatorTeacher))
                .when().put("/api/users/" + operatorAccount).then().statusCode(200);
        String operatorSession = login(operatorName, ACCOUNT_PASSWORD);
        fakeDingTalk.clearDirectory();

        // 顺序照页面提交：先普通账号，后本次操作者。
        Response confirmation = confirm(operatorSession, Map.of("deactivationSelections", List.of(
                selection(normalAccount, normalTeacher, normalUid),
                selection(operatorAccount, operatorTeacher, operatorUid))));

        confirmation.then().statusCode(409);
        assertThat(confirmation.jsonPath().getString("message")).contains("当前登录的账号");
        assertAccountKept(admin, normalAccount, normalTeacher, normalUid);
        assertThat(accountView(admin, operatorAccount).get("enabled"))
                .as("操作者自身账号保持启用").isEqualTo(true);
        assertThat(teacherView(admin, operatorTeacher).get("dingtalkUserId")).isEqualTo(operatorUid);
        assertThat(syncDisableAuditTotal(admin)).as("回滚后不留同步停用审计").isZero();
        as(operatorSession).when().get("/api/auth/me").then().statusCode(200);
    }

    // ---------- 4. 停用账号不碰已发布课表与教师档案 ----------

    @Test
    void stoppingTheTeacherAccountKeepsPublishedTimetableAndTeacherProfile() {
        String admin = adminToken();
        String uid = "safety-timetable-uid";
        long semesterId = TestData.semesterStarting(admin, ScheduleFixture.nextMonday());
        ScheduleFixture.Result published = ScheduleFixture.publishedIn(admin, 5, semesterId);

        Map<String, Object> original = teacherView(admin, published.teacherId());
        String name = (String) original.get("name");
        String employeeNo = (String) original.get("employeeNo");
        Map<String, Object> binding = new HashMap<>();
        binding.put("name", name);
        binding.put("employeeNo", employeeNo);
        binding.put("dingtalkUserId", uid);
        as(admin).body(binding).when().put("/api/teachers/" + published.teacherId())
                .then().statusCode(200);
        long accountId = linkedAccount(admin, "safety-timetable-" + TestData.suffix(), published.teacherId());

        List<Map<String, Object>> rowsBefore = timetableEntryRows(semesterId);
        List<Map<String, Object>> baselineBefore =
                ScheduleFixture.publishedEntries(admin, published.classId());
        assertThat(rowsBefore).as("发布后的课表必须非空").hasSize(published.lessonCount());
        assertThat(baselineBefore).as("基线视角必须非空").isNotEmpty();

        fakeDingTalk.clearDirectory();
        Response confirmation = confirm(admin, Map.of(
                "deactivationSelections", List.of(selection(accountId, published.teacherId(), uid))));

        confirmation.then().statusCode(200);
        assertThat(confirmation.jsonPath().getInt("accountDisabled")).isEqualTo(1);
        assertThat(accountView(admin, accountId).get("enabled")).isEqualTo(false);
        // 整行比对（含课程、教师、槽位、创建时间）：停用账号既不能删行也不能改内容。
        assertThat(timetableEntryRows(semesterId)).as("停用账号不得删改已发布课表").isEqualTo(rowsBefore);
        assertThat(ScheduleFixture.publishedEntries(admin, published.classId()))
                .as("班级基线视角前后一致").isEqualTo(baselineBefore);

        Map<String, Object> teacher = teacherView(admin, published.teacherId());
        assertThat(teacher.get("name")).isEqualTo(name);
        assertThat(teacher.get("employeeNo")).isEqualTo(employeeNo);
        assertThat(teacher.get("dingtalkUserId")).isEqualTo(uid);
        Map<String, Object> row = jdbc.queryForMap(
                "select deleted_at from teacher where id = ?", published.teacherId());
        assertThat(row.get("deleted_at")).as("教师档案不得随账号停用被软删").isNull();
    }

    // ---------- 5. 重新启用后同一个钉钉身份回到同一个账号 ----------

    @Test
    void reEnablingTheStoppedAccountRestoresTheSameDingTalkLogin() {
        String admin = adminToken();
        String uid = "safety-relogin-uid";
        String jobNumber = "SAFETY-REL-1";
        long teacherId = createTeacher(admin, "重启用教师", uid);
        long accountId = linkedAccount(admin, "safety-relogin-" + TestData.suffix(), teacherId);
        fakeDingTalk.clearDirectory();

        Response confirmation = confirm(admin, Map.of(
                "deactivationSelections", List.of(selection(accountId, teacherId, uid))));
        confirmation.then().statusCode(200);
        assertThat(confirmation.jsonPath().getInt("accountDisabled")).isEqualTo(1);
        assertThat(accountView(admin, accountId).get("enabled")).isEqualTo(false);

        fakeDingTalk.addUser(FakeDingTalkClient.ROOT_DEPARTMENT_ID,
                new DingUser(uid, "重启用教师", null, null, jobNumber));

        as(admin).when().post("/api/users/" + accountId + "/enable").then().statusCode(200);

        Response login = dingTalkLogin(jobNumber);
        login.then().statusCode(200);
        assertThat(login.jsonPath().getLong("user.id"))
                .as("重新启用后复用同一个账号，不另开新号").isEqualTo(accountId);
        assertThat(login.jsonPath().getLong("user.teacherId")).isEqualTo(teacherId);
    }

    // ---------- 6. 教务管理员 / 老师无权预演与确认，且不会停用任何账号 ----------

    @ParameterizedTest(name = "{0} 不能预演或确认停用")
    @EnumSource(DeniedRole.class)
    void nonIntegrationRolesCannotPreviewOrConfirm(DeniedRole role) {
        String admin = adminToken();
        String uid = "safety-forbidden-uid";
        long teacherId = createTeacher(admin, "越权教师", uid);
        long accountId = linkedAccount(admin, "safety-forbidden-" + TestData.suffix(), teacherId);

        String callerName = "safety-forbidden-" + role.name().toLowerCase(java.util.Locale.ROOT)
                + "-" + TestData.suffix();
        long callerId = createUser(admin, callerName, ACCOUNT_PASSWORD, role.code());
        createdAccountIds.add(callerId);
        String session = login(callerName, ACCOUNT_PASSWORD);
        fakeDingTalk.clearDirectory();

        as(session).when().post(PREVIEW).then().statusCode(403);
        confirm(session, Map.of("deactivationSelections",
                List.of(selection(accountId, teacherId, uid)))).then().statusCode(403);

        assertAccountKept(admin, accountId, teacherId, uid);
        assertThat(accountView(admin, callerId).get("enabled")).isEqualTo(true);
        assertThat(accountView(admin, callerId).get("roles")).isEqualTo(List.of(role.code()));
    }

    /** 编码沿用预置角色表：{@code admin} 是教务管理员，{@code teacher} 是老师。 */
    private enum DeniedRole {
        SCHOOL_ADMIN("admin"),
        TEACHER("teacher");

        private final String code;

        DeniedRole(String code) {
            this.code = code;
        }

        String code() {
            return code;
        }
    }

    // ---------- helpers ----------

    private void configureIntegration() {
        as(adminToken()).body(Map.of(
                        "enabled", true,
                        "appKey", "safety-test-key",
                        "appSecret", "safety-test-secret",
                        "agentId", "1000"))
                .when().put("/api/integrations/dingtalk/config")
                .then().statusCode(200);
    }

    private long createTeacher(String token, String name, String externalId) {
        return as(token).body(Map.of("name", name, "dingtalkUserId", externalId))
                .when().post("/api/teachers")
                .then().statusCode(200).extract().jsonPath().getLong("id");
    }

    private long linkedAccount(String token, String accountName, long teacherId) {
        long accountId = createUser(token, accountName, ACCOUNT_PASSWORD, "teacher");
        createdAccountIds.add(accountId);
        as(token).body(Map.of("teacherId", teacherId))
                .when().put("/api/users/" + accountId).then().statusCode(200);
        return accountId;
    }

    private Response confirm(String token, Map<String, Object> body) {
        RequestSpecification request = as(token);
        if (body != null) {
            request = request.body(body);
        }
        return request.when().post(CONFIRM);
    }

    private Map<String, Object> selection(long accountId, long teacherId, String uid) {
        return Map.of("accountId", accountId, "teacherId", teacherId, "dingtalkUserId", uid);
    }

    private Response dingTalkLogin(String jobNumber) {
        return RestAssured.given()
                .contentType(ContentType.JSON)
                .body(Map.of("authCode", "fake:" + jobNumber))
                .when().post("/api/auth/dingtalk");
    }

    /** 账号仍启用、教师绑定未变、没有留下同步停用审计——「原样」的三个可观察面。 */
    private void assertAccountKept(String token, long accountId, long teacherId, String uid) {
        assertThat(accountView(token, accountId).get("enabled")).as("账号保持启用").isEqualTo(true);
        assertThat(teacherView(token, teacherId).get("dingtalkUserId")).isEqualTo(uid);
        assertThat(syncDisableAuditTotal(token)).as("没有同步停用审计").isZero();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> accountView(String token, long accountId) {
        List<Map<String, Object>> users = as(token).when().get("/api/users")
                .then().statusCode(200).extract().jsonPath().getList("$");
        return users.stream()
                .filter(user -> ((Number) user.get("id")).longValue() == accountId)
                .findFirst()
                .orElseThrow(() -> new AssertionError("账号不存在: " + accountId));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> teacherView(String token, long teacherId) {
        List<Map<String, Object>> teachers = as(token).when().get("/api/teachers")
                .then().statusCode(200).extract().jsonPath().getList("$");
        return teachers.stream()
                .filter(teacher -> ((Number) teacher.get("id")).longValue() == teacherId)
                .findFirst()
                .orElseThrow(() -> new AssertionError("教师不存在: " + teacherId));
    }

    private long syncDisableAuditTotal(String token) {
        return as(token).queryParam("action", "ACCOUNT_SYNC_DISABLE")
                .when().get("/api/account-audits")
                .then().statusCode(200).extract().jsonPath().getLong("total");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> syncAuditEntries(String token) {
        return as(token).when().get("/api/integrations/dingtalk/logs")
                .then().statusCode(200).extract().jsonPath().getList("$");
    }

    private List<Map<String, Object>> timetableEntryRows(long semesterId) {
        return jdbc.queryForList(
                "select * from timetable_entry where semester_id = ? order by id", semesterId);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> candidates(Map<String, Object> preview) {
        Object value = preview.get("deactivations");
        return value == null ? List.of() : (List<Map<String, Object>>) value;
    }
}
