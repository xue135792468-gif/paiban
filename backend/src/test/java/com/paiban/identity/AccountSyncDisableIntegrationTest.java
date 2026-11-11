package com.paiban.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.equalTo;

import com.paiban.common.web.ApiException;
import com.paiban.identity.domain.AppUserRepository;
import com.paiban.identity.service.AccountMutationGuard;
import com.paiban.identity.service.UserService;
import com.paiban.support.ApiIntegrationTest;
import com.paiban.support.TestData;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 票 04-C1：把账号写入的锚点锁抽成共享组件后，同步停用入口必须复用同一套守卫，
 * 审计只留一条「同步停用」，且「没有事务就不许读改账号」的契约真实生效。
 *
 * <p>同步停用的公开 HTTP 入口在 C2 才接上，所以这里在服务层驱动，仍从公开 HTTP
 * 审计接口读取结果；独立 H2 库避免与其它测试共享 {@code app_user} 行。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:paiban_sync_disable;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
            + "DB_CLOSE_DELAY=-1"
})
class AccountSyncDisableIntegrationTest extends ApiIntegrationTest {

    private static final String PASSWORD = "sync-pw-4711";

    @Autowired
    private UserService userService;

    @Autowired
    private AccountMutationGuard accountMutationGuard;

    @Autowired
    private AppUserRepository userRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private final List<String> createdUsernames = new ArrayList<>();

    @AfterEach
    void restoreIdentityRows() {
        userRepository.findByUsername("admin").ifPresent(admin -> {
            admin.setEnabled(true);
            userRepository.saveAndFlush(admin);
        });
        for (String username : createdUsernames) {
            userRepository.findByUsername(username).ifPresent(userRepository::delete);
        }
        createdUsernames.clear();
        userRepository.flush();
        SecurityContextHolder.clearContext();
    }

    @Test
    void syncDisableLeavesExactlyOneSyncEventAndRefusesTheOldTokenNext() {
        String adminToken = adminToken();
        String username = createTrackedUser(adminToken, "teacher");
        long id = userId(adminToken, username);
        String userToken = login(username, PASSWORD);

        asPrincipal("admin");
        transactionTemplate.executeWithoutResult(status -> userService.disableForSync(id));

        as(adminToken).queryParam("action", "ACCOUNT_SYNC_DISABLE").when().get("/api/account-audits")
                .then().statusCode(200)
                .body("total", equalTo(1))
                .body("items[0].actor", equalTo("admin"))
                .body("items[0].targetUserId", equalTo((int) id))
                .body("items[0].before.enabled", equalTo(true))
                .body("items[0].after.enabled", equalTo(false));
        // 同一动作不会先落一条普通停用再落一条同步停用。
        as(adminToken).queryParam("action", "ACCOUNT_DISABLE").when().get("/api/account-audits")
                .then().statusCode(200)
                .body("total", equalTo(0));

        assertThat(userRepository.findById(id).orElseThrow().isEnabled()).isFalse();
        // 停用即时生效：旧 token 下一次请求就被挡住，不用等登录过期。
        as(userToken).when().get("/api/auth/me").then().statusCode(401);
    }

    @Test
    void syncDisableReusesTheSelfAndLastSuperAdminGuardsAndStaysIdempotent() {
        String adminToken = adminToken();
        String username = createTrackedUser(adminToken, "teacher");
        long id = userId(adminToken, username);

        asPrincipal("admin");
        transactionTemplate.executeWithoutResult(status -> userService.disableForSync(id));
        // 已停用账号再同步一次是幂等：不新增事件。
        transactionTemplate.executeWithoutResult(status -> userService.disableForSync(id));
        as(adminToken).queryParam("action", "ACCOUNT_SYNC_DISABLE").when().get("/api/account-audits")
                .then().statusCode(200)
                .body("total", equalTo(1));

        long adminId = userId(adminToken, "admin");
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(
                        status -> userService.disableForSync(adminId)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("当前登录的账号");

        // 最后一个启用超管不能被同步停用：B 成为唯一启用超管后，用非超管主体的服务调用验证守卫。
        // 先建好两个账号再动 admin，否则 admin 停用后它的 token 立刻就 401。
        String secondSuper = createTrackedUser(adminToken, "super_admin");
        long secondSuperId = userId(adminToken, secondSuper);
        String outsider = createTrackedUser(adminToken, "teacher");
        asPrincipal(secondSuper);
        transactionTemplate.executeWithoutResult(status -> userService.disable(adminId));
        asPrincipal(outsider);
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(
                        status -> userService.disableForSync(secondSuperId)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("最后一个启用的超级管理员");
        assertThat(userRepository.findById(secondSuperId).orElseThrow().isEnabled())
                .as("被拒绝的同步停用不能改动账号状态")
                .isTrue();
    }

    @Test
    void guardRefusesToRunWithoutAnAmbientTransaction() {
        String adminToken = adminToken();
        String username = createTrackedUser(adminToken, "teacher");
        long id = userId(adminToken, username);

        // 误用保护：guard 的公开方法标了 MANDATORY，锁外调用直接失败。
        assertThatThrownBy(() -> accountMutationGuard.lockAndRefresh(id))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    private void asPrincipal(String username) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(username, null, List.of()));
    }

    private String createTrackedUser(String adminToken, String role) {
        String username = "sync_" + TestData.suffix();
        createUser(adminToken, username, PASSWORD, role);
        createdUsernames.add(username);
        return username;
    }

    private long userId(String token, String username) {
        List<java.util.Map<String, Object>> users = as(token).when().get("/api/users")
                .then().statusCode(200).extract().jsonPath().getList("$");
        return users.stream()
                .filter(user -> username.equals(user.get("username")))
                .map(user -> ((Number) user.get("id")).longValue())
                .findFirst()
                .orElseThrow();
    }
}
