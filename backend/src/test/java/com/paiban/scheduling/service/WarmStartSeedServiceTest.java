package com.paiban.scheduling.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.paiban.foundation.domain.PeriodSection;
import com.paiban.scheduling.domain.Assignment;
import com.paiban.scheduling.domain.DraftLesson;
import com.paiban.scheduling.domain.DraftLessonRepository;
import com.paiban.scheduling.domain.TimetableDraft;
import com.paiban.scheduling.domain.TimetableDraftRepository;
import com.paiban.scheduling.solver.PeriodSlot;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Warm-start source selection, coverage validation and period seeding. */
class WarmStartSeedServiceTest {

    private static final AssignmentKey KEY = new AssignmentKey(1L, 1L, 1L);
    private static final Map<Long, PeriodSlot> PERIODS = Map.of(
            11L, slot(11L, 1),
            12L, slot(12L, 2));

    private final SemesterBaselineQuery baselines = mock(SemesterBaselineQuery.class);
    private final TimetableDraftRepository drafts = mock(TimetableDraftRepository.class);
    private final DraftLessonRepository draftLessons = mock(DraftLessonRepository.class);
    private final WarmStartSeedService service =
            new WarmStartSeedService(baselines, drafts, draftLessons);

    @Test
    void seedsEveryPeriodFromThePublishedBaselineFirst() {
        // Unsorted published rows still seed in period id order.
        when(baselines.publishedLessons(1L)).thenReturn(List.of(
                new SemesterBaselineQuery.PublishedLesson(1L, 1L, 1L, 12L),
                new SemesterBaselineQuery.PublishedLesson(1L, 1L, 1L, 11L)));
        // A complete draft is available, but the published baseline still wins.
        when(drafts.findBySemesterIdOrderByCreatedAtDesc(1L))
                .thenReturn(List.of(draft(5L, "2026-01-01T00:00:00Z")));
        when(draftLessons.findByDraftIdInOrderByDraftIdAscIdAsc(anyList()))
                .thenReturn(List.of(row(1L, 5L, 1L, 1L, 12L)));

        WarmStartSeedService.WarmStartSeed seed =
                service.seed(1L, PERIODS, List.of(assignment(21L, 1L, 1L, 1L, 2)));

        assertThat(seed.source()).isEqualTo("BASELINE");
        assertThat(seed.periods().get(KEY)).extracting(PeriodSlot::getId)
                .containsExactly(11L, 12L);
    }

    @Test
    void fallsBackToTheOldestCompleteDraft() {
        when(baselines.publishedLessons(1L)).thenReturn(List.of());
        when(drafts.findBySemesterIdOrderByCreatedAtDesc(1L)).thenReturn(List.of(
                draft(6L, "2026-01-02T00:00:00Z"),
                draft(5L, "2026-01-01T00:00:00Z")));
        // Rows come back ordered by draft id, so the oldest draft (5) is grouped first.
        when(draftLessons.findByDraftIdInOrderByDraftIdAscIdAsc(anyList())).thenReturn(List.of(
                row(1L, 5L, 1L, 1L, 11L),
                row(2L, 6L, 1L, 1L, 12L)));

        WarmStartSeedService.WarmStartSeed seed =
                service.seed(1L, PERIODS, List.of(assignment(21L, 1L, 1L, 1L, 1)));

        assertThat(seed.source()).isEqualTo("DRAFT");
        assertThat(seed.periods().get(KEY)).extracting(PeriodSlot::getId)
                .containsExactly(11L);
    }

    @Test
    void skipsADraftMissingWeeklyLessonsAndUsesTheNextCompleteOne() {
        when(baselines.publishedLessons(1L)).thenReturn(List.of());
        when(drafts.findBySemesterIdOrderByCreatedAtDesc(1L)).thenReturn(List.of(
                draft(6L, "2026-01-02T00:00:00Z"),
                draft(5L, "2026-01-01T00:00:00Z")));
        // The oldest draft (5) has one row but the assignment needs two lessons.
        when(draftLessons.findByDraftIdInOrderByDraftIdAscIdAsc(anyList())).thenReturn(List.of(
                row(1L, 5L, 1L, 1L, 11L),
                row(2L, 6L, 1L, 1L, 11L),
                row(3L, 6L, 1L, 1L, 12L)));

        WarmStartSeedService.WarmStartSeed seed =
                service.seed(1L, PERIODS, List.of(assignment(21L, 1L, 1L, 1L, 2)));

        assertThat(seed.source()).isEqualTo("DRAFT");
        assertThat(seed.periods().get(KEY)).extracting(PeriodSlot::getId)
                .containsExactly(11L, 12L);
    }

