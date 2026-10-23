package com.paiban.integration.org;

import com.paiban.integration.dingtalk.DingTalkApiException;
import com.paiban.integration.dingtalk.DingTalkClient;
import com.paiban.integration.dingtalk.DingUserPage;
import com.paiban.integration.domain.DingDepartment;
import com.paiban.integration.domain.DingUser;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Walks the DingTalk department tree depth-first and returns every readable user.
 *
 * <p>Scope is decided entirely by the tenant: the loader starts at the root
 * department and never filters by a local allowlist. Negative departments (such as
 * {@code -7}, the 家校通讯录) are skipped because they are not staff directories;
 * a department the tenant refuses to list is skipped as well so one bad branch does
 * not abort the whole sync.
 */
@Component
public class DingTalkDirectoryLoader {

    public static final long ROOT_DEPARTMENT_ID = 1L;
    private static final int PAGE_SIZE = 100;

    private final DingTalkClient client;

    public DingTalkDirectoryLoader(DingTalkClient client) {
        this.client = client;
    }

    public DingTalkDirectory load() {
        Map<Long, DingDepartment> scanned = new LinkedHashMap<>();
        Map<Long, DingDepartment> skipped = new LinkedHashMap<>();
        Map<String, DingUser> merged = new LinkedHashMap<>();
        Deque<DingDepartment> pending = new ArrayDeque<>();
        pending.push(new DingDepartment(ROOT_DEPARTMENT_ID, "根部门", null));

        int failed = 0;
        while (!pending.isEmpty()) {
            DingDepartment department = pending.pop();
            if (department.isSpecial()) {
                skipped.put(department.id(), department);
                continue;
            }
            if (scanned.containsKey(department.id())) {
                continue;
            }
            scanned.put(department.id(), department);

            List<DingDepartment> children;
            try {
                children = client.listSubDepartments(department.id());
            } catch (DingTalkApiException ex) {
                if (!ex.isDepartmentUnavailable()) {
                    throw ex;
                }
                failed++;
                children = List.of();
            }
            // Push in reverse so the first child is visited first: the traversal order
            // (and therefore preview grouping) stays stable across runs.
            for (int i = children.size() - 1; i >= 0; i--) {
                DingDepartment child = children.get(i);
                if (child.isSpecial()) {
                    skipped.put(child.id(), child);
                } else {
                    pending.push(child);
                }
            }

            try {
                loadUsers(department, merged);
            } catch (DingTalkApiException ex) {
                if (!ex.isDepartmentUnavailable()) {
                    throw ex;
                }
                failed++;
            }
        }
        return new DingTalkDirectory(
                new ArrayList<>(merged.values()),
                new ArrayList<>(scanned.values()),
                new ArrayList<>(skipped.values()),
                failed);
    }

    private void loadUsers(DingDepartment department, Map<String, DingUser> merged) {
        long cursor = 0;
        while (true) {
            DingUserPage page = client.listUsers(department.id(), cursor, PAGE_SIZE);
            for (DingUser user : page.users()) {
                if (user.userId() == null || user.userId().isBlank()) {
                    continue;
                }
                merged.merge(user.userId(), tagWithDepartment(user, department), DingTalkDirectoryLoader::merge);
            }
            if (!page.hasMore()) {
                return;
            }
            long next = page.nextCursor();
            if (next == cursor) {
                // A broken cursor must not turn into an infinite loop.
                return;
            }
            cursor = next;
        }
    }

    private DingUser tagWithDepartment(DingUser user, DingDepartment department) {
        boolean leaverDepartment = department.name() != null && department.name().contains("离职");
        List<String> departments = department.name() == null || department.name().isBlank()
                ? user.departmentNames()
                : List.of(department.name());
        return new DingUser(
                user.userId(),
                user.name(),
                user.mobile(),
                user.email(),
                user.jobNumber(),
                user.active() && !leaverDepartment,
                departments);
    }

    /** Keeps the first non-null value of each field and unions the departments. */
    private static DingUser merge(DingUser first, DingUser second) {
        List<String> departments = new ArrayList<>(first.departmentNames());
        for (String name : second.departmentNames()) {
            if (!departments.contains(name)) {
                departments.add(name);
            }
        }
        return new DingUser(
                first.userId(),
                first.name() != null ? first.name() : second.name(),
                first.mobile() != null ? first.mobile() : second.mobile(),
                first.email() != null ? first.email() : second.email(),
                first.jobNumber() != null ? first.jobNumber() : second.jobNumber(),
                // A user listed as leaving anywhere stays inactive.
                first.active() && second.active(),
                departments);
    }
}
