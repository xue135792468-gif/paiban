package com.paiban.scheduling.solver;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ConstraintEntryMatcherTest {

    @Test
    void courseMatchingDistinguishesStrictAllAndSport() {
        Map<String, Object> course = Map.of("courseId", 7L, "dayOfWeek", 3, "periodNo", 1,
                "roomType", "LAB", "subject", "语文");

        assertThat(ConstraintEntryMatcher.course(course, 7L)).isTrue();
        assertThat(ConstraintEntryMatcher.course(course, 8L)).isFalse();
        assertThat(ConstraintEntryMatcher.courseOrAll(Map.of(), 7L)).isTrue();
        assertThat(ConstraintEntryMatcher.courseOrAll(course, 8L)).isFalse();
        assertThat(ConstraintEntryMatcher.courseOrSport(Map.of(), 7L, true)).isTrue();
        assertThat(ConstraintEntryMatcher.courseOrSport(Map.of(), 7L, false)).isFalse();
        assertThat(ConstraintEntryMatcher.courseOrSport(course, 7L, false)).isTrue();
        assertThat(ConstraintEntryMatcher.courseWithRoomType(course, 7L)).isTrue();
        assertThat(ConstraintEntryMatcher.courseWithRoomType(Map.of("courseId", 7L), 7L)).isFalse();
    }

    @Test
    void teacherSlotAndDayOffShareTheSameSlotPredicate() {
        Map<String, Object> entry = Map.of("teacherId", 11L, "dayOfWeek", 3, "periodNo", 5);

        assertThat(ConstraintEntryMatcher.teacherSlot(entry, 11L, 3, 5)).isTrue();
        assertThat(ConstraintEntryMatcher.teacherSlot(entry, 12L, 3, 5)).isFalse();
        assertThat(ConstraintEntryMatcher.teacherSlot(entry, 11L, 3, 6)).isFalse();
        assertThat(ConstraintEntryMatcher.courseSlot(
                Map.of("courseId", 7L, "dayOfWeek", 3, "periodNo", 5), 7L, 3, 5)).isTrue();
        assertThat(ConstraintEntryMatcher.dayOff(entry, 11L, 3)).isTrue();
        assertThat(ConstraintEntryMatcher.dayOff(entry, 11L, 4)).isFalse();
    }

    @Test
    void blankMeetingSubjectMeansEveryTeacher() {
        assertThat(ConstraintEntryMatcher.meetingSubject(Map.of(), "语文")).isTrue();
        assertThat(ConstraintEntryMatcher.meetingSubject(Map.of("subject", "语文"), "语文")).isTrue();
        assertThat(ConstraintEntryMatcher.meetingSubject(Map.of("subject", "语文"), "数学")).isFalse();
        assertThat(ConstraintEntryMatcher.meetingSubject(Map.of("subject", "语文"), null)).isFalse();
    }
}
