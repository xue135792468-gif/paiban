package com.paiban.scheduling.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public final class AssignmentDtos {

    private AssignmentDtos() {}

    public record AssignmentDto(Long id,
                                Long classId,
                                String className,
                                Long gradeId,
                                String gradeName,
                                Long courseId,
                                String courseName,
                                Long teacherId,
                                String teacherName,
                                int weeklyLessons,
                                Long teachingClassId) {}

    public record SaveAssignmentRequest(@NotNull(message = "班级不能为空") Long classId,
                                        @NotNull(message = "课程不能为空") Long courseId,
                                        @NotNull(message = "教师不能为空") Long teacherId,
                                        @NotNull(message = "每周节数不能为空")
                                        @Min(value = 1, message = "每周至少 1 节")
                                        @Max(value = 20, message = "每周最多 20 节") Integer weeklyLessons) {}
}
