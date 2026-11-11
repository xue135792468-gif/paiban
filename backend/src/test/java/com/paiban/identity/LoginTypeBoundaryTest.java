package com.paiban.identity;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.assertj.core.api.Assertions.assertThat;

import com.paiban.foundation.domain.Teacher;
import com.paiban.foundation.domain.TeacherRepository;
import com.paiban.identity.domain.AppUser;
import com.paiban.identity.domain.AppUserRepository;
import com.paiban.identity.domain.LoginType;
import com.paiban.identity.domain.RoleRepository;
import com.paiban.identity.service.AuthService;
import com.paiban.support.ApiIntegrationTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 票 03-A2：登录类型边界的公开 HTTP 验收。
 *
 * <p>钉钉免登账号（{@code login_type = DINGTALK}、哈希为 null、绑定教师档案）在这里直接
 * 预置进数据库，再走真实的 {@code /api/auth/login} 与账号管理接口，断言外部行为：
 * 密码登录与重置密码都必须按类型拒绝，JWT 视角的类型字段必须可读，
 * 提升身份不改类型。测试不触碰私有方法，也不断言内部调用次数。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class LoginTypeBoundaryTest extends ApiIntegrationTest {

    @Autowired
    private AppUserRepository users;

    @Autowired
    private TeacherRepository teachers;

    @Autowired
    private RoleRepository roles;

    @Autowired
    private AuthService authService;

    private final List<Long> createdUserIds = new ArrayList<>();
    private final List<Long> createdTeacherIds = new ArrayList<>();

    @AfterEach
    void removeFixtures() {
        createdUserIds.forEach(users::deleteById);
        createdTeacherIds.forEach(teachers::deleteById);
        createdUserIds.clear();
        createdTeacherIds.clear();
    }

    @Test
    void dingTalkAccountIsRefusedOnPasswordLogin() {
        AppUser ding = dingTalkAccount();

        RestAssured.given()
                .contentType(ContentType.JSON)
                .body(Map.of("username", ding.getUsername(), "password", "anything-at-all"))
                .when().post("/api/auth/login")
                .then().statusCode(401)
                .body("message", containsString("请在钉钉中打开"));
    }

    @Test
    void resettingPasswordOnDingTalkAccountIsRefusedAndLeavesNoAudit() {
        AppUser ding = dingTalkAccount();
        String token = adminToken();

        as(token)
                .body(Map.of("newPassword", "brand-new-pass"))
                .when().post("/api/users/" + ding.getId() + "/reset-password")
                .then().statusCode(409)
                .body("message", containsString("请在钉钉中打开"));

        // 拒绝必须发生在写库前：账号仍然没有密码，也没有留下一条「重置密码」成功事件。
        assertThat(users.findById(ding.getId()).orElseThrow().getPasswordHash()).isNull();
        as(token)
                .queryParam("action", "ACCOUNT_RESET_PASSWORD")
                .queryParam("targetUserId", ding.getId())
                .when().get("/api/account-audits")
                .then().statusCode(200)
                .body("total", equalTo(0));
    }

    @Test
    void jwtRequestsReportTheAccountLoginType() {
        AppUser ding = dingTalkAccount();
        String dingToken = authService.issueFor(ding.getUsername()).token();

        // 没有哈希的钉钉账号也必须能凭 JWT 装回权限上下文（不伪造密码、不写库）。
        as(dingToken)
                .when().get("/api/auth/me")
                .then().statusCode(200)
                .body("username", equalTo(ding.getUsername()))
                .body("loginType", equalTo("DINGTALK"));

        // 密码总钥匙照旧：真实密码登录拿到 PASSWORD 类型。
        as(adminToken())
                .when().get("/api/auth/me")
                .then().statusCode(200)
                .body("username", equalTo("admin"))
                .body("loginType", equalTo("PASSWORD"));
    }

    @Test
    void changingRoleKeepsDingTalkTypeAndRecordsTheBindingInTheAudit() {
        AppUser ding = dingTalkAccount();
        String dingtalkUserId = teachers.findById(ding.getTeacherId()).orElseThrow().getDingtalkUserId();
        String token = adminToken();

        as(token)
                .body(Map.of("roleCodes", List.of("admin")))
                .when().put("/api/users/" + ding.getId())
                .then().statusCode(200)
                .body("roles", hasItem("admin"))
                .body("loginType", equalTo("DINGTALK"));

        AppUser reloaded = users.findById(ding.getId()).orElseThrow();
        assertThat(reloaded.getLoginType()).isEqualTo(LoginType.DINGTALK);
        assertThat(reloaded.getPasswordHash()).isNull();

        // 审计快照按 allowlist 追加登录类型与钉钉 UID：提升身份的前后都能看出账号类型和绑定关系。
        as(token)
                .queryParam("action", "ACCOUNT_ROLE_CHANGE")
                .queryParam("targetUserId", ding.getId())
                .when().get("/api/account-audits")
                .then().statusCode(200)
                .body("total", equalTo(1))
                .body("items[0].before.loginType", equalTo("DINGTALK"))
                .body("items[0].after.loginType", equalTo("DINGTALK"))
                .body("items[0].before.dingtalkUserId", equalTo(dingtalkUserId))
                .body("items[0].after.dingtalkUserId", equalTo(dingtalkUserId));
    }

    /** 预置一个已经绑定教师档案的钉钉免登账号：没有密码，只能走免登。 */
    private AppUser dingTalkAccount() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Teacher teacher = new Teacher();
        teacher.setName("钉钉老师" + suffix);
        teacher.setDingtalkUserId("ding-" + suffix);
        Teacher savedTeacher = teachers.save(teacher);
        createdTeacherIds.add(savedTeacher.getId());

        AppUser user = new AppUser();
        user.setUsername("ding_" + suffix);
        user.setDisplayName(savedTeacher.getName());
        user.setPasswordHash(null);
        user.setLoginType(LoginType.DINGTALK);
        user.setTeacherId(savedTeacher.getId());
        user.getRoles().add(roles.findByCode("teacher").orElseThrow());
        AppUser saved = users.save(user);
        createdUserIds.add(saved.getId());
        return saved;
    }
}
