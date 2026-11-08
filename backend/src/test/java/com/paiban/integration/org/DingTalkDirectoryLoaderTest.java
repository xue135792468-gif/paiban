package com.paiban.integration.org;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.paiban.integration.dingtalk.DingTalkApiException;
import com.paiban.integration.dingtalk.DingTalkClient;
import com.paiban.integration.dingtalk.DingUserPage;
import com.paiban.integration.dingtalk.FakeDingTalkClient;
import com.paiban.integration.domain.DingDepartment;
import com.paiban.integration.domain.DingUser;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** The traversal is pure logic: a fake client, no Spring, no network. */
class DingTalkDirectoryLoaderTest {

    private final FakeDingTalkClient client = new FakeDingTalkClient();
    private final DingTalkDirectoryLoader loader = new DingTalkDirectoryLoader(client);

    @Test
    void walksTheWholeDepartmentTreeDepthFirst() {
        client.clearDirectory();
        client.addDepartment(2, "全校教职工", 1)
                .addDepartment(3, "高一年级", 2)
                .addDepartment(4, "高二年级", 2)
                .addDepartment(5, "食堂", 1);
        client.addUser(3, new DingUser("u-1", "王老师", "13800000001", null, "EMP101"));
        client.addUser(4, new DingUser("u-2", "赵老师", "13800000002", null, "EMP102"));
        client.addUser(5, new DingUser("u-3", "厨师甲", null, null, null));
        client.addUser(1, new DingUser("u-root", "校长", "13800000000", null, "EMP100"));

        DingTalkDirectory directory = loader.load();

        assertThat(directory.users()).extracting(DingUser::userId)
                .containsExactly("u-root", "u-1", "u-2", "u-3");
        assertThat(directory.departmentsScanned()).isEqualTo(5);
        assertThat(directory.usersScanned()).isEqualTo(4);
        assertThat(directory.skippedDepartments()).isZero();
        assertThat(directory.failedDepartments()).isZero();
    }

    @Test
    void importsAUserWhoAppearsInSeveralDepartmentsOnlyOnce() {
        client.clearDirectory();
        client.addDepartment(2, "全校教职工", 1).addDepartment(3, "高一年级", 2);
        client.addUser(2, new DingUser("u-1", "王老师", "13800000001", null, "EMP101"));
        client.addUser(3, new DingUser("u-1", "王老师", "13800000001", null, "EMP101"));

        DingTalkDirectory directory = loader.load();

        assertThat(directory.users()).hasSize(1);
        DingUser user = directory.users().get(0);
        assertThat(user.departmentNames()).containsExactly("全校教职工", "高一年级");
        assertThat(user.jobNumber()).isEqualTo("EMP101");
    }

    @Test
    void skipsNegativeDepartmentIdsWithoutCallingTheApi() {
        client.clearDirectory();
        client.addDepartment(2, "全校教职工", 1).addDepartment(-7, "家校通讯录", 1);
        client.addUser(2, new DingUser("u-1", "王老师", null, null, "EMP101"));

        DingTalkDirectory directory = loader.load();

        assertThat(directory.departmentsScanned()).isEqualTo(2);
        assertThat(directory.skippedDepartments()).isEqualTo(1);
        assertThat(directory.users()).extracting(DingUser::userId).containsExactly("u-1");
        assertThat(client.userListCalls(-7)).isZero();
    }

    @Test
    void marksUsersInALeaverDepartmentInactive() {
        client.clearDirectory();
        client.addDepartment(2, "离职人员", 1);
        client.addUser(2, new DingUser("u-9", "离职老师", null, null, "EMP900"));

        DingTalkDirectory directory = loader.load();

        assertThat(directory.users()).hasSize(1);
        assertThat(directory.users().get(0).active()).isFalse();
    }

    @Test
    void keepsTheInactiveFlagDingTalkReturns() {
        client.clearDirectory();
        client.addUser(1, new DingUser("u-8", "停用老师", null, null, "EMP800", false, List.of()));

        assertThat(loader.load().users().get(0).active()).isFalse();
    }

    @Test
    void followsCursorPaginationToTheEnd() {
        client.clearDirectory().pageSizeLimit(2);
        for (int i = 1; i <= 5; i++) {
            client.addUser(1, new DingUser("u-" + i, "老师" + i, null, null, "E" + i));
        }

        DingTalkDirectory directory = loader.load();

        assertThat(directory.users()).hasSize(5);
        assertThat(client.userListCalls(1)).isEqualTo(3);
    }

    @Test
    void skipsAnUnreadableDepartmentButKeepsTheRest() {
        client.clearDirectory();
        client.addDepartment(2, "全校教职工", 1).addDepartment(3, "食堂", 1);
        client.addUser(2, new DingUser("u-1", "王老师", null, null, "EMP101"));
        client.addUser(3, new DingUser("u-2", "厨师甲", null, null, null));
        client.failUserList(3, new DingTalkApiException("拉取部门用户", 41, 0, "department not found"));

        DingTalkDirectory directory = loader.load();

        assertThat(directory.users()).extracting(DingUser::userId).containsExactly("u-1");
        assertThat(directory.failedDepartments()).isEqualTo(1);
        assertThat(directory.departmentsScanned()).isEqualTo(3);
    }

    @Test
    void propagatesAnIpWhitelistRejection() {
        client.clearDirectory();
        DingTalkApiException error = new DingTalkApiException("拉取部门用户", 88, 60020, "ip not in whitelist");
        client.failUserList(1, error);

        assertThatThrownBy(loader::load).isSameAs(error);
    }

