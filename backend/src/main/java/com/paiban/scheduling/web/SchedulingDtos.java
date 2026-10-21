package com.paiban.scheduling.web;

import com.paiban.foundation.web.SemesterDtos.PeriodDto;
import com.paiban.foundation.web.FoundationDtos.RoomDto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;

public final class SchedulingDtos {

    private SchedulingDtos() {}

    public record StartJobRequest(String draftName,
                                  @Min(value = 1, message = "求解时长至少 1 秒")
                                  @Max(value = 600, message = "求解时长最多 600 秒") Integer maxSeconds) {}

    public record JobDto(Long id, Long semesterId, String status, String score, int progress,
                         long elapsedMs, long estimatedRemainingMs, String errorMessage, Long draftId,
                         String draftName, int solveSeconds, Instant createdAt, Instant startedAt,
                         Instant finishedAt) {}

    public record DraftDto(Long id, Long semesterId, String name, String status, int scoreHard,
                           int scoreSoft, String createdBy, Instant createdAt, long lessonCount) {}

    public record DraftLessonDto(Long id, Long assignmentId, Long courseId, String courseName,
                                 Long teacherId, String teacherName, Long classId, String className,
                                 int studentCount, Long periodId, Integer dayOfWeek, Integer periodNo,
                                 String section, String periodLabel, Long roomId, String roomName) {}

    public record DraftDetailDto(DraftDto draft, List<DraftLessonDto> lessons,
                                 List<PeriodDto> periods, List<RoomDto> rooms) {}

    public record ValidateMoveRequest(@NotNull(message = "课节不能为空") Long lessonId,
                                      Long periodId, Long roomId) {}

    /**
     * One in-memory change to a draft lesson. A null field leaves that attribute
     * unchanged, which is what teacher-only overrides (cross-class teacher exchange)
     * need. Duplicate lesson ids merge field by field; later values win.
     */
    public record LessonOverride(@NotNull(message = "课节不能为空") Long lessonId,
                                 Long periodId, Long roomId, Long teacherId) {}

    public record ValidateMovesRequest(@NotNull(message = "覆盖列表不能为空")
                                       @Valid List<LessonOverride> moves) {}

    public record MoveLessonRequest(Long periodId, Long roomId, Boolean force) {}

    public record RenameDraftRequest(@NotBlank(message = "名称不能为空") String name) {}

    public record MoveResult(boolean saved, boolean valid, int hardConflicts,
                             List<com.paiban.scheduling.service.DraftValidator.Conflict> conflicts) {}
}
