package com.paiban.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;

import com.paiban.support.ApiIntegrationTest;
import io.restassured.response.Response;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
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

    @Test
    void presetRolesAreSuperAdminSchoolAdminAndTeacher() {
        as(adminToken()).when().get("/api/roles")
                .then().statusCode(200)
                .body("code", containsInAnyOrder("super_admin", "admin", "teacher"))
                .body("findAll { it.code == 'super_admin' }.name", contains("超级管理员"))
                // `admin` 编码保留，名字改成教务管理员（规格：升级后原 admin 账号变超管）
                .body("findAll { it.code == 'admin' }.name", contains("教务管理员"))
                .body("findAll { it.code == 'teacher' }.name", contains("老师"));
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
