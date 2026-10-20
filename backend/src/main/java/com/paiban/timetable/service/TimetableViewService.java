package com.paiban.timetable.service;

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
import com.paiban.foundation.web.FoundationDtos.ClassDto;
import com.paiban.identity.domain.AppUser;
import com.paiban.identity.service.UserService;
import com.paiban.substitution.domain.Substitution;
import com.paiban.substitution.domain.SubstitutionRepository;
import com.paiban.timetable.domain.TimetableEntry;
import com.paiban.timetable.domain.TimetableEntryRepository;
import com.paiban.timetable.web.TimetableDtos.SchoolTimetableDto;
import com.paiban.timetable.web.TimetableDtos.TimetableEntryDto;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read model for timetables. The baseline is read from timetable_entry and the
 * substitution overlay is merged here (ADR-0008), so callers never see two tables.
 */
@Service
public class TimetableViewService {

    private final TimetableEntryRepository entries;
    private final PeriodRepository periods;
    private final RoomRepository rooms;
    private final CourseRepository courses;
    private final TeacherRepository teachers;
    private final AdminClassRepository adminClasses;
    private final SubstitutionRepository substitutions;
    private final SemesterService semesters;
    private final UserService users;

    public TimetableViewService(
            TimetableEntryRepository entries,
            PeriodRepository periods,
            RoomRepository rooms,
            CourseRepository courses,
            TeacherRepository teachers,
            AdminClassRepository adminClasses,
            SubstitutionRepository substitutions,
            SemesterService semesters,
            UserService users) {
        this.entries = entries;
        this.periods = periods;
        this.rooms = rooms;
        this.courses = courses;
        this.teachers = teachers;
        this.adminClasses = adminClasses;
        this.substitutions = substitutions;
        this.semesters = semesters;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public List<TimetableEntryDto> classView(Long classId, String view, LocalDate date) {
        Long semesterId = semesters.requireCurrent().getId();
        Map<Long, Substitution> overlay = overlayMap(semesterId, view, date);
        return enrich(entries.findBySemesterIdAndAdminClassId(semesterId, classId), semesterId).stream()
                .map(dto -> apply(dto, overlay.get(dto.id()), semesterId))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<TimetableEntryDto> teacherView(Long teacherId, String view, LocalDate date) {
        Long semesterId = semesters.requireCurrent().getId();
        Map<Long, Substitution> overlay = overlayMap(semesterId, view, date);
        Set<Long> substitutedIn = overlay.values().stream()
                .filter(substitution -> teacherId.equals(substitution.getSubstituteTeacherId()))
                .map(Substitution::getEntryId)
                .collect(Collectors.toCollection(HashSet::new));
        return enrich(entries.findBySemesterId(semesterId), semesterId).stream()
                .filter(dto -> teacherId.equals(dto.teacherId()) || substitutedIn.contains(dto.id()))
                .map(dto -> apply(dto, overlay.get(dto.id()), semesterId))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<TimetableEntryDto> roomView(Long roomId, String view, LocalDate date) {
        Long semesterId = semesters.requireCurrent().getId();
        Map<Long, Substitution> overlay = overlayMap(semesterId, view, date);
        Set<Long> movedIn = overlay.values().stream()
                .filter(substitution -> roomId.equals(substitution.getNewRoomId()))
                .map(Substitution::getEntryId)
                .collect(Collectors.toCollection(HashSet::new));
        return enrich(entries.findBySemesterId(semesterId), semesterId).stream()
                .filter(dto -> roomId.equals(dto.roomId()) || movedIn.contains(dto.id()))
                .map(dto -> apply(dto, overlay.get(dto.id()), semesterId))
                .filter(dto -> Objects.equals(roomId, dto.roomId()))
                .toList();
    }

    @Transactional(readOnly = true)
    public SchoolTimetableDto schoolView(String view, LocalDate date) {
        Long semesterId = semesters.requireCurrent().getId();
        Map<Long, Substitution> overlay = overlayMap(semesterId, view, date);
        List<ClassDto> classes = adminClasses.findBySemesterIdOrderByGradeIdAscNameAsc(semesterId).stream()
                .map(adminClass -> new ClassDto(
                        adminClass.getId(), adminClass.getName(), adminClass.getGradeId(), null,
                        adminClass.getHeadTeacherId(), null, adminClass.getStudentCount(), null))
                .toList();
        List<TimetableEntryDto> all = enrich(entries.findBySemesterId(semesterId), semesterId).stream()
                .map(dto -> apply(dto, overlay.get(dto.id()), semesterId))
                .toList();
        return new SchoolTimetableDto(classes.stream().map(ClassDto::id).toList(), all);
    }

    public boolean canViewClass(String username, boolean canViewAll, boolean canViewClassScope, Long classId) {
        if (canViewAll) {
            return true;
        }
        if (!canViewClassScope) {
            return false;
        }
        AppUser user = users.getByUsername(username);
        if (user.getTeacherId() == null) {
            return false;
        }
        return adminClasses.findById(classId)
                .map(adminClass -> user.getTeacherId().equals(adminClass.getHeadTeacherId()))
                .orElse(false);
    }

    public boolean canViewTeacher(String username, boolean canViewAll, boolean canViewOwn, Long teacherId) {
        if (canViewAll) {
            return true;
        }
        if (!canViewOwn) {
            return false;
        }
        AppUser user = users.getByUsername(username);
        return user.getTeacherId() != null && user.getTeacherId().equals(teacherId);
    }

    @Transactional(readOnly = true)
    public Long semesterId() {
        return semesters.requireCurrent().getId();
    }

    public void requireClassExists(Long classId) {
        if (!adminClasses.existsById(classId)) {
            throw ApiException.notFound("班级不存在: " + classId);
        }
    }

    private Map<Long, Substitution> overlayMap(Long semesterId, String view, LocalDate date) {
        if ("baseline".equalsIgnoreCase(view)) {
            return Map.of();
        }
        // ADR-0008: latest view shows substitutions effective up to the requested day.
        java.time.LocalDate cutoff = date != null ? date : java.time.LocalDate.now();
        java.time.LocalDate semesterStart = semesters.require(semesterId).getStartDate();
        if (cutoff.isBefore(semesterStart)) {
            return Map.of();
        }
        List<Substitution> effective = substitutions.findBySemesterIdAndEffectiveDateBetweenAndStatus(
                semesterId, semesterStart, cutoff, Substitution.EFFECTIVE);
        return effective.stream()
                .sorted(Comparator.comparing(Substitution::getCreatedAt))
                .collect(Collectors.toMap(
                        Substitution::getEntryId, substitution -> substitution, (first, second) -> second));
    }

    private TimetableEntryDto apply(TimetableEntryDto dto, Substitution substitution, Long semesterId) {
        if (substitution == null) {
            return dto;
        }
        Teacher substitute = teachers.findById(substitution.getSubstituteTeacherId()).orElse(null);
        Room room = substitution.getNewRoomId() == null
                ? (dto.roomId() == null ? null : rooms.findById(dto.roomId()).orElse(null))
                : rooms.findById(substitution.getNewRoomId()).orElse(null);
        Long periodId = substitution.getNewPeriodId() != null ? substitution.getNewPeriodId() : dto.periodId();
        int dayOfWeek = substitution.getNewDayOfWeek() != null ? substitution.getNewDayOfWeek() : dto.dayOfWeek();
        int periodNo = substitution.getNewPeriodNo() != null ? substitution.getNewPeriodNo() : dto.periodNo();
        String section = dto.section();
        String periodLabel = "周" + dayOfWeek + "第" + periodNo + "节";
        if (substitution.getNewPeriodId() != null) {
            Period period = periods.findById(substitution.getNewPeriodId()).orElse(null);
            section = period == null ? section : period.getSection().name();
        }
        return new TimetableEntryDto(
                dto.id(),
                dto.classId(),
                dto.className(),
                dto.courseId(),
                dto.courseName(),
                substitution.getSubstituteTeacherId(),
                substitute == null ? dto.teacherName() : substitute.getName(),
                room == null ? null : room.getId(),
                room == null ? null : room.getName(),
                periodId,
                dayOfWeek,
                periodNo,
                section,
                periodLabel,
                true,
                substitution.getSubstituteTeacherId(),
                substitute == null ? null : substitute.getName(),
                "调代生效于 " + substitution.getEffectiveDate());
    }

    private List<TimetableEntryDto> enrich(List<TimetableEntry> source, Long semesterId) {
        Map<Long, AdminClass> classMap = new HashMap<>();
        adminClasses.findBySemesterIdOrderByGradeIdAscNameAsc(semesterId)
                .forEach(adminClass -> classMap.put(adminClass.getId(), adminClass));
        Map<Long, Course> courseMap = new HashMap<>();
        courses.findAll().forEach(course -> courseMap.put(course.getId(), course));
        Map<Long, Teacher> teacherMap = new HashMap<>();
        teachers.findAll().forEach(teacher -> teacherMap.put(teacher.getId(), teacher));
        Map<Long, Room> roomMap = new HashMap<>();
        rooms.findAll().forEach(room -> roomMap.put(room.getId(), room));
        Map<Long, Period> periodMap = new HashMap<>();
        periods.findBySemesterIdOrderByDayOfWeekAscPeriodNoAsc(semesterId)
                .forEach(period -> periodMap.put(period.getId(), period));

        List<TimetableEntryDto> result = new ArrayList<>();
        for (TimetableEntry entry : source) {
            AdminClass adminClass = classMap.get(entry.getAdminClassId());
            Course course = courseMap.get(entry.getCourseId());
            Teacher teacher = teacherMap.get(entry.getTeacherId());
            Room room = entry.getRoomId() == null ? null : roomMap.get(entry.getRoomId());
            Period period = periodMap.get(entry.getPeriodId());
            result.add(new TimetableEntryDto(
                    entry.getId(),
                    entry.getAdminClassId(),
                    adminClass == null ? null : adminClass.getName(),
                    entry.getCourseId(),
                    course == null ? null : course.getName(),
                    entry.getTeacherId(),
                    teacher == null ? null : teacher.getName(),
                    entry.getRoomId(),
                    room == null ? null : room.getName(),
                    entry.getPeriodId(),
                    entry.getDayOfWeek(),
                    entry.getPeriodNo(),
                    period == null ? null : period.getSection().name(),
                    "周" + entry.getDayOfWeek() + "第" + entry.getPeriodNo() + "节",
                    false,
                    null,
                    null,
                    null));
        }
        result.sort(Comparator.comparingInt(TimetableEntryDto::dayOfWeek)
                .thenComparingInt(TimetableEntryDto::periodNo));
        return result;
    }
}
