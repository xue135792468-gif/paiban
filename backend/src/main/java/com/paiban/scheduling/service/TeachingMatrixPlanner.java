package com.paiban.scheduling.service;

import static com.paiban.scheduling.service.TeachingMatrixWorkbook.CLASS;
import static com.paiban.scheduling.service.TeachingMatrixWorkbook.HOMEROOM;
import static com.paiban.scheduling.service.TeachingMatrixWorkbook.STUDENTS;
import static com.paiban.scheduling.web.TeachingMatrixDtos.Action.*;

import com.paiban.foundation.domain.AdminClass;
import com.paiban.foundation.domain.Course;
import com.paiban.foundation.domain.Grade;
import com.paiban.foundation.domain.Teacher;
import com.paiban.foundation.web.ImportDtos.ImportError;
import com.paiban.scheduling.domain.Assignment;
import com.paiban.scheduling.web.TeachingMatrixDtos.*;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

final class TeachingMatrixPlanner {

    private static final Pattern NUMERIC_CLASS = Pattern.compile("[0-9]+");
    private static final Pattern LAST_NUMBER = Pattern.compile("([0-9]+)(?!.*[0-9])");
    private static final Pattern IN_BRACKETS = Pattern.compile("\\(([0-9]+)\\)\\s*班?$");

    record Catalogue(Grade grade, List<AdminClass> classes, List<Teacher> teachers,
                     List<Course> courses, List<Assignment> assignments, Map<Long, Long> teachingClassIds,
                     int availablePeriods) {}

    record TeacherPlan(String name, Teacher existing) {
        TeacherPreview preview() {
            return new TeacherPreview(name, existing == null ? CREATE : UNCHANGED);
        }
    }

    record CoursePlan(String name, Course existing, Integer override, boolean saveAsDefault) {
        Integer defaultHours() {
            return existing == null ? null : existing.getWeeklyHours();
        }

        Integer effectiveHours() {
            return override == null ? defaultHours() : override;
        }

        CoursePreview preview(boolean missing) {
            Action action = existing == null ? CREATE : (saveAsDefault ? UPDATE : UNCHANGED);
            return new CoursePreview(name, action, defaultHours(), effectiveHours(), missing);
        }
    }

    record CellPlan(CoursePlan course, TeacherPlan teacher, Assignment existing, Integer hours, Action action) {
        CellPreview preview() {
            return new CellPreview(course.name(), teacher.name(), hours, action);
        }
    }

    record RowPlan(int line, String name, AdminClass existing, Long teachingClassId, int students, TeacherPlan headTeacher,
                   Action action, List<CellPlan> cells) {
        RowPreview preview() {
            return new RowPreview(line, name, students, headTeacher == null ? null : headTeacher.name(),
                    action, cells.stream().map(CellPlan::preview).toList());
        }
    }

    record Plan(List<TeacherPlan> teachers, List<CoursePlan> courses, List<RowPlan> rows, Report report) {}

    private final Catalogue catalogue;
    private final Map<String, Integer> overrides;
    private final boolean saveDefaults;
    private final Map<String, AdminClass> classesByName;
    private final Map<String, List<AdminClass>> classesByNumber;
    private final Map<String, List<Teacher>> teachersByName;
    private final Map<Long, Teacher> teachersById;
    private final Map<String, List<Course>> coursesByName;
    private final Map<Long, Map<Long, Assignment>> assignmentsByClass;
    private final List<ImportError> errors = new ArrayList<>();
    private final List<CapacityWarning> warnings = new ArrayList<>();
    private final Map<String, TeacherPlan> teacherPlans = new LinkedHashMap<>();
    private final Map<String, CoursePlan> coursePlans = new LinkedHashMap<>();
    private final Set<String> seenClasses = new HashSet<>();
    private final Set<String> missingHours = new HashSet<>();

