package com.paiban.substitution.web;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class SubstitutionDtos {

    private SubstitutionDtos() {}

    public record CreateSubstitutionRequest(@NotNull(message = "请选择课节") Long entryId,
                                            @NotNull(message = "请选择日期") LocalDate effectiveDate,
                                            String reason,
                                            Long expectedSubstituteTeacherId,
                                            String type,
                                            Long targetPeriodId) {}

    public record DecisionRequest(Long substituteTeacherId, String comment,
                                  Long newRoomId, Long newPeriodId) {}

    public record SubstitutionRequestDto(Long id,
                                         Long entryId,
                                         Long classId,
                                         String className,
                                         Long courseId,
                                         String courseName,
                                         Long originalTeacherId,
                                         String originalTeacherName,
                                         Long substituteTeacherId,
                                         String substituteTeacherName,
                                         LocalDate effectiveDate,
                                         Long periodId,
                                         int dayOfWeek,
                                         int periodNo,
                                         String periodLabel,
                                         Long targetPeriodId,
                                         String targetPeriodLabel,
                                         String type,
                                         String reason,
                                         String status,
                                         String approvalMode,
                                         String approver,
                                         Instant approvedAt,
                                         String approvalComment,
                                         Instant createdAt) {}

    public record HistoryDto(Long id, String fromStatus, String toStatus, String actor,
                             String comment, Instant createdAt) {}

    public record SubstitutionRecordDto(Long id, Long requestId, Long entryId, String type, Long planId,
                                        LocalDate effectiveDate,
                                        Long classId, String className, Long courseId, String courseName,
                                        Long originalTeacherId, String originalTeacherName,
                                        Long substituteTeacherId, String substituteTeacherName,
                                        Long roomId, String roomName, String status, Instant createdAt,
                                        int fromDayOfWeek, int fromPeriodNo, String fromPeriodLabel,
                                        int toDayOfWeek, int toPeriodNo, String toPeriodLabel) {}

    public record TeacherStatsDto(Long teacherId, String teacherName, long substitutedOut,
                                  long substitutedIn, long swapParticipation) {}

    /** One lesson moved by a plan, with display names resolved for the UI. */
    public record PlanMoveDto(Long id,
                              Long entryId,
                              Long classId,
                              String className,
                              Long courseId,
                              String courseName,
                              Long teacherId,
                              String teacherName,
                              Long roomId,
                              String roomName,
                              Long fromPeriodId,
                              int fromDayOfWeek,
                              int fromPeriodNo,
                              String fromPeriodLabel,
                              LocalDate fromDate,
                              Long toPeriodId,
                              int toDayOfWeek,
                              int toPeriodNo,
                              String toPeriodLabel,
                              LocalDate toDate,
                              int order) {}

    public record SubstitutionPlanDto(Long id,
                                      Long requestId,
                                      String status,
                                      String rankingPreset,
                                      String rankingPresetLabel,
                                      int impactDays,
                                      int impactTeachers,
                                      int softPenalty,
                                      int cycleLength,
                                      boolean sameWeek,
                                      Instant createdAt,
                                      Instant expiresAt,
                                      List<PlanMoveDto> moves) {}

    public record DiagnosisDto(String code, String message, Long entryId, Long periodId) {}

    public record GeneratePlansResponse(Long requestId,
                                        String rankingPreset,
                                        String rankingPresetLabel,
                                        List<SubstitutionPlanDto> plans,
                                        List<DiagnosisDto> diagnoses) {}

    /** One AI recommendation. {@code teacherId} always belongs to the backend candidate pool. */
    public record RecommendationDto(Long teacherId, String name, String reason, Integer score) {}

    public record RecommendationResponse(Long requestId,
                                         List<RecommendationDto> recommendations,
                                         String providerName,
                                         String modelName) {}

    public record MySubstitutionsDto(List<SubstitutionRequestDto> requests,
                                     List<SubstitutionRecordDto> records) {}
}
