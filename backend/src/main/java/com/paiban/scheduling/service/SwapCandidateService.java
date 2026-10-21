package com.paiban.scheduling.service;

import com.paiban.common.web.ApiException;
import com.paiban.foundation.domain.AdminClass;
import com.paiban.foundation.domain.AdminClassRepository;
import com.paiban.foundation.domain.Course;
import com.paiban.foundation.domain.CourseRepository;
import com.paiban.foundation.domain.Grade;
import com.paiban.foundation.domain.GradeRepository;
import com.paiban.foundation.domain.Period;
import com.paiban.foundation.domain.PeriodRepository;
import com.paiban.foundation.domain.Room;
import com.paiban.foundation.domain.RoomRepository;
import com.paiban.foundation.domain.Teacher;
import com.paiban.foundation.domain.TeacherRepository;
import com.paiban.scheduling.domain.DraftLesson;
import com.paiban.scheduling.domain.DraftLessonRepository;
import com.paiban.scheduling.domain.TimetableDraft;
import com.paiban.scheduling.domain.TimetableDraftRepository;
import com.paiban.scheduling.service.DraftValidator.LessonChange;
import com.paiban.scheduling.web.SchedulingDtos.LessonOverride;
import com.paiban.scheduling.web.SwapQueryDtos.MoveTargetDto;
import com.paiban.scheduling.web.SwapQueryDtos.MoveTargetsResponse;
import com.paiban.scheduling.web.SwapQueryDtos.SwapCandidateDto;
import com.paiban.scheduling.web.SwapQueryDtos.SwapCandidatesResponse;
import com.paiban.scheduling.web.SwapQueryDtos.SwapType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only queries that feed the manual adjustment panel and the drag preview.
 * Availability always comes from {@link DraftValidator}; this service only decides
 * which lesson pairs are candidates and how to describe them.
 */
@Service
public class SwapCandidateService {

    /** Panel narrowing filters; a null field means that dimension is not filtered. */
    public record CandidateFilters(Long courseId, Long gradeId, Long classId, Long teacherId) {}

    private final TimetableDraftRepository drafts;
    private final DraftLessonRepository draftLessons;
    private final PeriodRepository periods;
    private final RoomRepository rooms;
    private final CourseRepository courses;
    private final TeacherRepository teachers;
    private final AdminClassRepository adminClasses;
    private final GradeRepository grades;
    private final DraftValidator validator;

    public SwapCandidateService(
            TimetableDraftRepository drafts,
            DraftLessonRepository draftLessons,
            PeriodRepository periods,
            RoomRepository rooms,
            CourseRepository courses,
            TeacherRepository teachers,
            AdminClassRepository adminClasses,
            GradeRepository grades,
            DraftValidator validator) {
        this.drafts = drafts;
        this.draftLessons = draftLessons;
        this.periods = periods;
        this.rooms = rooms;
        this.courses = courses;
        this.teachers = teachers;
        this.adminClasses = adminClasses;
        this.grades = grades;
        this.validator = validator;
    }

    /**
     * Candidate lesson plus the exact changes that describe the exchange. An
     * intra-class candidate carries a second scenario that also exchanges rooms;
     * cross-class exchanges move both teachers only.
     */
    private record PendingCandidate(
            DraftLesson lesson,
            boolean intraClass,
            List<LessonChange> changes,
            List<LessonChange> roomSwapChanges,
            List<DraftValidator.Conflict> extraConflicts,
            List<String> warnings) {}

    @Transactional(readOnly = true)
    public SwapCandidatesResponse swapCandidates(
            Long draftId,
            Long lessonId,
            SwapType type,
            CandidateFilters filters,
            boolean includeUnavailable) {
        TimetableDraft draft = drafts.findById(draftId)
                .orElseThrow(() -> ApiException.notFound("课表草稿不存在: " + draftId));
        List<DraftLesson> lessons = draftLessons.findByDraftIdOrderByIdAsc(draftId);
        DraftLesson target = lessons.stream()
                .filter(lesson -> lesson.getId().equals(lessonId))
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("课节不存在: " + lessonId));
        if (target.getPeriodId() == null) {
            throw ApiException.badRequest("课节尚未排入节次，无法换课");
        }

