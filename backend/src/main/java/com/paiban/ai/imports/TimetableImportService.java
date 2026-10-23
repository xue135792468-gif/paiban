package com.paiban.ai.imports;

import com.paiban.ai.imports.ImportDtos.RowDto;
import com.paiban.ai.imports.ImportDtos.RowErrorDto;
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
import com.paiban.foundation.domain.Semester;
import com.paiban.foundation.domain.Teacher;
import com.paiban.foundation.domain.TeacherRepository;
import com.paiban.foundation.domain.TeachingClass;
import com.paiban.foundation.domain.TeachingClassRepository;
import com.paiban.foundation.service.SemesterService;
import com.paiban.foundation.web.SemesterDtos.SemesterDto;
import com.paiban.scheduling.domain.Assignment;
import com.paiban.scheduling.domain.AssignmentRepository;
import com.paiban.scheduling.domain.DraftLesson;
import com.paiban.scheduling.domain.DraftLessonRepository;
import com.paiban.scheduling.domain.TimetableDraft;
import com.paiban.scheduling.domain.TimetableDraftRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns timetable-shaped import rows (class/day/period/course/teacher/room) into a
 * DRAFT timetable. Rows are matched to existing foundation data by name; anything
 * unmatched fails the whole batch with row-level errors, so a draft is only ever
 * created from data the user could see in the preview.
 */
@Service
public class TimetableImportService {

    private static final DateTimeFormatter DRAFT_NAME_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final List<String> DAY_LABELS =
            List.of("周一", "周二", "周三", "周四", "周五", "周六", "周日");
    private static final int MAX_CANDIDATES = 10;

    /** Outcome of one import: either a new draft, or the row errors that blocked it. */
    public record Result(Long draftId, int imported, List<RowErrorDto> errors, List<String> warnings) {}

    /**
     * Preview-side counts. {@code lessons} covers every preview row and
     * {@code unresolved} the rows that could not be matched; {@code groups} and the
     * new/updated split are computed over the matched rows only.
     */
    public record Summary(int lessons, int unresolved, int groups, int newAssignments, int updatedAssignments) {}

    /** Read-only analysis of a preview: what blocks it, and what it would write. */
    public record Analysis(List<RowErrorDto> errors, Summary summary, List<GradeHint> gradeHints) {}

    /**
     * The grade guessed from an unmatched class name, with the semester's grades to choose
     * from. Preview-only: commit errors stay in {@link RowErrorDto} so they do not grow.
     */
    public record GradeHint(int row, String inferredGrade, List<String> gradeCandidates) {}

    private final SemesterService semesterService;
    private final AdminClassRepository adminClasses;
    private final GradeRepository grades;
    private final TeachingClassRepository teachingClasses;
    private final CourseRepository courses;
    private final TeacherRepository teachers;
    private final RoomRepository rooms;
    private final PeriodRepository periods;
    private final AssignmentRepository assignments;
    private final TimetableDraftRepository drafts;
    private final DraftLessonRepository draftLessons;

    public TimetableImportService(
            SemesterService semesterService,
            AdminClassRepository adminClasses,
            GradeRepository grades,
            TeachingClassRepository teachingClasses,
            CourseRepository courses,
            TeacherRepository teachers,
            RoomRepository rooms,
            PeriodRepository periods,
            AssignmentRepository assignments,
            TimetableDraftRepository drafts,
            DraftLessonRepository draftLessons) {
        this.semesterService = semesterService;
        this.adminClasses = adminClasses;
        this.grades = grades;
        this.teachingClasses = teachingClasses;
        this.courses = courses;
        this.teachers = teachers;
        this.rooms = rooms;
        this.periods = periods;
        this.assignments = assignments;
        this.drafts = drafts;
        this.draftLessons = draftLessons;
    }

