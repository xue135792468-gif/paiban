package com.paiban.scheduling.web;

import com.paiban.foundation.web.SemesterDtos.PeriodDto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;

public final class SchedulingDtos {

    private SchedulingDtos() {}

    /**
     * {@code sourceDraftId} is optional and appended last so an old client that omits it
     * keeps the legacy default warm-start policy exactly. When set, the job optimises from
     * that concrete draft and carries its locks and current arrangement into the new draft.
     *
     * <p>{@code gradeIds} is also appended last for the same compatibility reason. Null or
     * empty means "all grades"; otherwise only the selected grades are movable and every
     * other grade's already-scheduled lessons become fixed facts in the same problem.
     */
    public record StartJobRequest(String draftName,
                                  @Min(value = 1, message = "求解时长至少 1 秒")
                                  @Max(value = 600, message = "求解时长最多 600 秒") Integer maxSeconds,
                                  Long sourceDraftId,
                                  List<Long> gradeIds) {}

    public record JobDiagnosticDto(Long teacherId, String teacherName, int hardCount,
                                   List<String> messages, List<Long> gradeIds,
                                   List<String> classNames) {}

    public record JobDto(Long id, Long semesterId, String status, String score, int progress,
                         long elapsedMs, long estimatedRemainingMs, String errorMessage, Long draftId,
                         String draftName, String warmStartSource, int solveSeconds, Instant createdAt,
                         Instant startedAt, Instant finishedAt,
                         Long sourceDraftId, Long sourceDraftVersion, Integer inheritedLockCount,
                         Boolean resultPublishable, List<String> resultBlockers,
                         List<Long> gradeIds, List<JobDiagnosticDto> diagnostics) {}

    public record DraftDto(Long id, Long semesterId, String name, String status, int scoreHard,
                           int scoreSoft, String createdBy, Instant createdAt, long lessonCount,
                           long version, long lockedCount) {}

    public record DraftLessonDto(Long id, Long assignmentId, int lessonIndex, Long courseId,
                                 String courseName, Long teacherId, String teacherName,
                                 Long classId, String className, int studentCount, Long periodId,
                                 Integer dayOfWeek, Integer periodNo,
                                 String periodLabel, boolean locked) {}

    public record BlockedSlotDto(Long classId, Long periodId, String reason) {}

    public record DraftDetailDto(DraftDto draft, List<DraftLessonDto> lessons,
                                 List<PeriodDto> periods,
                                 List<BlockedSlotDto> blockedSlots, String rulesFingerprint) {}

    public record ValidateMoveRequest(@NotNull(message = "课节不能为空") Long lessonId,
                                      Long periodId) {}

    /**
     * One in-memory change to a draft lesson. A null field leaves that attribute
     * unchanged, which is what teacher-only overrides (cross-class teacher exchange)
     * need. Duplicate lesson ids merge field by field; later values win.
     */
    public record LessonOverride(@NotNull(message = "课节不能为空") Long lessonId,
                                 Long periodId, Long teacherId) {}

    public record ValidateMovesRequest(@NotNull(message = "覆盖列表不能为空")
                                       @Valid List<LessonOverride> moves) {}

    public record MoveLessonRequest(Long periodId, Boolean force, Long expectedVersion) {}

    public record RenameDraftRequest(@NotBlank(message = "名称不能为空") String name) {}

    public record MoveResult(boolean saved, boolean valid, int hardConflicts,
                             List<com.paiban.scheduling.service.DraftValidator.Conflict> conflicts,
                             long version) {}
}
