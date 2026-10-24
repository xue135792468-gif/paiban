package com.paiban.substitution.plan;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlanVerifierTest {

    private static final long CLASS_1 = PlanFixtures.CLASS_1;

    @Test
    void acceptsAPlanProducedByThePlanner() {
        PlanSnapshot snapshot = twoTeacherSnapshot();
        PlannerResult result = plan(snapshot);
        CandidatePlan plan = result.result().candidates().get(0);

        PlanVerifier.Result verification = PlanVerifier.verify(
                snapshot, plan.moves(), PlanVerifier.Kind.SWAP, plan.softPenalty());

        assertThat(verification.issues()).isEmpty();
        assertThat(verification.passed()).isTrue();
    }

    @Test
    void rejectsASwapThatChangesTheTeacher() {
        PlanSnapshot snapshot = twoTeacherSnapshot();
        CandidatePlan plan = plan(snapshot).result().candidates().get(0);
        PlanMove original = plan.moves().get(0);
        List<PlanMove> tampered = new ArrayList<>(plan.moves());
        tampered.set(0, new PlanMove(original.entryId(), original.classId(), original.courseId(),
                999L, original.roomId(), original.fromPeriodId(), original.fromDayOfWeek(),
                original.fromPeriodNo(), original.fromDate(), original.toPeriodId(),
                original.toDayOfWeek(), original.toPeriodNo(), original.toDate(), original.order()));

        PlanVerifier.Result verification = PlanVerifier.verify(
                snapshot, tampered, PlanVerifier.Kind.SWAP, -1);

        assertThat(verification.passed()).isFalse();
        assertThat(verification.issues()).extracting(PlanVerifier.Issue::code)
                .contains("TEACHER_CHANGED");
    }

    @Test
    void rejectsAMoveThatDoubleBooksAClassSlot() {
        PlanSnapshot snapshot = twoTeacherSnapshot();
        CandidatePlan plan = plan(snapshot).result().candidates().get(0);
        PlanMove original = plan.moves().get(0);
        List<PlanMove> tampered = new ArrayList<>(plan.moves());
        long otherPeriod = PlanFixtures.periodId(1, 3);
        tampered.set(0, new PlanMove(original.entryId(), original.classId(), original.courseId(),
                original.teacherId(), original.roomId(), original.fromPeriodId(), original.fromDayOfWeek(),
                original.fromPeriodNo(), original.fromDate(), otherPeriod, 1, 3,
                original.toDate(), original.order()));

        PlanVerifier.Result verification = PlanVerifier.verify(
                snapshot, tampered, PlanVerifier.Kind.SWAP, -1);

        assertThat(verification.passed()).isFalse();
        assertThat(verification.issues()).extracting(PlanVerifier.Issue::code)
                .contains("CLASS_DOUBLE_BOOKED");
    }

    @Test
    void rejectsWhenTheSolverReportedSoftScoreDoesNotMatch() {
        PlanSnapshot snapshot = twoTeacherSnapshot();
        CandidatePlan plan = plan(snapshot).result().candidates().get(0);

        PlanVerifier.Result verification = PlanVerifier.verify(
                snapshot, plan.moves(), PlanVerifier.Kind.SWAP, plan.softPenalty() + 7);

        assertThat(verification.passed()).isFalse();
        assertThat(verification.issues()).extracting(PlanVerifier.Issue::code)
                .contains("SOFT_SCORE_MISMATCH");
    }

    @Test
    void rejectsWhenTheTargetRoomIsAlreadyOccupied() {
        PlanFixtures.Builder builder = PlanFixtures.builder()
                .adminClass(CLASS_1, "高一1班", 45, null)
                .adminClass(2L, "高一2班", 45, null)
                .teacher(1, "张老师", "语文")
                .teacher(2, "李老师", "数学")
                .teacher(3, "王老师", "英语")
                .course(1, "语文", null)
                .course(2, "数学", null)
                .course(3, "英语", null)
                .room(1, "教室1", "NORMAL", 50)
                .room(2, "教室2", "NORMAL", 50)
                .room(9, "冲突教室", "NORMAL", 50)
                .lesson(CLASS_1, 1, 1, 1L, 1, 1)
                .lesson(CLASS_1, 2, 2, 2L, 1, 2)
                .lesson(2L, 3, 3, 9L, 1, 2);
        PlanSnapshot snapshot = builder.build();
        CandidatePlan plan = plan(snapshot).result().candidates().get(0);
        PlanMove original = plan.moves().get(0);
        List<PlanMove> tampered = new ArrayList<>(plan.moves());
        tampered.set(0, new PlanMove(original.entryId(), original.classId(), original.courseId(),
                original.teacherId(), 9L, original.fromPeriodId(), original.fromDayOfWeek(),
                original.fromPeriodNo(), original.fromDate(), original.toPeriodId(),
                original.toDayOfWeek(), original.toPeriodNo(), original.toDate(), original.order()));

        PlanVerifier.Result verification = PlanVerifier.verify(
                snapshot, tampered, PlanVerifier.Kind.SWAP, -1);

        assertThat(verification.passed()).isFalse();
        assertThat(verification.issues()).extracting(PlanVerifier.Issue::code)
                .contains("ROOM_DOUBLE_BOOKED");
    }

    @Test
    void rejectsWhenADateLosesALesson() {
        PlanSnapshot snapshot = twoTeacherSnapshot();
        CandidatePlan plan = plan(snapshot).result().candidates().get(0);
        PlanMove original = plan.moves().get(0);
        List<PlanMove> tampered = new ArrayList<>(plan.moves());
        tampered.set(0, new PlanMove(original.entryId(), original.classId(), original.courseId(),
                original.teacherId(), original.roomId(), original.fromPeriodId(), original.fromDayOfWeek(),
                original.fromPeriodNo(), original.fromDate(), original.toPeriodId(),
                original.toDayOfWeek(), original.toPeriodNo(), original.toDate().plusDays(1),
                original.order()));

        PlanVerifier.Result verification = PlanVerifier.verify(
                snapshot, tampered, PlanVerifier.Kind.SWAP, -1);

        assertThat(verification.passed()).isFalse();
        assertThat(verification.issues()).extracting(PlanVerifier.Issue::code)
                .contains("CLASS_DAY_COUNT_CHANGED");
    }

    @Test
    void verifyDiffRejectsAddedOrRemovedEntries() {
        PlanSnapshot before = twoTeacherSnapshot();
        PlanFixtures.Builder builder = PlanFixtures.builder()
                .adminClass(CLASS_1, "高一1班", 45, null)
                .teacher(1, "张老师", "语文")
                .teacher(2, "李老师", "数学")
                .course(1, "语文", null)
                .course(2, "数学", null)
                .room(1, "教室1", "NORMAL", 50)
                .room(2, "教室2", "NORMAL", 50)
                .lesson(CLASS_1, 1, 1, 1L, 1, 1)
                .lesson(CLASS_1, 2, 2, 2L, 1, 2)
                .lesson(CLASS_1, 1, 1, 1L, 1, 3)
                .lesson(CLASS_1, 2, 2, 2L, 1, 4);
        PlanSnapshot after = builder.build();

        PlanVerifier.Result verification = PlanVerifier.verifyDiff(before, after);

        assertThat(verification.passed()).isFalse();
        assertThat(verification.issues()).extracting(PlanVerifier.Issue::code)
                .contains("ENTRY_ADDED");
    }

    @Test
    void verifiesTwoThousandLessonsUnderOneHundredMilliseconds() {
        PlanFixtures.Builder builder = PlanFixtures.builder().periodsPerDay(10);
        for (long classId = 1; classId <= 200; classId++) {
            builder.adminClass(classId, "班级" + classId, 40, null);
            for (int periodNo = 1; periodNo <= 10; periodNo++) {
                long id = classId * 1000 + periodNo;
                builder.teacher(id, "老师" + id, "科目");
                builder.course(id, "课程" + id, null);
                builder.room(id, "教室" + id, "NORMAL", 50);
                builder.lesson(classId, id, id, id, 1, periodNo);
            }
        }
        PlanSnapshot snapshot = builder.build();
        List<com.paiban.scheduling.constraint.LessonView> views = snapshot
                .scheduleOn(PlanFixtures.date(1)).lessons().stream()
                .map(lesson -> new com.paiban.scheduling.constraint.LessonView(
                        lesson.entryId(), lesson.classId(), lesson.courseId(), lesson.teacherId(),
                        lesson.roomId(), lesson.periodId(), lesson.studentCount()))
                .toList();
        assertThat(views).hasSize(2000);

        com.paiban.scheduling.constraint.ConstraintChecker.checkHardFast(
                snapshot.context(), views);
        long best = Long.MAX_VALUE;
        for (int run = 0; run < 5; run++) {
            long start = System.nanoTime();
            com.paiban.scheduling.constraint.ConstraintChecker.checkHardFast(
                    snapshot.context(), views);
            best = Math.min(best, System.nanoTime() - start);
        }
        assertThat(best / 1_000_000).isLessThan(100);
    }

    private record PlannerResult(PlanningResult result) {}

    private static PlannerResult plan(PlanSnapshot snapshot) {
        long first = snapshot.baseline().stream()
                .filter(lesson -> lesson.classId() == CLASS_1 && lesson.periodNo() == 1)
                .findFirst().orElseThrow().entryId();
        return new PlannerResult(SwapPlanner.plan(
                snapshot,
                new SwapPlanner.Request(CLASS_1, first, PlanFixtures.date(1),
                        PlanFixtures.periodId(1, 2)),
                SwapPlanner.Options.defaults()));
    }

    private static PlanSnapshot twoTeacherSnapshot() {
        PlanFixtures.Builder builder = PlanFixtures.builder()
                .adminClass(CLASS_1, "高一1班", 45, null)
                .teacher(1, "张老师", "语文")
                .teacher(2, "李老师", "数学")
                .course(1, "语文", null)
                .course(2, "数学", null)
                .room(1, "教室1", "NORMAL", 50)
                .room(2, "教室2", "NORMAL", 50)
                .lesson(CLASS_1, 1, 1, 1L, 1, 1)
                .lesson(CLASS_1, 2, 2, 2L, 1, 2)
                .lesson(CLASS_1, 1, 1, 1L, 1, 3);
        return builder.build();
    }
}
