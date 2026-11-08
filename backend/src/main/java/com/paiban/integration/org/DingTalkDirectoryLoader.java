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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Walks the DingTalk department tree and returns every readable user.
 *
 * <p>Scope is decided entirely by the tenant: the loader starts at the root
 * department and never filters by a local allowlist. Negative departments (such as
 * {@code -7}, the 家校通讯录) are skipped because they are not staff directories;
 * a department the tenant refuses to list is skipped as well so one bad branch does
 * not abort the whole sync.
 *
 * <p>Every department costs two round trips (its children, then its users), and a real
 * tenant has around fifty of them. Walking that serially took 54 seconds in production and
 * outlived the browser's 30-second request timeout, so the fetches now run on a small
 * fixed pool ({@code paiban.integration.dingtalk.load-concurrency}, default 4). Only the
 * network calls are concurrent: the tree is discovered level by level and assembled
 * afterwards in the same depth-first order the serial walk produced, so preview grouping
 * and duplicate resolution stay identical from run to run.
 */
@Component
public class DingTalkDirectoryLoader {

    public static final long ROOT_DEPARTMENT_ID = 1L;
    private static final int PAGE_SIZE = 100;
    private static final int DEFAULT_LOAD_CONCURRENCY = 4;

    private final DingTalkClient client;
    private final int loadConcurrency;

    /** Unit-test seam; Spring uses the configured overload below. */
    public DingTalkDirectoryLoader(DingTalkClient client) {
        this(client, DEFAULT_LOAD_CONCURRENCY);
    }

    @Autowired
    public DingTalkDirectoryLoader(
            DingTalkClient client,
            @Value("${paiban.integration.dingtalk.load-concurrency:4}") int loadConcurrency) {
        this.client = client;
        this.loadConcurrency = Math.max(1, loadConcurrency);
    }

    public DingTalkDirectory load() {
        ExecutorService pool = Executors.newFixedThreadPool(loadConcurrency, task -> {
            Thread thread = new Thread(task, "dingtalk-directory");
            thread.setDaemon(true);
            return thread;
        });
        try {
            return loadWith(pool);
        } finally {
            pool.shutdownNow();
        }
    }

    private DingTalkDirectory loadWith(ExecutorService pool) {
        Map<Long, DingDepartment> discovered = new LinkedHashMap<>();
        Map<Long, List<Long>> childrenByParent = new LinkedHashMap<>();
        Map<Long, DingDepartment> skipped = new LinkedHashMap<>();
        AtomicInteger failedCalls = new AtomicInteger();

        DingDepartment root = new DingDepartment(ROOT_DEPARTMENT_ID, "根部门", null);
        discovered.put(root.id(), root);
        childrenByParent.put(root.id(), new ArrayList<>());

        List<DingDepartment> level = List.of(root);
        while (!level.isEmpty()) {
            List<Long> levelIds = level.stream().map(DingDepartment::id).toList();
            Map<Long, List<DingDepartment>> children = fetch(
                    pool, levelIds, client::listSubDepartments, List::of, failedCalls);
            List<DingDepartment> next = new ArrayList<>();
            for (DingDepartment department : level) {
                for (DingDepartment child : children.getOrDefault(department.id(), List.of())) {
                    if (child.isSpecial()) {
                        skipped.put(child.id(), child);
                        continue;
                    }
                    if (discovered.containsKey(child.id())) {
                        // A department listed under two parents is visited once, as before.
                        continue;
                    }
                    discovered.put(child.id(), child);
                    childrenByParent.put(child.id(), new ArrayList<>());
                    childrenByParent.get(department.id()).add(child.id());
                    next.add(child);
                }
            }
            level = next;
        }

        // Fetch users for every discovered department, then replay the results in the
        // depth-first order the serial walk produced so the merge order never depends on
        // which thread happened to finish first.
        List<Long> order = depthFirstOrder(childrenByParent);
        Map<Long, UserLoad> users = fetch(
                pool,
                order,
                this::loadUsers,
                () -> new UserLoad(List.of(), true),
                failedCalls);

        Map<String, DingUser> merged = new LinkedHashMap<>();
        List<DingDepartment> scanned = new ArrayList<>();
        for (Long id : order) {
            DingDepartment department = discovered.get(id);
            if (department == null) {
                continue;
            }
            scanned.add(department);
            UserLoad load = users.get(id);
            if (load == null) {
                continue;
            }
            if (load.unavailable()) {
                // A department whose user list is unreadable still counts as one skipped
                // branch; the pages already read above are kept, exactly as before.
                failedCalls.incrementAndGet();
            }
            for (DingUser user : load.users()) {
                if (user.userId() == null || user.userId().isBlank()) {
                    continue;
                }
                merged.merge(user.userId(), tagWithDepartment(user, department), DingTalkDirectoryLoader::merge);
            }
        }
        return new DingTalkDirectory(
                new ArrayList<>(merged.values()),
                scanned,
                new ArrayList<>(skipped.values()),
                failedCalls.get());
    }

