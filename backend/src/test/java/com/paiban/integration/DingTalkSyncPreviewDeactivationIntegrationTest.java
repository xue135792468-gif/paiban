package com.paiban.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.paiban.identity.domain.AppUserRepository;
import com.paiban.integration.dingtalk.DingTalkApiException;
import com.paiban.integration.dingtalk.FakeDingTalkClient;
import com.paiban.integration.domain.DingUser;
import com.paiban.support.ApiIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * 票 04-A：同步预演的「将停用」候选与扫描完整性。
 *
 * <p>预演只读：完整扫描时，按「已绑定钉钉且有启用账号、但本次原始目录里看不到」列出稳定账号；
 * 不完整（部门局部失败、坏游标、协议结构缺失）时绝不给候选，只给警告。排除部门 / 用户只影响导入，
 * 不改变缺失判断。
 *
 * <p>本类用独立 H2 库：{@code app_user} 不在 DatabaseResetExtension 的清空名单里，和其它测试共用库会
 * 互相看到账号；这里仍在每个用例后删除自己建的账号。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:paiban_dingtalk_preview_deactivation;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
            + "DB_CLOSE_DELAY=-1"
})
class DingTalkSyncPreviewDeactivationIntegrationTest extends ApiIntegrationTest {

    private static final String ACCOUNT_PASSWORD = "teacher123";

    @Autowired
    private FakeDingTalkClient fakeDingTalk;

    @Autowired
    private AppUserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbc;

    private final List<String> createdAccounts = new ArrayList<>();

    @BeforeEach
    void resetFakeDirectory() {
        fakeDingTalk.resetToDefault();
        configure(adminToken(), true);
    }

    @AfterEach
    void cleanUp() {
        for (String accountKey : createdAccounts) {
            userRepository.findByUsername(accountKey).ifPresent(userRepository::delete);
        }
        createdAccounts.clear();
        userRepository.flush();
        try {
            configure(adminToken(), false);
        } catch (RuntimeException ignored) {
            // best effort
        }
    }

    @Test
    void completeScanListsEnabledBoundAccountsMissingFromTheRawDirectory() {
        String token = adminToken();
        long visibleTeacher = createTeacher(token, "绑定可见", "E1", "dd-100");
        long missingTeacher = createTeacher(token, "绑定缺失", "E2", "dd-200");
        long alsoMissingTeacher = createTeacher(token, "绑定缺失二", "E3", "dd-500");
        long unboundTeacher = createTeacher(token, "未绑定钉钉", "E4", null);

        createAccount(token, "acc_visible", "可见账号", visibleTeacher);
        createAccount(token, "acc_unbound", "未绑定账号", unboundTeacher);
        createAccount(token, "acc_none", "无教师账号", null);
        // 建号顺序与 accountId 相反：候选必须按稳定 accountId 排序。
        long laterId = createAccount(token, "acc_missing_b", "缺失账号乙", alsoMissingTeacher);
        long earlierId = createAccount(token, "acc_missing_a", "缺失账号甲", missingTeacher);
        long disabledId = createAccount(
                token, "acc_disabled", "已停用账号", createTeacher(token, "绑定停用", "E5", "dd-600"));
        as(token).when().post("/api/users/" + disabledId + "/disable").then().statusCode(200);

        fakeDingTalk.clearDirectory();
        fakeDingTalk.addUser(1, new DingUser("dd-100", "绑定可见", null, null, "E1"));

        Map<String, Object> preview = preview(token, null);

        assertThat(preview.get("success")).isEqualTo(true);
        assertThat(preview.get("scanComplete")).isEqualTo(true);
        assertThat(preview.get("deactivationWarning")).isNull();
        List<Map<String, Object>> candidates = candidates(preview);
        assertThat(candidates).hasSize(2);
        assertThat(candidates.stream().map(item -> number(item.get("accountId"))))
                .as("按稳定 accountId 升序")
                .containsExactly((int) Math.min(earlierId, laterId), (int) Math.max(earlierId, laterId));
        Map<String, Object> first = candidates.get(0);
        assertThat(first.get("accountName")).isIn("acc_missing_a", "acc_missing_b");
        assertThat(first.get("displayName")).isNotNull();
        assertThat(first.get("teacherName")).isNotNull();
        assertThat(first.get("dingtalkUserId")).isIn("dd-200", "dd-500");
        assertThat(first.get("teacherId")).isNotNull();
    }

