package com.paiban.timetable.web;

import java.time.Instant;
import java.time.LocalDate;
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
                                    String substitutionType,
                                    Long substituteTeacherId,
                                    String substituteTeacherName,
                                    String originalTeacherName,
                                    String substitutionReason,
                                    String note,
                                    /** 该行被一次性调代覆盖时的授课实例日期（原始授课日期）；常规安排为 null。 */
                                    LocalDate lessonDate,
                                    /** 调课落点实例日期；仅 SWAP 且被调走时有值。 */
                                    LocalDate targetLessonDate) {}

    public record PublishResultDto(Long draftId, int entryCount, Instant publishedAt, Long publishId) {}

    public record PublishInfoDto(Long semesterId, Long draftId, String draftName, String publishedBy,
                                 Instant publishedAt) {}

    public record SchoolTimetableDto(List<Long> classIds, List<TimetableEntryDto> entries) {}
}