    private List<Long> depthFirstOrder(Map<Long, List<Long>> childrenByParent) {
        List<Long> order = new ArrayList<>();
        Deque<Long> pending = new ArrayDeque<>();
        pending.push(ROOT_DEPARTMENT_ID);
        while (!pending.isEmpty()) {
            Long id = pending.pop();
            order.add(id);
            List<Long> children = childrenByParent.getOrDefault(id, List.of());
            for (int i = children.size() - 1; i >= 0; i--) {
                pending.push(children.get(i));
            }
        }
        return order;
    }

    private UserLoad loadUsers(long departmentId) {
        List<DingUser> collected = new ArrayList<>();
        try {
            long cursor = 0;
            while (true) {
                DingUserPage page = client.listUsers(departmentId, cursor, PAGE_SIZE);
                collected.addAll(page.users());
                if (!page.hasMore()) {
                    return new UserLoad(collected, false);
                }
                long next = page.nextCursor();
                if (next == cursor) {
                    // A broken cursor must not turn into an infinite loop.
                    return new UserLoad(collected, false);
                }
                cursor = next;
            }
        } catch (DingTalkApiException ex) {
            if (ex.isDepartmentUnavailable()) {
                return new UserLoad(collected, true);
            }
            throw ex;
        }
    }

    /**
     * Runs one call per id on the pool and returns the results keyed by id. A department the
     * tenant refuses to list is counted and replaced with the fallback; anything else is
     * rethrown as the original exception so callers keep their established error contract.
     */
    private <T> Map<Long, T> fetch(
            ExecutorService pool,
            List<Long> ids,
            ValueLoader<T> loader,
            Supplier<T> unavailableFallback,
            AtomicInteger failedCalls) {
        Map<Long, Future<T>> futures = new LinkedHashMap<>();
        for (Long id : ids) {
            futures.put(id, pool.submit(() -> loader.load(id)));
        }
        Map<Long, T> values = new LinkedHashMap<>();
        RuntimeException fatal = null;
        for (Map.Entry<Long, Future<T>> entry : futures.entrySet()) {
            try {
                values.put(entry.getKey(), entry.getValue().get());
            } catch (ExecutionException ex) {
                Throwable cause = ex.getCause();
                if (cause instanceof DingTalkApiException api && api.isDepartmentUnavailable()) {
                    failedCalls.incrementAndGet();
                    values.put(entry.getKey(), unavailableFallback.get());
                } else if (cause instanceof RuntimeException runtime) {
                    fatal = fatal == null ? runtime : fatal;
                } else {
                    fatal = fatal == null ? new IllegalStateException(cause) : fatal;
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("拉取钉钉通讯录被中断", ex);
            }
        }
        if (fatal != null) {
            throw fatal;
        }
        return values;
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

    /** One department's users plus whether the tenant refused the list mid-way. */
    private record UserLoad(List<DingUser> users, boolean unavailable) {
    }

    @FunctionalInterface
    private interface ValueLoader<T> {
        T load(Long id);
    }
}
