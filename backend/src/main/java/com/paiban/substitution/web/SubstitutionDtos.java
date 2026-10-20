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
                                            Long expectedSubstituteTeacherId) {}

    public record DecisionRequest(Long substituteTeacherId, String comment) {}

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
                                         String reason,
                                         String status,
                                         String approvalMode,
                                         String approver,
                                         Instant approvedAt,
                                         String approvalComment,
                                         Instant createdAt) {}

    public record HistoryDto(Long id, String fromStatus, String toStatus, String actor,
                             String comment, Instant createdAt) {}

    public record SubstitutionRecordDto(Long id, Long requestId, Long entryId, LocalDate effectiveDate,
                                        Long classId, String className, Long courseId, String courseName,
                                        Long originalTeacherId, String originalTeacherName,
                                        Long substituteTeacherId, String substituteTeacherName,
                                        Long roomId, String roomName, String status, Instant createdAt) {}

    public record TeacherStatsDto(Long teacherId, String teacherName, long substitutedOut, long substitutedIn) {}

    public record MySubstitutionsDto(List<SubstitutionRequestDto> requests,
                                     List<SubstitutionRecordDto> records) {}
}
