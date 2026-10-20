package com.paiban.timetable.web;

import java.time.Instant;
import java.util.List;

public final class TimetableDtos {

    private TimetableDtos() {}

    public record TimetableEntryDto(Long id,
                                    Long classId,
                                    String className,
                                    Long courseId,
                                    String courseName,
                                    Long teacherId,
                                    String teacherName,
                                    Long roomId,
                                    String roomName,
                                    Long periodId,
                                    int dayOfWeek,
                                    int periodNo,
                                    String section,
                                    String periodLabel,
                                    boolean substituted,
                                    Long substituteTeacherId,
                                    String substituteTeacherName,
                                    String note) {}

    public record PublishResultDto(Long draftId, int entryCount, Instant publishedAt, Long publishId) {}

    public record PublishInfoDto(Long semesterId, Long draftId, String draftName, String publishedBy,
                                 Instant publishedAt) {}

    public record SchoolTimetableDto(List<Long> classIds, List<TimetableEntryDto> entries) {}
}
