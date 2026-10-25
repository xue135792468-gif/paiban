package com.paiban.scheduling.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure decision function behind the explicit "fill fixed rooms" action (ticket 07).
 *
 * <p>It answers, for every class that still has lessons without a room: which room do
 * we use, with which capacity, and is the association safe at all. It touches no
 * repository, so the preview and the apply step share one answer and a reviewer can
 * read the whole rule set in one screen.
 *
 * <p>Rooms in this system are global (no semester column), so the name is the only
 * stable association between a class and "its" room: {@code 学期名-班名-固定教室}.
 * A room with that exact name is reused only when it is an ordinary room with enough
 * seats; anything else is a person's problem, never silently adopted or enlarged.
 */
public final class RoomFillPlanner {

    public static final String ROOM_TYPE_NORMAL = "NORMAL";
    public static final String ACTION_CREATE = "CREATE";
    public static final String ACTION_REUSE = "REUSE";

    /** {@code room.name} column length. */
    public static final int MAX_ROOM_NAME_LENGTH = 100;

    /** The lesson's class is not part of this semester any more. */
    public static final String CLASS_NOT_FOUND = "CLASS_NOT_FOUND";
    /** The class has no usable student count, so no capacity can be derived. */
    public static final String CLASS_SIZE_UNKNOWN = "CLASS_SIZE_UNKNOWN";
    /** The lesson points at a course that no longer exists. */
    public static final String COURSE_NOT_FOUND = "COURSE_NOT_FOUND";
    /** The course needs a lab, the playground or another special room. */
    public static final String SPECIAL_ROOM_REQUIRED = "SPECIAL_ROOM_REQUIRED";
    /** The derived room name is longer than the column allows. */
    public static final String ROOM_NAME_TOO_LONG = "ROOM_NAME_TOO_LONG";
    /** A room with the derived name exists but is not a suitable ordinary room. */
    public static final String ROOM_NAME_CONFLICT = "ROOM_NAME_CONFLICT";

    public record ClassInfo(long id, String name, int studentCount) {}

    /** One lesson whose room is empty. {@code studentCount} is the lesson's own snapshot. */
    public record LessonGap(
            long lessonId, long classId, int studentCount, String courseName,
            boolean specialRoomRequired, boolean courseMissing) {}

    public record ExistingRoom(long id, String name, String type, int capacity) {}

    /**
     * The room one class gets. {@code roomId} is null for a room that still has to be
     * created; {@code roomCapacity} is the capacity it will have (or already has).
     * {@code studentCount} is the class size the capacity is derived from.
     */
    public record ClassPlan(
            long classId, String className, int studentCount, int requiredCapacity,
            int lessonCount, String roomName, Long roomId, int roomCapacity, String action) {}

    public record ManualItem(Long classId, String className, String code, String message) {}

    /** {@code safe()} is the single gate both the preview and the apply step read. */
    public record Plan(List<ClassPlan> classes, List<ManualItem> manualItems) {

        public boolean safe() {
            return manualItems.isEmpty();
        }
    }

    private RoomFillPlanner() {}

    /** The deterministic name that ties a class to its fixed ordinary room. */
    public static String roomName(String semesterName, String className) {
        return semesterName + "-" + className + "-固定教室";
    }

