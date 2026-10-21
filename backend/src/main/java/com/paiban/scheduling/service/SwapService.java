package com.paiban.scheduling.service;

import com.paiban.common.web.ApiException;
import com.paiban.foundation.domain.Course;
import com.paiban.foundation.domain.CourseRepository;
import com.paiban.foundation.domain.Teacher;
import com.paiban.foundation.domain.TeacherRepository;
import com.paiban.scheduling.domain.DraftLesson;
import com.paiban.scheduling.domain.DraftLessonRepository;
import com.paiban.scheduling.domain.TimetableDraft;
import com.paiban.scheduling.domain.TimetableDraftRepository;
import com.paiban.scheduling.service.DraftValidator.Conflict;
import com.paiban.scheduling.web.SwapDtos.SwapResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Executes the two manual lesson exchanges: intra-class swaps (periods, optionally
 * rooms) and cross-class teacher exchanges (teachers only). The type is derived
 * from the two lessons' classes; a client-supplied type is never trusted.
 */
@Service
public class SwapService {

    public static final String INTRA_CLASS = "INTRA_CLASS";
    public static final String CROSS_CLASS = "CROSS_CLASS";

    private final TimetableDraftRepository drafts;
    private final DraftLessonRepository draftLessons;
    private final CourseRepository courses;
    private final TeacherRepository teachers;
    private final DraftValidator validator;

    public SwapService(
            TimetableDraftRepository drafts,
            DraftLessonRepository draftLessons,
            CourseRepository courses,
            TeacherRepository teachers,
            DraftValidator validator) {
        this.drafts = drafts;
        this.draftLessons = draftLessons;
        this.courses = courses;
        this.teachers = teachers;
        this.validator = validator;
    }

    @Transactional
    public SwapResult swap(Long draftId, Long lessonId, Long targetLessonId, boolean swapRooms) {
        TimetableDraft draft = drafts.findById(draftId)
                .orElseThrow(() -> ApiException.notFound("课表草稿不存在: " + draftId));
        DraftLesson source = requireLesson(draftId, lessonId);
        DraftLesson target = requireLesson(draftId, targetLessonId);
        if (source.getId().equals(target.getId())) {
            throw ApiException.badRequest("不能与同一节课换课");
        }
        String type = Objects.equals(source.getAdminClassId(), target.getAdminClassId())
                ? INTRA_CLASS
                : CROSS_CLASS;

        Long sourcePeriod = source.getPeriodId();
        Long targetPeriod = target.getPeriodId();
        Long sourceRoom = source.getRoomId();
        Long targetRoom = target.getRoomId();
        Long sourceTeacher = source.getTeacherId();
        Long targetTeacher = target.getTeacherId();

        List<DraftValidator.LessonChange> changes;
        if (INTRA_CLASS.equals(type)) {
            changes = List.of(
                    new DraftValidator.LessonChange(
                            source.getId(), targetPeriod, swapRooms ? targetRoom : sourceRoom, sourceTeacher),
                    new DraftValidator.LessonChange(
                            target.getId(), sourcePeriod, swapRooms ? sourceRoom : targetRoom, targetTeacher));
        } else {
            changes = List.of(
                    new DraftValidator.LessonChange(
                            source.getId(), sourcePeriod, sourceRoom, targetTeacher),
                    new DraftValidator.LessonChange(
                            target.getId(), targetPeriod, targetRoom, sourceTeacher));
        }

        List<Conflict> conflicts = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (CROSS_CLASS.equals(type)) {
            checkCrossClassSubjects(source, target, conflicts, warnings);
        }
        DraftValidator.ValidationResult validation = validator.validateChanges(draftId, changes);
        conflicts.addAll(validation.conflicts());
        if (!conflicts.isEmpty()) {
            return new SwapResult(false, false, conflicts.size(), type, conflicts, warnings);
        }

        DraftValidator.LessonChange sourceChange = changes.get(0);
        DraftValidator.LessonChange targetChange = changes.get(1);
        source.setPeriodId(sourceChange.periodId());
        source.setRoomId(sourceChange.roomId());
        source.setTeacherId(sourceChange.teacherId());
        target.setPeriodId(targetChange.periodId());
        target.setRoomId(targetChange.roomId());
        target.setTeacherId(targetChange.teacherId());
        draftLessons.save(source);
        draftLessons.save(target);
        draft.setScoreHard(0);
        return new SwapResult(true, true, 0, type, List.of(), warnings);
    }

    /**
     * Cross-class exchanges must keep both courses on the same subject. Teacher
     * subject is checked against the course the teacher would take over; when
     * either side has no subject on file the check is skipped with a warning.
     */
    private void checkCrossClassSubjects(
            DraftLesson source,
            DraftLesson target,
            List<Conflict> conflicts,
            List<String> warnings) {
        Course sourceCourse = requireCourse(source.getCourseId());
        Course targetCourse = requireCourse(target.getCourseId());
        if (!Objects.equals(normalize(sourceCourse.getSubject()), normalize(targetCourse.getSubject()))) {
            conflicts.add(new Conflict("SUBJECT_MISMATCH",
                    "跨班换人要求两门课程同科目：" + sourceCourse.getName() + " 是「"
                            + displaySubject(sourceCourse.getSubject()) + "」，"
                            + targetCourse.getName() + " 是「"
                            + displaySubject(targetCourse.getSubject()) + "」",
                    source.getId()));
        }
        checkTeacherSubject(
                requireTeacher(source.getTeacherId()), targetCourse, source.getId(), conflicts, warnings);
        checkTeacherSubject(
                requireTeacher(target.getTeacherId()), sourceCourse, target.getId(), conflicts, warnings);
    }

    private void checkTeacherSubject(
            Teacher teacher,
            Course course,
            Long lessonId,
            List<Conflict> conflicts,
            List<String> warnings) {
        String teacherSubject = normalize(teacher.getSubject());
        String courseSubject = normalize(course.getSubject());
        if (teacherSubject == null || courseSubject == null) {
            warnings.add(teacher.getName() + " 的学科未维护，未校验与课程「"
                    + course.getName() + "」的学科一致性");
            return;
        }
        if (!teacherSubject.equals(courseSubject)) {
            conflicts.add(new Conflict("TEACHER_SUBJECT_MISMATCH",
                    teacher.getName() + " 的学科「" + teacher.getSubject().trim()
                            + "」与课程「" + course.getName() + "」的学科「"
                            + course.getSubject().trim() + "」不一致",
                    lessonId));
        }
    }

    private Course requireCourse(Long courseId) {
        return courses.findById(courseId)
                .orElseThrow(() -> ApiException.badRequest("课程不存在: " + courseId));
    }

    private Teacher requireTeacher(Long teacherId) {
        return teachers.findById(teacherId)
                .orElseThrow(() -> ApiException.badRequest("教师不存在: " + teacherId));
    }

    private String normalize(String subject) {
        return subject == null || subject.isBlank() ? null : subject.trim().toLowerCase(Locale.ROOT);
    }

    private String displaySubject(String subject) {
        return subject == null || subject.isBlank() ? "未设置" : subject.trim();
    }

    private DraftLesson requireLesson(Long draftId, Long lessonId) {
        DraftLesson lesson = draftLessons.findById(lessonId)
                .orElseThrow(() -> ApiException.notFound("课节不存在: " + lessonId));
        if (!lesson.getDraftId().equals(draftId)) {
            throw ApiException.badRequest("课节不属于该草稿");
        }
        return lesson;
    }
}
