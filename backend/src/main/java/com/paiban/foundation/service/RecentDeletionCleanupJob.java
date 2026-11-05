package com.paiban.foundation.service;

import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Daily purge of expired “最近删除” markers (03:30 Asia/Shanghai). The purge logic
 * lives in {@link LifecycleService#purgeExpired(Instant)} so it can be exercised
 * directly by tests; this class only owns the schedule.
 */
@Component
public class RecentDeletionCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(RecentDeletionCleanupJob.class);

    private final LifecycleService lifecycle;
    private final Clock clock;

    public RecentDeletionCleanupJob(LifecycleService lifecycle, Clock clock) {
        this.lifecycle = lifecycle;
        this.clock = clock;
    }

    @Scheduled(cron = "0 30 3 * * *", zone = "Asia/Shanghai")
    public void purgeExpiredRecycleRows() {
        int purged = lifecycle.purgeExpired(Instant.now(clock));
        if (purged > 0) {
            log.info("清理过期“最近删除”记录 {} 条", purged);
        }
    }
}
