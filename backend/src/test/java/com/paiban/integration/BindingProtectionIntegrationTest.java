package com.paiban.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.paiban.identity.domain.AppUserRepository;
import com.paiban.identity.domain.Permissions;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * 03-C：钉钉绑定的保护只在公开 HTTP 上验证——人工改 UID 的授权、预演/确认/事件三条自动路径
 * 都不许给「关联管理能力账号且尚未绑定」的档案首次绑定，也不许把已有的非空 UID 改绑。
 *
 * <p>保护口径是<b>能力</b>不是角色名：本类专门用一个「编码不是 admin、却持有
 * {@code foundation:manage}」的遗留角色验证这条，因为 ADR-0006 只要求预置角色用于初始化，
 * {@code listRoles}/{@code resolveRoles} 至今接受库里的自定义角色。
 *
 * <p>独立 H2 库 + 每个用例后只删自己创建的账号与自定义角色：{@code app_user} / {@code role}
 * 等身份表不在 {@code DatabaseResetExtension} 的清空名单里。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:paiban_binding_protection;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
            + "DB_CLOSE_DELAY=-1"
})
class BindingProtectionIntegrationTest extends ApiIntegrationTest {

    private static final String ACADEMIC_PASSWORD = "{{SECRET_3zwqk9da}}";
    private static final String TEACHERS = "/api/teachers";
    private static final String PREVIEW = "/api/integrations/dingtalk/sync/preview";
    private static final String CONFIRM = "/api/integrations/dingtalk/sync/confirm";
    private static final String EVENT = "/api/integrations/dingtalk/event";
    private static final String EVENT_TOKEN = "binding-protection-event-token";
    private static final String PROTECTED_REASON = "须超级管理员在教师档案确认";

    @Autowired
    private FakeDingTalkClient fakeDingTalk;

    @Autowired
    private AppUserRepository accounts;

    @Autowired
    private JdbcTemplate jdbc;

    private final List<Long> createdAccountIds = new ArrayList<>();
    private final List<String> createdRoleCodes = new ArrayList<>();

    @BeforeEach
    void resetExternalState() {
        resetIntegrationSettings();
        fakeDingTalk.resetToDefault();
        fakeDingTalk.clearDirectory();
        as(adminToken()).body(Map.of(
                        "enabled", true,
                        "appKey", "binding-test-key",
                        "appSecret", "binding-test-secret",
                        "agentId", "1000"))
                .when().put("/api/integrations/dingtalk/config").then().statusCode(200);
        as(adminToken()).body(Map.of("integration.dingtalk.event-token", EVENT_TOKEN))
                .when().put("/api/settings").then().statusCode(200);
    }

    @AfterEach
    void cleanOwnedIdentityRows() {
        for (Long accountId : createdAccountIds) {
            accounts.findById(accountId).ifPresent(accounts::delete);
        }
        accounts.flush();
        createdAccountIds.clear();
        for (String roleCode : createdRoleCodes) {
            List<Long> roleIds = jdbc.queryForList(
                    "select id from role where code = ?", Long.class, roleCode);
            for (Long roleId : roleIds) {
                jdbc.update("delete from role_permission where role_id = ?", roleId);
                jdbc.update("delete from user_role where role_id = ?", roleId);
                jdbc.update("delete from role where id = ?", roleId);
            }
        }
        createdRoleCodes.clear();
        resetIntegrationSettings();
        fakeDingTalk.resetToDefault();
    }

    // ---------- 1. 人工改 UID：教务管理员 403 且不留半写，超级管理员可改 ----------

    @Test
    void academicAdminCannotChangeTheBindingAndNothingIsHalfWritten() {
        String root = adminToken();
        long teacherId = createTeacher(root, "改绑教师", null, null);
        String academic = academicToken();

        Response denied = as(academic)
                .body(teacherBody("偷偷改的名字", null, "dd-stolen"))
                .when().put(TEACHERS + "/" + teacherId);

        denied.then().statusCode(403);
        Map<String, Object> teacher = teacherView(root, teacherId);
        assertThat(teacher.get("name")).as("403 时同请求里的其它字段也不能落库")
                .isEqualTo("改绑教师");
        assertThat(teacher.get("dingtalkUserId")).isNull();
    }

    @Test
    void academicAdminCanEditAProfileWithoutTouchingTheBinding() {
        String root = adminToken();
        String academic = academicToken();
        long teacherId = createTeacher(root, "普通档案", null, "dd-keep");

        as(academic).body(teacherBody("普通档案改名", null, null))
                .when().put(TEACHERS + "/" + teacherId).then().statusCode(200);
        // 编辑表单带着原 UID 回来（规范化后没变化）同样不算改绑。
        as(academic).body(teacherBody("普通档案再改名", null, " dd-keep "))
                .when().put(TEACHERS + "/" + teacherId).then().statusCode(200);

        Map<String, Object> teacher = teacherView(root, teacherId);
        assertThat(teacher.get("name")).isEqualTo("普通档案再改名");
        assertThat(teacher.get("dingtalkUserId")).isEqualTo("dd-keep");
    }

