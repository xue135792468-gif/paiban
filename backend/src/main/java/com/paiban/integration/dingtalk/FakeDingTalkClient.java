package com.paiban.integration.dingtalk;

import com.paiban.integration.domain.DingDepartment;
import com.paiban.integration.domain.DingUser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Deterministic stand-in used by tests and by developers without a DingTalk tenant.
 * Tests build arbitrary department trees through the mutators; {@link #resetToDefault()}
 * restores the two-teacher baseline so no test inherits another test's tree.
 */
@Component
@ConditionalOnProperty(name = "paiban.integration.dingtalk.fake", havingValue = "true")
public class FakeDingTalkClient implements DingTalkClient {

    public static final long ROOT_DEPARTMENT_ID = 1L;

    private final Map<Long, DingDepartment> departments = new LinkedHashMap<>();
    private final Map<Long, Map<String, DingUser>> usersByDepartment = new LinkedHashMap<>();
    private final Map<Long, DingTalkApiException> userListFailures = new ConcurrentHashMap<>();
    private final Map<Long, Integer> userListCalls = new ConcurrentHashMap<>();
    private int pageSizeLimit = 100;

    public FakeDingTalkClient() {
        resetToDefault();
    }

    public synchronized void resetToDefault() {
        clearDirectory();
        pageSizeLimit = 100;
        addUser(ROOT_DEPARTMENT_ID, new DingUser("dd-001", "张三", "13800000001", null, "EMP001"));
        addUser(ROOT_DEPARTMENT_ID, new DingUser("dd-002", "李四", "13800000002", null, "EMP002"));
    }

    /** Empties the tree but keeps the root department, for tests that build their own. */
    public synchronized FakeDingTalkClient clearDirectory() {
        departments.clear();
        usersByDepartment.clear();
        userListFailures.clear();
        userListCalls.clear();
        departments.put(ROOT_DEPARTMENT_ID, new DingDepartment(ROOT_DEPARTMENT_ID, "根部门", null));
        usersByDepartment.put(ROOT_DEPARTMENT_ID, new LinkedHashMap<>());
        return this;
    }

    public synchronized FakeDingTalkClient addDepartment(long id, String name, long parentId) {
        departments.put(id, new DingDepartment(id, name, parentId));
        usersByDepartment.putIfAbsent(id, new LinkedHashMap<>());
        return this;
    }

    public synchronized FakeDingTalkClient removeDepartment(long id) {
        departments.remove(id);
        usersByDepartment.remove(id);
        return this;
    }

    public synchronized FakeDingTalkClient addUser(long departmentId, DingUser user) {
        departments.putIfAbsent(departmentId, new DingDepartment(departmentId, null, null));
        usersByDepartment.computeIfAbsent(departmentId, ignored -> new LinkedHashMap<>())
                .put(user.userId(), user);
        return this;
    }

    /** Caps how many users one page carries, so tests can exercise cursor paging. */
    public synchronized FakeDingTalkClient pageSizeLimit(int limit) {
        this.pageSizeLimit = Math.max(1, limit);
        return this;
    }

    /** Makes {@code user/list} for this department fail the way the real API does. */
    public synchronized FakeDingTalkClient failUserList(long departmentId, DingTalkApiException exception) {
        userListFailures.put(departmentId, exception);
        return this;
    }

    /** How many times {@code user/list} was called for a department (including failed calls). */
    public int userListCalls(long departmentId) {
        return userListCalls.getOrDefault(departmentId, 0);
    }

    @Override
    public String exchangeAuthCode(String authCode) {
        if (authCode != null && authCode.startsWith("fake:")) {
            String employeeNo = authCode.substring("fake:".length());
            return allUsers().stream()
                    .filter(user -> employeeNo.equals(user.jobNumber()))
                    .map(DingUser::userId)
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("未知的测试工号: " + employeeNo));
        }
        throw new IllegalArgumentException("无效的测试 authCode");
    }

    @Override
    public synchronized List<DingDepartment> listSubDepartments(long departmentId) {
        return departments.values().stream()
                .filter(department -> Objects.equals(department.parentId(), departmentId))
                .toList();
    }

    @Override
    public synchronized DingUserPage listUsers(long departmentId, long cursor, int size) {
        userListCalls.merge(departmentId, 1, Integer::sum);
        DingTalkApiException failure = userListFailures.get(departmentId);
        if (failure != null) {
            throw failure;
        }
        List<DingUser> all = new ArrayList<>(usersByDepartment.getOrDefault(departmentId, Map.of()).values());
        int effectiveSize = Math.max(1, Math.min(size, pageSizeLimit));
        int from = (int) Math.min(Math.max(cursor, 0), all.size());
        int to = Math.min(from + effectiveSize, all.size());
        return new DingUserPage(all.subList(from, to), to < all.size(), to);
    }

    @Override
    public boolean sendWorkNotification(String dingtalkUserId, String title, String markdown, String link) {
        return dingtalkUserId != null && !dingtalkUserId.isBlank();
    }

    private List<DingUser> allUsers() {
        return usersByDepartment.values().stream()
                .flatMap(users -> users.values().stream())
                .toList();
    }
}
