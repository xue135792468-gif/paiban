package com.paiban.scheduling.service;

import com.paiban.foundation.domain.AdminClass;
import com.paiban.foundation.domain.AdminClassRepository;
import com.paiban.foundation.domain.Course;
import com.paiban.foundation.domain.CourseRepository;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The explicit action behind the {@code MISSING_ROOMS} blocker (ticket 07): give every
 * admin class with lessons that have no room one fixed ordinary room, after the user
 * confirmed a preview. Importing still never creates rooms (ADR-0026/0027); this is a
 * deliberate, visible action instead of the old per-lesson workaround.
 *
 * <p>Only empty {@code room_id} values are filled. The whole action is one transaction
 * and all-or-nothing: nothing is created or assigned when any class cannot be handled
 * safely, and new hard conflicts are refused rather than forced.
 */
@Service
public class RoomFillService {

    /** A class this action cannot handle on its own. */
    public static final String DRAFT_PUBLISHED = "DRAFT_PUBLISHED";
    public static final String SEMESTER_ALREADY_PUBLISHED = "SEMESTER_ALREADY_PUBLISHED";
    public static final String NEW_CONFLICT = "NEW_CONFLICT";

    /** Placeholder ids stand in for rooms a preview has not created yet. */
    private static final long PLACEHOLDER_BASE = -1_000_000L;

    private final TimetableDraftRepository drafts;
    private final DraftLessonRepository draftLessons;
    private final AdminClassRepository adminClasses;
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
        TimetableDraft draft = requireDraft(draftId);
        ManualItemDto guard = guardOf(draft);
        if (guard != null) {
            return planResponse(draftId, null, false, false, 0, 0, 0, Map.of(), List.of(guard));
        }
        Planning planning = planning(draft);
        if (!planning.plan().safe()) {
            return planResponse(
                    draftId, planning, false, false, 0, 0, 0, Map.of(), manualItems(planning.plan()));
        }
        DraftValidator.ChangeImpact impact =
                validator.previewRoomAssignment(draftId, plannedRoomByLesson(planning), plannedRooms(planning));
        List<ManualItemDto> conflicts = conflictItems(impact.added(), planning);
        return new RoomFillPlanDto(
                false,
                conflicts.isEmpty(),
                count(planning, RoomFillPlanner.ACTION_CREATE),
                count(planning, RoomFillPlanner.ACTION_REUSE),
                totalGaps(planning),
                classDtos(planning, Map.of()),
                conflicts,
                impact.remainingBlockers());
    }

    /**
     * Recomputes the plan inside one transaction and either writes all of it or nothing.
     * Rooms come from {@link FoundationService#createRoom}; lesson rooms from the draft
     * write path in {@link SchedulingService}, never from a forced patch or raw SQL.
     */
    @Transactional
    public Outcome apply(Long draftId) {
        TimetableDraft draft = requireDraft(draftId);
        ManualItemDto guard = guardOf(draft);
        if (guard != null) {
            return new Outcome(HttpStatus.CONFLICT, planResponse(
                    draftId, null, false, false, 0, 0, 0, Map.of(), List.of(guard)));
        }
        Planning planning = planning(draft);
        if (!planning.plan().safe()) {
            return new Outcome(HttpStatus.CONFLICT, planResponse(
                    draftId, planning, false, false, 0, 0, 0, Map.of(), manualItems(planning.plan())));
        }
        DraftValidator.ChangeImpact impact =
                validator.previewRoomAssignment(draftId, plannedRoomByLesson(planning), plannedRooms(planning));
        if (!impact.added().isEmpty()) {
            return new Outcome(HttpStatus.CONFLICT, new RoomFillPlanDto(
                    false,
                    false,
                    0,
                    0,
                    0,
                    classDtos(planning, Map.of()),
                    conflictItems(impact.added(), planning),
                    impact.remainingBlockers()));
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
                after.blockers()));
    }

    /** The two guards that stop the action before any plan is even made. */
    private ManualItemDto guardOf(TimetableDraft draft) {
        if ("PUBLISHED".equals(draft.getStatus())) {
            return new ManualItemDto(
                    null, null, DRAFT_PUBLISHED, "该草稿已发布，不能再补教室");
        }
        if (baselines.hasPublishedBaseline(draft.getSemesterId())) {
            return new ManualItemDto(
                    null, null, SEMESTER_ALREADY_PUBLISHED,
                    "该学期课表已发布，基线只读；后续调整请走调代流程");
        }
        return null;
    }

    // ---------- planning ----------

    /** The plan plus, per class, the lessons whose empty room this action would fill. */
    private record Planning(RoomFillPlanner.Plan plan, Map<Long, List<Long>> lessonIdsByClass) {}

    private Planning planning(TimetableDraft draft) {
        Semester semester = semesters.require(draft.getSemesterId());
        List<DraftLesson> lessons = draftLessons.findByDraftIdOrderByIdAsc(draft.getId());

        Map<Long, AdminClass> classById = new HashMap<>();
        adminClasses.findBySemesterIdOrderByGradeIdAscNameAsc(draft.getSemesterId())
                .forEach(adminClass -> classById.put(adminClass.getId(), adminClass));
        Map<Long, Course> courseById = new HashMap<>();
        courses.findAll().forEach(course -> courseById.put(course.getId(), course));

        List<RoomFillPlanner.ClassInfo> classes = classById.values().stream()
                .map(adminClass -> new RoomFillPlanner.ClassInfo(
                        adminClass.getId(), adminClass.getName(), adminClass.getStudentCount()))
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
                RoomFillPlanner.plan(semester.getName(), classes, gaps, existing),
                lessonIdsByClass);
    }

    private static boolean needsSpecialRoom(Course course) {
        if (course.isRequireOutdoor()) {
            return true;
        }
        String required = course.getRequiredRoomType();
        return required != null && !required.isBlank()
                && !RoomFillPlanner.ROOM_TYPE_NORMAL.equals(required);
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

    private RoomFillPlanDto planResponse(
            Long draftId,
            Planning planning,
            boolean applied,
            boolean applicable,
            int createdRooms,
            int reusedRooms,
            int assignedLessons,
            Map<Long, Long> roomIdByClass,
            List<ManualItemDto> manualItems) {
        List<DraftPublishReadiness.Blocker> blockers = currentBlockers(draftId);
        List<ClassRoomPlanDto> classes =
                planning == null ? List.of() : classDtos(planning, roomIdByClass);
        return new RoomFillPlanDto(
                applied, applicable, createdRooms, reusedRooms, assignedLessons,
                classes, manualItems, blockers);
    }

    private List<DraftPublishReadiness.Blocker> currentBlockers(Long draftId) {
        return validator.validateDraft(draftId).blockers();
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