    TeachingMatrixPlanner(Catalogue catalogue, Options options) {
        this.catalogue = catalogue;
        this.overrides = options == null ? Map.of() : options.weeklyHours();
        this.saveDefaults = options != null && options.saveAsDefaults();
        classesByName = catalogue.classes().stream().collect(Collectors.toMap(AdminClass::getName, Function.identity()));
        classesByNumber = catalogue.classes().stream()
                .filter(this::inTargetGrade)
                .filter(item -> classNumber(item.getName()) != null)
                .collect(Collectors.groupingBy(item -> classNumber(item.getName())));
        teachersByName = catalogue.teachers().stream().filter(item -> item.getDeletedAt() == null)
                .collect(Collectors.groupingBy(Teacher::getName));
        teachersById = catalogue.teachers().stream().collect(Collectors.toMap(Teacher::getId, Function.identity()));
        coursesByName = catalogue.courses().stream()
                .collect(Collectors.groupingBy(item -> TeachingMatrixWorkbook.cleanHeader(item.getName())));
        assignmentsByClass = new HashMap<>();
        for (Assignment item : catalogue.assignments()) {
            var byCourse = assignmentsByClass.computeIfAbsent(item.getAdminClassId(), ignored -> new HashMap<>());
            byCourse.put(item.getCourseId(), item);
        }
    }

    static Plan rejected(List<ImportError> errors) {
        var empty = new Counts(0, 0, 0);
        return new Plan(List.of(), List.of(), List.of(),
                new Report(new Summary(empty, empty, empty, empty), List.of(), List.of(), List.of(), errors, List.of()));
    }

    Plan plan(TeachingMatrixWorkbook.Input input) {
        if (!input.errors().isEmpty()) {
            return rejected(input.errors());
        }
        List<RowPlan> rows = new ArrayList<>();
        for (var inputRow : input.rows()) {
            RowPlan planned = planRow(inputRow);
            if (planned != null) {
                rows.add(planned);
            }
        }
        for (var override : overrides.entrySet()) {
            if (!coursePlans.containsKey(override.getKey())) {
                errors.add(new ImportError(0, override.getKey(), "该课程没有可导入的任课单元格"));
            }
        }

        var teacherPreviews = teacherPlans.values().stream().map(TeacherPlan::preview).toList();
        var coursePreviews = coursePlans.values().stream()
                .map(course -> course.preview(missingHours.contains(course.name()))).toList();
        var rowPreviews = rows.stream().map(RowPlan::preview).toList();
        var summary = new Summary(
                count(rowPreviews.stream().map(RowPreview::action).toList()),
                count(teacherPreviews.stream().map(TeacherPreview::action).toList()),
                count(coursePreviews.stream().map(CoursePreview::action).toList()),
                count(rowPreviews.stream().flatMap(row -> row.assignments().stream())
                        .map(CellPreview::action).toList()));
        return new Plan(List.copyOf(teacherPlans.values()), List.copyOf(coursePlans.values()), rows,
                new Report(summary, teacherPreviews, coursePreviews, rowPreviews, errors, warnings));
    }

