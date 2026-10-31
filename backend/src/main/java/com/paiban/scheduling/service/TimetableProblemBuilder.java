package com.paiban.scheduling.service;

import com.paiban.foundation.domain.Period;
import com.paiban.foundation.domain.PeriodRepository;
import com.paiban.foundation.domain.PeriodSection;
import com.paiban.foundation.domain.Room;
import com.paiban.foundation.domain.RoomRepository;
import com.paiban.foundation.domain.Semester;
import com.paiban.foundation.domain.SemesterRepository;
import com.paiban.foundation.service.FoundationService;
import com.paiban.foundation.web.FoundationDtos.ClassDto;
import com.paiban.foundation.web.FoundationDtos.CourseDto;
import com.paiban.foundation.web.FoundationDtos.TeacherDto;
import com.paiban.scheduling.domain.Assignment;
import com.paiban.scheduling.domain.AssignmentRepository;
import com.paiban.scheduling.solver.Lesson;
import com.paiban.scheduling.solver.PeriodSlot;
import com.paiban.scheduling.solver.RoomSlot;
import com.paiban.scheduling.solver.TimetableSolution;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** Builds the immutable solver problem from persisted semester data. */
@Service
public class TimetableProblemBuilder {

    private final PeriodRepository periodRepository;
    private final RoomRepository roomRepository;
    private final AssignmentRepository assignmentRepository;
    private final SemesterRepository semesterRepository;
    private final FoundationService foundation;

    public TimetableProblemBuilder(
            PeriodRepository periodRepository,
            RoomRepository roomRepository,
            AssignmentRepository assignmentRepository,
            SemesterRepository semesterRepository,
            FoundationService foundation) {
        this.periodRepository = periodRepository;
        this.roomRepository = roomRepository;
        this.assignmentRepository = assignmentRepository;
        this.semesterRepository = semesterRepository;
        this.foundation = foundation;
    }

    @Transactional(readOnly = true)
    public TimetableSolution build(Long semesterId) {
        List<Period> periods = periodRepository.findBySemesterIdOrderByDayOfWeekAscPeriodNoAsc(semesterId);
        Map<Integer, List<Period>> byDay = new HashMap<>();
        periods.forEach(period -> byDay.computeIfAbsent(period.getDayOfWeek(), key -> new ArrayList<>()).add(period));

        Map<Integer, Integer> firstPeriod = new HashMap<>();
        Map<Integer, Integer> lastPeriod = new HashMap<>();
        Map<Integer, Integer> firstAfternoon = new HashMap<>();
        byDay.forEach((day, dayPeriods) -> {
            firstPeriod.put(day, dayPeriods.stream().mapToInt(Period::getPeriodNo).min().orElse(0));
            lastPeriod.put(day, dayPeriods.stream().mapToInt(Period::getPeriodNo).max().orElse(0));
            dayPeriods.stream()
                    .filter(period -> period.getSection() == PeriodSection.AFTERNOON)
                    .mapToInt(Period::getPeriodNo)
                    .min()
                    .ifPresent(value -> firstAfternoon.put(day, value));
        });

        List<PeriodSlot> periodSlots = periods.stream()
                .map(period -> new PeriodSlot(
                        period.getId(),
                        period.getDayOfWeek(),
                        period.getPeriodNo(),
                        period.getSection(),
                        period.getPeriodNo() == firstPeriod.get(period.getDayOfWeek()),
                        period.getPeriodNo() == lastPeriod.get(period.getDayOfWeek()),
                        firstAfternoon.getOrDefault(period.getDayOfWeek(), -1) == period.getPeriodNo()))
                .sorted(Comparator.comparingInt(PeriodSlot::getDayOfWeek)
                        .thenComparingInt(PeriodSlot::getPeriodNo))
                .toList();

        List<RoomSlot> roomSlots = roomRepository.findAllByOrderByNameAsc().stream()
                .map(room -> new RoomSlot(room.getId(), room.getName(), room.getType(), room.getCapacity()))
                .toList();

        Map<Long, ClassDto> classes = foundation.classesById(semesterId);
        Map<Long, CourseDto> courses = foundation.coursesById();
        Map<Long, TeacherDto> teachers = foundation.teachersById();
        Map<Long, RoomSlot> fixedRooms = fixedRooms(semesterId, classes, roomSlots);

        List<Lesson> lessons = new ArrayList<>();
        long lessonId = 1;
        for (Assignment assignment : assignmentRepository.findBySemesterId(semesterId)) {
            ClassDto adminClass = classes.get(assignment.getAdminClassId());
            CourseDto course = courses.get(assignment.getCourseId());
            TeacherDto teacher = teachers.get(assignment.getTeacherId());
            int studentCount = adminClass == null ? 45 : adminClass.studentCount();
            String requiredRoomType = course == null ? null : course.requiredRoomType();
            boolean specialRoom = StringUtils.hasText(requiredRoomType);
            RoomSlot fixedRoom = specialRoom ? null : fixedRooms.get(assignment.getAdminClassId());
            for (int index = 0; index < assignment.getWeeklyLessons(); index++) {
                Lesson lesson = new Lesson(
                        lessonId++,
                        assignment.getId(),
                        assignment.getCourseId(),
                        course == null ? null : course.name(),
                        assignment.getTeacherId(),
                        assignment.getAdminClassId(),
                        adminClass == null ? null : adminClass.headTeacherId(),
                        teacher == null ? null : teacher.subject(),
                        requiredRoomType,
                        course != null && course.allowConsecutive(),
                        studentCount,
                        index);
                lesson.setFixedRoom(fixedRoom);
                if (fixedRoom != null) {
                    lesson.setRoom(fixedRoom);
                }
                lessons.add(lesson);
            }
        }
        return new TimetableSolution(lessons, periodSlots, roomSlots);
    }

    /**
     * Resolves each class's own ordinary classroom with the same deterministic name the
     * room-fill action uses ({@code 学期-年级班名-固定教室}). The solver then keeps the
     * lesson in that room instead of scattering a class over every interchangeable room.
     * Bad data (missing, too small, shared room) yields no pin, so the room stays free
     * rather than making the whole problem infeasible.
     */
    private Map<Long, RoomSlot> fixedRooms(
            Long semesterId, Map<Long, ClassDto> classes, List<RoomSlot> roomSlots) {
        Semester semester = semesterRepository.findById(semesterId).orElse(null);
        if (semester == null) {
            return Map.of();
        }
        Map<Long, RoomSlot> roomById = new HashMap<>();
        roomSlots.forEach(room -> roomById.put(room.getId(), room));

        Map<Long, RoomFillPlanner.ExistingRoom> planned = RoomFillPlanner.fixedRooms(
                semester.getName(),
                classes.values().stream()
                        .map(adminClass -> new RoomFillPlanner.ClassInfo(
                                adminClass.id(), adminClass.name(), adminClass.gradeName(),
                                adminClass.studentCount()))
                        .toList(),
                roomSlots.stream()
                        .map(room -> new RoomFillPlanner.ExistingRoom(
                                room.getId(), room.getName(), room.getType(), room.getCapacity()))
                        .toList());

        Map<Long, RoomSlot> fixed = new HashMap<>();
        planned.forEach((classId, room) -> {
            RoomSlot slot = roomById.get(room.id());
            if (slot != null) {
                fixed.put(classId, slot);
            }
        });
        return fixed;
    }
}
