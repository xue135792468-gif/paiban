package com.paiban.timetable.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.paiban.foundation.domain.AuditLogRepository;
import com.paiban.scheduling.domain.TimetableDraft;
import com.paiban.scheduling.domain.TimetableDraftRepository;
import com.paiban.substitution.domain.Substitution;
import com.paiban.substitution.domain.SubstitutionHistory;
import com.paiban.substitution.domain.SubstitutionHistoryRepository;
import com.paiban.substitution.domain.SubstitutionRepository;
import com.paiban.substitution.domain.SubstitutionRequest;
import com.paiban.substitution.domain.SubstitutionRequestRepository;
import com.paiban.timetable.domain.TimetableEntryRepository;
import com.paiban.timetable.domain.TimetablePublish;
import com.paiban.timetable.domain.TimetablePublishRepository;
import com.paiban.timetable.web.TimetableDtos.WithdrawResultDto;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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
 * "Today" belongs to the school-timezone clock, not the JVM's default zone. At
 * 2026-11-04T16:30Z Shanghai is already the 5th, so a row covering the 5th is history
 * and only the row covering the 6th still has to be cancelled.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TimetableWithdrawServiceTest {

    private static final ZoneId SCHOOL_ZONE =
            com.paiban.substitution.service.LessonDeadline.SCHOOL_ZONE;
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-11-04T16:30:00Z"), SCHOOL_ZONE);
    private static final LocalDate TODAY_IN_SCHOOL_ZONE = LocalDate.of(2026, 11, 5);

    @Mock
    private TimetableDraftRepository drafts;
    @Mock
    private TimetableEntryRepository entries;
    @Mock
    private TimetablePublishRepository publishes;
    @Mock
    private SubstitutionRepository substitutions;
    @Mock
    private SubstitutionRequestRepository requests;
    @Mock
    private SubstitutionHistoryRepository history;
    @Mock
    private AuditLogRepository auditLogs;

    private TimetableWithdrawService service;

    @BeforeEach
    void setUp() {
        service = new TimetableWithdrawService(
                drafts, entries, publishes, substitutions, requests, history, auditLogs, CLOCK);
        TimetableDraft draft = new TimetableDraft();
        draft.setId(1L);
        draft.setSemesterId(9L);
        draft.setStatus("PUBLISHED");
        when(drafts.findLiveWithLockById(1L)).thenReturn(Optional.of(draft));

        TimetablePublish publish = new TimetablePublish();
        publish.setId(30L);
        publish.setSemesterId(9L);
        publish.setDraftId(1L);
        publish.setStatus(TimetablePublish.ACTIVE);
        when(publishes.findFirstBySemesterIdAndDraftIdAndStatusOrderByPublishedAtDesc(
                9L, 1L, TimetablePublish.ACTIVE)).thenReturn(Optional.of(publish));
        when(entries.countBySemesterId(9L)).thenReturn(12L);
    }

    @Test
    void onlyRowsStillAheadOfTheSchoolDateAreCancelled() {
        assertThat(LocalDate.now(CLOCK)).isEqualTo(TODAY_IN_SCHOOL_ZONE);
        Substitution today = effectiveRow(1L, 101L, TODAY_IN_SCHOOL_ZONE);
        Substitution tomorrow = effectiveRow(2L, 102L, TODAY_IN_SCHOOL_ZONE.plusDays(1));
        when(substitutions.findBySemesterIdAndStatus(9L, Substitution.EFFECTIVE))
                .thenReturn(List.of(today, tomorrow));
        when(requests.findBySemesterIdAndStatusOrderByCreatedAtDesc(
                9L, SubstitutionRequest.APPROVED)).thenReturn(List.of());
        when(substitutions.findByRequestId(102L)).thenReturn(List.of(tomorrow));
        SubstitutionRequest request = new SubstitutionRequest();
        request.setId(102L);
        request.setSemesterId(9L);
        request.setStatus(SubstitutionRequest.EFFECTIVE);
        when(requests.findById(102L)).thenReturn(Optional.of(request));

        WithdrawResultDto result = service.withdraw(1L, "admin");

        assertThat(result.cancelledSubstitutions()).isEqualTo(1);
        assertThat(today.getStatus()).isEqualTo(Substitution.EFFECTIVE);
        assertThat(tomorrow.getStatus()).isEqualTo(Substitution.CANCELLED);
        assertThat(request.getStatus()).isEqualTo(SubstitutionRequest.CANCELLED);
    }

    @Test
    void cancellingARequestWritesOneHistoryRow() {
        Substitution tomorrow = effectiveRow(2L, 102L, TODAY_IN_SCHOOL_ZONE.plusDays(1));
        when(substitutions.findBySemesterIdAndStatus(9L, Substitution.EFFECTIVE))
                .thenReturn(List.of(tomorrow));
        when(requests.findBySemesterIdAndStatusOrderByCreatedAtDesc(
                9L, SubstitutionRequest.APPROVED)).thenReturn(List.of());
        when(substitutions.findByRequestId(102L)).thenReturn(List.of(tomorrow));
        SubstitutionRequest request = new SubstitutionRequest();
        request.setId(102L);
        request.setSemesterId(9L);
        request.setStatus(SubstitutionRequest.EFFECTIVE);
        when(requests.findById(102L)).thenReturn(Optional.of(request));

        service.withdraw(1L, "admin");

        ArgumentCaptor<SubstitutionHistory> captor =
                ArgumentCaptor.forClass(SubstitutionHistory.class);
        verify(history).save(captor.capture());
        SubstitutionHistory item = captor.getValue();
        assertThat(item.getRequestId()).isEqualTo(102L);
        assertThat(item.getFromStatus()).isEqualTo(SubstitutionRequest.EFFECTIVE);
        assertThat(item.getToStatus()).isEqualTo(SubstitutionRequest.CANCELLED);
        assertThat(item.getActor()).isEqualTo("admin");
        assertThat(item.getComment()).contains("发布撤回");
        assertThat(request.getStatus()).isEqualTo(SubstitutionRequest.CANCELLED);
        verify(requests).saveAll(any());
    }

    private static Substitution effectiveRow(long id, long requestId, LocalDate lessonDate) {
        Substitution row = new Substitution();
        row.setId(id);
        row.setSemesterId(9L);
        row.setRequestId(requestId);
        row.setEntryId(500L);
        row.setEffectiveDate(lessonDate);
        row.setLessonDate(lessonDate);
        row.setOriginalTeacherId(4L);
        row.setSubstituteTeacherId(8L);
        row.setStatus(Substitution.EFFECTIVE);
        return row;
    }
}
