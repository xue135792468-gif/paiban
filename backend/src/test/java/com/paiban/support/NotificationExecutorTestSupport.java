package com.paiban.support;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** Bounded FIFO barrier for the single-worker notification executor. */
public final class NotificationExecutorTestSupport {

    private static final long TIMEOUT_SECONDS = 10;

    private NotificationExecutorTestSupport() {}

    public static void awaitIdle(ThreadPoolTaskExecutor executor) {
        CompletableFuture<Void> sentinel = new CompletableFuture<>();
        try {
            executor.execute(() -> sentinel.complete(null));
            sentinel.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError("等待通知 executor 屏障时被中断", ex);
        } catch (ExecutionException | TimeoutException ex) {
            throw new AssertionError("通知 executor 未在 10 秒内完成 FIFO 屏障", ex);
        }

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (executor.getActiveCount() > 0
                || executor.getThreadPoolExecutor().getQueue().size() > 0) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("通知 executor 未在 10 秒内空闲");
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AssertionError("等待通知 executor 空闲时被中断", ex);
            }
        }
    }
}