    @Test
    void keepsDepthFirstOrderEvenWhenResponsesArriveOutOfOrder() {
        client.clearDirectory();
        client.addDepartment(2, "全校教职工", 1)
                .addDepartment(3, "高一年级", 2)
                .addDepartment(4, "高二年级", 2)
                .addDepartment(5, "食堂", 1);
        client.addUser(1, new DingUser("u-root", "校长", null, null, "EMP100"));
        client.addUser(3, new DingUser("u-1", "王老师", null, null, "EMP101"));
        client.addUser(4, new DingUser("u-2", "赵老师", null, null, "EMP102"));
        client.addUser(5, new DingUser("u-3", "厨师甲", null, null, null));

        // The departments the serial walk reaches first are the slowest here, so a loader
        // that merged in completion order would produce u-2 before u-1.
        DingTalkClient delayed = new DelayingClient(client, Map.of(2L, 250L, 3L, 150L));
        DingTalkDirectory directory = new DingTalkDirectoryLoader(delayed, 4).load();

        assertThat(directory.users()).extracting(DingUser::userId)
                .containsExactly("u-root", "u-1", "u-2", "u-3");
    }

    @Test
    void runsTheDepartmentFetchesInParallel() {
        client.clearDirectory();
        for (long id = 2; id <= 9; id++) {
            client.addDepartment(id, "部门" + id, 1);
        }
        // Four threads must be inside listSubDepartments at the same time or the barrier
        // times out: a serial loader fails this test instead of silently passing.
        CyclicBarrier barrier = new CyclicBarrier(4);
        DingTalkDirectory directory =
                new DingTalkDirectoryLoader(new BarrierClient(client, barrier), 4).load();

        assertThat(directory.departmentsScanned()).isEqualTo(9);
        assertThat(directory.failedDepartments()).isZero();
    }

    @Test
    void mergesOverlappingUsersAcrossManyDepartments() {
        client.clearDirectory();
        int departments = 20;
        for (long id = 2; id <= departments + 1; id++) {
            client.addDepartment(id, "部门" + id, 1);
            client.addUser(id, new DingUser("u-" + id, "老师" + id, null, null, "E" + id));
            client.addUser(id, new DingUser("u-shared", "共同老师", null, null, "E0"));
        }

        DingTalkDirectory directory = new DingTalkDirectoryLoader(client, 4).load();

        assertThat(directory.users()).hasSize(departments + 1);
        DingUser shared = directory.users().stream()
                .filter(user -> "u-shared".equals(user.userId()))
                .findFirst()
                .orElseThrow();
        List<String> expected = IntStream.rangeClosed(2, departments + 1)
                .mapToObj(id -> "部门" + id)
                .toList();
        assertThat(shared.departmentNames()).containsExactlyElementsOf(expected);
    }

    /** Delays the listed departments so completion order is the reverse of traversal order. */
    private static final class DelayingClient implements DingTalkClient {

        private final DingTalkClient delegate;
        private final Map<Long, Long> delaysMs;

        private DelayingClient(DingTalkClient delegate, Map<Long, Long> delaysMs) {
            this.delegate = delegate;
            this.delaysMs = delaysMs;
        }

        @Override
        public String exchangeAuthCode(String authCode) {
            return delegate.exchangeAuthCode(authCode);
        }

        @Override
        public List<DingDepartment> listSubDepartments(long departmentId) {
            sleep(delaysMs.getOrDefault(departmentId, 0L));
            return delegate.listSubDepartments(departmentId);
        }

        @Override
        public DingUserPage listUsers(long departmentId, long cursor, int size) {
            sleep(delaysMs.getOrDefault(departmentId, 0L));
            return delegate.listUsers(departmentId, cursor, size);
        }

        @Override
        public boolean sendWorkNotification(String dingtalkUserId, String title, String markdown, String link) {
            return delegate.sendWorkNotification(dingtalkUserId, title, markdown, link);
        }

        private static void sleep(long millis) {
            if (millis <= 0) {
                return;
            }
            try {
                Thread.sleep(millis);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(ex);
            }
        }
    }

    /** Holds every non-root department until {@code parties} calls are inside at once. */
    private static final class BarrierClient implements DingTalkClient {

        private final DingTalkClient delegate;
        private final CyclicBarrier barrier;

        private BarrierClient(DingTalkClient delegate, CyclicBarrier barrier) {
            this.delegate = delegate;
            this.barrier = barrier;
        }

        @Override
        public String exchangeAuthCode(String authCode) {
            return delegate.exchangeAuthCode(authCode);
        }

        @Override
        public List<DingDepartment> listSubDepartments(long departmentId) {
            if (departmentId != DingTalkDirectoryLoader.ROOT_DEPARTMENT_ID) {
                try {
                    barrier.await(5, TimeUnit.SECONDS);
                } catch (Exception ex) {
                    throw new IllegalStateException("并发度不足：等待其他部门超时", ex);
                }
            }
            return delegate.listSubDepartments(departmentId);
        }

        @Override
        public DingUserPage listUsers(long departmentId, long cursor, int size) {
            return delegate.listUsers(departmentId, cursor, size);
        }

        @Override
        public boolean sendWorkNotification(String dingtalkUserId, String title, String markdown, String link) {
            return delegate.sendWorkNotification(dingtalkUserId, title, markdown, link);
        }
    }
}