        Map<Long, Period> periodById = new HashMap<>();
        periods.findBySemesterIdOrderByDayOfWeekAscPeriodNoAsc(draft.getSemesterId())
                .forEach(period -> periodById.put(period.getId(), period));
        Map<Long, Room> roomById = new HashMap<>();
        rooms.findAll().forEach(room -> roomById.put(room.getId(), room));
        Map<Long, Course> courseById = new HashMap<>();
        courses.findAll().forEach(course -> courseById.put(course.getId(), course));
        Map<Long, Teacher> teacherById = new HashMap<>();
        teachers.findAll().forEach(teacher -> teacherById.put(teacher.getId(), teacher));
        Map<Long, AdminClass> classById = new HashMap<>();
        adminClasses.findBySemesterIdOrderByGradeIdAscNameAsc(draft.getSemesterId())
                .forEach(adminClass -> classById.put(adminClass.getId(), adminClass));
        Map<Long, Grade> gradeById = new HashMap<>();
        grades.findBySemesterIdOrderBySortOrderAscNameAsc(draft.getSemesterId())
                .forEach(grade -> gradeById.put(grade.getId(), grade));

        CandidateFilters activeFilters = filters == null
                ? new CandidateFilters(null, null, null, null)
                : filters;
        SwapType requested = type == null ? SwapType.ALL : type;
        List<PendingCandidate> pending = new ArrayList<>();
        if (requested != SwapType.CROSS_CLASS) {
            collectIntraClass(target, lessons, classById, activeFilters, pending);
        }
        if (requested != SwapType.INTRA_CLASS) {
            collectCrossClass(target, lessons, courseById, teacherById, classById,
                    activeFilters, pending);
        }

        List<List<LessonChange>> scenarios = new ArrayList<>(pending.size());
        for (PendingCandidate candidate : pending) {
            scenarios.add(candidate.changes());
            if (candidate.roomSwapChanges() != null) {
                scenarios.add(candidate.roomSwapChanges());
            }
        }
        List<DraftValidator.ValidationResult> results =
                validator.validateChangeScenarios(draftId, scenarios);

