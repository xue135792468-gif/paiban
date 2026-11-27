package com.paiban.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.paiban.common.web.ApiException;
import com.paiban.identity.domain.AppUser;
import com.paiban.identity.domain.AppUserRepository;
import com.paiban.identity.domain.Role;
import com.paiban.identity.domain.RoleRepository;
import com.paiban.identity.service.UserService;
import com.paiban.support.ApiIntegrationTest;
import com.paiban.support.TestData;
import io.restassured.response.Response;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 票 01：最后一个启用中的超级管理员既不能被降级，也不能被停用。
 *
 * <p>并发保证有两层，两层都在这里钉住：
 * <ol>
 *   <li><b>确定性交错</b>：一个事务提前把两位超管读进持久化上下文（旧快照），另一个事务停用其中一位
 *       并提交，随后第一个事务再停用剩下那位。判断必须问数据库而不是信一级缓存。</li>
 *   <li><b>HTTP 不变量</b>：两位超管同时降级对方，只允许一方成功，且系统里始终留着一位启用超管。</li>
 * </ol>
 *
 * <p>本类用独立 H2 库并把预置 admin 与本类新建账号在每个用例后恢复 / 删除：
 * 测试之间 {@code app_user}/{@code user_role} 是保留的，改坏 admin 会污染同一上下文里的其他用例。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:paiban_super_admin_guard;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
            + "DB_CLOSE_DELAY=-1"
})
class SuperAdminGuardIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private UserService userService;

    @Autowired
    private AppUserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private final List<String> createdUsernames = new ArrayList<>();

    @AfterEach
    void restoreIdentityRows() {
        userRepository.findByUsername("admin").ifPresent(admin -> {
            Role superAdmin = roleRepository.findByCode("super_admin").orElseThrow();
            admin.setEnabled(true);
            admin.getRoles().clear();
            admin.getRoles().add(superAdmin);
            userRepository.saveAndFlush(admin);
        });
        for (String username : createdUsernames) {
            userRepository.findByUsername(username).ifPresent(userRepository::delete);
        }
        createdUsernames.clear();
        userRepository.flush();
    }

    @Test
    void lastEnabledSuperAdminCannotBeDowngraded() {
        String superToken = adminToken();
        long selfId = userId(superToken, "admin");
        long secondId = createSuperAdminAccount(superToken, "sa_");

        // 还有另一位超管时，降级是允许的。
        as(superToken).body(Map.of("roleCodes", List.of("admin")))
                .when().put("/api/users/" + secondId)
                .then().statusCode(200);

        // 现在自己是最后一位启用超管，降级必须被拒绝，并且角色不变。
        as(superToken).body(Map.of("roleCodes", List.of("admin")))
                .when().put("/api/users/" + selfId)
                .then().statusCode(409);
        as(superToken).when().get("/api/auth/me")
                .then().statusCode(200)
                .body("roles", org.hamcrest.Matchers.contains("super_admin"));
    }

    @Test
    void lastEnabledSuperAdminCannotBeDisabledFromTheEditForm() {
        String superToken = adminToken();
        long secondId = createSuperAdminAccount(superToken, "sa_");
        long selfId = userId(superToken, "admin");

        // 另一位超管可以被停用（不是最后一个）。
        as(superToken).body(Map.of("enabled", false))
                .when().put("/api/users/" + secondId)
                .then().statusCode(200);

        // 剩下自己时，同一条编辑路径也不能把自己停掉。
        as(superToken).body(Map.of("enabled", false))
                .when().put("/api/users/" + selfId)
                .then().statusCode(409);
        assertThat(login("admin", "admin123")).isNotBlank();
    }

    @Test
    void stalePreloadedSnapshotCannotDisableTheLastEnabledSuperAdmin() throws Exception {
        String token = adminToken();
        long adminId = userId(token, "admin");
        String otherUsername = createSuperAdminAccountName(token, "sa_");
        long otherId = userId(token, otherUsername);

        CountDownLatch preloaded = new CountDownLatch(1);
        CountDownLatch otherDisabled = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<String> staleAttempt = pool.submit(() -> {
                try {
                    transactionTemplate.executeWithoutResult(status -> {
                        // 旧快照：两位超管此刻都还是 enabled = true。
                        userRepository.findById(adminId).orElseThrow();
                        userRepository.findById(otherId).orElseThrow();
                        preloaded.countDown();
                        try {
                            if (!otherDisabled.await(15, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("等待另一个事务超时");
                            }
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("被中断", ex);
                        }
                        // 此事务的一级缓存里另一位超管仍然是启用的；判断必须读数据库。
                        userService.disable(otherId);
                    });
                    return "DISABLED";
                } catch (ApiException ex) {
                    return "CONFLICT";
                }
            });

            assertThat(preloaded.await(15, TimeUnit.SECONDS)).isTrue();
            // 另一个事务：停用预置 admin 并提交（此时 other 仍然启用，所以允许）。
            userService.disable(adminId);
            otherDisabled.countDown();

            assertThat(staleAttempt.get(30, TimeUnit.SECONDS))
                    .as("旧快照事务必须被拒绝，而不是把最后一位启用超管停掉")
                    .isEqualTo("CONFLICT");
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }

        assertThat(enabledSuperAdminCount()).as("并发后仍有一位启用超管").isEqualTo(1);
    }

    @Test
    void concurrentDowngradesLeaveOneEnabledSuperAdmin() throws Exception {
        String token = adminToken();
        long firstId = createSuperAdminAccount(token, "sa_a_");
        String secondUsername = createSuperAdminAccountName(token, "sa_b_");
        long secondId = userId(token, secondUsername);
        String firstUsername = usernameOf(firstId);
        String firstToken = login(firstUsername, passwordFor(firstUsername));
        String secondToken = login(secondUsername, passwordFor(secondUsername));

        // 预置 admin 先退出赛道（两位新超管已经启用，所以允许），让「谁是最后一个」成立。
        userService.disable(userId(token, "admin"));

        // 两位超管同时把对方降级：只允许一方成功。
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Response first;
        Response second;
        try {
            Future<Response> firstFuture = submitDowngrade(pool, start, firstToken, secondId);
            Future<Response> secondFuture = submitDowngrade(pool, start, secondToken, firstId);
            start.countDown();
            first = firstFuture.get(30, TimeUnit.SECONDS);
            second = secondFuture.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }

        List<Integer> codes = List.of(first.statusCode(), second.statusCode());
        assertThat(codes).as("同一时刻只有一方能成功降级").filteredOn(code -> code == 200).hasSize(1);
        // 后到的请求可能已经被降级（权限重载后 403），也可能拿到角色锁（409）；
        // HTTP 层只钉不变量，精确的 409 由上面的旧快照服务测试钉住。
        assertThat(codes).as("另一方是可读的拒绝，不是 500")
                .allSatisfy(code -> assertThat(code).isIn(200, 403, 409));
        assertThat(enabledSuperAdminCount()).as("并发降级后仍留下启用中的超级管理员").isEqualTo(1);
    }

    private long enabledSuperAdminCount() {
        return userRepository.findAllByRolesCode("super_admin").stream()
                .filter(AppUser::isEnabled)
                .count();
    }

    private Future<Response> submitDowngrade(
            ExecutorService pool, CountDownLatch start, String token, long targetId) {
        return pool.submit(() -> {
            start.await(10, TimeUnit.SECONDS);
            return as(token).body(Map.of("roleCodes", List.of("admin")))
                    .when().put("/api/users/" + targetId);
        });
    }

    private long createSuperAdminAccount(String token, String prefix) {
        return userId(token, createSuperAdminAccountName(token, prefix));
    }

    private String createSuperAdminAccountName(String token, String prefix) {
        String username = prefix + TestData.suffix();
        createUser(token, username, passwordFor(username), "super_admin");
        createdUsernames.add(username);
        return username;
    }

    /** 密码按用户名派生，避免并发用例里两条账号共用同一个口令常量。 */
    private String passwordFor(String username) {
        return "pw-" + Integer.toHexString(username.hashCode()) + "9";
    }

    private String usernameOf(long userId) {
        return userRepository.findById(userId).orElseThrow().getUsername();
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
