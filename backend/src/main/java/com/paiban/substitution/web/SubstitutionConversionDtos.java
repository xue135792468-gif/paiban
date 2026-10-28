package com.paiban.substitution.web;

import com.paiban.substitution.conversion.LegacyConversionPlanner;
import java.time.Instant;
import java.util.List;

/**
 * 历史转换（票 02）的 HTTP 契约：预检报告、转换状态、确认批次与确认结果。
 * 预检与状态只读；确认走数据切换门，成功与失败都以批次留痕。
 */
public final class SubstitutionConversionDtos {

    private SubstitutionConversionDtos() {}

    /** 预检报告：逐条结论 + 汇总（同一次推导派生，数量恒一致）。 */
    public record ConversionPrecheckDto(
            Long semesterId,
            Instant generatedAt,
            SummaryDto summary,
            List<ItemDto> items) {}

    public record SummaryDto(
            long totalRows,
            long convertible,
            long blocked,
            long alreadyConverted,
            long changes) {}

    /** 逐条记录：日期依据、推导出的实例日期或阻塞原因、以及转换前后差异。 */
    public record ItemDto(
            long substitutionId,
            Long requestId,
            Long planId,
            long entryId,
            String type,
            String status,
            String basis,
            String effectiveDate,
            String lessonDate,
            String targetLessonDate,
            String blockerCode,
            String blockerReason,
            String className,
            String courseName,
            String originalTeacherName,
            String substituteTeacherName,
            String fromPeriodLabel,
            List<AffectedDateDto> affectedDates) {}

    /** 某一天该条目转换前后的差异；before/after 为 null 表示该日无课。 */
    public record AffectedDateDto(
            String date,
            boolean restoredToBaseline,
            LessonDto before,
            LessonDto after) {}

    public record LessonDto(
            Long substitutionId,
            Long teacherId,
            String teacherName,
            Long roomId,
            String roomName,
            int dayOfWeek,
            int periodNo,
            String periodLabel,
            String className,
            String courseName) {}

    /** 转换状态（票 03 的数据级判定也读这里）：未转换 / 已转换 / 含阻塞。 */
    public record ConversionStatusDto(
            Long semesterId,
            long legacyRowCount,
            long convertibleCount,
            long blockedCount,
            boolean converted,
            boolean canConfirm,
            List<ConversionBatchDto> conversions) {}

    /** 一条确认批次（CONVERTED）或失败记录（FAILED，含失败原因）。 */
    public record ConversionBatchDto(
            long id,
            String status,
            long legacyTotal,
            long convertedCount,
            long blockedCount,
            String confirmedBy,
            Instant confirmedAt,
            String confirmationComment,
            String failureReason,
            Instant createdAt,
            List<ConversionItemRecordDto> items) {}

    public record ConversionItemRecordDto(
            long substitutionId,
            long entryId,
            String type,
            String lessonDateBefore,
            String targetLessonDateBefore,
            String lessonDateAfter,
            String targetLessonDateAfter,
            String basis) {}

    /** 确认转换的结果：幂等重跑时 convertedCount 为 0 且 alreadyConverted 为 true。 */
    public record ConversionConfirmResultDto(
            long convertedCount,
            long blockedCount,
            boolean alreadyConverted,
            ConversionBatchDto conversion) {}

    public static SummaryDto toSummaryDto(LegacyConversionPlanner.Summary summary) {
        return new SummaryDto(
                summary.totalRows(),
                summary.convertible(),
                summary.blocked(),
                summary.alreadyConverted(),
                summary.changes());
    }
}
