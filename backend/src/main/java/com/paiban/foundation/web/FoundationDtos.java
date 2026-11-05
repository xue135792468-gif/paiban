package com.paiban.foundation.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public final class FoundationDtos {

    private FoundationDtos() {}

    public record TeacherDto(Long id, String name, String employeeNo, String phone, String email,
                             String subject, boolean active, String dingtalkUserId,
                             String bindingStatus) {}

    public record TeacherOptionDto(Long id, String name) {}

    public record SaveTeacherRequest(@NotBlank(message = "姓名不能为空") @Size(max = 100) String name,
                                     @Size(max = 50) String employeeNo,
                                     @Size(max = 30) String phone,
                                     @Size(max = 120) String email,
                                     @Size(max = 100) String subject,
                                     Boolean active,
                                     @Size(max = 100) String dingtalkUserId) {}

    public record CourseDto(Long id, String name, String subject, boolean allowConsecutive,
                            String color, boolean active) {}

    public record SaveCourseRequest(@NotBlank(message = "课程名称不能为空") @Size(max = 100) String name,
                                    @Size(max = 100) String subject,
                                    Boolean allowConsecutive,
                                    @Size(max = 20) String color) {}

    public record GradeDto(Long id, String name, int sortOrder, boolean active) {}

    public record SaveGradeRequest(@NotBlank(message = "年级名称不能为空") @Size(max = 100) String name,
                                   Integer sortOrder) {}

    public record ClassDto(Long id, String name, Long gradeId, String gradeName, Long headTeacherId,
                           String headTeacherName, int studentCount, Long teachingClassId,
                           boolean active) {}

    public record SaveClassRequest(@NotNull(message = "年级不能为空") Long gradeId,
                                   @NotBlank(message = "班级名称不能为空") @Size(max = 100) String name,
                                   Long headTeacherId,
                                   @Positive Integer studentCount) {}

    public record AuditLogDto(Long id, String actor, String action, String entityType, String detail,
                              int successRows, int failedRows, Instant createdAt) {}
}