    @Test
    void creatingATeacherWithAnUidIsReservedForTheIntegrationManager() {
        String root = adminToken();
        String academic = academicToken();

        as(academic).body(teacherBody("带UID新建", null, "dd-new"))
                .when().post(TEACHERS).then().statusCode(403);
        as(academic).body(teacherBody("不带UID新建", null, null))
                .when().post(TEACHERS).then().statusCode(200);
        // 超级管理员在教师档案里显式填写 UID，就是本版确认可信首次绑定的路径。
        long teacherId = createTeacher(root, "超管显式绑定", null, "dd-super");
        assertThat(teacherView(root, teacherId).get("dingtalkUserId")).isEqualTo("dd-super");
    }

    // ---------- 2. 预演 key 命中：受保护档案整行跳过（启用 / 停用账号都算） ----------

    @ParameterizedTest(name = "管理账号 enabled={0} 时 key 命中也不首次绑定")
    @ValueSource(booleans = {true, false})
    void aKeyMatchNeverFirstBindsAProtectedProfile(boolean accountEnabled) {
        String root = adminToken();
        long teacherId = createTeacher(root, "王校长", "EMP-100", null);
        long accountId = linkedAccount(root, "binding-admin-" + TestData.suffix(), "admin", teacherId);
        if (!accountEnabled) {
            as(root).body(Map.of("enabled", false))
                    .when().put("/api/users/" + accountId).then().statusCode(200);
        }
        fakeDingTalk.addUser(FakeDingTalkClient.ROOT_DEPARTMENT_ID,
                new DingUser("dd-principal", "王校长", null, null, "EMP-100"));

        Map<String, Object> preview = preview(root);
        Map<String, Object> item = singleItem(preview);
        assertThat(item.get("category")).isEqualTo("SKIP");
        assertThat((String) item.get("reason")).contains(PROTECTED_REASON);
        assertThat((List<?>) item.get("fills")).as("预演不宣称可补齐").isEmpty();

        Response confirmation = as(root).body(Map.of()).when().post(CONFIRM);
        confirmation.then().statusCode(200);
        assertThat(teacherView(root, teacherId).get("dingtalkUserId")).isNull();
    }

    // ---------- 3. 显式同名合并：受保护档案同样整行跳过 ----------

    @Test
    void anExplicitSameNameMergeNeverFirstBindsAProtectedProfile() {
        String root = adminToken();
        long teacherId = createTeacher(root, "李主任", null, null);
        linkedAccount(root, "binding-dean-" + TestData.suffix(), "admin", teacherId);
        fakeDingTalk.addUser(FakeDingTalkClient.ROOT_DEPARTMENT_ID,
                new DingUser("dd-dean", "李主任", null, null, null));

        Map<String, Object> item = singleItem(preview(root));
        assertThat(item.get("category")).as("没有 key 命中时先给管理员确认").isEqualTo("SUSPECTED");

        Response confirmation = as(root).body(Map.of("suspectedDecisions", List.of(
                Map.of("userId", "dd-dean", "action", "MERGE", "teacherId", teacherId))))
                .when().post(CONFIRM);

        confirmation.then().statusCode(200);
        assertThat(confirmation.jsonPath().getInt("updated")).isZero();
        assertThat(confirmation.jsonPath().getString("message")).contains("跳过 1");
        assertThat(teacherView(root, teacherId).get("dingtalkUserId")).isNull();
    }

    // ---------- 4. 遗留自定义角色：编码不是 admin，但持管理权限，仍然受保护 ----------

    @Test
    void aLegacyRoleWithManagementPermissionStillProtectsTheProfile() {
        String root = adminToken();
        String roleCode = "legacy_dean_" + TestData.suffix();
        createCustomRole(roleCode, Permissions.FOUNDATION_MANAGE);

        long teacherId = createTeacher(root, "老角色教师", "EMP-200", null);
        long accountId = linkedAccount(root, "binding-legacy-" + TestData.suffix(), roleCode, teacherId);
        assertThat(accountView(root, accountId).get("roles")).isEqualTo(List.of(roleCode));
        fakeDingTalk.addUser(FakeDingTalkClient.ROOT_DEPARTMENT_ID,
                new DingUser("dd-legacy", "老角色教师", null, null, "EMP-200"));

        Map<String, Object> item = singleItem(preview(root));
        assertThat(item.get("category")).isEqualTo("SKIP");
        assertThat((String) item.get("reason")).contains(PROTECTED_REASON);

        as(root).body(Map.of()).when().post(CONFIRM).then().statusCode(200);
        assertThat(teacherView(root, teacherId).get("dingtalkUserId")).isNull();
    }

    // ---------- 5. 钉钉事件：不首次绑定受保护档案，也不改绑非空 UID ----------

    @Test
    void anEventNeverFirstBindsAProtectedProfile() {
        String root = adminToken();
        long teacherId = createTeacher(root, "事件教师", "EMP-300", null);
        linkedAccount(root, "binding-event-" + TestData.suffix(), "super_admin", teacherId);

        event(Map.of("type", "user_modify", "userId", "dd-event",
                "jobNumber", "EMP-300", "name", "事件改的名字")).then().statusCode(200);

        Map<String, Object> teacher = teacherView(root, teacherId);
        assertThat(teacher.get("dingtalkUserId")).isNull();
        assertThat(teacher.get("name")).as("受保护档案连资料也不由事件改").isEqualTo("事件教师");
    }

