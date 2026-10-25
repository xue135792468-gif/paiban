package com.paiban.timetable.service;

import com.paiban.common.event.TimetablePublishedEvent;
import com.paiban.common.web.ApiException;
import com.paiban.foundation.domain.Period;
import com.paiban.foundation.domain.PeriodRepository;
import com.paiban.scheduling.domain.DraftLesson;
import com.paiban.scheduling.domain.DraftLessonRepository;
import com.paiban.scheduling.domain.TimetableDraft;
import com.paiban.scheduling.domain.TimetableDraftRepository;
import com.paiban.scheduling.service.DraftPublishReadiness;
import com.paiban.scheduling.service.DraftValidator;
import com.paiban.timetable.domain.TimetableEntry;
import com.paiban.timetable.domain.TimetableEntryRepository;
import com.paiban.timetable.domain.TimetablePublish;
import com.paiban.timetable.domain.TimetablePublishRepository;
import com.paiban.timetable.web.TimetableDtos.PublishInfoDto;
import com.paiban.timetable.web.TimetableDtos.PublishResultDto;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TimetablePublishService {

    private final TimetableDraftRepository drafts;
    private final DraftLessonRepository draftLessons;
    private final TimetableEntryRepository entries;
    private final TimetablePublishRepository publishes;
    private final DraftValidator validator;
    private final PeriodRepository periods;
    private final ApplicationEventPublisher events;

    public TimetablePublishService(
            TimetableDraftRepository drafts,
            DraftLessonRepository draftLessons,
            TimetableEntryRepository entries,
            TimetablePublishRepository publishes,
            DraftValidator validator,
            PeriodRepository periods,
            ApplicationEventPublisher events) {
        this.drafts = drafts;
        this.draftLessons = draftLessons;
        this.entries = entries;
        this.publishes = publishes;
        this.validator = validator;
        this.periods = periods;
        this.events = events;
    }

    @Transactional
    public PublishResultDto publish(Long draftId, String actor) {
        TimetableDraft draft = drafts.findById(draftId)
                .orElseThrow(() -> ApiException.notFound("课表草稿不存在: " + draftId));
        // Same judgement the page's whole-draft check shows: no second opinion here.
        DraftValidator.ValidationResult validation = validator.validateDraft(draftId);
        refuse(validation);
        List<DraftLesson> lessons = draftLessons.findByDraftIdOrderByIdAsc(draftId);

        entries.deleteBySemesterId(draft.getSemesterId());
        entries.flush();
        Instant now = Instant.now();
        java.util.Map<Long, Period> periodMap = new java.util.HashMap<>();
        periods.findBySemesterIdOrderByDayOfWeekAscPeriodNoAsc(draft.getSemesterId())
                .forEach(period -> periodMap.put(period.getId(), period));
        List<TimetableEntry> baseline = new ArrayList<>();
        for (DraftLesson lesson : lessons) {
            Period period = periodMap.get(lesson.getPeriodId());
            if (period == null) {
                throw ApiException.badRequest("课节对应的作息时段不存在: " + lesson.getPeriodId());
            }
            TimetableEntry entry = new TimetableEntry();
            entry.setSemesterId(draft.getSemesterId());
            entry.setDraftId(draftId);
            entry.setAdminClassId(lesson.getAdminClassId());
            entry.setCourseId(lesson.getCourseId());
            entry.setTeacherId(lesson.getTeacherId());
            entry.setRoomId(lesson.getRoomId());
            entry.setPeriodId(lesson.getPeriodId());
            entry.setDayOfWeek(period.getDayOfWeek());
            entry.setPeriodNo(period.getPeriodNo());
            entry.setCreatedAt(now);
            baseline.add(entry);
        }
        entries.saveAll(baseline);

        drafts.findBySemesterIdOrderByCreatedAtDesc(draft.getSemesterId()).forEach(other -> {
            if (!other.getId().equals(draftId) && "PUBLISHED".equals(other.getStatus())) {
                other.setStatus("ARCHIVED");
            }
        });
        draft.setStatus("PUBLISHED");
        drafts.save(draft);

        TimetablePublish publish = new TimetablePublish();
        publish.setSemesterId(draft.getSemesterId());
        publish.setDraftId(draftId);
        publish.setPublishedBy(actor);
        publish.setPublishedAt(now);
        TimetablePublish saved = publishes.save(publish);

        events.publishEvent(new TimetablePublishedEvent(draft.getSemesterId(), draftId, actor));
        return new PublishResultDto(draftId, baseline.size(), now, saved.getId());
    }

    /**
     * Turns the draft's first blocker into the refusal. Codes map back to the HTTP
     * semantics publishing always had: a status clash is 409, incomplete or dangling data
     * is 400, and a scheduling conflict stays 409 with its reasons attached.
     */
    private void refuse(DraftValidator.ValidationResult validation) {
        if (validation.blockers().isEmpty()) {
            return;
        }
        DraftPublishReadiness.Blocker blocker = validation.blockers().get(0);
        switch (blocker.code()) {
            case DraftPublishReadiness.DRAFT_PUBLISHED,
                    DraftPublishReadiness.SEMESTER_ALREADY_PUBLISHED ->
                    throw ApiException.conflict(blocker.message());
            case DraftPublishReadiness.HARD_CONFLICTS -> throw ApiException.conflict(
                    "课表存在 " + blocker.count() + " 处硬冲突，不能发布：" + reasons(validation));
            default -> throw ApiException.badRequest(blocker.message());
        }
    }

    private String reasons(DraftValidator.ValidationResult validation) {
        return validation.conflicts().stream()
                .limit(3)
                .map(DraftValidator.Conflict::message)
                .collect(Collectors.joining("；"));
    }

    @Transactional(readOnly = true)
    public PublishInfoDto publishInfo(Long semesterId) {
        return publishes.findFirstBySemesterIdOrderByPublishedAtDesc(semesterId)
                .map(publish -> new PublishInfoDto(
                        publish.getSemesterId(),
                        publish.getDraftId(),
                        drafts.findById(publish.getDraftId()).map(TimetableDraft::getName).orElse(null),
                        publish.getPublishedBy(),
                        publish.getPublishedAt()))
                .orElse(null);
    }
}