    @Test
    void previewWritesNothingAtAll() {
        String token = adminToken();
        long teacherId = createTeacher(token, "只读缺失", "E11", "dd-110");
        createAccount(token, "acc_readonly", "只读账号", teacherId);
        fakeDingTalk.clearDirectory();

        long auditBefore = auditCount();
        long teachersBefore = jdbc.queryForObject("select count(*) from teacher", Long.class);
        long disabledBefore = jdbc.queryForObject("select count(*) from app_user where enabled = false", Long.class);

        Map<String, Object> preview = preview(token, null);

        assertThat(preview.get("scanComplete")).isEqualTo(true);
        assertThat(candidates(preview)).hasSize(1);
        assertThat(auditCount()).as("预演不能写审计").isEqualTo(auditBefore);
        assertThat(jdbc.queryForObject("select count(*) from teacher", Long.class)).isEqualTo(teachersBefore);
        assertThat(jdbc.queryForObject("select count(*) from app_user where enabled = false", Long.class))
                .isEqualTo(disabledBefore);
        as(token).when().get("/api/integrations/dingtalk/logs")
                .then().statusCode(200)
                .body("$", org.hamcrest.Matchers.hasSize(0));
    }

    @Test
    void importExclusionsDoNotCreateOrHideMissingCandidates() {
        String token = adminToken();
        long visibleTeacher = createTeacher(token, "排除可见", "E21", "dd-210");
        long missingTeacher = createTeacher(token, "排除缺失", "E22", "dd-220");
        createAccount(token, "acc_excl_visible", "排除可见账号", visibleTeacher);
        createAccount(token, "acc_excl_missing", "排除缺失账号", missingTeacher);
        fakeDingTalk.clearDirectory().addDepartment(2, "全校教职工", 1);
        fakeDingTalk.addUser(2, new DingUser("dd-210", "排除可见", null, null, "E21"));

        // 两个 UID 都被排除出导入：可见的仍可见，缺失的仍是候选。
        Map<String, Object> preview = preview(token, Map.of(
                "excludedDepartments", List.of("全校教职工"),
                "excludedUserIds", List.of("dd-210", "dd-220")));

        assertThat(preview.get("scanComplete")).isEqualTo(true);
        List<Map<String, Object>> candidates = candidates(preview);
        assertThat(candidates).extracting(item -> item.get("accountName"))
                .containsExactly("acc_excl_missing");
        assertThat(candidates).extracting(item -> item.get("dingtalkUserId")).containsExactly("dd-220");
    }

    @Test
    void partialDepartmentFailureGivesAWarningAndNoCandidates() {
        String token = adminToken();
        long teacherId = createTeacher(token, "局部失败", "E31", "dd-310");
        createAccount(token, "acc_partial", "局部失败账号", teacherId);
        fakeDingTalk.clearDirectory().addDepartment(2, "一部", 1).addDepartment(3, "二部", 1);
        fakeDingTalk.addUser(2, new DingUser("dd-320", "另一位", null, null, "E32"));
        fakeDingTalk.addUser(3, new DingUser("dd-330", "第三位", null, null, "E33"));
        fakeDingTalk.failUserList(3, new DingTalkApiException("拉取钉钉部门用户 (dept_id=3)", 41, 0, "no permission"));

        Map<String, Object> preview = preview(token, null);

        assertThat(preview.get("success")).isEqualTo(true);
        assertThat(preview.get("scanComplete")).isEqualTo(false);
        assertThat((String) preview.get("deactivationWarning")).contains("部门");
        // 部分扫描时顶部 message 也不能出现「将停用账号 0」这种会被读成「没有要停用的人」的误导。
        assertThat((String) preview.get("message"))
                .contains("扫描不完整")
                .doesNotContain("将停用账号");
        assertThat(candidates(preview)).isEmpty();
    }

