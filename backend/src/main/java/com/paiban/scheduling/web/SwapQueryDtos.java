package com.paiban.scheduling.web;

import com.paiban.scheduling.service.DraftValidator;
import java.util.List;

/**
 * Query DTOs for manual adjustment. Kept apart from {@link SchedulingDtos} so the
 * write-side contracts can evolve independently.
 */
public final class SwapQueryDtos {

    private SwapQueryDtos() {}

    /** Which swap groups the caller wants; ALL returns both. */
    public enum SwapType {
        ALL,
        INTRA_CLASS,
        CROSS_CLASS
    }

    /**
     * One lesson that can be exchanged with the target lesson. {@code conflicts} is
     * empty when {@code available} is true; warnings flag checks that were skipped
     * because the data needed for them is missing (for example a blank teacher subject).
     * {@code roomSwapRequired} means the exchange is only valid when the two rooms are
     * exchanged as well, so the client must send {@code swapRooms=true}.
     */
    public record SwapCandidateDto(
            Long lessonId,
            Long courseId,
            String courseName,
            String subject,
            Long teacherId,
            String teacherName,
            Long classId,
            String className,
            Long gradeId,
            String gradeName,
            Long periodId,
            Integer dayOfWeek,
            Integer periodNo,
            String periodLabel,
            Long roomId,
            String roomName,
            boolean available,
            boolean roomSwapRequired,
            List<DraftValidator.Conflict> conflicts,
            List<String> warnings) {}

    public record SwapCandidatesResponse(
            Long lessonId,
            List<SwapCandidateDto> intraClass,
            List<SwapCandidateDto> crossClass) {}

    /** Result of moving the target lesson to one period; the room never changes. */
    public record MoveTargetDto(
            Long periodId,
            Integer dayOfWeek,
            Integer periodNo,
            String periodLabel,
            String section,
            boolean available,
            int hardConflicts,
            List<DraftValidator.Conflict> conflicts) {}

    public record MoveTargetsResponse(
            Long lessonId,
            Long roomId,
            List<MoveTargetDto> targets) {}
}