    @Test
    void skipsAPartialDraft() {
        when(baselines.publishedLessons(1L)).thenReturn(List.of());
        when(drafts.findBySemesterIdOrderByCreatedAtDesc(1L))
                .thenReturn(List.of(draft(8L, "2026-01-01T00:00:00Z")));
        when(draftLessons.findByDraftIdInOrderByDraftIdAscIdAsc(anyList()))
                .thenReturn(List.of(row(1L, 8L, 1L, 1L, 11L)));

        WarmStartSeedService.WarmStartSeed seed =
                service.seed(1L, PERIODS, List.of(assignment(21L, 1L, 1L, 1L, 2)));

        assertThat(seed.source()).isEqualTo("NONE");
        assertThat(seed.periods()).isEmpty();
    }

    @Test
    void skipsADraftWithTwoLessonsOfTheSameClassInOnePeriod() {
        when(baselines.publishedLessons(1L)).thenReturn(List.of());
        when(drafts.findBySemesterIdOrderByCreatedAtDesc(1L))
                .thenReturn(List.of(draft(6L, "2026-01-01T00:00:00Z")));
        when(draftLessons.findByDraftIdInOrderByDraftIdAscIdAsc(anyList())).thenReturn(List.of(
                row(1L, 6L, 1L, 1L, 11L),
                row(2L, 6L, 1L, 1L, 11L)));

        WarmStartSeedService.WarmStartSeed seed =
                service.seed(1L, PERIODS, List.of(assignment(21L, 1L, 1L, 1L, 2)));

        assertThat(seed.source()).isEqualTo("NONE");
        assertThat(seed.periods()).isEmpty();
    }

    @Test
    void sameClassDifferentTeachersInOnePeriodIsRejected() {
        when(baselines.publishedLessons(1L)).thenReturn(List.of());
        when(drafts.findBySemesterIdOrderByCreatedAtDesc(1L))
                .thenReturn(List.of(draft(6L, "2026-01-01T00:00:00Z")));
        // Both assignments are fully covered and the teachers differ, so only the class
        // double-booking in period 11 can reject this draft.
        when(draftLessons.findByDraftIdInOrderByDraftIdAscIdAsc(anyList())).thenReturn(List.of(
                rowWithCourse(1L, 6L, 1L, 1L, 1L, 11L),
                rowWithCourse(2L, 6L, 1L, 2L, 2L, 11L)));

        WarmStartSeedService.WarmStartSeed seed = service.seed(1L, PERIODS, List.of(
                assignment(21L, 1L, 1L, 1L, 1),
                assignment(22L, 1L, 2L, 2L, 1)));

        assertThat(seed.source()).isEqualTo("NONE");
        assertThat(seed.periods()).isEmpty();
    }

    @Test
    void skipsADraftWithOneTeacherInTwoClassesAtTheSamePeriod() {
        when(baselines.publishedLessons(1L)).thenReturn(List.of());
        when(drafts.findBySemesterIdOrderByCreatedAtDesc(1L))
                .thenReturn(List.of(draft(7L, "2026-01-01T00:00:00Z")));
        // Each assignment is fully covered, but the shared teacher is double-booked at period 11.
        when(draftLessons.findByDraftIdInOrderByDraftIdAscIdAsc(anyList())).thenReturn(List.of(
                row(1L, 7L, 1L, 1L, 11L),
                row(2L, 7L, 2L, 1L, 11L)));

        WarmStartSeedService.WarmStartSeed seed = service.seed(1L, PERIODS, List.of(
                assignment(21L, 1L, 1L, 1L, 1),
                assignment(22L, 2L, 1L, 1L, 1)));

        assertThat(seed.source()).isEqualTo("NONE");
        assertThat(seed.periods()).isEmpty();
    }

