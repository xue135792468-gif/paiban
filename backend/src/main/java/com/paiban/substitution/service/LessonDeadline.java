package com.paiban.substitution.service;

import com.paiban.common.web.ApiException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 开课截止计算（规格第 6 节）。学校时区固定 Asia/Shanghai：
 * 服务端权威时钟 + 课节开始时间组成开课时刻；本单截止 = 本次将改变的所有实例
 * 「当前安排与拟生效安排」开课时刻的最小值；确认只允许严格早于截止（等于也拒绝）。
 *
 * <p>纯函数，无 Spring 依赖，方便对多实例取最小、边界三分做纯单测。
 */
public final class LessonDeadline {

    /** 本期学校时区固定值，不随服务器默认时区漂移。 */
    public static final ZoneId SCHOOL_ZONE = ZoneId.of("Asia/Shanghai");

    /**
     * 一笔将被改变的实例时刻：代课/调课里每个「当前安排」与「拟生效安排」
     * 各算一个槽位（日期 + 课节开始时间）。
     */
    public record ChangedSlot(LocalDate lessonDate, LocalTime startTime) {}

    /** 时区、日期或课节开始时间无法确定时抛出，消息列出缺失项，不用默认时间猜测。 */
    public static final class MissingStartTimeException extends RuntimeException {

        public MissingStartTimeException(String message) {
            super(message);
        }
    }

    private LessonDeadline() {}

    /** 取所有槽位开课时刻的最小值；任一槽位缺开始时间即失败并列出缺失项。 */
    public static Instant earliest(List<ChangedSlot> slots) {
        List<String> missing = new ArrayList<>();
        Instant earliest = null;
        for (ChangedSlot slot : slots) {
            if (slot.startTime() == null || slot.lessonDate() == null) {
                missing.add(slot.lessonDate() == null
                        ? "缺失授课日期"
                        : slot.lessonDate() + " 缺少课节开始时间");
                continue;
            }
            Instant start = ZonedDateTime.of(slot.lessonDate(), slot.startTime(), SCHOOL_ZONE)
                    .toInstant();
            if (earliest == null || start.isBefore(earliest)) {
                earliest = start;
            }
        }
        if (!missing.isEmpty()) {
            throw new MissingStartTimeException(
                    "无法确定开课时刻，拒绝写入（缺失项：" + String.join("；", missing) + "）");
        }
        if (earliest == null) {
            throw new MissingStartTimeException("无法确定开课时刻，拒绝写入（没有任何实例时刻）");
        }
        return earliest;
    }

    /** 严格小于截止才可确认；恰好开课（等于）或之后都拒绝。 */
    public static boolean canConfirm(Instant now, Instant deadline) {
        return now.isBefore(deadline);
    }
}