    @Test
    void brokenPaginationCursorGivesAWarningAndNoCandidates() {
        String token = adminToken();
        long teacherId = createTeacher(token, "坏游标", "E41", "dd-410");
        createAccount(token, "acc_cursor", "坏游标账号", teacherId);
        fakeDingTalk.clearDirectory();
        fakeDingTalk.addUser(1, new DingUser("dd-420", "正常用户", null, null, "E42"));
        fakeDingTalk.stuckUserListCursor(1);

        Map<String, Object> preview = preview(token, null);

        assertThat(preview.get("success")).isEqualTo(true);
        assertThat(preview.get("scanComplete")).isEqualTo(false);
        assertThat((String) preview.get("deactivationWarning")).contains("分页");
        assertThat(candidates(preview)).isEmpty();
    }

    @Test
    void protocolFailureFailsThePreviewWithoutCandidates() {
        String token = adminToken();
        long teacherId = createTeacher(token, "协议失败", "E51", "dd-510");
        createAccount(token, "acc_protocol", "协议失败账号", teacherId);
        fakeDingTalk.clearDirectory().addDepartment(2, "一部", 1);
        fakeDingTalk.addUser(2, new DingUser("dd-520", "别人", null, null, "E52"));
        fakeDingTalk.failUserList(2, DingTalkApiException.incomplete("拉取钉钉部门用户 (dept_id=2)"));

        Map<String, Object> preview = preview(token, null);

        assertThat(preview.get("success")).isEqualTo(false);
        assertThat(preview.get("scanComplete")).isEqualTo(false);
        assertThat((String) preview.get("deactivationWarning")).isNotBlank();
        assertThat(candidates(preview)).isEmpty();
    }

    private void configure(String token, boolean enabled) {
        as(token).body(Map.of(
                        "enabled", enabled,
                        "appKey", "test-key",
                        "appSecret", "test-secret",
                        "agentId", "1000",
                        "syncStrategy", "dingtalk"))
                .when().put("/api/integrations/dingtalk/config")
                .then().statusCode(200);
        as(token).body(Map.of("integration.dingtalk.event-token", "test-event-token"))
                .when().put("/api/settings")
                .then().statusCode(200);
    }

    private long createTeacher(String token, String name, String employeeNo, String dingtalkUserId) {
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("name", name);
        payload.put("employeeNo", employeeNo);
        if (dingtalkUserId != null) {
            payload.put("dingtalkUserId", dingtalkUserId);
        }
        return as(token).body(payload).when().post("/api/teachers")
                .then().statusCode(200).extract().jsonPath().getLong("id");
    }

    private long createAccount(String token, String accountKey, String displayName, Long teacherId) {
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("user" + "name", accountKey);
        payload.put("password", ACCOUNT_PASSWORD);
        payload.put("displayName", displayName);
        payload.put("roleCodes", List.of("teacher"));
        if (teacherId != null) {
            payload.put("teacherId", teacherId);
        }
        long id = as(token).body(payload).when().post("/api/users")
                .then().statusCode(200).extract().jsonPath().getLong("id");
        createdAccounts.add(accountKey);
        return id;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> preview(String token, Object body) {
        var request = as(token);
        if (body != null) {
            request = request.body(body);
        }
        return request.when().post("/api/integrations/dingtalk/sync/preview")
                .then().statusCode(200).extract().jsonPath().getMap("$");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> candidates(Map<String, Object> preview) {
        Object value = preview.get("deactivations");
        return value == null ? List.of() : (List<Map<String, Object>>) value;
    }

    private int number(Object value) {
        return ((Number) value).intValue();
    }

    private long auditCount() {
        return jdbc.queryForObject("select count(*) from audit_log", Long.class);
    }
}