    @Transactional
    public Result importRows(List<RowDto> rows, Long semesterId, String username) {
        if (rows == null || rows.isEmpty()) {
            throw ApiException.badRequest("没有可入库的数据");
        }
        Semester semester = semesterId == null
                ? semesterService.requireCurrent()
                : semesterService.require(semesterId);
        Long sid = semester.getId();
        Match match = match(rows, sid);
        if (!match.errors().isEmpty()) {
            return new Result(null, 0, List.copyOf(match.errors()), List.of());
        }
        List<ResolvedRow> resolved = match.resolved();
        Map<AssignmentKey, List<ResolvedRow>> groups = new LinkedHashMap<>();
        for (ResolvedRow row : resolved) {
            groups.computeIfAbsent(new AssignmentKey(row.adminClass().getId(), row.course().getId()), key -> new ArrayList<>())
                    .add(row);
        }

        Map<AssignmentKey, Assignment> assignmentsByKey = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        for (Map.Entry<AssignmentKey, List<ResolvedRow>> entry : groups.entrySet()) {
            AssignmentKey key = entry.getKey();
            List<ResolvedRow> group = entry.getValue();
            TeachingClass teachingClass = teachingClasses.findByAdminClassId(key.adminClassId())
                    .orElseGet(() -> createTeachingClass(sid, group.get(0).adminClass()));
            Assignment assignment = upsertAssignment(sid, group, teachingClass, key.courseId());
            assignmentsByKey.put(key, assignment);
            warnOnMultipleTeachers(group, warnings);
        }

        TimetableDraft draft = new TimetableDraft();
        draft.setSemesterId(sid);
        draft.setName("导入课表 " + LocalDateTime.now().format(DRAFT_NAME_TIME));
        draft.setStatus("DRAFT");
        draft.setScoreHard(0);
        draft.setScoreSoft(0);
        draft.setCreatedBy(username);
        draft.setCreatedAt(Instant.now());
        TimetableDraft savedDraft = drafts.save(draft);

        List<DraftLesson> lessons = new ArrayList<>();
        Map<AssignmentKey, Integer> nextIndex = new LinkedHashMap<>();
        for (ResolvedRow row : resolved) {
            AssignmentKey key = new AssignmentKey(row.adminClass().getId(), row.course().getId());
            int index = nextIndex.merge(key, 1, Integer::sum) - 1;
            DraftLesson lesson = new DraftLesson();
            lesson.setDraftId(savedDraft.getId());
            lesson.setAssignmentId(assignmentsByKey.get(key).getId());
            lesson.setLessonIndex(index);
            lesson.setCourseId(row.course().getId());
            lesson.setTeacherId(row.teacher().getId());
            lesson.setAdminClassId(row.adminClass().getId());
            lesson.setStudentCount(row.adminClass().getStudentCount());
            lesson.setPeriodId(row.period().getId());
            lesson.setRoomId(row.room() == null ? null : row.room().getId());
            lessons.add(lesson);
        }
        draftLessons.saveAll(lessons);
        return new Result(savedDraft.getId(), lessons.size(), List.of(), List.copyOf(warnings));
    }

    /**
     * The preview analysis: the row errors {@link #importRows} would report plus the
     * lesson and teaching-task counts the grid shows before anything is written.
     */
    @Transactional(readOnly = true)
    public Analysis inspect(List<RowDto> rows, Long semesterId) {
        if (rows == null || rows.isEmpty()) {
            return new Analysis(List.of(), new Summary(0, 0, 0, 0, 0), List.of());
        }
        Long sid = semesterId == null
                ? semesterService.current().map(SemesterDto::id).orElse(null)
                : semesterService.require(semesterId).getId();
        if (sid == null) {
            // Nothing can be matched without a current semester: report it as a
            // page-level blocker instead of a preview that looks importable.
            return new Analysis(
                    List.of(new RowErrorDto(0, "semester", "请先设置当前学期", List.of())),
                    new Summary(rows.size(), rows.size(), 0, 0, 0),
                    List.of());
        }
        Match match = match(rows, sid);
        Map<AssignmentKey, List<ResolvedRow>> groups = new LinkedHashMap<>();
        for (ResolvedRow row : match.resolved()) {
            groups.computeIfAbsent(new AssignmentKey(row.adminClass().getId(), row.course().getId()),
                            key -> new ArrayList<>())
                    .add(row);
        }
        int updated = 0;
        if (!groups.isEmpty()) {
            Map<Long, Long> teachingClassByAdminClass = new LinkedHashMap<>();
            for (TeachingClass teachingClass : teachingClasses.findBySemesterId(sid)) {
                teachingClassByAdminClass.putIfAbsent(
                        teachingClass.getAdminClassId(), teachingClass.getId());
            }
            java.util.Set<String> existing = new java.util.HashSet<>();
            for (Assignment assignment : assignments.findBySemesterId(sid)) {
                existing.add(assignment.getTeachingClassId() + "|" + assignment.getCourseId());
            }
            for (AssignmentKey key : groups.keySet()) {
                Long teachingClassId = teachingClassByAdminClass.get(key.adminClassId());
                if (teachingClassId != null && existing.contains(teachingClassId + "|" + key.courseId())) {
                    updated++;
                }
            }
        }
        int total = groups.size();
        int unresolved = rows.size() - match.resolved().size();
        return new Analysis(
                List.copyOf(match.errors()),
                new Summary(rows.size(), unresolved, total, total - updated, updated),
                gradeHints(match.errors(), rows, sid));
    }

