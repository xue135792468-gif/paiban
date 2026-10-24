package com.paiban.scheduling.constraint;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paiban.scheduling.domain.ConstraintConfig;
import com.paiban.scheduling.domain.ConstraintType;
import com.paiban.scheduling.solver.ConstraintSettings;
import com.paiban.substitution.plan.PlanFixtures;
import com.paiban.substitution.plan.PlanSnapshot;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The four core hard rules, exercised through the published-timetable seam. */
class ConstraintCheckerTest {

    private static final long TEACHER_1 = 11L;
    private static final long TEACHER_2 = 12L;
    private static final long ROOM_1 = 21L;
    private static final long ROOM_2 = 22L;
    private static final long COURSE_1 = 31L;
    private static final long COURSE_2 = 32L;

    @Test
    void teacherCannotBeInTwoClassesAtTheSameTime() {
        PlanSnapshot snapshot = base()
                .lesson(PlanFixtures.CLASS_1, COURSE_1, TEACHER_1, ROOM_1, 1, 1)
                .lesson(PlanFixtures.CLASS_2, COURSE_2, TEACHER_1, ROOM_2, 1, 1)
                .build();

        List<ConstraintViolation> violations = ConstraintChecker.checkHard(
                snapshot.context(), viewsOn(snapshot, 1));

        assertThat(violations).extracting(ConstraintViolation::code).contains("TEACHER_CONFLICT");
    }

    @Test
    void classCannotHaveTwoCoursesInTheSameSlot() {
        PlanSnapshot snapshot = base()
                .lesson(PlanFixtures.CLASS_1, COURSE_1, TEACHER_1, ROOM_1, 1, 1)
                .lesson(PlanFixtures.CLASS_1, COURSE_2, TEACHER_2, ROOM_2, 1, 1)
                .build();

        List<ConstraintViolation> violations = ConstraintChecker.checkHard(
                snapshot.context(), viewsOn(snapshot, 1));

        assertThat(violations).extracting(ConstraintViolation::code).contains("CLASS_CONFLICT");
    }

    @Test
    void roomCannotBeUsedByTwoClassesAtTheSameTime() {
        PlanSnapshot snapshot = base()
                .lesson(PlanFixtures.CLASS_1, COURSE_1, TEACHER_1, ROOM_1, 1, 1)
                .lesson(PlanFixtures.CLASS_2, COURSE_2, TEACHER_2, ROOM_1, 1, 1)
                .build();

        List<ConstraintViolation> violations = ConstraintChecker.checkHard(
                snapshot.context(), viewsOn(snapshot, 1));

        assertThat(violations).extracting(ConstraintViolation::code).contains("ROOM_CONFLICT");
    }

    @Test
    void courseRequiresTheConfiguredRoomType() {
        PlanSnapshot snapshot = PlanFixtures.builder()
                .settings(settings(
                        ConstraintType.TEACHER_CONFLICT,
                        ConstraintType.CLASS_CONFLICT,
                        ConstraintType.ROOM_CONFLICT,
                        ConstraintType.COURSE_REQUIRED_ROOM_TYPE))
                .adminClass(PlanFixtures.CLASS_1, "高一1班", 45, null)
                .teacher(TEACHER_1, "张老师", "化学")
                .course(COURSE_1, "化学实验", "LAB")
                .room(ROOM_1, "普通教室", "NORMAL", 50)
                .lesson(PlanFixtures.CLASS_1, COURSE_1, TEACHER_1, ROOM_1, 1, 1)
                .build();

        List<ConstraintViolation> violations = ConstraintChecker.checkHard(
                snapshot.context(), viewsOn(snapshot, 1));

        assertThat(violations).extracting(ConstraintViolation::code).contains("ROOM_TYPE");
    }

    private static PlanFixtures.Builder base() {
        return PlanFixtures.builder()
                .adminClass(PlanFixtures.CLASS_1, "高一1班", 45, null)
                .adminClass(PlanFixtures.CLASS_2, "高一2班", 45, null)
                .teacher(TEACHER_1, "张老师", "语文")
                .teacher(TEACHER_2, "李老师", "数学")
                .course(COURSE_1, "语文", null)
                .course(COURSE_2, "数学", null)
                .room(ROOM_1, "教室1", "NORMAL", 50)
                .room(ROOM_2, "教室2", "NORMAL", 50);
    }

    private static List<LessonView> viewsOn(PlanSnapshot snapshot, int dayOfWeek) {
        return snapshot.scheduleOn(PlanFixtures.date(dayOfWeek)).lessons().stream()
                .map(lesson -> new LessonView(lesson.entryId(), lesson.classId(), lesson.courseId(),
                        lesson.teacherId(), lesson.roomId(), lesson.periodId(), lesson.studentCount()))
                .toList();
    }

    private static ConstraintSettings settings(ConstraintType... types) {
        List<ConstraintConfig> configs = java.util.Arrays.stream(types).map(type -> {
            ConstraintConfig config = new ConstraintConfig();
            config.setType(type.name());
            config.setEnabled(true);
            config.setHard(true);
            config.setWeight(type.getDefaultWeight());
            config.setParamsJson("{}");
            return config;
        }).toList();
        return ConstraintSettings.from(configs, new ObjectMapper());
    }
}