    private RowPlan planRow(TeachingMatrixWorkbook.InputRow row) {
        int line = row.number();
        if (row.className() == null) {
            errors.add(new ImportError(line, CLASS, "班级不能为空"));
            return null;
        }
        int errorsBeforeMatch = errors.size();
        AdminClass adminClass = matchClass(line, row.className());
        if (errors.size() != errorsBeforeMatch) {
            return null;
        }
        String name = adminClass == null ? newClassName(row.className()) : adminClass.getName();
        if (name == null || name.length() > 100) {
            errors.add(new ImportError(line, CLASS, "请填写本年级已有班名或有效班号（新建班名最多 100 字）"));
            return null;
        }
        if (adminClass == null && classesByName.containsKey(name)) {
            errors.add(new ImportError(line, CLASS, "将新建的班名已占用：" + name));
            return null;
        }
        if (!seenClasses.add(name)) {
            errors.add(new ImportError(line, CLASS, "班级重复：" + name));
            return null;
        }

        int count = studentCount(line, row.students(), adminClass);
        TeacherPlan head = row.homeroom() == null ? existingHomeroom(adminClass)
                : teacher(line, HOMEROOM, row.homeroom());
        Long previousHead = adminClass == null ? null : adminClass.getHeadTeacherId();
        boolean changedHead = row.homeroom() != null && head != null
                && (head.existing() == null || !Objects.equals(previousHead, head.existing().getId()));
        Action action = adminClass == null ? CREATE
                : (count != adminClass.getStudentCount() || changedHead ? UPDATE : UNCHANGED);
        Map<Long, Assignment> previous = adminClass == null ? Map.of()
                : assignmentsByClass.getOrDefault(adminClass.getId(), Map.of());
        List<CellPlan> cells = new ArrayList<>();
        for (var cell : row.cells().entrySet()) {
            CellPlan planned = planCell(line, cell.getKey(), cell.getValue(), previous);
            if (planned != null) {
                cells.add(planned);
            }
        }
        Long teachingClassId = adminClass == null ? null : catalogue.teachingClassIds().get(adminClass.getId());
        if (adminClass != null && teachingClassId == null && cells.stream().anyMatch(cell -> cell.action() == CREATE)) {
            errors.add(new ImportError(line, CLASS, "班级缺少对应的教学班"));
        }
        capacity(line, name, previous, cells);
        return new RowPlan(line, name, adminClass, teachingClassId, count, head, action, cells);
    }

    private AdminClass matchClass(int line, String name) {
        AdminClass exact = classesByName.get(name);
        boolean numeric = NUMERIC_CLASS.matcher(Normalizer.normalize(name, Normalizer.Form.NFKC)).matches();
        if (exact != null && !numeric) {
            if (!inTargetGrade(exact)) {
                errors.add(new ImportError(line, CLASS, "该班级不属于目标年级：" + name));
            } else if (exact.getDeletedAt() != null) {
                errors.add(new ImportError(line, CLASS, "该班级在最近删除中，请先恢复：" + name));
            }
            return exact;
        }
        String number = classNumber(name);
        List<AdminClass> matches = number == null ? List.of() : classesByNumber.getOrDefault(number, List.of());
        if (matches.size() > 1) {
            errors.add(new ImportError(line, CLASS, "班号 " + number + " 匹配到多个班级，请填写完整班名"));
            return null;
        }
        if (matches.isEmpty()) {
            return null;
        }
        AdminClass match = matches.getFirst();
        if (match.getDeletedAt() != null) {
            errors.add(new ImportError(line, CLASS, "该班级在最近删除中，请先恢复：" + match.getName()));
        }
        return match;
    }

    private boolean inTargetGrade(AdminClass item) {
        return catalogue.grade().getId().equals(item.getGradeId());
    }

    private String newClassName(String name) {
        String number = classNumber(name);
        return number == null ? null : catalogue.grade().getName() + "（" + number + "）班";
    }

    private static String classNumber(String name) {
        String normalized = Normalizer.normalize(name, Normalizer.Form.NFKC);
        if (normalized.matches("[+-]?[0-9]+(?:\\.[0-9]+)?") && !normalized.matches("[0-9]+")) {
            return null;
        }
        var inBrackets = IN_BRACKETS.matcher(normalized);
        var last = LAST_NUMBER.matcher(normalized);
        String number = inBrackets.find() ? inBrackets.group(1) : (last.find() ? last.group(1) : null);
        if (number == null) {
            return null;
        }
        String positive = number.replaceFirst("^0+", "");
        return positive.isEmpty() ? null : positive;
    }

