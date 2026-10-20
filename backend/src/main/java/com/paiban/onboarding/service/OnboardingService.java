package com.paiban.onboarding.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paiban.common.web.ApiException;
import com.paiban.foundation.service.FoundationService;
import com.paiban.foundation.service.SemesterService;
import com.paiban.identity.domain.AppUser;
import com.paiban.identity.service.UserService;
import com.paiban.onboarding.domain.OnboardingProgress;
import com.paiban.onboarding.domain.OnboardingProgressRepository;
import com.paiban.onboarding.web.OnboardingDtos.HintDto;
import com.paiban.onboarding.web.OnboardingDtos.ProgressDto;
import com.paiban.onboarding.web.OnboardingDtos.SaveProgressRequest;
import com.paiban.onboarding.web.OnboardingDtos.StatusDto;
import com.paiban.scheduling.domain.AssignmentRepository;
import com.paiban.timetable.domain.TimetablePublishRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OnboardingService {

    private final OnboardingProgressRepository progressRepository;
    private final UserService users;
    private final SemesterService semesters;
    private final FoundationService foundation;
    private final AssignmentRepository assignments;
    private final TimetablePublishRepository publishes;
    private final ObjectMapper objectMapper;

    public OnboardingService(
            OnboardingProgressRepository progressRepository,
            UserService users,
            SemesterService semesters,
            FoundationService foundation,
            AssignmentRepository assignments,
            TimetablePublishRepository publishes,
            ObjectMapper objectMapper) {
        this.progressRepository = progressRepository;
        this.users = users;
        this.semesters = semesters;
        this.foundation = foundation;
        this.assignments = assignments;
        this.publishes = publishes;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public ProgressDto get(String username) {
        AppUser user = users.getByUsername(username);
        return progressRepository.findByUserId(user.getId())
                .map(this::toDto)
                .orElseGet(() -> new ProgressDto(0, List.of(), Map.of(), false, null));
    }

    @Transactional
    public ProgressDto save(String username, SaveProgressRequest request) {
        AppUser user = users.getByUsername(username);
        OnboardingProgress progress = progressRepository.findByUserId(user.getId())
                .orElseGet(() -> {
                    OnboardingProgress created = new OnboardingProgress();
                    created.setUserId(user.getId());
                    return created;
                });
        if (request.currentStep() != null) {
            progress.setCurrentStep(request.currentStep());
        }
        if (request.completedSteps() != null) {
            progress.setCompletedStepsJson(toJson(request.completedSteps()));
        }
        if (request.data() != null) {
            progress.setDataJson(toJson(request.data()));
        }
        if (request.skipped() != null) {
            progress.setSkipped(request.skipped());
        }
        progress.setUpdatedAt(Instant.now());
        return toDto(progressRepository.save(progress));
    }

    @Transactional
    public void reset(String username) {
        AppUser user = users.getByUsername(username);
        progressRepository.deleteByUserId(user.getId());
    }

    @Transactional(readOnly = true)
    public StatusDto status(String username) {
        AppUser user = users.getByUsername(username);
        OnboardingProgress progress = progressRepository.findByUserId(user.getId()).orElse(null);
        List<HintDto> hints = hints();
        boolean fresh = hints.stream().anyMatch(hint ->
                ("classes".equals(hint.key()) || "periods".equals(hint.key())) && !hint.done());
        boolean skipped = progress != null && progress.isSkipped();
        boolean showWizard = !skipped && progress == null && fresh;
        return new StatusDto(showWizard, skipped, progress != null, hints);
    }

    @Transactional(readOnly = true)
    public List<HintDto> hints() {
        List<HintDto> hints = new ArrayList<>();
        boolean hasSemester = !semesters.list().isEmpty();
        hints.add(hint("semester", "创建学期", "还没有学期，请先创建本学期", "/admin/semesters", hasSemester));

        Long semesterId = semesters.current().map(dto -> dto.id()).orElse(null);
        boolean hasPeriods = semesterId != null && !semesters.listPeriods(semesterId).isEmpty();
        hints.add(hint("periods", "配置作息表", "还未配置作息表，排课前必须完成", "/admin/semesters", hasPeriods));

        boolean hasGrades = semesterId != null && !foundation.listGrades(semesterId).isEmpty();
        hints.add(hint("grades", "录入年级", "还没有年级数据", "/admin/grades", hasGrades));

        boolean hasClasses = semesterId != null && !foundation.listClasses(semesterId).isEmpty();
        hints.add(hint("classes", "录入行政班", "还没有班级数据", "/admin/classes", hasClasses));

        boolean hasTeachers = !foundation.listTeachers().isEmpty();
        hints.add(hint("teachers", "录入教师", "还没有教师数据", "/admin/teachers", hasTeachers));

        boolean hasRooms = !foundation.listRooms().isEmpty();
        hints.add(hint("rooms", "录入教室", "还没有教室数据", "/admin/rooms", hasRooms));

        boolean hasCourses = !foundation.listCourses().isEmpty();
        hints.add(hint("courses", "录入课程", "还没有课程数据", "/admin/courses", hasCourses));

        boolean hasAssignments = semesterId != null && assignments.countBySemesterId(semesterId) > 0;
        hints.add(hint("assignments", "录入授课任务", "还没有授课任务", "/admin/assignments", hasAssignments));

        boolean published = semesterId != null && publishes.findFirstBySemesterIdOrderByPublishedAtDesc(semesterId)
                .isPresent();
        hints.add(hint("published", "发布课表", "还没有发布过课表", "/admin/scheduling", published));
        return hints;
    }

    private HintDto hint(String key, String label, String message, String route, boolean done) {
        return new HintDto(key, label, message, route, done);
    }

    private ProgressDto toDto(OnboardingProgress progress) {
        return new ProgressDto(
                progress.getCurrentStep(),
                fromJson(progress.getCompletedStepsJson(), new TypeReference<List<Integer>>() {}, List.of()),
                fromJson(progress.getDataJson(), new TypeReference<Map<String, Object>>() {}, Map.of()),
                progress.isSkipped(),
                progress.getUpdatedAt());
    }

    private <T> T fromJson(String json, TypeReference<T> type, T fallback) {
        if (json == null || json.isBlank()) {
            return fallback;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception ex) {
            return fallback;
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw ApiException.badRequest("无法保存向导数据");
        }
    }
}