    /**
     * Guesses a grade for every unmatched class name so the mapping step can offer it.
     * The semester's own grades are the candidates, in the order the user sees them.
     */
    private List<GradeHint> gradeHints(List<RowErrorDto> errors, List<RowDto> rows, Long sid) {
        List<String> semesterGrades = grades.findBySemesterIdOrderBySortOrderAscNameAsc(sid).stream()
                .map(Grade::getName)
                .toList();
        List<GradeHint> hints = new ArrayList<>();
        for (RowErrorDto error : errors) {
            if (error.row() < 1 || !"className".equals(error.field())) {
                continue;
            }
            String inferred = GradeInference.infer(value(rows.get(error.row() - 1), "className"));
            hints.add(new GradeHint(error.row(),
                    GradeInference.matchExisting(inferred, semesterGrades), semesterGrades));
        }
        return List.copyOf(hints);
    }

    /** Resolves every row to foundation entities; any unmatched name becomes a row error. */
    private Match match(List<RowDto> rows, Long sid) {
        Map<String, AdminClass> classByName = new LinkedHashMap<>();
        for (AdminClass adminClass : adminClasses.findBySemesterIdOrderByGradeIdAscNameAsc(sid)) {
            classByName.putIfAbsent(normalizeName(adminClass.getName()), adminClass);
        }
        Map<String, Course> courseByName = new LinkedHashMap<>();
        for (Course course : courses.findAllByOrderByNameAsc()) {
            courseByName.putIfAbsent(normalizeName(course.getName()), course);
        }
        Map<String, Teacher> teacherByName = new LinkedHashMap<>();
        for (Teacher teacher : teachers.findAllByOrderByNameAsc()) {
            teacherByName.putIfAbsent(normalizeName(teacher.getName()), teacher);
        }
        Map<String, Room> roomByName = new LinkedHashMap<>();
        for (Room room : rooms.findAllByOrderByNameAsc()) {
            roomByName.putIfAbsent(normalizeName(room.getName()), room);
        }
        Map<String, Period> periodBySlot = new LinkedHashMap<>();
        Map<Integer, List<Period>> periodsByDay = new LinkedHashMap<>();
        for (Period period : periods.findBySemesterIdOrderByDayOfWeekAscPeriodNoAsc(sid)) {
            periodBySlot.put(period.getDayOfWeek() + "-" + period.getPeriodNo(), period);
            periodsByDay.computeIfAbsent(period.getDayOfWeek(), key -> new ArrayList<>()).add(period);
        }

        List<RowErrorDto> errors = new ArrayList<>();
        List<ResolvedRow> resolved = new ArrayList<>();
        List<String> classCandidates = candidates(classByName.values().stream().map(AdminClass::getName).toList());
        List<String> courseCandidates = candidates(courseByName.values().stream().map(Course::getName).toList());
        List<String> teacherCandidates = candidates(teacherByName.values().stream().map(Teacher::getName).toList());
        List<String> roomCandidates = candidates(roomByName.values().stream().map(Room::getName).toList());
        for (int i = 0; i < rows.size(); i++) {
            RowDto row = rows.get(i);
            int line = i + 1;
            boolean failed = false;

            String className = value(row, "className");
            AdminClass adminClass = null;
            if (className.isBlank()) {
                errors.add(new RowErrorDto(line, "className", "班级不能为空", classCandidates));
                failed = true;
            } else {
                adminClass = classByName.get(normalizeName(className));
                if (adminClass == null) {
                    errors.add(new RowErrorDto(line, "className", "系统中找不到班级：" + className, classCandidates));
                    failed = true;
                }
            }

            String courseName = value(row, "courseName");
            Course course = null;
            if (courseName.isBlank()) {
                errors.add(new RowErrorDto(line, "courseName", "课程不能为空", courseCandidates));
                failed = true;
            } else {
                course = courseByName.get(normalizeName(courseName));
                if (course == null) {
                    errors.add(new RowErrorDto(line, "courseName", "系统中找不到课程：" + courseName, courseCandidates));
                    failed = true;
                }
            }

            String teacherName = value(row, "teacherName");
            Teacher teacher = null;
            if (teacherName.isBlank()) {
                errors.add(new RowErrorDto(line, "teacherName", "教师不能为空", teacherCandidates));
                failed = true;
            } else {
                teacher = teacherByName.get(normalizeName(teacherName));
                if (teacher == null) {
                    errors.add(new RowErrorDto(line, "teacherName", "系统中找不到教师：" + teacherName, teacherCandidates));
                    failed = true;
                }
            }

            String roomName = value(row, "roomName");
            Room room = null;
            if (!roomName.isBlank()) {
                room = roomByName.get(normalizeName(roomName));
                if (room == null) {
                    errors.add(new RowErrorDto(line, "roomName", "系统中找不到教室：" + roomName, roomCandidates));
                    failed = true;
                }
            }

            String dayText = value(row, "dayOfWeek");
            Integer day = null;
            if (dayText.isBlank()) {
                errors.add(new RowErrorDto(line, "dayOfWeek", "星期不能为空", DAY_LABELS));
                failed = true;
            } else {
                day = parseDay(dayText).orElse(null);
                if (day == null) {
                    errors.add(new RowErrorDto(line, "dayOfWeek", "无法识别星期：" + dayText, DAY_LABELS));
                    failed = true;
                }
            }

            String periodText = value(row, "periodNo");
            Integer periodNo = null;
            if (periodText.isBlank()) {
                errors.add(new RowErrorDto(line, "periodNo", "节次不能为空", periodCandidates(periodsByDay, day)));
                failed = true;
            } else {
                periodNo = parsePeriodNo(periodText).orElse(null);
                if (periodNo == null) {
                    errors.add(new RowErrorDto(line, "periodNo", "无法识别节次：" + periodText,
                            periodCandidates(periodsByDay, day)));
                    failed = true;
                }
            }

            Period period = null;
            if (day != null && periodNo != null) {
                period = periodBySlot.get(day + "-" + periodNo);
                if (period == null) {
                    errors.add(new RowErrorDto(line, "periodNo", "该星期没有第 " + periodNo + " 节",
                            periodCandidates(periodsByDay, day)));
                    failed = true;
                }
            }

            if (!failed) {
                resolved.add(new ResolvedRow(adminClass, course, teacher, room, period));
            }
        }
        return new Match(errors, resolved);
    }