    private int studentCount(int line, String value, AdminClass adminClass) {
        int fallback = adminClass == null ? AdminClass.DEFAULT_STUDENT_COUNT : adminClass.getStudentCount();
        if (value == null) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed > 0) {
                return parsed;
            }
        } catch (NumberFormatException ignored) {
        }
        errors.add(new ImportError(line, STUDENTS, "人数必须为正整数"));
        return fallback;
    }

    private TeacherPlan existingHomeroom(AdminClass adminClass) {
        Teacher previous = adminClass == null ? null : teachersById.get(adminClass.getHeadTeacherId());
        return previous == null ? null : new TeacherPlan(previous.getName(), previous);
    }

    private TeacherPlan teacher(int line, String field, String name) {
        if (name.length() > 100) {
            errors.add(new ImportError(line, field, "教师姓名不能超过 100 个字符"));
            return null;
        }
        List<Teacher> matches = teachersByName.getOrDefault(name, List.of());
        if (matches.size() > 1) {
            errors.add(new ImportError(line, field, "存在多名同名教师，请先在教师管理中区分：" + name));
            return null;
        }
        return teacherPlans.computeIfAbsent(name, key -> new TeacherPlan(key, matches.isEmpty() ? null : matches.getFirst()));
    }

    private CoursePlan course(int line, String name) {
        List<Course> matches = coursesByName.getOrDefault(name, List.of());
        if (matches.size() > 1) {
            errors.add(new ImportError(line, name, "清理表头空格后匹配到多门同名课程"));
            return null;
        }
        Course course = matches.isEmpty() ? null : matches.getFirst();
        if (course != null && course.getDeletedAt() != null) {
            errors.add(new ImportError(line, name, "该课程在最近删除中，请先恢复"));
            return null;
        }
        Integer override = overrides.get(name);
        if (overrides.containsKey(name) && (override == null || override < 1 || override > 20)) {
            errors.add(new ImportError(line, name, "每周节数必须为 1–20 的整数"));
        }
        boolean saveAsDefault = saveDefaults && override != null
                && (course == null || !Objects.equals(course.getWeeklyHours(), override));
        return coursePlans.computeIfAbsent(name, key -> new CoursePlan(key, course, override, saveAsDefault));
    }

    private CellPlan planCell(int line, String name, String teacherName, Map<Long, Assignment> previous) {
        CoursePlan course = course(line, name);
        TeacherPlan teacher = teacher(line, name, teacherName);
        if (course == null || teacher == null) {
            return null;
        }
        Assignment existing = course.existing() == null ? null : previous.get(course.existing().getId());
        if (existing != null && existing.getDeletedAt() != null) {
            errors.add(new ImportError(line, name, AssignmentService.DELETED_ASSIGNMENT_MESSAGE));
            return null;
        }
        Integer hours = existing != null && course.override() == null
                ? Integer.valueOf(existing.getWeeklyLessons()) : course.effectiveHours();
        if (hours == null) {
            missingHours.add(name);
        }
        Action action;
        if (existing == null) {
            action = CREATE;
        } else if (teacher.existing() == null || !existing.getTeacherId().equals(teacher.existing().getId())) {
            action = UPDATE_TEACHER;
        } else {
            action = Objects.equals(hours, existing.getWeeklyLessons()) ? UNCHANGED : UPDATE_HOURS;
        }
        return new CellPlan(course, teacher, existing, hours, action);
    }

    private void capacity(int line, String name, Map<Long, Assignment> previous, List<CellPlan> cells) {
        int total = previous.values().stream().filter(item -> item.getDeletedAt() == null && item.isActive())
                .mapToInt(Assignment::getWeeklyLessons).sum();
        for (CellPlan cell : cells) {
            if (cell.hours() == null) {
                continue;
            }
            if (cell.existing() == null) {
                total += cell.hours();
            } else if (cell.existing().isActive()) {
                total += cell.hours() - cell.existing().getWeeklyLessons();
            }
        }
        if (total > catalogue.availablePeriods()) {
            warnings.add(new CapacityWarning(line, name, total, catalogue.availablePeriods()));
        }
    }

    private static Counts count(List<Action> actions) {
        int created = (int) actions.stream().filter(action -> action == CREATE).count();
        int unchanged = (int) actions.stream().filter(action -> action == UNCHANGED).count();
        return new Counts(created, actions.size() - created - unchanged, unchanged);
    }
}