    /**
     * Plans one room per class with at least one empty room. Classes are ordered by
     * name so the preview and the apply step list them identically.
     */
    public static Plan plan(
            String semesterName,
            List<ClassInfo> classes,
            List<LessonGap> gaps,
            List<ExistingRoom> rooms) {
        Map<Long, ClassInfo> classById = new HashMap<>();
        classes.forEach(info -> classById.put(info.id(), info));
        Map<String, ExistingRoom> roomByName = new LinkedHashMap<>();
        rooms.forEach(room -> roomByName.putIfAbsent(room.name(), room));

        Map<Long, List<LessonGap>> gapsByClass = new LinkedHashMap<>();
        for (LessonGap gap : gaps) {
            gapsByClass.computeIfAbsent(gap.classId(), key -> new ArrayList<>()).add(gap);
        }

        List<ClassPlan> plans = new ArrayList<>();
        List<ManualItem> manualItems = new ArrayList<>();
        for (Long classId : orderedClassIds(gapsByClass, classById)) {
            List<LessonGap> classGaps = gapsByClass.get(classId);
            ClassInfo info = classById.get(classId);
            if (info == null) {
                manualItems.add(new ManualItem(
                        classId, null, CLASS_NOT_FOUND,
                        "有课节引用的行政班不在本学期，请先修正课节"));
                continue;
            }
            LessonGap missingCourse = classGaps.stream()
                    .filter(LessonGap::courseMissing).findFirst().orElse(null);
            if (missingCourse != null) {
                manualItems.add(new ManualItem(
                        classId, info.name(), COURSE_NOT_FOUND,
                        "有课节引用的课程不存在，请先修正课节"));
                continue;
            }
            if (info.studentCount() <= 0) {
                manualItems.add(new ManualItem(
                        classId, info.name(), CLASS_SIZE_UNKNOWN,
                        "班级人数未设置，无法推算固定教室容量；请先在班级信息中填写人数"));
                continue;
            }
            LessonGap special = classGaps.stream()
                    .filter(LessonGap::specialRoomRequired).findFirst().orElse(null);
            if (special != null) {
                manualItems.add(new ManualItem(
                        classId, info.name(), SPECIAL_ROOM_REQUIRED,
                        "「" + special.courseName() + "」需要专用教室，固定普通教室不能代替，请手工分配"));
                continue;
            }
            int requiredCapacity = requiredCapacity(info, classGaps);
            String name = roomName(semesterName, info.name());
            if (name.length() > MAX_ROOM_NAME_LENGTH) {
                manualItems.add(new ManualItem(
                        classId, info.name(), ROOM_NAME_TOO_LONG,
                        "教室名「" + name + "」超过 " + MAX_ROOM_NAME_LENGTH
                                + " 个字符，请先缩短学期或班级名称"));
                continue;
            }
            ExistingRoom existing = roomByName.get(name);
            if (existing == null) {
                plans.add(new ClassPlan(
                        classId, info.name(), info.studentCount(), requiredCapacity,
                        classGaps.size(), name, null, requiredCapacity, ACTION_CREATE));
            } else if (ROOM_TYPE_NORMAL.equals(existing.type())
                    && existing.capacity() >= requiredCapacity) {
                plans.add(new ClassPlan(
                        classId, info.name(), info.studentCount(), requiredCapacity,
                        classGaps.size(), name, existing.id(), existing.capacity(), ACTION_REUSE));
            } else {
                manualItems.add(new ManualItem(
                        classId, info.name(), ROOM_NAME_CONFLICT,
                        "已存在同名教室「" + name + "」（" + existing.type() + "，容量 "
                                + existing.capacity() + "），与本次需要的普通教室（容量至少 "
                                + requiredCapacity + "）不符，不能自动复用；请人工处理这间教室"));
            }
        }
        return new Plan(List.copyOf(plans), List.copyOf(manualItems));
    }

    /**
     * Capacity is the class size, raised to any lesson snapshot that is larger: the
     * room must fit every lesson it is about to receive, not only today's roster.
     */
    private static int requiredCapacity(ClassInfo info, List<LessonGap> classGaps) {
        int capacity = info.studentCount();
        for (LessonGap gap : classGaps) {
            capacity = Math.max(capacity, gap.studentCount());
        }
        return capacity;
    }

    private static List<Long> orderedClassIds(
            Map<Long, List<LessonGap>> gapsByClass, Map<Long, ClassInfo> classById) {
        return gapsByClass.keySet().stream()
                .sorted(Comparator
                        .comparing((Long id) -> nameOf(classById, id))
                        .thenComparing(Comparator.naturalOrder()))
                .toList();
    }

    private static String nameOf(Map<Long, ClassInfo> classById, Long id) {
        ClassInfo info = classById.get(id);
        return info == null ? "" : info.name();
    }
}
