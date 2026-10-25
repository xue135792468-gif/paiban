package com.paiban.scheduling.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paiban.common.web.ApiException;
import com.paiban.foundation.domain.AdminClassRepository;
import com.paiban.foundation.domain.CourseRepository;
import com.paiban.foundation.domain.PeriodRepository;
import com.paiban.foundation.domain.RoomRepository;
import com.paiban.foundation.domain.TeacherRepository;
import com.paiban.scheduling.constraint.ConstraintChecker;
import com.paiban.scheduling.constraint.ConstraintContext;
import com.paiban.scheduling.constraint.ConstraintViolation;
import com.paiban.scheduling.constraint.LessonView;
import com.paiban.scheduling.domain.ConstraintConfig;
import com.paiban.scheduling.domain.ConstraintType;
import com.paiban.scheduling.domain.DraftLesson;
import com.paiban.scheduling.domain.DraftLessonRepository;
import com.paiban.scheduling.domain.TimetableDraft;
import com.paiban.scheduling.domain.TimetableDraftRepository;
import com.paiban.scheduling.solver.ConstraintSettings;
import com.paiban.scheduling.web.SchedulingDtos.LessonOverride;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Millisecond conflict checking for manual adjustments. Returns human readable
 * reasons ("张老师此时在 3 班有数学课") instead of a bare score. The rule
 * definitions live in {@link ConstraintChecker} so the published-timetable
 * verifier uses exactly the same 24 rules.
 *
 * <p>{@link #validateDraft} goes one step further than a local check: it also reports
 * the {@link DraftPublishReadiness} blockers, which is the same judgement
 * {@code TimetablePublishService} refuses on. The page therefore cannot say "passed"
 * for a draft publishing will reject.
 */
@Service
public class DraftValidator {

    public record Conflict(String type, String message, Long lessonId) {}

    /**
     * The shape every validation endpoint returns. {@code valid}, {@code hardConflicts} and
     * {@code conflicts} keep their original meaning for existing consumers;
     * {@code publishable} and {@code blockers} are additive.
     *
     * <p>{@code valid} answers "is this draft complete enough to be a timetable?" — no
     * scheduling conflict, no unscheduled lesson, no dangling period and no lesson without
     * a room. It deliberately says nothing about the already-published state
     * ({@code DRAFT_PUBLISHED}, {@code SEMESTER_ALREADY_PUBLISHED}): those are terminal
     * states, not defects of the content, so they only show up in {@code publishable} and
     * {@code blockers}. Otherwise the very draft that was just published would read back as
     * "invalid", which is the response change ticket 13 promised not to make.
     *
     * <p>A local check ({@code validate-move}, {@code validate-moves}, swap scenarios) only
     * looks at the lessons it was asked about, so it reports no blockers and
     * {@code publishable} mirrors {@code valid} there. Only the whole-draft check
     * ({@link #validateDraft}) answers the publishing question.
     */
    public record ValidationResult(
            boolean valid,
            int hardConflicts,
            List<Conflict> conflicts,
            boolean publishable,
            List<DraftPublishReadiness.Blocker> blockers) {}

    private final TimetableDraftRepository drafts;
    private final DraftLessonRepository draftLessons;
    private final PeriodRepository periods;
    private final RoomRepository rooms;
    private final CourseRepository courses;
    private final TeacherRepository teachers;
    private final AdminClassRepository adminClasses;
    private final ConstraintService constraints;
    private final SemesterBaselineQuery baselines;
    private final ObjectMapper objectMapper;

    public DraftValidator(
            TimetableDraftRepository drafts,
            DraftLessonRepository draftLessons,
            PeriodRepository periods,
            RoomRepository rooms,
            CourseRepository courses,
            TeacherRepository teachers,
            AdminClassRepository adminClasses,
            ConstraintService constraints,
            SemesterBaselineQuery baselines,
            ObjectMapper objectMapper) {
        this.drafts = drafts;
        this.draftLessons = draftLessons;
        this.periods = periods;
        this.rooms = rooms;
        this.courses = courses;
        this.teachers = teachers;
        this.adminClasses = adminClasses;
        this.constraints = constraints;
        this.baselines = baselines;
        this.objectMapper = objectMapper;
    }

    /**
     * Single-lesson special case of {@link #validateMoves}. Period and room are
     * assigned unconditionally, including nulls, so the existing endpoint keeps its
     * exact behaviour.
     */
    @Transactional(readOnly = true)
    public ValidationResult validateMove(Long draftId, Long lessonId, Long periodId, Long roomId) {
        Snapshot snapshot = snapshot(draftId);
        L moved = snapshot.lessons.stream()
                .filter(lesson -> lesson.id.equals(lessonId))
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("课节不存在: " + lessonId));
        moved.periodId = periodId;
        moved.roomId = roomId;
        return resultOf(checkAffected(snapshot, Set.of(moved.id)));
    }

    @Transactional(readOnly = true)
    public ValidationResult validateMoves(Long draftId, List<LessonOverride> overrides) {
        Snapshot snapshot = snapshot(draftId);
        Set<Long> affected = applyOverrides(snapshot, overrides == null ? List.of() : overrides);
        return resultOf(checkAffected(snapshot, affected));
    }

    /**
     * A change that assigns every mutable attribute unconditionally, nulls included.
     * Unlike {@link LessonOverride}, an explicit null clears the field, which is what
     * an exact swap (including a room being handed over as null) needs.
     */
    public record LessonChange(Long lessonId, Long periodId, Long roomId, Long teacherId) {}

    @Transactional(readOnly = true)
    public ValidationResult validateChanges(Long draftId, List<LessonChange> changes) {
        Snapshot snapshot = snapshot(draftId);
        return resultOf(checkAffected(snapshot, applyChanges(snapshot, changes)));
    }

    /**
     * A room that does not exist yet, used by a preview that must judge the change
     * before anything is written. Its id is a negative placeholder.
     */
    public record PlannedRoom(long placeholderId, String name, String type, int capacity) {}

    /**
     * Before and after the same whole-draft judgement. {@code added} holds only the
     * conflicts the change introduces: pre-existing ones stay visible but never block
     * a change on their own.
     */
    public record ChangeImpact(
            List<Conflict> before,
            List<Conflict> after,
            List<Conflict> added,
            List<DraftPublishReadiness.Blocker> remainingBlockers) {}

    /**
     * Impact of filling empty rooms only. Unlike {@link #validateChanges}, nothing but
     * the room is assigned, so a preview cannot accidentally clear a period or teacher.
     * Planned rooms are judged through their name, type and capacity before they exist.
     */
    @Transactional(readOnly = true)
    public ChangeImpact previewRoomAssignment(
            Long draftId, Map<Long, Long> roomIdByLesson, List<PlannedRoom> plannedRooms) {
        Snapshot snapshot = snapshot(draftId);
        List<Conflict> before = checkAffected(snapshot, null);
        Snapshot after = snapshot.withPlannedRooms(plannedRooms);
        for (L lesson : after.lessons) {
            Long roomId = roomIdByLesson.get(lesson.id);
            if (roomId != null) {
                lesson.roomId = roomId;
            }
        }
        List<Conflict> afterConflicts = checkAffected(after, null);
        return new ChangeImpact(
                before,
                afterConflicts,
                addedConflicts(before, afterConflicts),
                DraftPublishReadiness.blockers(factsOf(after, afterConflicts.size())));
    }

    /** Multiset difference by code, lesson and message: what the change newly breaks. */
    private List<Conflict> addedConflicts(List<Conflict> before, List<Conflict> after) {
        Map<String, Integer> remaining = new HashMap<>();
        before.forEach(conflict -> remaining.merge(keyOf(conflict), 1, Integer::sum));
        List<Conflict> added = new ArrayList<>();
        for (Conflict conflict : after) {
            String key = keyOf(conflict);
            Integer count = remaining.get(key);
            if (count == null || count == 0) {
                added.add(conflict);
            } else {
                remaining.put(key, count - 1);
            }
        }
        return added;
    }

    private static String keyOf(Conflict conflict) {
        return conflict.type() + "|" + conflict.lessonId() + "|" + conflict.message();
    }

    /**
     * Whole-draft check: the 24 conflict rules plus every publishing precondition, from
     * the one judgement publishing itself uses ({@link DraftPublishReadiness}). A draft
     * with lessons but no rooms is therefore not "valid" any more: it cannot be published.
     *
     * <p>{@code valid} and {@code publishable} both come from this one list, differing only
     * in that the two terminal-state blockers ({@code DRAFT_PUBLISHED},
     * {@code SEMESTER_ALREADY_PUBLISHED}) do not make the content invalid — see
     * {@link ValidationResult}.
     */
    @Transactional(readOnly = true)
    public ValidationResult validateDraft(Long draftId) {
        Snapshot snapshot = snapshot(draftId);
        List<Conflict> conflicts = checkAffected(snapshot, null);
        List<DraftPublishReadiness.Blocker> blockers =
                DraftPublishReadiness.blockers(factsOf(snapshot, conflicts.size()));
        return new ValidationResult(
                contentComplete(blockers), conflicts.size(), conflicts, blockers.isEmpty(), blockers);
    }

    /**
     * True when every blocker is about the draft's content; a blocker that only says "this
     * is already published" is a state of the world, not something the content lacks.
     */
    private static boolean contentComplete(List<DraftPublishReadiness.Blocker> blockers) {
        return blockers.stream().allMatch(blocker -> isTerminalState(blocker.code()));
    }

    /** The codes that describe a publish state rather than a gap in the draft. */
    private static boolean isTerminalState(String code) {
        return DraftPublishReadiness.DRAFT_PUBLISHED.equals(code)
                || DraftPublishReadiness.SEMESTER_ALREADY_PUBLISHED.equals(code);
    }

    /** Counts the structural and resource gaps the publishing preconditions care about. */
    private DraftPublishReadiness.Facts factsOf(Snapshot snapshot, int hardConflicts) {
        int unscheduled = 0;
        int missingRooms = 0;
        int invalidPeriods = 0;
        for (L lesson : snapshot.lessons) {
            if (lesson.periodId == null) {
                unscheduled++;
            } else if (!snapshot.context.periods().containsKey(lesson.periodId)) {
                invalidPeriods++;
            }
            if (lesson.roomId == null) {
                missingRooms++;
            }
        }
        return new DraftPublishReadiness.Facts(
                "PUBLISHED".equals(snapshot.draft.getStatus()),
                snapshot.lessons.size(),
                unscheduled,
                missingRooms,
                invalidPeriods,
                hardConflicts,
                baselines.hasPublishedBaseline(snapshot.draft.getSemesterId()));
    }

    /**
     * Evaluates independent override scenarios against one snapshot. Each scenario
     * starts from a fresh copy of the lessons, so it never sees another scenario's
     * changes. The snapshot itself (periods, rooms, courses, teachers, classes and
     * constraint settings) is built once; this is what the drag preview and the swap
     * candidate list need, where one request asks "what if" for many targets.
     */
    @Transactional(readOnly = true)
    public List<ValidationResult> validateScenarios(Long draftId, List<List<LessonOverride>> scenarios) {
        Snapshot base = snapshot(draftId);
        List<ValidationResult> results = new ArrayList<>(scenarios.size());
        for (List<LessonOverride> scenario : scenarios) {
            Snapshot copy = base.withFreshLessons();
            results.add(resultOf(checkAffected(copy, applyOverrides(copy, scenario))));
        }
        return results;
    }

    /**
     * Same as {@link #validateScenarios}, but each scenario is an exact change list
     * (an explicit null clears a field). Swap candidates need this because exchanging
     * rooms may hand a lesson a null room, which {@link LessonOverride} cannot express.
     */
    @Transactional(readOnly = true)
    public List<ValidationResult> validateChangeScenarios(Long draftId, List<List<LessonChange>> scenarios) {
        Snapshot base = snapshot(draftId);
        List<ValidationResult> results = new ArrayList<>(scenarios.size());
        for (List<LessonChange> scenario : scenarios) {
            Snapshot copy = base.withFreshLessons();
            results.add(resultOf(checkAffected(copy, applyChanges(copy, scenario))));
        }
        return results;
    }

    /** Applies exact changes to the in-memory snapshot and returns the affected lesson ids. */
    private Set<Long> applyChanges(Snapshot snapshot, List<LessonChange> changes) {
        Map<Long, L> byId = new HashMap<>();
        snapshot.lessons.forEach(lesson -> byId.put(lesson.id, lesson));
        Set<Long> affected = new LinkedHashSet<>();
        for (LessonChange change : changes == null ? List.<LessonChange>of() : changes) {
            L lesson = byId.get(change.lessonId());
            if (lesson == null) {
                throw ApiException.notFound("课节不存在: " + change.lessonId());
            }
            lesson.periodId = change.periodId();
            lesson.roomId = change.roomId();
            lesson.teacherId = change.teacherId();
            affected.add(lesson.id);
        }
        return affected;
    }

    /** Applies every override to the in-memory snapshot and returns the affected lesson ids. */
    private Set<Long> applyOverrides(Snapshot snapshot, List<LessonOverride> overrides) {
        Map<Long, L> byId = new HashMap<>();
        snapshot.lessons.forEach(lesson -> byId.put(lesson.id, lesson));
        Set<Long> affected = new LinkedHashSet<>();
        for (LessonOverride override : overrides) {
            L lesson = byId.get(override.lessonId());
            if (lesson == null) {
                throw ApiException.notFound("课节不存在: " + override.lessonId());
            }
            if (override.periodId() != null) {
                lesson.periodId = override.periodId();
            }
            if (override.roomId() != null) {
                lesson.roomId = override.roomId();
            }
            if (override.teacherId() != null) {
                lesson.teacherId = override.teacherId();
            }
            affected.add(lesson.id);
        }
        return affected;
    }

    private List<Conflict> checkAffected(Snapshot s, Set<Long> affectedLessonIds) {
        List<LessonView> views = s.lessons.stream().map(L::toView).toList();
        List<ConstraintViolation> violations =
                ConstraintChecker.check(s.context(), views, affectedLessonIds);
        return violations.stream()
                .map(violation -> new Conflict(violation.code(), violation.message(), violation.lessonId()))
                .toList();
    }

    /** Local checks report conflicts only; they never gate on the rest of the draft. */
    private ValidationResult resultOf(List<Conflict> conflicts) {
        boolean valid = conflicts.isEmpty();
        return new ValidationResult(valid, conflicts.size(), conflicts, valid, List.of());
    }

    private Snapshot snapshot(Long draftId) {
        TimetableDraft draft = drafts.findById(draftId)
                .orElseThrow(() -> ApiException.notFound("课表草稿不存在: " + draftId));
        List<DraftLesson> lessons = draftLessons.findByDraftIdOrderByIdAsc(draftId);
        Map<Long, ConstraintContext.PeriodView> periodMap = new HashMap<>();
        periods.findBySemesterIdOrderByDayOfWeekAscPeriodNoAsc(draft.getSemesterId())
                .forEach(period -> periodMap.put(period.getId(),
                        new ConstraintContext.PeriodView(
                                period.getId(), period.getDayOfWeek(), period.getPeriodNo(),
                                period.getSection())));
        Map<Long, ConstraintContext.RoomView> roomMap = new HashMap<>();
        rooms.findAll().forEach(room -> roomMap.put(room.getId(),
                new ConstraintContext.RoomView(
                        room.getId(), room.getName(), room.getType(), room.getCapacity())));
        Map<Long, ConstraintContext.CourseView> courseMap = new HashMap<>();
        courses.findAll().forEach(course -> courseMap.put(course.getId(),
                new ConstraintContext.CourseView(
                        course.getId(), course.getName(), course.getRequiredRoomType())));
        Map<Long, ConstraintContext.TeacherView> teacherMap = new HashMap<>();
        teachers.findAll().forEach(teacher -> teacherMap.put(teacher.getId(),
                new ConstraintContext.TeacherView(
                        teacher.getId(), teacher.getName(), teacher.getSubject())));
        Map<Long, ConstraintContext.ClassView> classMap = new HashMap<>();
        adminClasses.findBySemesterIdOrderByGradeIdAscNameAsc(draft.getSemesterId())
                .forEach(adminClass -> classMap.put(adminClass.getId(),
                        new ConstraintContext.ClassView(
                                adminClass.getId(), adminClass.getName(),
                                adminClass.getStudentCount(),
                                adminClass.getHeadTeacherId())));

        List<ConstraintConfig> active = constraints.active(draft.getSemesterId());
        ConstraintSettings settings = constraints.hasConfigs(draft.getSemesterId())
                ? ConstraintSettings.from(active, objectMapper)
                : ConstraintSettings.defaults();
        ConstraintContext context =
                new ConstraintContext(periodMap, roomMap, courseMap, teacherMap, classMap, settings);
        List<L> copies = lessons.stream().map(L::of).toList();
        return new Snapshot(draft, copies, context);
    }

    private record Snapshot(TimetableDraft draft, List<L> lessons, ConstraintContext context) {

        /** Same reference data, independent lesson copies, for scenario evaluation. */
        Snapshot withFreshLessons() {
            return new Snapshot(draft, lessons.stream().map(L::copy).toList(), context);
        }

        /**
         * Independent lesson copies plus the rooms a preview is about to create, so the
         * rules can judge assignments to rooms that do not exist yet.
         */
        Snapshot withPlannedRooms(List<PlannedRoom> plannedRooms) {
            if (plannedRooms == null || plannedRooms.isEmpty()) {
                return withFreshLessons();
            }
            Map<Long, ConstraintContext.RoomView> roomMap = new HashMap<>(context.rooms());
            for (PlannedRoom room : plannedRooms) {
                roomMap.put(room.placeholderId(), new ConstraintContext.RoomView(
                        room.placeholderId(), room.name(), room.type(), room.capacity()));
            }
            ConstraintContext widened = new ConstraintContext(
                    context.periods(), Map.copyOf(roomMap), context.courses(),
                    context.teachers(), context.classes(), context.settings());
            return new Snapshot(draft, lessons.stream().map(L::copy).toList(), widened);
        }
    }

    private static final class L {
        private Long id;
        private Long assignmentId;
        private Long courseId;
        private Long teacherId;
        private Long classId;
        private Long periodId;
        private Long roomId;
        private int studentCount;

        static L of(DraftLesson lesson) {
            L copy = new L();
            copy.id = lesson.getId();
            copy.assignmentId = lesson.getAssignmentId();
            copy.courseId = lesson.getCourseId();
            copy.teacherId = lesson.getTeacherId();
            copy.classId = lesson.getAdminClassId();
            copy.periodId = lesson.getPeriodId();
            copy.roomId = lesson.getRoomId();
            copy.studentCount = lesson.getStudentCount();
            return copy;
        }

        LessonView toView() {
            return new LessonView(id, classId, courseId, teacherId, roomId, periodId, studentCount);
        }

        L copy() {
            L copy = new L();
            copy.id = id;
            copy.assignmentId = assignmentId;
            copy.courseId = courseId;
            copy.teacherId = teacherId;
            copy.classId = classId;
            copy.periodId = periodId;
            copy.roomId = roomId;
            copy.studentCount = studentCount;
            return copy;
        }
    }
}
