package com.paiban.substitution.service;

import com.paiban.common.web.ApiException;
import java.time.Instant;
import java.util.function.Supplier;

/**
 * 开课截止复查器（规格第 6 节）：每次调用实时读时钟，绝不缓存——
 * 「等待行锁后必须重新读取时间，避免排队跨点」。服务在获取学期行锁之后调用它；
 * 预览提交与确认生效前各复查一次。
 */
public final class DeadlineChecker {

    private final Supplier<Instant> clock;

    /** 生产环境传 clock::instant；测试传可控时钟面。 */
    public DeadlineChecker(Supplier<Instant> clock) {
        this.clock = clock;
    }

    /** 当前时间必须严格早于截止，否则抛 409 冲突，说明是哪一节课挡住了本次操作。 */
    public void requireBeforeDeadline(Instant deadline, String lessonLabel) {
        Instant now = clock.get();
        if (!LessonDeadline.canConfirm(now, deadline)) {
            throw ApiException.conflict("已到开课时刻或已开课（" + lessonLabel + "），不能再调整");
        }
    }
}
