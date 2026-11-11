package com.paiban.substitution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

import com.paiban.common.web.ApiException;
import com.paiban.foundation.domain.AuditLog;
import com.paiban.foundation.domain.AuditLogRepository;
import com.paiban.substitution.domain.Substitution;
import com.paiban.substitution.domain.SubstitutionHistory;
import com.paiban.substitution.domain.SubstitutionHistoryRepository;
import com.paiban.substitution.domain.SubstitutionRepository;
import com.paiban.substitution.domain.SubstitutionRequest;
import com.paiban.substitution.domain.SubstitutionRequestRepository;
import com.paiban.substitution.service.SubstitutionApprovalService;
import com.paiban.substitution.web.SubstitutionDtos.ApproveSubstitutionRequest;
import com.paiban.support.ApiIntegrationTest;
import com.paiban.support.ScheduleFixture;
import com.paiban.support.TestData;
import io.restassured.response.Response;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 票 05 有界并发验收（测试独有写集，不含生产实现）。
 *
 * <p>该票的核心承诺是「先处理者为准，后到者收到『已被处理』且不留下任何部分生效」。
 * 本类钉住五个实质边界，每个都靠真实的状态守卫而不是线程时序：
 *
 * <ol>
 *   <li>{@link #staleSubmittedSnapshotCannotApproveAfterAnotherApprovalCommitted()}：
 *       同一 EntityManager 先读出 SUBMITTED 旧快照，另一事务批准并提交后，旧快照
 *       仍要能在服务层拿到 409，且只留一条生效行——证明锁后 {@code refresh} 有效，
 *       一级缓存里的旧状态不会把第二笔批准放过去。</li>
 *   <li>{@link #stalePendingSnapshotCannotApproveAfterTheRequesterWithdrewElsewhere()}：
 *       同样的旧快照，中间改由申请人自己撤回；旧批准必须 409 且不产生生效行。</li>
 *   <li>{@link #twoAdminsApprovingConcurrentlyOverHttpYieldOneEffectAndOneRefusal()}：
 *       两名不同管理员账号通过 HTTP 同时批准，一 200 一 409，落库只有一次生效、
 *       一条成功状态历史、一条成功审计，审批人就是胜出账号。</li>
 *   <li>{@link #twoConcurrentHttpSubmitsForOneInstanceLeaveOnePendingRequestAndNoEffect()}：
 *       同一授课实例并发提交（本人 + 有代发权限的管理员），一 200 一 409，
 *       只剩一张待审批、零生效行。</li>
 *   <li>{@link #concurrentApproveAndRejectOverHttpLeaveExactlyOneDecision()}：
 *       批准与驳回并发，只有一方成功，最终状态、生效行、历史与审计互相自洽。</li>
 * </ol>
 *
 * <p>这些断言只走公开 HTTP 契约（状态码 + 落库/历史/审计回查），HTTP 用例不假设谁先拿到
 * 学期行锁：胜出方归属由响应决定，断言按「胜出方」而非固定顺序判断。
 *
 * <p><b>运行边界</b>：第 2、5 条依赖 {@code POST /api/substitution-requests/{id}/withdraw}
 * 与驳回的完整实现（票 05 施工中），只按契约用 HTTP 字符串路径准备，不引用尚未存在的
 * 服务方法。检查点 {@code 16851f2} 上可直接运行的只有第 1、3、4 条；第 2、5 条须在
 * 完整实现合入后再跑。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:paiban_substitution_concurrency;MODE=PostgreSQL;"
            + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000"
})
class SubstitutionApprovalConcurrencyTest extends ApiIntegrationTest {

    private static final String PASSWORD = "teacher123";

    /** 等待并发事务对齐/HTTP 返回的上限；超时即失败，不用它来衡量性能。 */
    private static final long WAIT_SECONDS = 30;

    @Autowired
    private SubstitutionApprovalService approvals;

    @Autowired
    private SubstitutionRequestRepository requests;

    @Autowired
    private SubstitutionRepository substitutions;

    @Autowired
    private SubstitutionHistoryRepository history;

    @Autowired
    private AuditLogRepository auditLogs;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /** 一名任课老师账号、一名空闲代课老师、一节未来已发布课表上的课。 */
    private record Scenario(long semesterId, long classId, long entryId, LocalDate date,
                            long originalTeacherId, long substituteTeacherId, Account owner) {}

    private record Account(long userId, String username, String token) {}

    private record Attempt(Account account, Response response) {}

    // ---------------------------------------------------------------- 1. 旧快照批准

    /**
     * 同一事务先读出 SUBMITTED 旧快照，另一事务批准提交后，旧快照上的批准必须被状态守卫拦下。
     *
     * <p>没有锁后 refresh 的实现会在这里写出第二条生效行（或第二个 EFFECTIVE 历史），
     * 所以断言「刷新后 409 + 只有一条生效行」直接钉住 {@code lockRequest} 的重读语义。
     */
    @Test
    void staleSubmittedSnapshotCannotApproveAfterAnotherApprovalCommitted() throws Exception {
        Scenario scenario = prepare();
        long requestId = submit(scenario);
        Account first = schoolAdmin();
        Account late = schoolAdmin();

        CountDownLatch staleLoaded = new CountDownLatch(1);
        CountDownLatch firstCommitted = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(1);
        Future<ApiException> lateRefusal;
        try {
            lateRefusal = pool.submit(() -> {
                try {
                    transactionTemplate().execute(status -> {
                        // 事务内先读：这个实例就是之后 approve 会命中的一级缓存对象。
                        SubstitutionRequest stale = requests.findById(requestId).orElseThrow();
                        assertThat(stale.getStatus())
                                .as("第二事务预读的快照必须是批准前的 SUBMITTED")
                                .isEqualTo(SubstitutionRequest.SUBMITTED);
                        staleLoaded.countDown();
                        await(firstCommitted);
                        // 另一事务已提交 EFFECTIVE，但本事务缓存仍停在旧状态。
                        assertThat(stale.getStatus())
                                .as("批准已在另一事务提交，本事务一级缓存必须仍是旧快照")
                                .isEqualTo(SubstitutionRequest.SUBMITTED);
                        approvals.approve(late.username(), requestId,
                                new ApproveSubstitutionRequest(null, "迟到的批准"));
                        return null;
                    });
                    return null;
                } catch (ApiException ex) {
                    // 异常在 define 的事务模板之外捕获：让内层事务先回滚，再读状态码。
                    return ex;
                }
            });

            await(staleLoaded);
            as(first.token()).body(Map.of("comment", "先到者批准"))
                    .when().post("/api/substitution-requests/" + requestId + "/approve")
                    .then().statusCode(200)
                    .body("status", equalTo("EFFECTIVE"))
                    .body("approverUserId", equalTo((int) first.userId()));
            assertThat(requests.findById(requestId).orElseThrow().getStatus())
                    .as("先到者的批准必须已经提交")
                    .isEqualTo(SubstitutionRequest.EFFECTIVE);
            firstCommitted.countDown();
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        }

        ApiException refusal = lateRefusal.get(60, TimeUnit.SECONDS);
        assertThat(refusal).as("旧快照上的第二次批准必须被拒绝，而不是静默成功").isNotNull();
        assertThat(refusal.getStatus().value()).isEqualTo(409);
        assertThat(refusal.getMessage()).contains("已被处理");

        assertOnlyOneApprovalEffect(requestId, scenario, first);
    }

    // ---------------------------------------------------------------- 2. 撤回后旧批准

    /**
     * 旧快照上待审批的申请被申请人自己撤回后，旧批准必须 409 且不留任何生效行。
     *
     * <p>这条依赖撤回端点（票 05 施工中）。撤回在另一线程用公开 HTTP 完成，是真实提交的
     * 独立事务；本事务完全不参与它，只保留旧快照。
     */
    @Test
    void stalePendingSnapshotCannotApproveAfterTheRequesterWithdrewElsewhere() throws Exception {
        Scenario scenario = prepare();
        long requestId = submit(scenario);
        Account approver = schoolAdmin();

        CountDownLatch staleLoaded = new CountDownLatch(1);
        CountDownLatch withdrawn = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(1);
        Future<ApiException> lateRefusal;
        try {
            lateRefusal = pool.submit(() -> {
                try {
                    transactionTemplate().execute(status -> {
                        SubstitutionRequest stale = requests.findById(requestId).orElseThrow();
                        assertThat(stale.getStatus()).isEqualTo(SubstitutionRequest.SUBMITTED);
                        staleLoaded.countDown();
                        await(withdrawn);
                        assertThat(stale.getStatus())
                                .as("撤回已在另一事务提交，本事务一级缓存必须仍是旧快照")
                                .isEqualTo(SubstitutionRequest.SUBMITTED);
                        approvals.approve(approver.username(), requestId, null);
                        return null;
                    });
                    return null;
                } catch (ApiException ex) {
                    return ex;
                }
            });

            await(staleLoaded);
            as(scenario.owner().token())
                    .when().post("/api/substitution-requests/" + requestId + "/withdraw")
                    .then().statusCode(200)
                    .body("status", equalTo("WITHDRAWN"));
            withdrawn.countDown();
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        }

        ApiException refusal = lateRefusal.get(60, TimeUnit.SECONDS);
        assertThat(refusal).as("撤回后旧批准必须被拒绝").isNotNull();
        assertThat(refusal.getStatus().value()).isEqualTo(409);
        assertThat(refusal.getMessage()).contains("已被处理");

        assertThat(requests.findById(requestId).orElseThrow().getStatus())
                .as("撤回是终态，迟到的批准不得把状态推回审批链")
                .isEqualTo(SubstitutionRequest.WITHDRAWN);
        assertThat(substitutions.findByRequestId(requestId))
                .as("被撤回的申请绝不允许产生生效行").isEmpty();
        assertThat(history.findByRequestIdOrderByCreatedAtAsc(requestId))
                .extracting(SubstitutionHistory::getToStatus)
                .containsExactly(SubstitutionRequest.SUBMITTED, SubstitutionRequest.WITHDRAWN);
        List<String> actions = requestAudits(requestId).stream().map(AuditLog::getAction).toList();
        assertThat(actions).as("迟到的批准不得留成功审计")
                .doesNotContain("SUBSTITUTION_REQUEST_APPROVED")
                .anyMatch(action -> action.contains("WITHDRAW"));
    }

    // ---------------------------------------------------------------- 3. 双管理员 HTTP 批准

    /**
     * 两名管理员同时批准同一张申请：以先处理者为准，后到者 409，只落一次生效。
     *
     * <p>只断言「一 200 一 409 + 单一生效/历史/审计 + 审批人是胜出账号」这类稳定不变量；
     * 谁先拿到学期行锁由运行时决定，不假设固定顺序。
     */
    @Test
    void twoAdminsApprovingConcurrentlyOverHttpYieldOneEffectAndOneRefusal() throws Exception {
        Scenario scenario = prepare();
        long requestId = submit(scenario);
        Account one = schoolAdmin();
        Account other = schoolAdmin();
        String url = "/api/substitution-requests/" + requestId + "/approve";

        List<Attempt> attempts = race(
                () -> new Attempt(one, as(one.token())
                        .body(Map.of("comment", "并发批准甲"))
                        .when().post(url)),
                () -> new Attempt(other, as(other.token())
                        .body(Map.of("comment", "并发批准乙"))
                        .when().post(url)));

        assertThat(attempts).extracting(attempt -> attempt.response().statusCode())
                .as("并发批准只能有一个成功：" + responses(attempts))
                .containsExactlyInAnyOrder(200, 409);
        Attempt winner = attempts.stream()
                .filter(attempt -> attempt.response().statusCode() == 200)
                .findFirst().orElseThrow();
        Attempt loser = attempts.stream()
                .filter(attempt -> attempt.response().statusCode() == 409)
                .findFirst().orElseThrow();

        winner.response().then().body("status", equalTo("EFFECTIVE"))
                .body("approverUserId", equalTo((int) winner.account().userId()));
        loser.response().then().body("message", containsString("已被处理"));

        assertOnlyOneApprovalEffect(requestId, scenario, winner.account());
    }

    // ---------------------------------------------------------------- 4. 并发提交

    /**
     * 同一授课实例并发提交两张申请（任课老师本人 + 有代发权限的教务管理员）：
     * 一 200 一 409，只留一张待审批，且不产生任何生效行。
     *
     * <p>「同一实例只能有一张待审批」必须落在学期行锁之后：锁前查重会让两个请求都看到
     * 「没有待批」然后各写一张。真实 actor 用落库的 {@code operator_user_id} 核对。
     */
    @Test
    void twoConcurrentHttpSubmitsForOneInstanceLeaveOnePendingRequestAndNoEffect() throws Exception {
        Scenario scenario = prepare();
        Account proxy = schoolAdmin();
        Map<String, Object> body = Map.of(
                "entryId", scenario.entryId(),
                "effectiveDate", scenario.date().toString(),
                "reason", "并发提交",
                "expectedSubstituteTeacherId", scenario.substituteTeacherId());

        List<Attempt> attempts = race(
                () -> new Attempt(scenario.owner(), as(scenario.owner().token())
                        .body(body).when().post("/api/substitution-requests")),
                () -> new Attempt(proxy, as(proxy.token())
                        .body(body).when().post("/api/substitution-requests")));

        assertThat(attempts).extracting(attempt -> attempt.response().statusCode())
                .as("并发提交只能有一张落库：" + responses(attempts))
                .containsExactlyInAnyOrder(200, 409);
        Attempt winner = attempts.stream()
                .filter(attempt -> attempt.response().statusCode() == 200)
                .findFirst().orElseThrow();
        Attempt loser = attempts.stream()
                .filter(attempt -> attempt.response().statusCode() == 409)
                .findFirst().orElseThrow();

        winner.response().then().body("status", equalTo("SUBMITTED"));
        loser.response().then().body("message", containsString("待处理"));

        List<SubstitutionRequest> pending = requests.findByEntryIdAndEffectiveDate(
                scenario.entryId(), scenario.date());
        assertThat(pending).as("同一实例同一日期只允许一张待审批").hasSize(1);
        SubstitutionRequest kept = pending.get(0);
        assertThat(kept.getStatus()).isEqualTo(SubstitutionRequest.SUBMITTED);
        assertThat(kept.getOperatorUserId())
                .as("落库的真实操作账号必须是胜出请求的账号")
                .isEqualTo(winner.account().userId());
        assertThat(kept.getRequesterTeacherId())
                .as("老师本人提交时请求人就是本人")
                .isEqualTo(scenario.owner().userId() == winner.account().userId()
                        ? scenario.originalTeacherId() : null);

        assertThat(substitutions.findByEntryId(scenario.entryId()))
                .as("待审批不生效，不得写任何生效行").isEmpty();
        assertThat(history.findByRequestIdOrderByCreatedAtAsc(kept.getId()))
                .extracting(SubstitutionHistory::getToStatus)
                .containsExactly(SubstitutionRequest.SUBMITTED);
        assertThat(requestAudits(kept.getId())).extracting(AuditLog::getAction)
                .as("只有胜出的那次提交写成功审计")
                .containsExactly("SUBSTITUTION_REQUEST_SUBMITTED");
    }

    // ---------------------------------------------------------------- 5. 批准 vs 驳回

    /**
     * 批准与驳回并发：只有一方生效，最终状态、生效行、状态历史与审计互相自洽。
     *
     * <p>这条依赖驳回端点，按契约用 HTTP 路径准备（检查点之后才可运行）。
     */
    @Test
    void concurrentApproveAndRejectOverHttpLeaveExactlyOneDecision() throws Exception {
        Scenario scenario = prepare();
        long requestId = submit(scenario);
        Account approver = schoolAdmin();
        Account rejecter = schoolAdmin();
        String base = "/api/substitution-requests/" + requestId;

        List<Attempt> attempts = race(
                () -> new Attempt(approver, as(approver.token())
                        .body(Map.of("comment", "并发批准"))
                        .when().post(base + "/approve")),
                () -> new Attempt(rejecter, as(rejecter.token())
                        .body(Map.of("reason", "并发驳回理由"))
                        .when().post(base + "/reject")));

        assertThat(attempts).extracting(attempt -> attempt.response().statusCode())
                .as("批准与驳回并发，只能有一方生效：" + responses(attempts))
                .containsExactlyInAnyOrder(200, 409);
        Attempt winner = attempts.stream()
                .filter(attempt -> attempt.response().statusCode() == 200)
                .findFirst().orElseThrow();
        Attempt loser = attempts.stream()
                .filter(attempt -> attempt.response().statusCode() == 409)
                .findFirst().orElseThrow();
        loser.response().then().body("message", containsString("已被处理"));

        String decided = winner.response().jsonPath().getString("status");
        SubstitutionRequest stored = requests.findById(requestId).orElseThrow();
        assertThat(stored.getStatus()).as("最终状态必须等于胜出方结果").isEqualTo(decided);
        assertThat(stored.getApproverUserId())
                .as("落库的审批/驳回人必须是胜出账号")
                .isEqualTo(winner.account().userId());
        assertThat(stored.getApprovalComment())
                .isEqualTo(winner.response().jsonPath().getString("approvalComment"));

        List<String> transitions = history.findByRequestIdOrderByCreatedAtAsc(requestId).stream()
                .map(SubstitutionHistory::getToStatus).toList();
        List<String> actions = requestAudits(requestId).stream().map(AuditLog::getAction).toList();
        if (SubstitutionRequest.EFFECTIVE.equals(decided)) {
            assertOnlyOneApprovalEffect(requestId, scenario, winner.account());
        } else {
            assertThat(decided).isEqualTo(SubstitutionRequest.REJECTED);
            assertThat(substitutions.findByRequestId(requestId))
                    .as("驳回不产生生效行").isEmpty();
            assertThat(transitions).containsExactly(
                    SubstitutionRequest.SUBMITTED, SubstitutionRequest.REJECTED);
            assertThat(actions).as("只有一次终态决策留成功审计")
                    .containsExactlyInAnyOrder(
                            SubstitutionRequest.SUBMITTED, "SUBSTITUTION_REQUEST_REJECTED")
                    .doesNotContain("SUBSTITUTION_REQUEST_APPROVED");
            assertThat(stored.getApprovalComment()).isEqualTo("并发驳回理由");
        }
    }

    // ---------------------------------------------------------------- 断言与夹具

    /** 只允许一条生效行、一条 SUBMITTED→EFFECTIVE 历史、一条 APPROVED 审计，且审批人是赢家。 */
    private void assertOnlyOneApprovalEffect(long requestId, Scenario scenario, Account winner) {
        SubstitutionRequest stored = requests.findById(requestId).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(SubstitutionRequest.EFFECTIVE);
        assertThat(stored.getApproverUserId())
                .as("审批人必须是真实批准成功的账号")
                .isEqualTo(winner.userId());

        List<Substitution> rows = substitutions.findByRequestId(requestId);
        assertThat(rows).as("一张申请只能留下一条生效行").hasSize(1);
        assertThat(rows.get(0).getStatus()).isEqualTo(Substitution.EFFECTIVE);
        assertThat(rows.get(0).getSubstituteTeacherId()).isEqualTo(scenario.substituteTeacherId());
        assertThat(rows.get(0).getLessonDate()).isEqualTo(scenario.date());

        assertThat(history.findByRequestIdOrderByCreatedAtAsc(requestId))
                .extracting(SubstitutionHistory::getToStatus)
                .containsExactly(SubstitutionRequest.SUBMITTED, SubstitutionRequest.EFFECTIVE);
        assertThat(history.findByRequestIdOrderByCreatedAtAsc(requestId))
                .filteredOn(row -> SubstitutionRequest.EFFECTIVE.equals(row.getToStatus()))
                .singleElement()
                .satisfies(row -> assertThat(row.getActor()).isEqualTo(winner.username()));

        List<AuditLog> audits = requestAudits(requestId);
        assertThat(audits).extracting(AuditLog::getAction)
                .as("提交与批准各一次成功审计，后到者不留成功审计")
                .containsExactlyInAnyOrder(
                        "SUBSTITUTION_REQUEST_SUBMITTED", "SUBSTITUTION_REQUEST_APPROVED");
        assertThat(audits).filteredOn(log -> "SUBSTITUTION_REQUEST_APPROVED".equals(log.getAction()))
                .singleElement()
                .satisfies(log -> {
                    assertThat(log.getActor()).isEqualTo(winner.username());
                    assertThat(log.getDetail()).contains("approverUserId=" + winner.userId());
                });

        // 公开读模型：这节课在当天已经换成代课老师。
        as(winner.token()).queryParam("date", scenario.date().toString())
                .when().get("/api/timetable/class/" + scenario.classId())
                .then().statusCode(200)
                .body("find { it.id == " + scenario.entryId() + " }.substituted", equalTo(true))
                .body("find { it.id == " + scenario.entryId() + " }.teacherId",
                        equalTo((int) scenario.substituteTeacherId()));
    }

    /** 未来学期 + 已发布课表 + 一名任课老师账号：批准路径受真实开课截止约束，必须锚在未来。 */
    private Scenario prepare() {
        String admin = adminToken();
        String suffix = TestData.suffix();
        LocalDate monday = ScheduleFixture.nextMonday();
        ScheduleFixture.Result fixture = ScheduleFixture.publishedIn(
                admin, 5, TestData.semesterStarting(admin, monday));
        Map<String, Object> entry = ScheduleFixture.classEntries(admin, fixture.classId()).stream()
                .filter(item -> ((Number) item.get("teacherId")).longValue() == fixture.teacherId())
                .findFirst()
                .orElseThrow();
        long entryId = ((Number) entry.get("id")).longValue();
        int dayOfWeek = ((Number) entry.get("dayOfWeek")).intValue();
        LocalDate date = monday.plusDays(dayOfWeek - 1L);
        long substituteTeacherId = TestData.teacher(admin, "并发代课" + suffix);
        Account owner = createAccount(admin, "t5c_t_" + suffix, List.of("teacher"), fixture.teacherId());
        return new Scenario(fixture.semesterId(), fixture.classId(), entryId, date,
                fixture.teacherId(), substituteTeacherId, owner);
    }

    private long submit(Scenario scenario) {
        return as(scenario.owner().token()).body(Map.of(
                        "entryId", scenario.entryId(),
                        "effectiveDate", scenario.date().toString(),
                        "reason", "并发用例准备的申请",
                        "expectedSubstituteTeacherId", scenario.substituteTeacherId()))
                .when().post("/api/substitution-requests")
                .then().statusCode(200)
                .body("status", equalTo("SUBMITTED"))
                .extract().jsonPath().getLong("id");
    }

    /** 教务管理员账号（持审批权限），与超级管理员 admin 区分开，便于核对真实审批人。 */
    private Account schoolAdmin() {
        String suffix = TestData.suffix();
        return createAccount(adminToken(), "t5c_a_" + suffix, List.of("admin"), null);
    }

    private Account createAccount(String adminToken, String username, List<String> roles, Long teacherId) {
        Map<String, Object> body = new HashMap<>();
        body.put("username", username);
        body.put("password", PASSWORD);
        body.put("displayName", username);
        body.put("roleCodes", roles);
        if (teacherId != null) {
            body.put("teacherId", teacherId);
        }
        long userId = as(adminToken).body(body)
                .when().post("/api/users")
                .then().statusCode(200)
                .extract().jsonPath().getLong("id");
        return new Account(userId, username, login(username, PASSWORD));
    }

    private TransactionTemplate transactionTemplate() {
        return new TransactionTemplate(transactionManager);
    }

    private List<AuditLog> requestAudits(long requestId) {
        return auditLogs.findAll().stream()
                .filter(log -> log.getDetail() != null
                        && log.getDetail().contains("requestId=" + requestId))
                .toList();
    }

    /** 用 start latch 让两个 HTTP 请求尽量同时发出；调用方只断言状态码与落库不变量。 */
    @SafeVarargs
    private static List<Attempt> race(Callable<Attempt>... actions) {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(actions.length);
        try {
            List<Future<Attempt>> futures = new ArrayList<>();
            for (Callable<Attempt> action : actions) {
                futures.add(pool.submit(() -> {
                    await(start);
                    return action.call();
                }));
            }
            start.countDown();
            List<Attempt> attempts = new ArrayList<>();
            for (Future<Attempt> future : futures) {
                attempts.add(future.get(WAIT_SECONDS * 2, TimeUnit.SECONDS));
            }
            return attempts;
        } catch (Exception ex) {
            throw new IllegalStateException("并发 HTTP 请求未能完成", ex);
        } finally {
            pool.shutdownNow();
            try {
                pool.awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static String responses(List<Attempt> attempts) {
        List<String> parts = new ArrayList<>();
        for (Attempt attempt : attempts) {
            parts.add(attempt.response().statusCode() + " " + attempt.response().asString());
        }
        return String.join(" | ", parts);
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
}
