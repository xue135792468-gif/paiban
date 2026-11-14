package com.paiban.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import com.paiban.common.web.ApiException;
import com.paiban.foundation.domain.Teacher;
import com.paiban.foundation.domain.TeacherRepository;
import com.paiban.identity.domain.AppUser;
import com.paiban.identity.domain.AppUserRepository;
import com.paiban.identity.domain.LoginType;
import com.paiban.integration.auth.DingTalkAccountProvisioner;
import com.paiban.integration.dingtalk.FakeDingTalkClient;
import com.paiban.integration.domain.DingUser;
import com.paiban.support.ApiIntegrationTest;
import com.paiban.support.TestData;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 票 03 有界并发验收：首次免登的开号 / 配对必须在账号写锚点下串行，旧快照不能覆盖先到者。
 *
 * <p>两个身份场景各钉两层：
 * <ol>
 *   <li><b>服务层确定性交错</b>：用 {@link TransactionTemplate} 让两个事务都先把「UID 为空」
 *       的教师读进一级缓存，先到方提交后，后到方才调用 {@code resolveAccount}；
 *       断言必须先于任何写入证明一级缓存里仍是旧值，再断言锁后重查的结果。</li>
 *   <li><b>HTTP 不变量</b>：两个 Fake 免登请求用 start latch 同时发出，只断言状态码与落库
 *       行数（一个账号、两条审计、教师 UID 不被覆盖），不依赖真实并发时序。</li>
 * </ol>
 *
 * <p>本类钉的是已实现锁的回归，不是最初 TDD 的红例：生产代码未改，锁由 A3 实现。
 * 独立 H2、Fake 企业目录；收尾只删本类自建账号 / 教师并恢复 settings 与 Fake。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:paiban_dingtalk_concurrency;MODE=PostgreSQL;"
            + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000"
})
class DingTalkProvisionConcurrencyIntegrationTest extends ApiIntegrationTest {

    private static final long WAIT_SECONDS = 20;

    @Autowired
    private FakeDingTalkClient fakeDingTalk;

    @Autowired
    private AppUserRepository accounts;

    @Autowired
    private TeacherRepository teachers;

    @Autowired
    private DingTalkAccountProvisioner provisioner;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private final List<Long> ownedTeachers = new ArrayList<>();

    @BeforeEach
    void configureFakeTenant() {
        resetIntegrationSettings();
        fakeDingTalk.clearDirectory();
        as(adminToken()).body(Map.of(
                        "enabled", true, "appKey", "concurrency-key", "appSecret", "concurrency-secret",
                        "agentId", "1000", "corpId", "concurrency-corp"))
                .when().put("/api/integrations/dingtalk/config").then().statusCode(200);
    }

    @AfterEach
    void removeOnlyThisFixturesRows() {
        for (long teacherId : ownedTeachers) {
            accounts.deleteAll(accounts.findAllByTeacherId(teacherId));
        }
        accounts.flush();
        for (long teacherId : ownedTeachers) {
            teachers.findById(teacherId).ifPresent(teachers::delete);
        }
        teachers.flush();
        ownedTeachers.clear();
        resetIntegrationSettings();
        fakeDingTalk.resetToDefault();
    }

