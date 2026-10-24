package com.paiban.substitution.plan;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Fixed-seed property test: every cycle the planner produces must pass the
 * independent verifier, and any single tampered move must be rejected.
 */
class SwapPlanPropertyTest {

    private static final int SEEDS = 200;

    @Test
    void everyGeneratedPlanPassesAndEverySingleMoveTamperFails() {
        for (int seed = 0; seed < SEEDS; seed++) {
            Random random = new Random(seed);
            PlanSnapshot snapshot = randomSnapshot();
            int day = 1 + random.nextInt(5);
            int sourceNo = 1 + random.nextInt(3);
            int targetNo = sourceNo + 1 + random.nextInt(3);
            long classId = 1 + random.nextInt(2);
            long sourceEntry = snapshot.baseline().stream()
                    .filter(lesson -> lesson.classId() == classId
                            && lesson.dayOfWeek() == day && lesson.periodNo() == sourceNo)
                    .findFirst().orElseThrow().entryId();

            PlanningResult result = SwapPlanner.plan(
                    snapshot,
                    new SwapPlanner.Request(classId, sourceEntry, PlanFixtures.date(day),
                            PlanFixtures.periodId(day, targetNo)),
                    new SwapPlanner.Options(7, 6, RankingPreset.MIN_IMPACT, 5_000, 30));

            assertThat(result.candidates()).as("seed " + seed).isNotEmpty();
            for (CandidatePlan plan : result.candidates()) {
                PlanVerifier.Result verification = PlanVerifier.verify(
                        snapshot, plan.moves(), PlanVerifier.Kind.SWAP, plan.softPenalty());
                assertThat(verification.issues()).as("seed " + seed).isEmpty();
                assertThat(verification.softPenalty()).as("seed " + seed).isEqualTo(plan.softPenalty());

                List<PlanMove> tampered = tamper(plan, day, random);
                assertThat(PlanVerifier.verify(snapshot, tampered, PlanVerifier.Kind.SWAP, -1).passed())
                        .as("seed " + seed)
                        .isFalse();
            }
        }
    }

    private static List<PlanMove> tamper(CandidatePlan plan, int day, Random random) {
        Set<Long> used = new HashSet<>();
        plan.moves().forEach(move -> {
            used.add(move.fromPeriodId());
            used.add(move.toPeriodId());
        });
        List<Long> free = new ArrayList<>();
        for (int periodNo = 1; periodNo <= 6; periodNo++) {
            long id = PlanFixtures.periodId(day, periodNo);
            if (!used.contains(id)) {
                free.add(id);
            }
        }
        long target = free.get(random.nextInt(free.size()));
        int index = random.nextInt(plan.moves().size());
        PlanMove move = plan.moves().get(index);
        List<PlanMove> tampered = new ArrayList<>(plan.moves());
        tampered.set(index, new PlanMove(move.entryId(), move.classId(), move.courseId(),
                move.teacherId(), move.roomId(), move.fromPeriodId(), move.fromDayOfWeek(),
                move.fromPeriodNo(), move.fromDate(), target, day,
                (int) (target % 100), move.toDate(), move.order()));
        return tampered;
    }

    private static PlanSnapshot randomSnapshot() {
        PlanFixtures.Builder builder = PlanFixtures.builder().periodsPerDay(6);
        for (long classId = 1; classId <= 2; classId++) {
            builder.adminClass(classId, "班级" + classId, 40, null);
            for (int day = 1; day <= 5; day++) {
                for (int periodNo = 1; periodNo <= 6; periodNo++) {
                    long teacherId = classId * 100 + day * 10 + periodNo;
                    builder.teacher(teacherId, "老师" + teacherId, "科目" + classId);
                    builder.course(teacherId, "课程" + teacherId, null);
                    builder.room(teacherId, "教室" + teacherId, "NORMAL", 50);
                    builder.lesson(classId, teacherId, teacherId, teacherId, day, periodNo);
                }
            }
        }
        return builder.build();
    }
}
