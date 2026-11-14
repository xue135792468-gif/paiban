package com.paiban.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

import com.paiban.identity.domain.Role;
import com.paiban.identity.domain.RoleRepository;
import com.paiban.support.ApiIntegrationTest;
import com.paiban.support.TestData;
import io.restassured.response.Response;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 票 01：预置角色改为超级管理员 / 教务管理员 / 老师。
 *
 * <p>断言只看 HTTP 契约：角色目录里有什么、每个角色拿到哪些权限点，
 * 不关心种子方法的内部结构。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RoleReshapeIntegrationTest extends ApiIntegrationTest {

    /** 票 01 的三个预置角色；退休的 head_teacher 不在其中，下面按 code 显式排除它。 */
    private static final Set<String> PRESET_ROLE_CODES = Set.of("super_admin", "admin", "teacher");

    @Autowired
    private RoleRepository roleRepository;

    @Test
    void presetRolesAreSuperAdminSchoolAdminAndTeacher() {
        // 身份表被 DatabaseResetExtension 有意保留，角色目录里本来就可能存在历史自定义角色。
        // 这里在本用例内显式种一个，独立复现该输入，不依赖其它测试类的执行顺序。
        String legacyCode = "legacy_custom_" + TestData.suffix();
        roleRepository.save(new Role(legacyCode, "历史自定义角色"));

        Response response = as(adminToken()).when().get("/api/roles");
        response.then().statusCode(200)
                .body("findAll { it.code == 'super_admin' }.name", contains("超级管理员"))
                // `admin` 编码保留，名字改成教务管理员（规格：升级后原 admin 账号变超管）
                .body("findAll { it.code == 'admin' }.name", contains("教务管理员"))
                .body("findAll { it.code == 'teacher' }.name", contains("老师"))
                // 退休的班主任角色：整份目录（含任何自定义角色）都不允许再出现。
                .body("code", not(hasItem("head_teacher")))
                // 自定义角色经真实 HTTP 可见：目录保留历史角色，而不是只剩三种预置角色。
                .body("findAll { it.code == '" + legacyCode + "' }.name", contains("历史自定义角色"));

        List<String> codes = response.jsonPath().getList("code", String.class);
        // 预置角色在目录里恰好三项（同时卡住重复与冒名）；其余编码只能是保留下来的自定义角色。
        assertThat(codes).filteredOn(PRESET_ROLE_CODES::contains)
                .containsExactlyInAnyOrder("super_admin", "admin", "teacher");
        assertThat(codes).contains(legacyCode);
    }

    @Test
    void superAdminRoleHasEveryPermission() {
        Map<String, List<String>> roles = rolePermissions(adminToken());
        assertThat(roles.get("super_admin"))
                .contains("user:manage", "integration:manage", "audit:view:account",
                        "settings:manage", "timetable:view", "timetable:edit",
                        "substitution:approve", "foundation:manage")
                // 班主任下线后「按本班查看」两个权限点保留在目录里，但不分配给任何预置角色。
                .doesNotContain("timetable:view:class", "substitution:view:class");
    }

    @Test
    void schoolAdminRoleIsEverythingExceptAccountsIntegrationAndAudit() {
        Map<String, List<String>> roles = rolePermissions(adminToken());
        assertThat(roles.get("admin"))
                .contains("settings:manage", "timetable:view", "timetable:edit",
                        "timetable:publish", "substitution:approve", "foundation:manage")
                .doesNotContain("user:manage", "integration:manage", "audit:view:account",
                        "timetable:view:class", "substitution:view:class");
    }

    @Test
    void teacherRoleReadsTheWholeSchoolTimetableButKeepsNoManagementPermission() {
        Map<String, List<String>> roles = rolePermissions(adminToken());
        assertThat(roles.get("teacher"))
                .contains("timetable:view", "timetable:view:own", "assignment:view:own",
                        "substitution:submit", "substitution:view:own")
                .doesNotContain("timetable:edit", "timetable:publish", "constraint:manage",
                        "settings:manage", "user:manage", "integration:manage",
                        "audit:view:account", "substitution:approve",
                        "timetable:view:class", "substitution:view:class");
    }

    private Map<String, List<String>> rolePermissions(String token) {
        Response response = as(token).when().get("/api/roles");
        response.then().statusCode(200);
        List<Map<String, Object>> rows = response.jsonPath().getList("$");
        return rows.stream().collect(java.util.stream.Collectors.toMap(
                row -> String.valueOf(row.get("code")),
                row -> ((List<?>) row.get("permissions")).stream().map(String::valueOf).toList()));
    }
}