    @Test
    void sameUidStalePreloadReusesTheCommittedAccountAndKeepsOnlyTheTwoAudits() throws Exception {
        String admin = adminToken();
        String suffix = TestData.suffix();
        String name = "并发同号教师" + suffix;
        String externalId = "dt-same-" + suffix;
        String jobNumber = "SAME-" + suffix;
        long teacherId = createTeacher(admin, name);
        DingUser identity = new DingUser(externalId, name, null, null, jobNumber);

        CountDownLatch preloaded = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch firstCommitted = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        String firstUsername;
        String secondUsername;
        try {
            Future<String> first = pool.submit(() -> {
                try {
                    return transactionTemplate.execute(status -> {
                        loadUnboundTeacherSnapshot(teacherId, preloaded, go);
                        return provisioner.resolveAccount(identity);
                    });
                } finally {
                    firstCommitted.countDown();
                }
            });
            Future<String> second = pool.submit(() -> transactionTemplate.execute(status -> {
                Teacher stale = loadUnboundTeacherSnapshot(teacherId, preloaded, go);
                await(firstCommitted);
                // 先到方已提交绑定，但本事务的一级缓存仍停在「UID 为空」的旧快照上。
                assertThat(stale.getDingtalkUserId())
                        .as("第二个事务预加载的教师快照必须仍是绑定前的 UID 空值")
                        .isNull();
                return provisioner.resolveAccount(identity);
            }));
            assertThat(preloaded.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            firstUsername = first.get(60, TimeUnit.SECONDS);
            secondUsername = second.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(10, TimeUnit.SECONDS);
        }

        List<AppUser> linked = accounts.findAllByTeacherId(teacherId);
        assertThat(linked).as("同一 UID 的第二次首登不得新建第二个账号").hasSize(1);
        AppUser account = linked.get(0);
        assertThat(account.getLoginType()).isEqualTo(LoginType.DINGTALK);
        assertThat(account.getPasswordHash()).isNull();
        assertThat(secondUsername).as("两个事务必须复用同一个账号名").isEqualTo(firstUsername);
        assertThat(account.getUsername()).isEqualTo(firstUsername);
        assertThat(teachers.findById(teacherId).orElseThrow().getDingtalkUserId()).isEqualTo(externalId);

        Response audits = as(admin).queryParam("targetUserId", account.getId())
                .when().get("/api/account-audits");
        audits.then().statusCode(200).body("total", equalTo(2));
        assertThat(audits.jsonPath().getList("items.action", String.class)).containsExactlyInAnyOrder(
                "ACCOUNT_DINGTALK_AUTO_PROVISION", "ACCOUNT_DINGTALK_LINK");
        assertThat(audits.jsonPath().getList("items.actor", String.class))
                .containsOnly("系统（钉钉免登）");
        audits.then().body("items.find { it.action == 'ACCOUNT_DINGTALK_LINK' }.after.dingtalkUserId",
                equalTo(externalId));
    }

    @Test
    void secondUidCannotOverrideTheBindingCommittedWhileItHeldTheUnboundSnapshot() throws Exception {
        String admin = adminToken();
        String suffix = TestData.suffix();
        String name = "并发抢配教师" + suffix;
        String winningUid = "dt-win-" + suffix;
        String lateUid = "dt-late-" + suffix;
        long teacherId = createTeacher(admin, name);
        DingUser winner = new DingUser(winningUid, name, null, null, "WIN-" + suffix);
        DingUser late = new DingUser(lateUid, name, null, null, "LATE-" + suffix);

        CountDownLatch preloaded = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch firstCommitted = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        String firstUsername;
        Rejected rejection;
        try {
            Future<String> first = pool.submit(() -> {
                try {
                    return transactionTemplate.execute(status -> {
                        loadUnboundTeacherSnapshot(teacherId, preloaded, go);
                        return provisioner.resolveAccount(winner);
                    });
                } finally {
                    firstCommitted.countDown();
                }
            });
            Future<Rejected> second = pool.submit(() -> {
                // 拒绝必须让事务代理看到异常并回滚；捕获放在 execute 之外读取原因码。
                try {
                    return transactionTemplate.execute(status -> {
                        Teacher stale = loadUnboundTeacherSnapshot(teacherId, preloaded, go);
                        await(firstCommitted);
                        assertThat(stale.getDingtalkUserId())
                                .as("第二个事务预加载的教师快照必须仍是绑定前的 UID 空值")
                                .isNull();
                        provisioner.resolveAccount(late);
                        return new Rejected(200, "PROVISIONED");
                    });
                } catch (ApiException ex) {
                    return new Rejected(ex.getStatus().value(),
                            String.valueOf(ex.getDetails().get("code")));
                }
            });
            assertThat(preloaded.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            firstUsername = first.get(60, TimeUnit.SECONDS);
            rejection = second.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(10, TimeUnit.SECONDS);
        }

        assertThat(rejection.status()).as("后到 UID 必须是可读的拒绝，不是 500").isEqualTo(401);
        assertThat(rejection.code())
                .as("锁后重读发现教师已绑定另一 UID，只能报未开通，不能覆盖先到者")
                .isEqualTo("NOT_PROVISIONED");

        List<AppUser> linked = accounts.findAllByTeacherId(teacherId);
        assertThat(linked).as("后到 UID 不得新建账号").hasSize(1);
        assertThat(linked.get(0).getUsername()).isEqualTo(firstUsername);
        assertThat(teachers.findById(teacherId).orElseThrow().getDingtalkUserId())
                .as("先到者的绑定不得被覆盖").isEqualTo(winningUid);

        Response audits = as(admin).queryParam("targetUserId", linked.get(0).getId())
                .when().get("/api/account-audits");
        audits.then().statusCode(200).body("total", equalTo(2));
        assertThat(audits.jsonPath().getList("items.action", String.class)).containsExactlyInAnyOrder(
                "ACCOUNT_DINGTALK_AUTO_PROVISION", "ACCOUNT_DINGTALK_LINK");
        audits.then().body("items.find { it.action == 'ACCOUNT_DINGTALK_LINK' }.after.dingtalkUserId",
                equalTo(winningUid));
    }

    @Test
    void twoConcurrentHttpLoginsWithTheSameVerifiedUidYieldOneAccountAndOneSession() throws Exception {
        String admin = adminToken();
        String suffix = TestData.suffix();
        String name = "HTTP同号教师" + suffix;
        String externalId = "dt-http-same-" + suffix;
        String jobNumber = "HTTP-SAME-" + suffix;
        long teacherId = createTeacher(admin, name);
        fakeDingTalk.addUser(1, new DingUser(externalId, name, null, null, jobNumber));

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Response first;
        Response second;
        try {
            Future<Response> firstFuture = pool.submit(() -> {
                await(start);
                return dingTalkLogin(jobNumber);
            });
            Future<Response> secondFuture = pool.submit(() -> {
                await(start);
                return dingTalkLogin(jobNumber);
            });
            start.countDown();
            first = firstFuture.get(60, TimeUnit.SECONDS);
            second = secondFuture.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(10, TimeUnit.SECONDS);
        }

        assertThat(first.statusCode()).as(first.asString()).isEqualTo(200);
        assertThat(second.statusCode()).as(second.asString()).isEqualTo(200);
        long accountId = first.jsonPath().getLong("user.id");
        assertThat(second.jsonPath().getLong("user.id")).isEqualTo(accountId);

        List<AppUser> linked = accounts.findAllByTeacherId(teacherId);
        assertThat(linked).as("并发重复打开只允许一个账号").hasSize(1);
        assertThat(linked.get(0).getId()).isEqualTo(accountId);
        assertThat(teachers.findById(teacherId).orElseThrow().getDingtalkUserId()).isEqualTo(externalId);
        as(admin).queryParam("targetUserId", accountId).when().get("/api/account-audits")
                .then().statusCode(200).body("total", equalTo(2));
    }

    @Test
    void twoConcurrentHttpLoginsForOneUniqueNameLeaveOneBindingAndRejectTheOtherUid() throws Exception {
        String admin = adminToken();
        String suffix = TestData.suffix();
        String name = "HTTP抢配教师" + suffix;
        String firstUid = "dt-http-a-" + suffix;
        String secondUid = "dt-http-b-" + suffix;
        String firstJob = "HTTP-A-" + suffix;
        String secondJob = "HTTP-B-" + suffix;
        long teacherId = createTeacher(admin, name);
        fakeDingTalk.addUser(1, new DingUser(firstUid, name, null, null, firstJob));
        fakeDingTalk.addUser(1, new DingUser(secondUid, name, null, null, secondJob));

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Attempt attemptA;
        Attempt attemptB;
        try {
            Future<Response> firstFuture = pool.submit(() -> {
                await(start);
                return dingTalkLogin(firstJob);
            });
            Future<Response> secondFuture = pool.submit(() -> {
                await(start);
                return dingTalkLogin(secondJob);
            });
            start.countDown();
            attemptA = new Attempt(firstUid, firstFuture.get(60, TimeUnit.SECONDS));
            attemptB = new Attempt(secondUid, secondFuture.get(60, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(10, TimeUnit.SECONDS);
        }

        List<Attempt> sessions = new ArrayList<>();
        if (attemptA.response().statusCode() == 200) {
            sessions.add(attemptA);
        }
        if (attemptB.response().statusCode() == 200) {
            sessions.add(attemptB);
        }
        assertThat(sessions).as("两个 UID 抢同一个唯一同名教师，只能有一个拿到账号").hasSize(1);
        Attempt winner = sessions.get(0);
        Attempt loser = winner.uid().equals(firstUid) ? attemptB : attemptA;

        loser.response().then().statusCode(401).body("code", equalTo("NOT_PROVISIONED"));
        assertThat(teachers.findById(teacherId).orElseThrow().getDingtalkUserId())
                .as("教师只能保留胜出请求的 UID").isEqualTo(winner.uid());

        List<AppUser> linked = accounts.findAllByTeacherId(teacherId);
        assertThat(linked).as("失败的 UID 不得落第二个账号").hasSize(1);
        assertThat(linked.get(0).getUsername())
                .isEqualTo(winner.response().jsonPath().getString("user.username"));

        Response audits = as(admin).queryParam("targetUserId", linked.get(0).getId())
                .when().get("/api/account-audits");
        audits.then().statusCode(200).body("total", equalTo(2));
        assertThat(audits.jsonPath().getList("items.action", String.class)).containsExactlyInAnyOrder(
                "ACCOUNT_DINGTALK_AUTO_PROVISION", "ACCOUNT_DINGTALK_LINK");
    }

    private long createTeacher(String token, String name) {
        long teacherId = as(token).body(Map.of("name", name))
                .when().post("/api/teachers").then().statusCode(200)
                .extract().jsonPath().getLong("id");
        ownedTeachers.add(teacherId);
        return teacherId;
    }

    /**
     * 在已开启的事务里把「UID 为空」的教师读进一级缓存，然后与另一个事务对齐后继续。
     * 返回的实体就是之后 {@code resolveAccount} 会命中缓存的那个实例。
     */
    private Teacher loadUnboundTeacherSnapshot(long teacherId, CountDownLatch preloaded, CountDownLatch go) {
        Teacher teacher = teachers.findById(teacherId).orElseThrow();
        preloaded.countDown();
        await(go);
        return teacher;
    }

    private Response dingTalkLogin(String jobNumber) {
        return RestAssured.given().contentType(ContentType.JSON)
                .body(Map.of("authCode", "fake:" + jobNumber))
                .when().post("/api/auth/dingtalk");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("等待并发事务对齐超时");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待并发事务时被中断", ex);
        }
    }

    private record Rejected(int status, String code) {}

    private record Attempt(String uid, Response response) {}
}
