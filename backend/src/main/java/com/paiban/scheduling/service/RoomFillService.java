package com.paiban.scheduling.service;

import com.paiban.foundation.domain.AdminClass;
import com.paiban.foundation.domain.AdminClassRepository;
import com.paiban.foundation.domain.Course;
import com.paiban.foundation.domain.CourseRepository;
import com.paiban.foundation.domain.GradeRepository;
import com.paiban.foundation.domain.RoomRepository;
import com.paiban.foundation.domain.Semester;
import com.paiban.foundation.service.FoundationService;
import com.paiban.foundation.service.SemesterService;
import com.paiban.foundation.web.FoundationDtos.RoomDto;
import com.paiban.foundation.web.FoundationDtos.SaveRoomRequest;
import com.paiban.scheduling.domain.DraftLesson;
import com.paiban.scheduling.domain.DraftLessonRepository;
import com.paiban.scheduling.domain.TimetableDraft;
import com.paiban.scheduling.domain.TimetableDraftRepository;
import com.paiban.scheduling.web.RoomFillDtos.ClassRoomPlanDto;
import com.paiban.scheduling.web.RoomFillDtos.ManualItemDto;
import com.paiban.scheduling.web.RoomFillDtos.RoomFillPlanDto;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * The explicit action behind the {@code MISSING_ROOMS} blocker (ticket 07): give every
 * admin class with lessons that have no room one fixed ordinary room, after the user
 * confirmed a preview. Importing still never creates rooms (ADR-0026/0027); this is a
 * deliberate, visible action instead of the old per-lesson workaround.
 *
 * <p>Only empty {@code room_id} values are filled. The whole action is one transaction
 * and all-or-nothing: nothing is created or assigned when any class cannot be handled
 * safely, and new hard conflicts are refused rather than forced.
 *
 * <p>The two situations that stop the action before it plans anything (the draft is already
 * published, the semester already has a baseline) are read from
 * {@link DraftPublishReadiness} through {@link DraftValidator#validateDraft}, exactly like
 * publishing: this action never gets its own opinion about whether a draft may change.
 */
@Service
public class RoomFillService {

    /** A new conflict this action would introduce. */
    public static final String NEW_CONFLICT = "NEW_CONFLICT";
    /** Headline of a refusal whose items need a person; the items carry the detail. */
    private static final String REFUSED = "补教室未执行";

    /** Placeholder ids stand in for rooms a preview has not created yet. */
    private static final long PLACEHOLDER_BASE = -1_000_000L;

    private final TimetableDraftRepository drafts;
    private final DraftLessonRepository draftLessons;
    private final AdminClassRepository adminClasses;
    private final GradeRepository grades;
    private final CourseRepository courses;
    private final RoomRepository rooms;
    private final SemesterService semesters;
    private final SemesterBaselineQuery baselines;
    private final FoundationService foundation;
    private final DraftValidator validator;
    private final SchedulingService scheduling;

    public RoomFillService(
            TimetableDraftRepository drafts,
            DraftLessonRepository draftLessons,
            AdminClassRepository adminClasses,
            GradeRepository grades,
            CourseRepository courses,
            RoomRepository rooms,
            SemesterService semesters,
            SemesterBaselineQuery baselines,
            FoundationService foundation,
            DraftValidator validator,
            SchedulingService scheduling) {
        this.drafts = drafts;
        this.draftLessons = draftLessons;
        this.adminClasses = adminClasses;
        this.grades = grades;
        this.courses = courses;
        this.rooms = rooms;
        this.semesters = semesters;
        this.baselines = baselines;
        this.foundation = foundation;
        this.validator = validator;
        this.scheduling = scheduling;
    }

    /** The outcome of an apply request: {@code OK} when written, {@code CONFLICT} when refused. */
    public record Outcome(HttpStatus status, RoomFillPlanDto plan) {}

    /** Read-only: the same plan the apply step recomputes, plus what would still block publishing. */
    @Transactional(readOnly = true)
    public RoomFillPlanDto preview(Long draftId) {
        Preparation prep = prepare(draftId);
        if (prep.guard() != null) {
            return refusal(prep, List.of(prep.guard()));
        }
        Planning planning = prep.planning();
        if (!planning.plan().safe()) {
            return refusal(prep, manualItems(planning.plan()));
        }
        List<ManualItemDto> conflicts = conflictItems(prep.impact().added(), planning);
        return new RoomFillPlanDto(
                false,
                conflicts.isEmpty(),
                count(planning, RoomFillPlanner.ACTION_CREATE),
                count(planning, RoomFillPlanner.ACTION_REUSE),
                totalGaps(planning),
                classDtos(planning, Map.of()),
                conflicts,
                prep.impact().remainingBlockers(),
                null);
    }

    /**
     * Recomputes the plan inside one transaction and either writes all of it or nothing.
     * Rooms come from {@link FoundationService#createRoom}; lesson rooms from the draft
     * write path in {@link SchedulingService}, never from a forced patch or raw SQL.
     */
    @Transactional
    public Outcome apply(Long draftId) {
        Preparation prep = prepare(draftId);
        if (prep.guard() != null) {
            return new Outcome(HttpStatus.CONFLICT, refusal(prep, List.of(prep.guard())));
        }
        Planning planning = prep.planning();
        if (!planning.plan().safe()) {
            return new Outcome(HttpStatus.CONFLICT, refusal(prep, manualItems(planning.plan())));
        }
        if (!prep.impact().added().isEmpty()) {
            return new Outcome(
                    HttpStatus.CONFLICT, refusal(prep, conflictItems(prep.impact().added(), planning)));
        }

        Map<Long, Long> roomIdByClass = new HashMap<>();
        int created = 0;
        for (RoomFillPlanner.ClassPlan classPlan : planning.plan().classes()) {
            if (RoomFillPlanner.ACTION_REUSE.equals(classPlan.action())) {
                roomIdByClass.put(classPlan.classId(), classPlan.roomId());
                continue;
            }
            RoomDto room = foundation.createRoom(new SaveRoomRequest(
                    classPlan.roomName(),
                    classPlan.requiredCapacity(),
                    RoomFillPlanner.ROOM_TYPE_NORMAL,
                    null));
            roomIdByClass.put(classPlan.classId(), room.id());
            created++;
        }
        Map<Long, Long> roomIdByLesson = new HashMap<>();
        planning.lessonIdsByClass().forEach((classId, lessons) -> {
            Long roomId = roomIdByClass.get(classId);
            if (roomId != null) {
                lessons.forEach(lessonId -> roomIdByLesson.put(lessonId, roomId));
            }
        });
        int assigned = scheduling.fillMissingRooms(draftId, roomIdByLesson);

        DraftValidator.ValidationResult after = validator.validateDraft(draftId);
        return new Outcome(HttpStatus.OK, new RoomFillPlanDto(
                true,
                true,
                created,
                planning.plan().classes().size() - created,
                assigned,
                classDtos(planning, roomIdByClass),
                List.of(),
                after.blockers(),
                null));
    }

    // ---------- the shared prelude: guard, plan, safety, conflicts ----------

    /**
     * What both entry points need, in the one order that matters: the publishing judgement
     * first (a published draft or semester never gets planned), then the plan, then its
     * safety, then the conflicts it would introduce. {@code guard}, {@code planning} and
     * {@code impact} are null at the stage that stopped it.
     */
    private record Preparation(
            List<DraftPublishReadiness.Blocker> blockers,
            ManualItemDto guard,
            Planning planning,
            DraftValidator.ChangeImpact impact) {}

    private Preparation prepare(Long draftId) {
        TimetableDraft draft = requireDraft(draftId);
        List<DraftPublishReadiness.Blocker> blockers = validator.validateDraft(draftId).blockers();
        ManualItemDto guard = guardOf(blockers);
        if (guard != null) {
            return new Preparation(blockers, guard, null, null);
        }
        Planning planning = planning(draft);
        if (!planning.plan().safe()) {
            return new Preparation(blockers, null, planning, null);
        }
        return new Preparation(blockers, null, planning, validator.previewRoomAssignment(
                draftId, plannedRoomByLesson(planning), plannedRooms(planning)));
    }

    /**
     * The two terminal states stop the action before any plan is made. Codes and messages
     * are the publishing judgement's own (ticket 13), so the page cannot be told two
     * different reasons by "fill rooms" and "publish" for the same draft.
     */
    private static ManualItemDto guardOf(List<DraftPublishReadiness.Blocker> blockers) {
        for (DraftPublishReadiness.Blocker blocker : blockers) {
            if (DraftPublishReadiness.DRAFT_PUBLISHED.equals(blocker.code())
                    || DraftPublishReadiness.SEMESTER_ALREADY_PUBLISHED.equals(blocker.code())) {
                return new ManualItemDto(null, null, blocker.code(), blocker.message());
            }
        }
        return null;
    }

    // ---------- planning ----------

    /** The plan plus, per class, the lessons whose empty room this action would fill. */
    private record Planning(RoomFillPlanner.Plan plan, Map<Long, List<Long>> lessonIdsByClass) {}

    private Planning planning(TimetableDraft draft) {
        Semester semester = semesters.require(draft.getSemesterId());
        List<DraftLesson> lessons = draftLessons.findByDraftIdOrderByIdAsc(draft.getId());

        Map<Long, String> gradeNameById = new HashMap<>();
        grades.findAll().forEach(grade -> gradeNameById.put(grade.getId(), grade.getName()));
        Map<Long, AdminClass> classById = new HashMap<>();
        adminClasses.findBySemesterIdOrderByGradeIdAscNameAsc(draft.getSemesterId())
                .forEach(adminClass -> classById.put(adminClass.getId(), adminClass));
        Map<Long, Course> courseById = new HashMap<>();
        courses.findAll().forEach(course -> courseById.put(course.getId(), course));

        List<RoomFillPlanner.ClassInfo> classes = classById.values().stream()
                .map(adminClass -> new RoomFillPlanner.ClassInfo(
                        adminClass.getId(),
                        adminClass.getName(),
                        gradeNameById.get(adminClass.getGradeId()),
                        adminClass.getStudentCount()))
                .toList();
        List<RoomFillPlanner.LessonGap> gaps = new ArrayList<>();
        Map<Long, List<Long>> lessonIdsByClass = new LinkedHashMap<>();
        for (DraftLesson lesson : lessons) {
            if (lesson.getRoomId() != null) {
                continue;
            }
            Course course = courseById.get(lesson.getCourseId());
            gaps.add(new RoomFillPlanner.LessonGap(
                    lesson.getId(),
                    lesson.getAdminClassId(),
                    lesson.getStudentCount(),
                    course == null ? null : course.getName(),
                    course != null && needsSpecialRoom(course),
                    course == null));
            lessonIdsByClass.computeIfAbsent(lesson.getAdminClassId(), key -> new ArrayList<>())
                    .add(lesson.getId());
        }
        List<RoomFillPlanner.ExistingRoom> existing = rooms.findAll().stream()
                .map(room -> new RoomFillPlanner.ExistingRoom(
                        room.getId(), room.getName(), room.getType(), room.getCapacity()))
                .toList();
        return new Planning(
                RoomFillPlanner.plan(
                        semester.getName(), classes, gaps, existing, roomOccupants(draft.getSemesterId())),
                lessonIdsByClass);
    }

    /**
     * Which admin class this semester already puts in each room. A same-named room may only
     * be reused by the class that owns it, so the planner needs the whole picture: every
     * draft of the semester (this one included) plus the published baseline. Rooms here are
     * global, so without this check a room could be handed to a class another one already
     * uses, or to the class of another grade with the same name.
     */
    private Map<Long, Set<Long>> roomOccupants(long semesterId) {
        Map<Long, Set<Long>> occupants = new HashMap<>();
        List<Long> draftIds = drafts.findBySemesterIdOrderByCreatedAtDesc(semesterId).stream()
                .map(TimetableDraft::getId)
                .toList();
        if (!draftIds.isEmpty()) {
            for (DraftLesson lesson : draftLessons.findByDraftIdIn(draftIds)) {
                if (lesson.getRoomId() != null && lesson.getAdminClassId() != null) {
                    occupants.computeIfAbsent(lesson.getRoomId(), key -> new HashSet<>())
                            .add(lesson.getAdminClassId());
                }
            }
        }
        // The guard above already refuses a semester with a baseline; this keeps the answer
        // complete anyway, so relaxing that guard cannot silently hand out a used room.
        baselines.publishedRoomOccupants(semesterId).forEach((roomId, classIds) ->
                occupants.computeIfAbsent(roomId, key -> new HashSet<>()).addAll(classIds));
        return occupants;
    }

    private static boolean needsSpecialRoom(Course course) {
        if (course.isRequireOutdoor()) {
            return true;
        }
        String required = course.getRequiredRoomType();
        return StringUtils.hasText(required) && !RoomFillPlanner.ROOM_TYPE_NORMAL.equals(required);
    }

    /** Every planned assignment, with placeholder ids for rooms that do not exist yet. */
    private Map<Long, Long> plannedRoomByLesson(Planning planning) {
        Map<Long, Long> roomIdByLesson = new HashMap<>();
        long placeholder = PLACEHOLDER_BASE;
        for (RoomFillPlanner.ClassPlan classPlan : planning.plan().classes()) {
            Long roomId = classPlan.roomId() == null ? placeholder-- : classPlan.roomId();
            planning.lessonIdsByClass().getOrDefault(classPlan.classId(), List.of())
                    .forEach(lessonId -> roomIdByLesson.put(lessonId, roomId));
        }
        return roomIdByLesson;
    }

    private List<DraftValidator.PlannedRoom> plannedRooms(Planning planning) {
        long placeholder = PLACEHOLDER_BASE;
        List<DraftValidator.PlannedRoom> rooms = new ArrayList<>();
        for (RoomFillPlanner.ClassPlan classPlan : planning.plan().classes()) {
            if (classPlan.roomId() == null) {
                rooms.add(new DraftValidator.PlannedRoom(
                        placeholder++, classPlan.roomName(),
                        RoomFillPlanner.ROOM_TYPE_NORMAL, classPlan.requiredCapacity()));
            }
        }
        return rooms;
    }

    // ---------- response mapping ----------

    /** The 409 shape: nothing was written, so every count stays 0. */
    private RoomFillPlanDto refusal(Preparation prep, List<ManualItemDto> items) {
        return new RoomFillPlanDto(
                false,
                false,
                0,
                0,
                0,
                prep.planning() == null ? List.of() : classDtos(prep.planning(), Map.of()),
                items,
                prep.blockers(),
                refusalMessage(items));
    }

    /**
     * The one-line reason the page shows when it refuses. A single item speaks for itself;
     * with several, the list in {@code manualItems} carries the detail.
     */
    private static String refusalMessage(List<ManualItemDto> items) {
        if (items.isEmpty()) {
            return REFUSED;
        }
        if (items.size() == 1) {
            return items.get(0).message();
        }
        return REFUSED + "，有 " + items.size() + " 项需要人工处理";
    }

    private List<ClassRoomPlanDto> classDtos(Planning planning, Map<Long, Long> roomIdByClass) {
        return planning.plan().classes().stream()
                .map(classPlan -> new ClassRoomPlanDto(
                        classPlan.classId(),
                        classPlan.className(),
                        classPlan.studentCount(),
                        classPlan.requiredCapacity(),
                        classPlan.lessonCount(),
                        classPlan.roomName(),
                        roomIdByClass.getOrDefault(classPlan.classId(), classPlan.roomId()),
                        classPlan.roomCapacity(),
                        classPlan.action()))
                .toList();
    }

    private List<ManualItemDto> manualItems(RoomFillPlanner.Plan plan) {
        return plan.manualItems().stream()
                .map(item -> new ManualItemDto(
                        item.classId(), item.className(), item.code(), item.message()))
                .toList();
    }

    /** Newly introduced conflicts, attributed to the class whose lesson they hit. */
    private List<ManualItemDto> conflictItems(
            List<DraftValidator.Conflict> conflicts, Planning planning) {
        Map<Long, Long> classByLesson = new HashMap<>();
        planning.lessonIdsByClass().forEach((classId, lessonIds) ->
                lessonIds.forEach(lessonId -> classByLesson.put(lessonId, classId)));
        Map<Long, String> classNameById = new HashMap<>();
        planning.plan().classes()
                .forEach(classPlan -> classNameById.put(classPlan.classId(), classPlan.className()));
        List<ManualItemDto> items = new ArrayList<>();
        for (DraftValidator.Conflict conflict : conflicts) {
            Long classId = conflict.lessonId() == null ? null : classByLesson.get(conflict.lessonId());
            items.add(new ManualItemDto(
                    classId,
                    classId == null ? null : classNameById.get(classId),
                    NEW_CONFLICT,
                    "补固定教室会新增冲突，已整次拒绝：" + conflict.message()));
        }
        return items;
    }

    private int count(Planning planning, String action) {
        return (int) planning.plan().classes().stream()
                .filter(classPlan -> action.equals(classPlan.action()))
                .count();
    }

    private int totalGaps(Planning planning) {
        return planning.lessonIdsByClass().values().stream().mapToInt(List::size).sum();
    }

    private TimetableDraft requireDraft(Long draftId) {
        return drafts.findById(draftId)
                .orElseThrow(() -> com.paiban.common.web.ApiException.notFound("课表草稿不存在: " + draftId));
    }
}
