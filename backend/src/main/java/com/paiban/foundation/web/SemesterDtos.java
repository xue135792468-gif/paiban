package com.paiban.foundation.web;

import com.paiban.foundation.domain.PeriodSection;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public final class SemesterDtos {

    private SemesterDtos() {}

    public record SemesterDto(Long id, String name, LocalDate startDate, LocalDate endDate, boolean current) {}

    public record SaveSemesterRequest(@NotBlank(message = "学期名称不能为空") String name,
                                      @NotNull(message = "开始日期不能为空") LocalDate startDate,
                                      @NotNull(message = "结束日期不能为空") LocalDate endDate) {}

    public record PeriodDto(Long id,
                            int dayOfWeek,
                            int periodNo,
                            String label,
                            PeriodSection section,
                            LocalTime startTime,
                            LocalTime endTime) {}

    public record PeriodSlot(@NotNull Integer periodNo,
                             @NotBlank String label,
                             @NotNull PeriodSection section,
                             @NotNull LocalTime startTime,
                             @NotNull LocalTime endTime,
                             @NotEmpty(message = "至少选择一个星期") List<Integer> days) {}

    public record SavePeriodsRequest(@NotEmpty List<@Valid PeriodSlot> slots) {}

    public record GenerateDefaultPeriodsRequest(Integer morningCount,
                                                Integer afternoonCount,
                                                Integer eveningCount,
                                                List<Integer> days) {}

    public record CopyResultDto(int periods, int grades, int classes, int assignments) {}
}
