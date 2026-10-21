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
        requireEditable(draft);
        DraftLesson source = requireLesson(draftId, lessonId);
        DraftLesson target = requireLesson(draftId, targetLessonId);
        if (source.getId().equals(target.getId())) {
            throw ApiException.badRequest("不能与同一节课换课");
        }
        if (source.getPeriodId() == null || target.getPeriodId() == null) {
            throw ApiException.badRequest("未排课的课节不能参与换课");
        }
        String type = Objects.equals(source.getAdminClassId(), target.getAdminClassId())
                ? INTRA_CLASS
                : CROSS_CLASS;
        if (CROSS_CLASS.equals(type) && Objects.equals(source.getTeacherId(), target.getTeacherId())) {
            throw ApiException.badRequest("两位教师相同，无需换课");
        }

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
     * The same {@link SubjectMatch} rule feeds the candidate panel, so a candidate
     * it lists as available is not rejected here (and vice versa).
     */
    private void checkCrossClassSubjects(
            DraftLesson source,
            DraftLesson target,
            List<Conflict> conflicts,
            List<String> warnings) {
        Course sourceCourse = requireCourse(source.getCourseId());
        Course targetCourse = requireCourse(target.getCourseId());
        SubjectMatch.Result courseMatch =
                SubjectMatch.of(sourceCourse.getSubject(), targetCourse.getSubject());
        if (courseMatch == SubjectMatch.Result.MISMATCH) {
            conflicts.add(new Conflict("SUBJECT_MISMATCH",
                    "跨班换人要求两门课程同科目：" + sourceCourse.getName() + " 是「"
                            + SubjectMatch.display(sourceCourse.getSubject()) + "」，"
                            + targetCourse.getName() + " 是「"
                            + SubjectMatch.display(targetCourse.getSubject()) + "」",
                    source.getId()));
        } else if (courseMatch == SubjectMatch.Result.UNKNOWN) {
            warnings.add("课程「" + sourceCourse.getName() + "」与「" + targetCourse.getName()
                    + "」的科目未维护，未校验两门课程的科目一致性");
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
        SubjectMatch.Result result = SubjectMatch.of(teacher.getSubject(), course.getSubject());
        if (result == SubjectMatch.Result.UNKNOWN) {
            warnings.add(teacher.getName() + " 的学科未维护，未校验与课程「"
                    + course.getName() + "」的学科一致性");
            return;
        }
        if (result == SubjectMatch.Result.MISMATCH) {
            conflicts.add(new Conflict("TEACHER_SUBJECT_MISMATCH",
                    teacher.getName() + " 的学科「" + SubjectMatch.display(teacher.getSubject())
                            + "」与课程「" + course.getName() + "」的学科「"
                            + SubjectMatch.display(course.getSubject()) + "」不一致",
                    lessonId));
        }
    }

    /** Only an editable draft can be changed by hand; published ones go through substitution. */
    private void requireEditable(TimetableDraft draft) {
        String status = draft.getStatus();
        if ("DRAFT".equals(status)) {
            return;
        }
        if ("PUBLISHED".equals(status)) {
            throw ApiException.badRequest("课表已发布，发布后请走调代流程");
        }
        if ("ARCHIVED".equals(status)) {
            throw ApiException.badRequest("课表已归档，不能再换课");
        }
        throw ApiException.badRequest("当前课表状态不允许换课");
    }

    private Course requireCourse(Long courseId) {
        return courses.findById(courseId)
                .orElseThrow(() -> ApiException.badRequest("课程不存在: " + courseId));
    }

    private Teacher requireTeacher(Long teacherId) {
        return teachers.findById(teacherId)
                .orElseThrow(() -> ApiException.badRequest("教师不存在: " + teacherId));
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