        List<SwapCandidateDto> intra = new ArrayList<>();
        List<SwapCandidateDto> cross = new ArrayList<>();
        int scenarioIndex = 0;
        for (PendingCandidate candidate : pending) {
            DraftValidator.ValidationResult unchangedRooms = results.get(scenarioIndex++);
            DraftValidator.ValidationResult exchangedRooms = candidate.roomSwapChanges() == null
                    ? null
                    : results.get(scenarioIndex++);
            boolean available;
            boolean roomSwapRequired = false;
            List<DraftValidator.Conflict> conflicts;
            List<DraftValidator.Conflict> unchangedConflicts = new ArrayList<>(unchangedRooms.conflicts());
            unchangedConflicts.addAll(candidate.extraConflicts());
            if (unchangedConflicts.isEmpty()) {
                available = true;
                conflicts = List.of();
            } else if (exchangedRooms != null && exchangedRooms.valid()) {
                // Keeping the rooms blocks the exchange but swapping them does not.
                available = true;
                conflicts = List.of();
                roomSwapRequired = true;
            } else {
                available = false;
                conflicts = unchangedConflicts;
            }
            if (!available && !includeUnavailable) {
                continue;
            }
            SwapCandidateDto dto = toDto(candidate.lesson(), periodById, roomById, courseById,
                    teacherById, classById, gradeById, available, roomSwapRequired,
                    conflicts, candidate.warnings());
            (candidate.intraClass() ? intra : cross).add(dto);
        }
        intra.sort(candidateOrder());
        cross.sort(candidateOrder());
        return new SwapCandidatesResponse(lessonId, intra, cross);
    }

    private void collectIntraClass(
            DraftLesson target,
            List<DraftLesson> lessons,
            Map<Long, AdminClass> classById,
            CandidateFilters filters,
            List<PendingCandidate> pending) {
        for (DraftLesson candidate : lessons) {
            if (candidate.getId().equals(target.getId())
                    || !candidate.getAdminClassId().equals(target.getAdminClassId())
                    || candidate.getPeriodId() == null
                    || excluded(candidate, filters, classById)) {
                continue;
            }
            List<LessonChange> keepRooms = List.of(
                    new LessonChange(target.getId(), candidate.getPeriodId(),
                            target.getRoomId(), target.getTeacherId()),
                    new LessonChange(candidate.getId(), target.getPeriodId(),
                            candidate.getRoomId(), candidate.getTeacherId()));
            List<LessonChange> exchangeRooms = List.of(
                    new LessonChange(target.getId(), candidate.getPeriodId(),
                            candidate.getRoomId(), target.getTeacherId()),
                    new LessonChange(candidate.getId(), target.getPeriodId(),
                            target.getRoomId(), candidate.getTeacherId()));
            pending.add(new PendingCandidate(
                    candidate, true, keepRooms, exchangeRooms, List.of(), List.of()));
        }
    }

    private void collectCrossClass(
            DraftLesson target,
            List<DraftLesson> lessons,
            Map<Long, Course> courseById,
            Map<Long, Teacher> teacherById,
            Map<Long, AdminClass> classById,
            CandidateFilters filters,
            List<PendingCandidate> pending) {
        Course targetCourse = courseById.get(target.getCourseId());
        Teacher targetTeacher = teacherById.get(target.getTeacherId());
        for (DraftLesson candidate : lessons) {
            Course candidateCourse = courseById.get(candidate.getCourseId());
            if (candidate.getId().equals(target.getId())
                    || candidate.getAdminClassId().equals(target.getAdminClassId())
                    || candidate.getPeriodId() == null
                    || Objects.equals(candidate.getTeacherId(), target.getTeacherId())
                    || excluded(candidate, filters, classById)) {
                continue;
            }
            SubjectMatch.Result courseMatch =
                    SubjectMatch.of(subjectOf(targetCourse), subjectOf(candidateCourse));
            if (courseMatch == SubjectMatch.Result.MISMATCH) {
                continue;
            }
            Teacher candidateTeacher = teacherById.get(candidate.getTeacherId());
            List<DraftValidator.Conflict> extraConflicts = new ArrayList<>();
            List<String> warnings = new ArrayList<>();
            if (courseMatch == SubjectMatch.Result.UNKNOWN) {
                addWarning(warnings, "课程「" + courseName(targetCourse) + "」与「"
                        + courseName(candidateCourse) + "」的科目未维护，已跳过科目一致性校验");
            }
            checkSubjectMatch(extraConflicts, warnings, targetTeacher, candidateCourse, candidate.getId());
            checkSubjectMatch(extraConflicts, warnings, candidateTeacher, targetCourse, target.getId());
            pending.add(new PendingCandidate(candidate, false, List.of(
                    new LessonChange(target.getId(), target.getPeriodId(),
                            target.getRoomId(), candidate.getTeacherId()),
                    new LessonChange(candidate.getId(), candidate.getPeriodId(),
                            candidate.getRoomId(), target.getTeacherId())),
                    null, extraConflicts, warnings));
        }
    }

    /**
     * A cross-class exchange is only sound when both teachers can teach the course
     * they receive. Blank data skips the check and is surfaced as a warning; this is
     * the same {@link SubjectMatch} rule the write endpoint applies.
     */
    private void checkSubjectMatch(
            List<DraftValidator.Conflict> conflicts,
            List<String> warnings,
            Teacher teacher,
            Course course,
            Long lessonId) {
        if (teacher == null || course == null) {
            return;
        }
        SubjectMatch.Result result = SubjectMatch.of(teacher.getSubject(), course.getSubject());
        if (result == SubjectMatch.Result.UNKNOWN) {
            addWarning(warnings, SubjectMatch.normalize(teacher.getSubject()) == null
                    ? "「" + teacher.getName() + "」未配置教师学科，已跳过学科校验"
                    : "课程「" + course.getName() + "」未配置科目，已跳过学科校验");
            return;
        }
        if (result == SubjectMatch.Result.MISMATCH) {
            conflicts.add(new DraftValidator.Conflict(
                    "TEACHER_SUBJECT_MISMATCH",
                    teacher.getName() + " 的学科「" + SubjectMatch.display(teacher.getSubject())
                            + "」与课程「" + course.getName() + "」的科目「"
                            + SubjectMatch.display(course.getSubject()) + "」不一致",
                    lessonId));
        }
    }

    private void addWarning(List<String> warnings, String warning) {
        if (!warnings.contains(warning)) {
            warnings.add(warning);
        }
    }

    private boolean excluded(
            DraftLesson candidate,
            CandidateFilters filters,
            Map<Long, AdminClass> classById) {
        return (filters.courseId() != null && !filters.courseId().equals(candidate.getCourseId()))
                || (filters.gradeId() != null && !filters.gradeId().equals(gradeIdOf(candidate, classById)))
                || (filters.classId() != null && !filters.classId().equals(candidate.getAdminClassId()))
                || (filters.teacherId() != null && !filters.teacherId().equals(candidate.getTeacherId()));
    }

    private Comparator<SwapCandidateDto> candidateOrder() {
        return Comparator
                .comparing((SwapCandidateDto dto) -> dto.dayOfWeek(), Comparator.nullsLast(Integer::compareTo))
                .thenComparing(SwapCandidateDto::periodNo, Comparator.nullsLast(Integer::compareTo))
                .thenComparing(SwapCandidateDto::lessonId);
    }

    private String subjectOf(Course course) {
        return course == null ? null : course.getSubject();
    }

    private String courseName(Course course) {
        return course == null ? "课程" : course.getName();
    }

    private String periodLabel(Period period) {
        return "周" + period.getDayOfWeek() + "第" + period.getPeriodNo() + "节";
    }

    private SwapCandidateDto toDto(
            DraftLesson candidate,
            Map<Long, Period> periodById,
            Map<Long, Room> roomById,
            Map<Long, Course> courseById,
            Map<Long, Teacher> teacherById,
            Map<Long, AdminClass> classById,
            Map<Long, Grade> gradeById,
            boolean available,
            boolean roomSwapRequired,
            List<DraftValidator.Conflict> conflicts,
            List<String> warnings) {
        Period period = periodById.get(candidate.getPeriodId());
        Room room = candidate.getRoomId() == null ? null : roomById.get(candidate.getRoomId());
        Course course = courseById.get(candidate.getCourseId());
        Teacher teacher = teacherById.get(candidate.getTeacherId());
        AdminClass adminClass = classById.get(candidate.getAdminClassId());
        Grade grade = adminClass == null ? null : gradeById.get(adminClass.getGradeId());
        return new SwapCandidateDto(
                candidate.getId(),
                candidate.getCourseId(),
                course == null ? null : course.getName(),
                course == null ? null : course.getSubject(),
                candidate.getTeacherId(),
                teacher == null ? null : teacher.getName(),
                candidate.getAdminClassId(),
                adminClass == null ? null : adminClass.getName(),
                adminClass == null ? null : adminClass.getGradeId(),
                grade == null ? null : grade.getName(),
                candidate.getPeriodId(),
                period == null ? null : period.getDayOfWeek(),
                period == null ? null : period.getPeriodNo(),
                period == null ? null : periodLabel(period),
                candidate.getRoomId(),
                room == null ? null : room.getName(),
                available,
                roomSwapRequired,
                conflicts,
                warnings);
    }

    private Long gradeIdOf(DraftLesson lesson, Map<Long, AdminClass> classById) {
        AdminClass adminClass = classById.get(lesson.getAdminClassId());
        return adminClass == null ? null : adminClass.getGradeId();
    }

    /**
     * One validation per period, all against the same snapshot, with the lesson's
     * current room carried along. Replaces the drag preview's request-per-slot loop.
     */
    @Transactional(readOnly = true)
    public MoveTargetsResponse moveTargets(Long draftId, Long lessonId) {
        TimetableDraft draft = drafts.findById(draftId)
                .orElseThrow(() -> ApiException.notFound("课表草稿不存在: " + draftId));
        List<DraftLesson> lessons = draftLessons.findByDraftIdOrderByIdAsc(draftId);
        DraftLesson target = lessons.stream()
                .filter(lesson -> lesson.getId().equals(lessonId))
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("课节不存在: " + lessonId));
        List<Period> periodList =
                periods.findBySemesterIdOrderByDayOfWeekAscPeriodNoAsc(draft.getSemesterId());
        List<List<LessonOverride>> scenarios = periodList.stream()
                .<List<LessonOverride>>map(period ->
                        List.of(new LessonOverride(lessonId, period.getId(), null, null)))
                .toList();
        List<DraftValidator.ValidationResult> results = validator.validateScenarios(draftId, scenarios);

        List<MoveTargetDto> targets = new ArrayList<>(periodList.size());
        for (int index = 0; index < periodList.size(); index++) {
            Period period = periodList.get(index);
            DraftValidator.ValidationResult result = results.get(index);
            targets.add(new MoveTargetDto(
                    period.getId(),
                    period.getDayOfWeek(),
                    period.getPeriodNo(),
                    periodLabel(period),
                    period.getSection().name(),
                    result.valid(),
                    result.hardConflicts(),
                    result.conflicts()));
        }
        return new MoveTargetsResponse(lessonId, target.getRoomId(), targets);
    }
}