    private record Match(List<RowErrorDto> errors, List<ResolvedRow> resolved) {}

    /**
     * ADR-0022: the draft lesson's teacher is authoritative. When one (class, course)
     * arrives with several teachers the created assignment takes the first row's
     * teacher, every lesson keeps its own, and the user is told about the mismatch.
     */
    private void warnOnMultipleTeachers(List<ResolvedRow> group, List<String> warnings) {
        List<String> names = group.stream().map(row -> row.teacher().getName()).distinct().toList();
        if (names.size() <= 1) {
            return;
        }
        warnings.add("班级 " + group.get(0).adminClass().getName()
                + " 的课程 " + group.get(0).course().getName()
                + " 有 " + names.size() + " 位教师（" + String.join("、", names)
                + "），授课任务使用 " + group.get(0).teacher().getName() + "，课节保留各自教师");
    }

    private TeachingClass createTeachingClass(Long semesterId, AdminClass adminClass) {
        TeachingClass teachingClass = new TeachingClass();
        teachingClass.setSemesterId(semesterId);
        teachingClass.setAdminClassId(adminClass.getId());
        teachingClass.setName(adminClass.getName());
        return teachingClasses.save(teachingClass);
    }

    /**
     * Finds or creates the task for one (class, course) pair. The import is the
     * authority for the task's teacher and lesson count; ADR-0022 keeps each draft
     * lesson's own teacher when the pair arrives with several teachers.
     */
    private Assignment upsertAssignment(
            Long semesterId, List<ResolvedRow> group, TeachingClass teachingClass, Long courseId) {
        ResolvedRow first = group.get(0);
        Assignment assignment = assignments
                .findBySemesterIdAndTeachingClassIdAndCourseId(semesterId, teachingClass.getId(), courseId)
                .orElseGet(() -> {
                    Assignment created = new Assignment();
                    created.setCreatedAt(Instant.now());
                    return created;
                });
        assignment.setSemesterId(semesterId);
        assignment.setAdminClassId(first.adminClass().getId());
        assignment.setTeachingClassId(teachingClass.getId());
        assignment.setCourseId(courseId);
        assignment.setTeacherId(first.teacher().getId());
        assignment.setWeeklyLessons(group.size());
        return assignments.save(assignment);
    }