    @Test
    void skipsADraftPointingAtAnUnknownPeriod() {
        when(baselines.publishedLessons(1L)).thenReturn(List.of());
        when(drafts.findBySemesterIdOrderByCreatedAtDesc(1L))
                .thenReturn(List.of(draft(9L, "2026-01-01T00:00:00Z")));
        when(draftLessons.findByDraftIdInOrderByDraftIdAscIdAsc(anyList()))
                .thenReturn(List.of(row(1L, 9L, 1L, 1L, 99L)));

        WarmStartSeedService.WarmStartSeed seed =
                service.seed(1L, PERIODS, List.of(assignment(21L, 1L, 1L, 1L, 1)));

        assertThat(seed.source()).isEqualTo("NONE");
        assertThat(seed.periods()).isEmpty();
    }

    @Test
    void rowsWithNullClassOrTeacherAreSkippedWithoutCrash() {
        when(baselines.publishedLessons(1L)).thenReturn(List.of());
        when(drafts.findBySemesterIdOrderByCreatedAtDesc(1L))
                .thenReturn(List.of(draft(6L, "2026-01-01T00:00:00Z")));
        // A partial import can leave one owner null; the row must be treated as an
        // incomplete match instead of blowing up in the uniqueness maps.
        when(draftLessons.findByDraftIdInOrderByDraftIdAscIdAsc(anyList())).thenReturn(List.of(
                rowWithOwners(1L, 6L, null, 1L, 11L),
                rowWithOwners(2L, 6L, 1L, null, 12L)));

        WarmStartSeedService.WarmStartSeed seed =
                service.seed(1L, PERIODS, List.of(assignment(21L, 1L, 1L, 1L, 2)));

        assertThat(seed.source()).isEqualTo("NONE");
        assertThat(seed.periods()).isEmpty();
    }

    @Test
    void reportsNoneWhenThereIsNeitherBaselineNorDraft() {
        when(baselines.publishedLessons(1L)).thenReturn(List.of());
        when(drafts.findBySemesterIdOrderByCreatedAtDesc(1L)).thenReturn(List.of());

        WarmStartSeedService.WarmStartSeed seed =
                service.seed(1L, PERIODS, List.of(assignment(21L, 1L, 1L, 1L, 1)));

        assertThat(seed.source()).isEqualTo("NONE");
        assertThat(seed.periods()).isEmpty();
    }

    private static Assignment assignment(
            long id, long classId, long courseId, long teacherId, int weeklyLessons) {
        Assignment assignment = new Assignment();
        assignment.setId(id);
        assignment.setSemesterId(1L);
        assignment.setAdminClassId(classId);
        assignment.setCourseId(courseId);
        assignment.setTeacherId(teacherId);
        assignment.setWeeklyLessons(weeklyLessons);
        return assignment;
    }

    private static TimetableDraft draft(long id, String createdAt) {
        TimetableDraft draft = new TimetableDraft();
        draft.setId(id);
        draft.setSemesterId(1L);
        draft.setCreatedAt(Instant.parse(createdAt));
        return draft;
    }

    private static DraftLesson row(
            long id, long draftId, long classId, long teacherId, Long periodId) {
        return draftRow(id, draftId, classId, 1L, teacherId, periodId);
    }

    private static DraftLesson rowWithCourse(
            long id, long draftId, long classId, long courseId, long teacherId, Long periodId) {
        return draftRow(id, draftId, classId, courseId, teacherId, periodId);
    }

    private static DraftLesson rowWithOwners(
            long id, long draftId, Long classId, Long teacherId, Long periodId) {
        return draftRow(id, draftId, classId, 1L, teacherId, periodId);
    }

    private static DraftLesson draftRow(
            long id, long draftId, Long classId, Long courseId, Long teacherId, Long periodId) {
        DraftLesson row = new DraftLesson();
        row.setId(id);
        row.setDraftId(draftId);
        row.setAdminClassId(classId);
        row.setCourseId(courseId);
        row.setTeacherId(teacherId);
        row.setPeriodId(periodId);
        return row;
    }

    private static PeriodSlot slot(long id, int periodNo) {
        return new PeriodSlot(id, 1, periodNo, PeriodSection.MORNING, periodNo == 1, false, false);
    }
}
