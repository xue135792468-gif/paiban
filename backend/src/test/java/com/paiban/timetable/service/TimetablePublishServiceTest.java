package com.paiban.timetable.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.paiban.foundation.domain.AuditLog;
import com.paiban.foundation.domain.AuditLogRepository;
import com.paiban.foundation.domain.Period;
import com.paiban.foundation.domain.PeriodRepository;
import com.paiban.scheduling.domain.DraftLesson;
import com.paiban.scheduling.domain.DraftLessonRepository;
import com.paiban.scheduling.domain.TimetableDraft;
import com.paiban.scheduling.domain.TimetableDraftRepository;
import com.paiban.scheduling.service.DraftPublishReadiness;
import com.paiban.scheduling.service.DraftValidator;
import com.paiban.substitution.domain.Substitution;
import com.paiban.substitution.domain.SubstitutionRepository;
import com.paiban.timetable.domain.TimetableEntryRepository;
import com.paiban.timetable.domain.TimetablePublish;
import com.paiban.timetable.domain.TimetablePublishRepository;
import com.paiban.timetable.web.TimetableDtos.PublishResultDto;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Two contracts the integration tests cannot reach on their own: publishing never
 * leaves more than one ACTIVE publish row behind (validation refuses the sequential
 * case, so the demotion only guards races and legacy rows), and the "cancelled
 * substitutions" count is measured against the injected school-timezone clock.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TimetablePublishServiceTest {

    private static final ZoneId SCHOOL_ZONE =
            com.paiban.substitution.service.LessonDeadline.SCHOOL_ZONE;
    /** 2026-11-04T16:30Z is already 2026-11-05 in Asia/Shanghai. */
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-11-04T16:30:00Z"), SCHOOL_ZONE);

    @Mock
    private TimetableDraftRepository drafts;
    @Mock
    private DraftLessonRepository draftLessons;
    @Mock
    private TimetableEntryRepository entries;
    @Mock
    private TimetablePublishRepository publishes;
    @Mock
    private DraftValidator validator;
    @Mock
    private PeriodRepository periods;
    @Mock
    private AuditLogRepository auditLogs;
    @Mock
    private SubstitutionRepository substitutions;

    private TimetablePublishService service;

    @BeforeEach
    void setUp() {
        service = new TimetablePublishService(
                drafts, draftLessons, entries, publishes, validator, periods,
                auditLogs, substitutions, CLOCK);
    }

    private TimetableDraft draft() {
        TimetableDraft draft = new TimetableDraft();
        draft.setId(1L);
        draft.setSemesterId(9L);
        draft.setStatus("DRAFT");
        return draft;
    }

    private DraftLesson lesson(long periodId) {
        DraftLesson lesson = new DraftLesson();
        lesson.setId(11L);
        lesson.setDraftId(1L);
        lesson.setAdminClassId(2L);
        lesson.setCourseId(3L);
        lesson.setTeacherId(4L);
        lesson.setPeriodId(periodId);
        return lesson;
    }

    private Period period(long id) {
        Period period = new Period();
        period.setId(id);
        period.setSemesterId(9L);
        period.setDayOfWeek(1);
        period.setPeriodNo(1);
        return period;
    }

    private void givenPublishableDraft() {
        TimetableDraft draft = draft();
        when(drafts.findLiveWithLockById(1L)).thenReturn(Optional.of(draft));
        when(validator.validateDraft(1L)).thenReturn(new DraftValidator.ValidationResult(
                true, 0, List.of(), true, List.<DraftPublishReadiness.Blocker>of(), "fp"));
        when(draftLessons.findByDraftIdOrderByIdAsc(1L)).thenReturn(List.of(lesson(100L)));
        when(periods.findBySemesterIdOrderByDayOfWeekAscPeriodNoAsc(9L))
                .thenReturn(List.of(period(100L)));
        when(drafts.findBySemesterIdOrderByCreatedAtDesc(9L)).thenReturn(List.of(draft));
        when(entries.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(publishes.save(any(TimetablePublish.class))).thenAnswer(invocation -> {
            TimetablePublish row = invocation.getArgument(0);
            if (row.getId() == null) {
                row.setId(77L);
            }
            return row;
        });
        when(substitutions.findBySemesterIdAndStatus(9L, Substitution.CANCELLED))
                .thenReturn(List.of());
    }

    @Test
    void publishDemotesEarlierActiveRowsSoTheSemesterKeepsOneBaseline() {
        givenPublishableDraft();
        TimetablePublish stale = new TimetablePublish();
        stale.setId(50L);
        stale.setSemesterId(9L);
        stale.setDraftId(5L);
        stale.setPublishedAt(Instant.parse("2026-11-01T00:00:00Z"));
        stale.setStatus(TimetablePublish.ACTIVE);
        when(publishes.findBySemesterIdAndStatus(9L, TimetablePublish.ACTIVE))
                .thenReturn(List.of(stale));

        PublishResultDto result = service.publish(1L, "admin");

        assertThat(stale.getStatus()).isEqualTo(TimetablePublish.WITHDRAWN);
        assertThat(stale.getWithdrawnAt()).isNotNull();
        assertThat(stale.getWithdrawnBy()).isEqualTo("admin");
        assertThat(result.publishId()).isEqualTo(77L);
    }

    @Test
    void cancelledSubstitutionCountUsesTheSchoolClockAndOnlyFutureRows() {
        givenPublishableDraft();
        when(publishes.findBySemesterIdAndStatus(9L, TimetablePublish.ACTIVE))
                .thenReturn(List.of());

        LocalDate todayInSchoolZone = LocalDate.now(CLOCK);
        assertThat(todayInSchoolZone).isEqualTo(LocalDate.of(2026, 11, 5));
        Substitution yesterday = cancelledRow(todayInSchoolZone.minusDays(1));
        Substitution tomorrow = cancelledRow(todayInSchoolZone.plusDays(1));
        when(substitutions.findBySemesterIdAndStatus(9L, Substitution.CANCELLED))
                .thenReturn(new ArrayList<>(List.of(yesterday, tomorrow)));

        PublishResultDto result = service.publish(1L, "admin");

        assertThat(result.cancelledSubstitutions()).isEqualTo(1);
    }

    @Test
    void publishWritesTheAuditRowAndReportsTheEvent() {
        givenPublishableDraft();
        when(publishes.findBySemesterIdAndStatus(9L, TimetablePublish.ACTIVE))
                .thenReturn(List.of());

        service.publish(1L, "admin");

        ArgumentCaptor<AuditLog> audit = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogs).save(audit.capture());
        assertThat(audit.getValue().getAction()).isEqualTo("PUBLISH_TIMETABLE");
        verify(entries).saveAll(any());
        verify(publishes, atLeastOnce()).save(any(TimetablePublish.class));
    }

    private static Substitution cancelledRow(LocalDate lessonDate) {
        Substitution row = new Substitution();
        row.setId(Math.abs(lessonDate.toEpochDay()));
        row.setSemesterId(9L);
        row.setRequestId(lessonDate.toEpochDay());
        row.setEffectiveDate(lessonDate);
        row.setLessonDate(lessonDate);
        row.setOriginalTeacherId(4L);
        row.setSubstituteTeacherId(8L);
        row.setStatus(Substitution.CANCELLED);
        return row;
    }
}
