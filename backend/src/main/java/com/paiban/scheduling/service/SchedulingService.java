package com.paiban.scheduling.service;

import ai.timefold.solver.core.api.score.buildin.hardsoft.HardSoftScore;
import ai.timefold.solver.core.api.solver.Solver;
import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.solver.SolverConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paiban.common.web.ApiException;
import com.paiban.foundation.domain.AdminClass;
import com.paiban.foundation.domain.AdminClassRepository;
import com.paiban.foundation.domain.Course;
import com.paiban.foundation.domain.CourseRepository;
import com.paiban.foundation.domain.Period;
import com.paiban.foundation.domain.PeriodRepository;
import com.paiban.foundation.domain.Room;
import com.paiban.foundation.domain.RoomRepository;
import com.paiban.foundation.domain.Teacher;
import com.paiban.foundation.domain.TeacherRepository;
import com.paiban.foundation.service.SemesterService;
import com.paiban.foundation.web.SemesterDtos.PeriodDto;
import com.paiban.foundation.web.FoundationDtos.RoomDto;
import com.paiban.scheduling.domain.AssignmentRepository;
import com.paiban.scheduling.domain.DraftLesson;
import com.paiban.scheduling.domain.DraftLessonRepository;
import com.paiban.scheduling.domain.SchedulingJob;
import com.paiban.scheduling.domain.SchedulingJobRepository;
import com.paiban.scheduling.domain.TimetableDraft;
import com.paiban.scheduling.domain.TimetableDraftRepository;
import com.paiban.scheduling.solver.ConstraintSettings;
import com.paiban.scheduling.solver.ConstraintSettingsHolder;
import com.paiban.scheduling.solver.Lesson;
import com.paiban.scheduling.solver.PaibanConstraintProvider;
import com.paiban.scheduling.solver.TimetableSolution;
import com.paiban.scheduling.web.SchedulingDtos.DraftDetailDto;
import com.paiban.scheduling.web.SchedulingDtos.DraftDto;
import com.paiban.scheduling.web.SchedulingDtos.DraftLessonDto;
import com.paiban.scheduling.web.SchedulingDtos.JobDto;
import com.paiban.scheduling.web.SchedulingDtos.LessonOverride;
import com.paiban.scheduling.web.SchedulingDtos.MoveResult;
import com.paiban.scheduling.web.SchedulingDtos.StartJobRequest;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SchedulingService {

    private static final Logger log = LoggerFactory.getLogger(SchedulingService.class);

    private final SchedulingJobRepository jobs;
    private final TimetableDraftRepository drafts;
    private final DraftLessonRepository draftLessons;
    private final AssignmentRepository assignments;
    private final PeriodRepository periods;
    private final RoomRepository rooms;
    private final CourseRepository courses;
    private final TeacherRepository teachers;
    private final AdminClassRepository adminClasses;
    private final SemesterService semesters;
    private final ConstraintService constraints;
    private final TimetableProblemBuilder problemBuilder;
    private final DraftValidator validator;
    private final ObjectMapper objectMapper;
    private final TaskExecutor executor;
    private final int defaultSolveSeconds;
    private final Map<Long, Solver<TimetableSolution>> runningSolvers = new ConcurrentHashMap<>();
    private final Object startLock = new Object();

    public SchedulingService(
            SchedulingJobRepository jobs,
            TimetableDraftRepository drafts,
            DraftLessonRepository draftLessons,
            AssignmentRepository assignments,
            PeriodRepository periods,
            RoomRepository rooms,
            CourseRepository courses,
            TeacherRepository teachers,
            AdminClassRepository adminClasses,
            SemesterService semesters,
            ConstraintService constraints,
            TimetableProblemBuilder problemBuilder,
            DraftValidator validator,
            ObjectMapper objectMapper,
            @Qualifier("schedulingExecutor") TaskExecutor executor,
            @Value("${paiban.scheduling.max-solve-seconds:60}") int defaultSolveSeconds) {
        this.jobs = jobs;
        this.drafts = drafts;
        this.draftLessons = draftLessons;
        this.assignments = assignments;
        this.periods = periods;
        this.rooms = rooms;
        this.courses = courses;
        this.teachers = teachers;
        this.adminClasses = adminClasses;
        this.semesters = semesters;
        this.constraints = constraints;
        this.problemBuilder = problemBuilder;
        this.validator = validator;
        this.objectMapper = objectMapper;
        this.executor = executor;
        this.defaultSolveSeconds = defaultSolveSeconds;
    }

    public JobDto start(Long semesterId, StartJobRequest request, String actor) {
        Long resolved = semesterId != null
                ? semesters.require(semesterId).getId()
                : semesters.requireCurrent().getId();
        synchronized (startLock) {
            if (jobs.existsBySemesterIdAndStatusIn(
                    resolved, List.of(SchedulingJob.PENDING, SchedulingJob.RUNNING))) {
                throw ApiException.conflict("当前学期已有排课任务在运行，请等待完成或先取消");
            }
            if (assignments.countBySemesterId(resolved) == 0) {
                throw ApiException.badRequest("请先录入授课任务再提交排课");
            }
            if (periods.countBySemesterId(resolved) == 0) {
                throw ApiException.badRequest("请先配置作息表再提交排课");
            }
            if (rooms.count() == 0) {
                throw ApiException.badRequest("请先配置教室再提交排课");
            }
            SchedulingJob job = new SchedulingJob();
            job.setSemesterId(resolved);
            job.setStatus(SchedulingJob.PENDING);
            job.setCreatedAt(Instant.now());
            job.setCreatedBy(actor);
            job.setDraftName(request == null || request.draftName() == null || request.draftName().isBlank()
                    ? "方案 " + DateTimeFormatter.ofPattern("MM-dd HH:mm").format(java.time.LocalDateTime.now())
                    : request.draftName());
            job.setSolveSeconds(request == null || request.maxSeconds() == null
                    ? defaultSolveSeconds
                    : request.maxSeconds());
            SchedulingJob saved = jobs.save(job);
            executor.execute(() -> run(saved.getId()));
            return toDto(saved);
        }
    }

    public void run(Long jobId) {
        SchedulingJob job = jobs.findById(jobId).orElse(null);
        if (job == null || SchedulingJob.CANCELLED.equals(job.getStatus())) {
            return;
        }
        if (jobs.markRunning(jobId, Instant.now()) == 0) {
            return;
        }
        long startedAt = System.currentTimeMillis();
        try {
            ConstraintSettings settings =
                    ConstraintSettings.from(constraints.active(job.getSemesterId()), objectMapper);
            ConstraintSettingsHolder.set(settings);
            TimetableSolution problem = problemBuilder.build(job.getSemesterId());
            if (problem.getLessons().isEmpty()) {
                throw new IllegalStateException("当前学期没有可排的授课任务");
            }
            SolverConfig solverConfig = new SolverConfig()
                    .withSolutionClass(TimetableSolution.class)
                    .withEntityClasses(Lesson.class)
                    .withConstraintProviderClass(PaibanConstraintProvider.class)
                    .withTerminationSpentLimit(Duration.ofSeconds(job.getSolveSeconds()));
            Solver<TimetableSolution> solver =
                    SolverFactory.<TimetableSolution>create(solverConfig).buildSolver();
            runningSolvers.put(jobId, solver);
            if (isCancelled(jobId)) {
                // The cancel arrived while the solver was being built, before it was
                // registered; without this check the single solver thread would stay
                // busy for the whole time limit and block the next submission.
                markFinished(jobId, SchedulingJob.CANCELLED, null, null, System.currentTimeMillis() - startedAt);
                return;
            }
            AtomicLong lastWrite = new AtomicLong(0);
            solver.addEventListener(event -> {
                long now = System.currentTimeMillis();
                if (now - lastWrite.get() >= 400) {
                    lastWrite.set(now);
                    updateProgress(jobId, event.getNewBestSolution(), startedAt);
                }
            });
            TimetableSolution solution = solver.solve(problem);
            long elapsed = System.currentTimeMillis() - startedAt;
            if (solver.isTerminateEarly()) {
                markFinished(jobId, SchedulingJob.CANCELLED, null, null, elapsed);
            } else {
                persistDraft(jobId, solution, elapsed);
            }
        } catch (Exception ex) {
            log.warn("Scheduling job {} failed: {}", jobId, ex.getMessage(), ex);
            markFinished(
                    jobId,
                    SchedulingJob.FAILED,
                    null,
                    ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage(),
                    System.currentTimeMillis() - startedAt);
        } finally {
            runningSolvers.remove(jobId);
            ConstraintSettingsHolder.reset();
        }
    }

    private void updateProgress(Long jobId, TimetableSolution solution, long startedAt) {
        jobs.findById(jobId).ifPresent(job -> {
            if (SchedulingJob.RUNNING.equals(job.getStatus())) {
                long elapsed = System.currentTimeMillis() - startedAt;
                job.setElapsedMs(elapsed);
                job.setProgress((int) Math.min(99, elapsed * 100 / Math.max(1, job.getSolveSeconds() * 1000L)));
                if (solution.getScore() != null) {
                    job.setScore(solution.getScore().toString());
                }
                jobs.save(job);
            }
        });
    }

    private boolean isCancelled(Long jobId) {
        return jobs.findById(jobId)
                .map(job -> SchedulingJob.CANCELLED.equals(job.getStatus()))
                .orElse(true);
    }

    private void markFinished(Long jobId, String status, Long draftId, String error, long elapsed) {
        jobs.findById(jobId).ifPresent(job -> {
            job.setStatus(status);
            job.setElapsedMs(elapsed);
            job.setFinishedAt(Instant.now());
            if (draftId != null) {
                job.setDraftId(draftId);
            }
            if (error != null) {
                job.setErrorMessage(error);
            }
            if (SchedulingJob.COMPLETED.equals(status)) {
                job.setProgress(100);
            }
            jobs.save(job);
        });
    }

    private void persistDraft(Long jobId, TimetableSolution solution, long elapsed) {
        SchedulingJob job = jobs.findById(jobId)
                .orElseThrow(() -> ApiException.notFound("排课任务不存在: " + jobId));
        if (SchedulingJob.CANCELLED.equals(job.getStatus())) {
            return;
        }
        TimetableDraft draft = new TimetableDraft();
        draft.setSemesterId(job.getSemesterId());
        draft.setName(job.getDraftName());
        draft.setStatus("DRAFT");
        HardSoftScore score = solution.getScore();
        draft.setScoreHard(score == null ? 0 : score.hardScore());
        draft.setScoreSoft(score == null ? 0 : score.softScore());
        draft.setCreatedBy(job.getCreatedBy());
        draft.setCreatedAt(Instant.now());
        TimetableDraft savedDraft = drafts.save(draft);

        List<DraftLesson> rows = new ArrayList<>();
        for (Lesson lesson : solution.getLessons()) {
            DraftLesson row = new DraftLesson();
            row.setDraftId(savedDraft.getId());
            row.setAssignmentId(lesson.getAssignmentId());
            row.setLessonIndex(lesson.getLessonIndex());
            row.setCourseId(lesson.getCourseId());
            row.setTeacherId(lesson.getTeacherId());
            row.setAdminClassId(lesson.getAdminClassId());
            row.setStudentCount(lesson.getStudentCount());
            row.setPeriodId(lesson.getPeriod() == null ? null : lesson.getPeriod().getId());
            row.setRoomId(lesson.getRoom() == null ? null : lesson.getRoom().getId());
            rows.add(row);
        }
        draftLessons.saveAll(rows);

        job.setStatus(SchedulingJob.COMPLETED);
        job.setDraftId(savedDraft.getId());
        job.setScore(score == null ? "0hard/0soft" : score.toString());
        job.setElapsedMs(elapsed);
        job.setProgress(100);
        job.setFinishedAt(Instant.now());
        jobs.save(job);
    }

    public JobDto cancel(Long jobId) {
        SchedulingJob job = jobs.findById(jobId)
                .orElseThrow(() -> ApiException.notFound("排课任务不存在: " + jobId));
        if (SchedulingJob.COMPLETED.equals(job.getStatus())
                || SchedulingJob.FAILED.equals(job.getStatus())
                || SchedulingJob.CANCELLED.equals(job.getStatus())) {
            throw ApiException.conflict("任务已结束，无法取消");
        }
        job.setStatus(SchedulingJob.CANCELLED);
        job.setFinishedAt(Instant.now());
        jobs.save(job);
        Solver<TimetableSolution> solver = runningSolvers.get(jobId);
        if (solver != null) {
            solver.terminateEarly();
        }
        return toDto(job);
    }

    @Transactional(readOnly = true)
    public JobDto get(Long jobId) {
        return toDto(jobs.findById(jobId)
                .orElseThrow(() -> ApiException.notFound("排课任务不存在: " + jobId)));
    }

    @Transactional(readOnly = true)
    public List<JobDto> listJobs(Long semesterId) {
        Long resolved = semesterId != null ? semesterId : semesters.requireCurrent().getId();
        return jobs.findBySemesterIdOrderByCreatedAtDesc(resolved).stream().map(this::toDto).toList();
    }

    // ---------- drafts ----------

    @Transactional(readOnly = true)
    public List<DraftDto> listDrafts(Long semesterId) {
        Long resolved = semesterId != null ? semesterId : semesters.requireCurrent().getId();
        return drafts.findBySemesterIdOrderByCreatedAtDesc(resolved).stream()
                .map(draft -> toDto(draft, draftLessons.countByDraftId(draft.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public DraftDetailDto draftDetail(Long draftId) {
        TimetableDraft draft = requireDraft(draftId);
        List<DraftLesson> rows = draftLessons.findByDraftIdOrderByIdAsc(draftId);
        Map<Long, Period> periodMap = new HashMap<>();
        periods.findBySemesterIdOrderByDayOfWeekAscPeriodNoAsc(draft.getSemesterId())
                .forEach(period -> periodMap.put(period.getId(), period));
        Map<Long, Room> roomMap = new HashMap<>();
        rooms.findAll().forEach(room -> roomMap.put(room.getId(), room));
        Map<Long, Course> courseMap = new HashMap<>();
        courses.findAll().forEach(course -> courseMap.put(course.getId(), course));
        Map<Long, Teacher> teacherMap = new HashMap<>();
        teachers.findAll().forEach(teacher -> teacherMap.put(teacher.getId(), teacher));
        Map<Long, AdminClass> classMap = new HashMap<>();
        adminClasses.findBySemesterIdOrderByGradeIdAscNameAsc(draft.getSemesterId())
                .forEach(adminClass -> classMap.put(adminClass.getId(), adminClass));

        List<DraftLessonDto> lessons = rows.stream()
                .map(row -> {
                    Period period = row.getPeriodId() == null ? null : periodMap.get(row.getPeriodId());
                    Room room = row.getRoomId() == null ? null : roomMap.get(row.getRoomId());
                    Course course = courseMap.get(row.getCourseId());
                    Teacher teacher = teacherMap.get(row.getTeacherId());
                    AdminClass adminClass = classMap.get(row.getAdminClassId());
                    return new DraftLessonDto(
                            row.getId(),
                            row.getAssignmentId(),
                            row.getCourseId(),
                            course == null ? null : course.getName(),
                            row.getTeacherId(),
                            teacher == null ? null : teacher.getName(),
                            row.getAdminClassId(),
                            adminClass == null ? null : adminClass.getName(),
                            row.getStudentCount(),
                            row.getPeriodId(),
                            period == null ? null : period.getDayOfWeek(),
                            period == null ? null : period.getPeriodNo(),
                            period == null ? null : period.getSection().name(),
                            period == null ? null : "周" + period.getDayOfWeek() + "第" + period.getPeriodNo() + "节",
                            row.getRoomId(),
                            room == null ? null : room.getName());
                })
                .toList();

        List<PeriodDto> periodDtos = periods
                .findBySemesterIdOrderByDayOfWeekAscPeriodNoAsc(draft.getSemesterId()).stream()
                .map(period -> new PeriodDto(
                        period.getId(),
                        period.getDayOfWeek(),
                        period.getPeriodNo(),
                        period.getLabel(),
                        period.getSection(),
                        period.getStartTime(),
                        period.getEndTime()))
                .toList();
        List<RoomDto> roomDtos = rooms.findAllByOrderByNameAsc().stream()
                .map(room -> new RoomDto(
                        room.getId(), room.getName(), room.getCapacity(), room.getType(), room.getBuilding()))
                .toList();
        return new DraftDetailDto(
                toDto(draft, rows.size()), lessons, periodDtos, roomDtos);
    }

    @Transactional
    public DraftDto renameDraft(Long draftId, String name) {
        TimetableDraft draft = requireDraft(draftId);
        draft.setName(name);
        return toDto(draft, draftLessons.countByDraftId(draftId));
    }

    @Transactional
    public void deleteDraft(Long draftId) {
        TimetableDraft draft = requireDraft(draftId);
        if ("PUBLISHED".equals(draft.getStatus())) {
            throw ApiException.conflict("已发布的课表不能删除");
        }
        draftLessons.deleteByDraftId(draftId);
        drafts.delete(draft);
    }

    @Transactional(readOnly = true)
    public DraftValidator.ValidationResult validateMove(Long draftId, Long lessonId, Long periodId, Long roomId) {
        return validator.validateMove(draftId, lessonId, periodId, roomId);
    }

    @Transactional(readOnly = true)
    public DraftValidator.ValidationResult validateMoves(Long draftId, List<LessonOverride> moves) {
        return validator.validateMoves(draftId, moves);
    }

    @Transactional(readOnly = true)
    public DraftValidator.ValidationResult validateDraft(Long draftId) {
        return validator.validateDraft(draftId);
    }

    @Transactional
    public MoveResult moveLesson(Long draftId, Long lessonId, Long periodId, Long roomId, boolean force) {
        DraftValidator.ValidationResult result = validator.validateMove(draftId, lessonId, periodId, roomId);
        if (!result.valid() && !force) {
            return new MoveResult(false, false, result.hardConflicts(), result.conflicts());
        }
        DraftLesson lesson = draftLessons.findById(lessonId)
                .orElseThrow(() -> ApiException.notFound("课节不存在: " + lessonId));
        if (!lesson.getDraftId().equals(draftId)) {
            throw ApiException.badRequest("课节不属于该草稿");
        }
        // A forced move bypasses the "period missing" hard conflict, so the reference is
        // checked here instead of relying on the draft_lesson.period_id foreign key.
        if (periodId != null && !periods.existsById(periodId)) {
            throw ApiException.badRequest("课节对应的作息时段不存在: " + periodId);
        }
        lesson.setPeriodId(periodId);
        lesson.setRoomId(roomId);
        TimetableDraft draft = requireDraft(draftId);
        draft.setScoreHard(-result.hardConflicts());
        return new MoveResult(true, result.valid(), result.hardConflicts(), result.conflicts());
    }

    private TimetableDraft requireDraft(Long draftId) {
        return drafts.findById(draftId)
                .orElseThrow(() -> ApiException.notFound("课表草稿不存在: " + draftId));
    }

    private JobDto toDto(SchedulingJob job) {
        long remaining = "RUNNING".equals(job.getStatus())
                ? Math.max(0, job.getSolveSeconds() * 1000L - job.getElapsedMs())
                : 0;
        return new JobDto(
                job.getId(),
                job.getSemesterId(),
                job.getStatus(),
                job.getScore(),
                job.getProgress(),
                job.getElapsedMs(),
                remaining,
                job.getErrorMessage(),
                job.getDraftId(),
                job.getDraftName(),
                job.getSolveSeconds(),
                job.getCreatedAt(),
                job.getStartedAt(),
                job.getFinishedAt());
    }

    private DraftDto toDto(TimetableDraft draft, long lessonCount) {
        return new DraftDto(
                draft.getId(),
                draft.getSemesterId(),
                draft.getName(),
                draft.getStatus(),
                draft.getScoreHard(),
                draft.getScoreSoft(),
                draft.getCreatedBy(),
                draft.getCreatedAt(),
                lessonCount);
    }
}