    @Test
    void anEventNeverRebindsADifferentExistingUid() {
        String root = adminToken();
        long teacherId = createTeacher(root, "已绑定教师", "EMP-400", null);
        as(root).body(teacherBody("已绑定教师", "EMP-400", "dd-old"))
                .when().put(TEACHERS + "/" + teacherId).then().statusCode(200);

        event(Map.of("type", "user_modify", "userId", "dd-other",
                "jobNumber", "EMP-400", "name", "已绑定教师", "mobile", "13700000000"))
                .then().statusCode(200);

        Map<String, Object> teacher = teacherView(root, teacherId);
        assertThat(teacher.get("dingtalkUserId")).as("非空 UID 绝不被事件改绑").isEqualTo("dd-old");
        assertThat(teacher.get("phone")).as("其它 metadata 语义保持").isEqualTo("13700000000");
    }

    // ---------- 6. 普通老师：自动配对照旧成功 ----------

    @Test
    void anOrdinaryTeacherStillBindsThroughAKeyMatch() {
        String root = adminToken();
        long teacherId = createTeacher(root, "普通老师", "EMP-500", null);
        linkedAccount(root, "binding-teacher-" + TestData.suffix(), "teacher", teacherId);
        fakeDingTalk.addUser(FakeDingTalkClient.ROOT_DEPARTMENT_ID,
                new DingUser("dd-teacher", "普通老师", null, null, "EMP-500"));

        Map<String, Object> item = singleItem(preview(root));
        assertThat(item.get("category")).isEqualTo("MERGE");

        as(root).body(Map.of()).when().post(CONFIRM).then().statusCode(200);
        assertThat(teacherView(root, teacherId).get("dingtalkUserId")).isEqualTo("dd-teacher");
    }

    // ---------- helpers ----------

    private String academicToken() {
        String username = "binding-academic-" + TestData.suffix();
        long accountId = createUser(adminToken(), username, ACADEMIC_PASSWORD, "admin");
        createdAccountIds.add(accountId);
        return login(username, ACADEMIC_PASSWORD);
    }

    private long createTeacher(String token, String name, String employeeNo, String dingtalkUserId) {
        return as(token).body(teacherBody(name, employeeNo, dingtalkUserId))
                .when().post(TEACHERS)
                .then().statusCode(200).extract().jsonPath().getLong("id");
    }

    /** 只带名字和可选 UID，其它字段留空，避免测试依赖无关列。 */
    private Map<String, Object> teacherBody(String name, String employeeNo, String dingtalkUserId) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        if (employeeNo != null) {
            body.put("employeeNo", employeeNo);
        }
        if (dingtalkUserId != null) {
            body.put("dingtalkUserId", dingtalkUserId);
        }
        return body;
    }

    private long linkedAccount(String token, String username, String roleCode, long teacherId) {
        long accountId = createUser(token, username, ACADEMIC_PASSWORD, roleCode);
        createdAccountIds.add(accountId);
        as(token).body(Map.of("teacherId", teacherId))
                .when().put("/api/users/" + accountId).then().statusCode(200);
        return accountId;
    }

    /** 模拟升级后遗留的自定义角色：编码不是 admin，权限点却是管理类。 */
    private void createCustomRole(String roleCode, String permissionCode) {
        jdbc.update("insert into role (code, name) values (?, ?)", roleCode, "遗留管理角色");
        createdRoleCodes.add(roleCode);
        Long roleId = jdbc.queryForObject("select id from role where code = ?", Long.class, roleCode);
        Long permissionId = jdbc.queryForObject(
                "select id from permission where code = ?", Long.class, permissionCode);
        jdbc.update("insert into role_permission (role_id, permission_id) values (?, ?)",
                roleId, permissionId);
    }

    private Map<String, Object> preview(String token) {
        return as(token).body(Map.of()).when().post(PREVIEW)
                .then().statusCode(200).extract().jsonPath().getMap("$");
    }

    private Response event(Map<String, Object> body) {
        return RestAssured.given()
                .contentType(ContentType.JSON)
                .header("X-DingTalk-Token", EVENT_TOKEN)
                .body(body)
                .when().post(EVENT);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> singleItem(Map<String, Object> preview) {
        List<Map<String, Object>> groups =
                (List<Map<String, Object>>) preview.get("departments");
        return ((List<Map<String, Object>>) groups.get(0).get("items")).get(0);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> teacherView(String token, long teacherId) {
        List<Map<String, Object>> teachers = as(token).when().get(TEACHERS)
                .then().statusCode(200).extract().jsonPath().getList("$");
        return teachers.stream()
                .filter(teacher -> ((Number) teacher.get("id")).longValue() == teacherId)
                .findFirst()
                .orElseThrow(() -> new AssertionError("教师不存在: " + teacherId));
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
}
