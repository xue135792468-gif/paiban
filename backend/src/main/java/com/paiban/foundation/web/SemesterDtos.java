package com.paiban.foundation.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public final class SemesterDtos {

    private SemesterDtos() {}

    public record SemesterDto(Long id, String name, LocalDate startDate, LocalDate endDate,
                              boolean current, boolean active) {}

    public record SaveSemesterRequest(@NotBlank(message = "学期名称不能为空") String name,
                                      @NotNull(message = "开始日期不能为空") LocalDate startDate,
                                      @NotNull(message = "结束日期不能为空") LocalDate endDate) {}

    public record PeriodDto(Long id,
                            int dayOfWeek,
                            int periodNo,
                            String label,
                            LocalTime startTime,
                            LocalTime endTime) {}

    public record PeriodSlot(@NotNull Integer periodNo,
                             @NotBlank String label,
                             @NotNull LocalTime startTime,
                             @NotNull LocalTime endTime,
                             @NotEmpty(message = "至少选择一个星期") List<Integer> days) {}

    public record SavePeriodsRequest(@NotEmpty List<@Valid PeriodSlot> slots) {}

    /**
     * 生成默认作息的请求。上午/下午/晚自习三个数量只作为「每天生成几节」的旧输入，
     * 不再存储为硬分区（票 15：作息只保留节次与时间）；服务端按 1..N 顺序生成。
     * {@code periodCount} 为票 15 后的简化入参，优先于三个分段计数；两者都兼容。
     */
    public record GenerateDefaultPeriodsRequest(Integer morningCount,
                                                 Integer afternoonCount,
                                                 Integer eveningCount,
                                                 Integer periodCount,
                                                 List<Integer> days) {}

    public record CopyResultDto(int periods, int grades, int classes, int assignments) {}
}