    private static List<String> candidates(List<String> names) {
        return names.stream().distinct().limit(MAX_CANDIDATES).toList();
    }

    private static List<String> periodCandidates(Map<Integer, List<Period>> periodsByDay, Integer day) {
        if (day != null) {
            return periodsByDay.getOrDefault(day, List.of()).stream()
                    .sorted(java.util.Comparator.comparingInt(Period::getPeriodNo))
                    .map(period -> "第" + period.getPeriodNo() + "节")
                    .toList();
        }
        return periodsByDay.values().stream()
                .flatMap(List::stream)
                .map(Period::getPeriodNo)
                .distinct()
                .sorted()
                .map(no -> "第" + no + "节")
                .toList();
    }

    private static Optional<Integer> parseDay(String raw) {
        String value = normalizeSymbols(raw);
        value = value.replace("星期", "").replace("礼拜", "").replace("周", "");
        return switch (value) {
            case "一", "1" -> Optional.of(1);
            case "二", "2" -> Optional.of(2);
            case "三", "3" -> Optional.of(3);
            case "四", "4" -> Optional.of(4);
            case "五", "5" -> Optional.of(5);
            case "六", "6" -> Optional.of(6);
            case "日", "天", "七", "7" -> Optional.of(7);
            default -> Optional.empty();
        };
    }

    private static Optional<Integer> parsePeriodNo(String raw) {
        String value = normalizeSymbols(raw);
        if (value.startsWith("第")) {
            value = value.substring(1);
        }
        if (value.endsWith("节")) {
            value = value.substring(0, value.length() - 1);
        }
        if (value.isEmpty()) {
            return Optional.empty();
        }
        int number;
        if (value.matches("\\d+")) {
            number = Integer.parseInt(value);
        } else {
            number = chineseNumber(value);
        }
        return number > 0 ? Optional.of(number) : Optional.empty();
    }

    /** Parses 一..九, 十, 十一..九十九; anything else yields 0. */
    private static int chineseNumber(String value) {
        String digits = "一二三四五六七八九";
        int total = 0;
        int current = 0;
        for (char c : value.toCharArray()) {
            int digit = c == '两' ? 2 : digits.indexOf(c) + 1;
            if (digit > 0) {
                current = digit;
            } else if (c == '十') {
                total += (current == 0 ? 1 : current) * 10;
                current = 0;
            } else {
                return 0;
            }
        }
        return total + current;
    }

    private static String normalizeSymbols(String value) {
        if (value == null) {
            return "";
        }
        return java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKC)
                .replaceAll("\\s+", "");
    }

    private static String normalizeName(String value) {
        return normalizeSymbols(value).toLowerCase(java.util.Locale.ROOT);
    }

    private static String value(RowDto row, String field) {
        if (row == null || row.values() == null) {
            return "";
        }
        String value = row.values().get(field);
        return value == null ? "" : value.trim();
    }

    private record AssignmentKey(Long adminClassId, Long courseId) {}

    private record ResolvedRow(AdminClass adminClass, Course course, Teacher teacher, Room room, Period period) {}
}
