package com.paiban.scheduling.service;

import ai.timefold.solver.core.api.score.buildin.hardsoft.HardSoftScore;
import ai.timefold.solver.core.api.solver.Solver;
import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.constructionheuristic.ConstructionHeuristicPhaseConfig;
import ai.timefold.solver.core.config.constructionheuristic.ConstructionHeuristicType;
import ai.timefold.solver.core.config.localsearch.LocalSearchPhaseConfig;
import ai.timefold.solver.core.config.solver.SolverConfig;
import com.paiban.common.web.ApiException;
import com.paiban.foundation.domain.AdminClass;
import com.paiban.foundation.domain.AdminClassRepository;
import com.paiban.foundation.domain.AuditLog;
import com.paiban.foundation.domain.AuditLogRepository;
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
import com.paiban.scheduling.domain.ConstraintType;
import com.paiban.scheduling.domain.DraftLesson;
import com.paiban.scheduling.domain.DraftLessonRepository;
import com.paiban.scheduling.domain.SchedulingJob;
import com.paiban.scheduling.domain.SchedulingJobRepository;
import com.paiban.scheduling.domain.TimetableDraft;
import com.paiban.scheduling.domain.TimetableDraftRepository;
import com.paiban.scheduling.solver.ConstraintSettings;
import com.paiban.scheduling.solver.ConstraintSettingsHolder;
import com.paiban.scheduling.solver.ConstraintEntryMatcher;
import com.paiban.scheduling.solver.Lesson;
import com.paiban.scheduling.solver.PaibanConstraintProvider;
import com.paiban.scheduling.solver.TimetableSolution;
import com.paiban.scheduling.web.SchedulingDtos.DraftDetailDto;
import com.paiban.scheduling.web.SchedulingDtos.BlockedSlotDto;
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
import java.util.Arrays;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

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
    private final ClassCapacityService capacity;
    private final TimetableProblemBuilder problemBuilder;
    private final DraftValidator validator;
    private final DraftWriteGuard guard;
    private final AuditLogRepository auditLogs;
    private final SourceDraftSnapshotService sourceSnapshots;
    private final TransactionTemplate transactions;
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
            ClassCapacityService capacity,
            TimetableProblemBuilder problemBuilder,
            DraftValidator validator,
            DraftWriteGuard guard,
            AuditLogRepository auditLogs,
            SourceDraftSnapshotService sourceSnapshots,
            PlatformTransactionManager transactionManager,
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
        this.capacity = capacity;
        this.problemBuilder = problemBuilder;
        this.validator = validator;
        this.guard = guard;
        this.auditLogs = auditLogs;
        this.sourceSnapshots = sourceSnapshots;
        this.transactions = new TransactionTemplate(transactionManager);
        this.executor = executor;
        this.defaultSolveSeconds = defaultSolveSeconds;
    }

    public JobDto start(Long semesterId, StartJobRequest request, String actor) {
        Long resolved = semesterId != null
                ? semesters.require(semesterId).getId()
                : semesters.requireCurrent().getId();
        // An explicit source is validated and frozen before any job exists. Its absence
        // keeps the legacy path (and its default warm-start policy) exactly unchanged.
        SourceDraftSnapshot snapshot = request != null && request.sourceDraftId() != null
                ? sourceSnapshots.capture(request.sourceDraftId(), resolved)
                : null;
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
            // Necessary condition only: an obvious shortage is explained before a job
            // is created. Assignments, drafts and published baselines stay untouched.
            String shortage = capacity.message(resolved, constraints.settings(resolved));
            if (StringUtils.hasText(shortage)) {
                throw ApiException.badRequest(shortage);
            }
            SchedulingJob job = new SchedulingJob();
            job.setSemesterId(resolved);
            job.setStatus(SchedulingJob.PENDING);
            job.setCreatedAt(Instant.now());
            job.setCreatedBy(actor);
            job.setDraftName(draftName(request, snapshot));
            job.setSolveSeconds(request == null || request.maxSeconds() == null
                    ? defaultSolveSeconds
                    : request.maxSeconds());
            if (snapshot != null) {
                job.setSourceDraftId(snapshot.draftId());
                job.setSourceDraftVersion(snapshot.draftVersion());
                job.setSourceSnapshotJson(sourceSnapshots.toJson(snapshot));
                job.setInheritedLockCount(sourceSnapshots.lockedCount(snapshot));
            }
            SchedulingJob saved = jobs.save(job);
            executor.execute(() -> run(saved.getId()));
            return toDto(saved);
        }
    }

    private static String draftName(StartJobRequest request, SourceDraftSnapshot snapshot) {
        if (request != null && request.draftName() != null && !request.draftName().isBlank()) {
            return request.draftName();
        }
        String stamp = DateTimeFormatter.ofPattern("MM-dd HH:mm")
                .format(java.time.LocalDateTime.now());
        return snapshot == null ? "方案 " + stamp : "基于「" + snapshot.draftName() + "」优化 " + stamp;
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
            // Same snapshot builder as the draft check and the substitution verifier,
            // so the solver cannot read a different rule set.
            ConstraintSettings settings = constraints.settings(job.getSemesterId());
            // The queued job used the settings at submission; rules may have changed
            // while it waited, so the real start re-checks against the current ones.
            String shortage = capacity.message(job.getSemesterId(), settings);
            if (StringUtils.hasText(shortage)) {
                throw new IllegalStateException(shortage);
            }
            ConstraintSettingsHolder.set(settings);
            // A job with an explicit source works from its frozen snapshot, never from
            // the default baseline/oldest-draft policy. The fingerprint is re-checked
            // before solving so an edited assignment fails instead of mixing states.
            SourceDraftSnapshot snapshot = null;
            TimetableProblemBuilder.BuiltProblem built;
            if (job.getSourceDraftId() != null) {
                snapshot = sourceSnapshots.fromJson(job.getSourceSnapshotJson());
                sourceSnapshots.requireAssignmentsUnchanged(snapshot, job.getSemesterId());
                built = problemBuilder.buildFromSource(job.getSemesterId(), settings, snapshot);
            } else {
                built = problemBuilder.buildWithWarmStart(job.getSemesterId(), settings);
            }
            // markRunning is a bulk update that bypasses the entity read above, so that
            // instance is stale; write only the warm-start source with a targeted update
            // instead of saving the entity, which could overwrite a concurrent CANCELLED.
            jobs.updateWarmStartSource(jobId, built.warmStartSource());
            TimetableSolution problem = built.solution();
            if (problem.getLessons().isEmpty()) {
                throw new IllegalStateException("当前学期没有可排的授课任务");
            }
            // CHEAPEST_INSERTION builds a much better first solution than the default
            // first-fit heuristic on a tight timetable: it costs every lesson/period slot
            // before committing, so teacher conflicts are avoided up front instead of
            // being left for local search to untangle.
            SolverConfig solverConfig = new SolverConfig()
                    .withSolutionClass(TimetableSolution.class)
                    .withEntityClasses(Lesson.class)
                    .withConstraintProviderClass(PaibanConstraintProvider.class)
                    .withPhases(
                            new ConstructionHeuristicPhaseConfig()
                                    .withConstructionHeuristicType(ConstructionHeuristicType.CHEAPEST_INSERTION),
                            new LocalSearchPhaseConfig())
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
                if (snapshot != null) {
                    // Assignments may have changed while the solver ran; refuse to write
                    // a draft whose lesson identity no longer matches the snapshot.
                    sourceSnapshots.requireAssignmentsUnchanged(snapshot, job.getSemesterId());
                }
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

    /**
     * Persists the new draft and the job result as one unit. This method is called from
     * {@link #run} inside the same bean, so an {@code @Transactional} annotation here
     * would be ignored (self-invocation); a {@link TransactionTemplate} gives the same
     * guarantee explicitly. A failed lock check or validation write rolls everything
     * back, leaving no half draft and the source draft untouched.
     */
    private void persistDraft(Long jobId, TimetableSolution solution, long elapsed) {
        transactions.executeWithoutResult(status -> persistDraftInTransaction(jobId, solution, elapsed));
    }

    private void persistDraftInTransaction(Long jobId, TimetableSolution solution, long elapsed) {
        SchedulingJob job = jobs.findById(jobId)
                .orElseThrow(() -> ApiException.notFound("排课任务不存在: " + jobId));
        if (SchedulingJob.CANCELLED.equals(job.getStatus())) {
            return;
        }
        if (job.getSourceDraftId() != null) {
            // The hard constraint should make this impossible; the explicit check turns
            // a solver bug into a clear failure instead of a silently moved locked lesson.
            for (Lesson lesson : solution.getLessons()) {
                if (lesson.getLockedPeriodId() != null
                        && (lesson.getPeriod() == null
                        || !lesson.getLockedPeriodId().equals(lesson.getPeriod().getId()))) {
                    throw new IllegalStateException("锁定课节无法在本次求解中保持时间，未生成新草稿");
                }
            }
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
            row.setLocked(lesson.isLocked());
            rows.add(row);
        }
        draftLessons.saveAll(rows);

        // Report the actual publish verdict instead of equating "the solver finished"
        // with "this draft can be published" (spec §七).
        DraftValidator.ValidationResult validation = validator.validateDraft(savedDraft.getId());
        job.setResultPublishable(validation.publishable());
        List<String> blockerMessages = validation.blockers().stream()
                .map(DraftPublishReadiness.Blocker::message)
                .toList();
        job.setResultBlockers(blockerMessages.isEmpty() ? null : String.join("\n", blockerMessages));

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
                            row.getLessonIndex(),
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
                            room == null ? null : room.getName(),
                            row.isLocked());
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
        ConstraintSettings settings = constraints.settings(draft.getSemesterId());
        List<BlockedSlotDto> blockedSlots = new ArrayList<>();
        for (Long classId : classMap.keySet().stream().sorted().toList()) {
            for (PeriodDto period : periodDtos) {
                String reason = blockedSlotReason(
                        settings, classId, period.dayOfWeek(), period.periodNo());
                if (reason != null) {
                    blockedSlots.add(new BlockedSlotDto(classId, period.id(), reason));
                }
            }
        }
        return new DraftDetailDto(
                toDto(draft, rows.size()), lessons, periodDtos, roomDtos, blockedSlots,
                settings.fingerprint());
    }

    /**
     * Why one class's slot is blocked, named by the requirement that actually covers it.
     * A whole-school rule and this class's own rule are reported apart — and both when
     * they overlap — so the tooltip says which requirement a person has to edit. Pausing
     * a requirement removes it from the snapshot, so its source disappears with it.
     *
     * <p>The matching itself stays in {@link ConstraintEntryMatcher}: asking it about
     * {@code null} as the class is how that matcher expresses "the whole-school entry",
     * so the page's wording cannot drift from what the solver evaluates. Only a slot
     * something actually covers gets a reason at all.
     */
    private static String blockedSlotReason(
            ConstraintSettings settings, Long classId, int dayOfWeek, int periodNo) {
        boolean wholeSchool = false;
        boolean selectedClass = false;
        for (Map<String, Object> entry : settings.entries(ConstraintType.CLASS_UNAVAILABLE)) {
            if (!ConstraintEntryMatcher.classSlot(entry, classId, dayOfWeek, periodNo)) {
                continue;
            }
            if (ConstraintEntryMatcher.classSlot(entry, null, dayOfWeek, periodNo)) {
                wholeSchool = true;
            } else {
                selectedClass = true;
            }
        }
        if (!wholeSchool && !selectedClass) {
            return null;
        }
        if (wholeSchool && selectedClass) {
            return "全校规则、指定班级规则（本班）";
        }
        return wholeSchool ? "全校规则" : "指定班级规则（本班）";
    }

    @Transactional
    public DraftDto renameDraft(Long draftId, String name) {
        TimetableDraft draft = requireDraft(draftId);
        draft.setName(name);
        return toDto(draft, draftLessons.countByDraftId(draftId));
    }

    @Transactional
    public void deleteDraft(Long draftId, boolean unlockLocked, Integer expectedLockedCount, String actor) {
        TimetableDraft draft = guard.lockEditable(draftId, null);
        int locked = (int) draftLessons.countByDraftIdAndLockedTrue(draftId);
        // The default stays conservative: a plain DELETE never touches a locked lesson.
        // Only the explicit "unlock and delete" confirmation may drop them, and the
        // count the user saw has to match the count still in the draft.
        if (locked > 0 && !unlockLocked) {
            throw ApiException.conflict("草稿含 " + locked + " 节锁定课节，请先解锁后再删除");
        }
        // An explicit unlock-and-delete always has to carry the count the user saw.
        // Comparing it even when nothing is locked now (locked == 0) closes the race
        // where someone else unlocked the draft between the dialog and the request.
        if (unlockLocked) {
            if (expectedLockedCount == null) {
                throw ApiException.badRequest("删除含锁草稿需要确认锁定数量");
            }
            if (expectedLockedCount != locked) {
                throw ApiException.conflict("锁定状态已变化，请刷新后重试");
            }
        }
        draftLessons.deleteByDraftId(draftId);
        drafts.delete(draft);
        auditLogs.save(new AuditLog(
                actor == null ? "system" : actor,
                "DRAFT_DELETE",
                "TIMETABLE_DRAFT",
                "draftId=" + draftId + ", locked=" + locked,
                1,
                0));
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
    public MoveResult moveLesson(
            Long draftId, Long lessonId, Long periodId, Long roomId, boolean force, Long expectedVersion) {
        TimetableDraft draft = guard.lockEditable(draftId, expectedVersion);
        DraftLesson lesson = draftLessons.findById(lessonId)
                .orElseThrow(() -> ApiException.notFound("课节不存在: " + lessonId));
        if (!lesson.getDraftId().equals(draftId)) {
            throw ApiException.badRequest("课节不属于该草稿");
        }
        guard.requireUnlocked(lesson, "移动");
        // A forced move bypasses the "period missing" hard conflict, so the reference is
        // checked here instead of relying on the draft_lesson.period_id foreign key.
        if (periodId != null) {
            Period period = periods.findById(periodId)
                    .orElseThrow(() -> ApiException.badRequest("课节对应的作息时段不存在: " + periodId));
            if (!draft.getSemesterId().equals(period.getSemesterId())) {
                throw ApiException.badRequest("作息时段不属于该学期");
            }
        }
        DraftValidator.ValidationResult result = validator.validateMove(draftId, lessonId, periodId, roomId);
        boolean unavailable = result.conflicts().stream()
                .anyMatch(conflict ->
                        ConstraintType.CLASS_UNAVAILABLE.name().equals(conflict.type()));
        if (!result.valid() && (!force || unavailable)) {
            return new MoveResult(false, false, result.hardConflicts(), result.conflicts(), draft.getVersion());
        }
        lesson.setPeriodId(periodId);
        lesson.setRoomId(roomId);
        guard.bump(draft);
        // The stored score is the solver's last result; a manual move does not rewrite it.
        // The current publish verdict comes from the draft validation endpoint instead.
        return new MoveResult(true, result.valid(), result.hardConflicts(), result.conflicts(), draft.getVersion());
    }

    /**
     * The write path for the explicit "fill fixed rooms" action (ticket 07): assigns a
     * room only to lessons whose {@code roomId} is still empty, so a value a person
     * entered by hand survives. The caller owns the transaction and the all-or-nothing
     * decision. Locks fix period and teacher only, so a locked lesson can still get a room.
     */
    @Transactional
    public int fillMissingRooms(Long draftId, Map<Long, Long> roomIdByLesson) {
        TimetableDraft draft = guard.lockEditable(draftId, null);
        int assigned = 0;
        for (Map.Entry<Long, Long> entry : roomIdByLesson.entrySet()) {
            DraftLesson lesson = draftLessons.findById(entry.getKey())
                    .orElseThrow(() -> ApiException.notFound("课节不存在: " + entry.getKey()));
            if (!lesson.getDraftId().equals(draftId)) {
                throw ApiException.badRequest("课节不属于该草稿");
            }
            if (lesson.getRoomId() == null) {
                lesson.setRoomId(entry.getValue());
                assigned++;
            }
        }
        if (assigned > 0) {
            guard.bump(draft);
        }
        return assigned;
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
                job.getWarmStartSource(),
                job.getSolveSeconds(),
                job.getCreatedAt(),
                job.getStartedAt(),
                job.getFinishedAt(),
                job.getSourceDraftId(),
                job.getSourceDraftVersion(),
                job.getInheritedLockCount(),
                job.getResultPublishable(),
                blockerList(job.getResultBlockers()));
    }

    private static List<String> blockerList(String stored) {
        if (!StringUtils.hasText(stored)) {
            return List.of();
        }
        return Arrays.stream(stored.split("\n"))
                .filter(StringUtils::hasText)
                .toList();
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
                lessonCount,
                draft.getVersion());
    }
}
