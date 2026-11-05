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

    /**
     * {@code cancelledSubstitutions} is the number of future overlay rows a withdrawal
     * cancelled and that nothing has re-done yet; the page turns it into a "re-process
     * substitutions" prompt after the success toast.
     */
    public record PublishResultDto(Long draftId,
                                   int entryCount,
                                   Instant publishedAt,
                                   Long publishId,
                                   int cancelledSubstitutions) {}

    /**
     * {@code published} is the product answer ("is there a live baseline?"); the
     * timestamps and status let the UI show the last withdrawn publish as history.
     */
    public record PublishInfoDto(boolean published,
                                 Long semesterId,
                                 Long publishId,
                                 Long draftId,
                                 String draftName,
                                 String publishedBy,
                                 Instant publishedAt,
                                 Instant withdrawnAt,
                                 String status) {}

    /** What withdrawing would change, shown before the admin confirms. */
    public record WithdrawPreviewDto(boolean published,
                                     Long draftId,
                                     Long publishId,
                                     int futureSubstitutions,
                                     long allSubstitutions) {}

    public record WithdrawResultDto(Long draftId,
                                    Long publishId,
                                    Instant withdrawnAt,
                                    int cancelledSubstitutions,
                                    int removedEntries) {}

    public record SchoolTimetableDto(List<Long> classIds, List<TimetableEntryDto> entries) {}
}
