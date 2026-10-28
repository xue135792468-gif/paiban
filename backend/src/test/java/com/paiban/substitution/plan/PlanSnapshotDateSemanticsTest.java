package com.paiban.substitution.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.paiban.substitution.plan.PlanSnapshot.DaySchedule;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * 授课实例的日期语义（ADR-0030）：新格式生效行只作用于所指授课日期，
 * 旧格式历史行保持「自生效日起持续覆盖」。求解快照、独立校验器与只读 CLI
 * 都经由 {@link PlanSnapshot#scheduleOn(LocalDate)} 取日课表，这里钉住三者的共同语义。
 */
class PlanSnapshotDateSemanticsTest {

    private static final LocalDate MONDAY = PlanFixtures.MONDAY;
    private static final long CLASS_1 = PlanFixtures.CLASS_1;

    @Test
    void datedCoverAppliesOnlyOnItsLessonDate() {
        long entryId = PlanFixtures.builder()
                .adminClass(CLASS_1, "高一1班", 45, null)
                .teacher(1, "张老师", "语文")
                .teacher(2, "李老师", "数学")
                .course(1, "语文", null)
                .room(1, "教室1", "NORMAL", 50)
                .lesson(CLASS_1, 1, 1, 1L, 1, 1)
                .build().baseline().get(0).entryId();
        PlanSnapshot snapshot = PlanFixtures.builder()
                .adminClass(CLASS_1, "高一1班", 45, null)
                .teacher(1, "张老师", "语文")
                .teacher(2, "李老师", "数学")
                .course(1, "语文", null)
                .room(1, "教室1", "NORMAL", 50)
                .lesson(CLASS_1, 1, 1, 1L, 1, 1)
                .datedSubstitution(entryId, MONDAY, null, null, 1, 1, 1L, 2L, "COVER")
                .build();

        // 授课日当天：代课教师接替。
        DaySchedule lessonDay = snapshot.scheduleOn(MONDAY);
        assertThat(lessonDay.lessons()).hasSize(1);
        assertThat(lessonDay.lessons().get(0).teacherId()).isEqualTo(2L);

        // 下周同一槽位：恢复常规安排，一次性代课不延续。
        DaySchedule nextWeek = snapshot.scheduleOn(MONDAY.plusDays(7));
        assertThat(nextWeek.lessons()).hasSize(1);
        assertThat(nextWeek.lessons().get(0).teacherId()).isEqualTo(1L);
    }

    @Test
    void datedSwapMovesOutOnTheSourceDateAndAppearsOnTheTargetDate() {
        long entryId = PlanFixtures.builder()
                .adminClass(CLASS_1, "高一1班", 45, null)
                .teacher(1, "张老师", "语文")
                .course(1, "语文", null)
                .room(1, "教室1", "NORMAL", 50)
                .lesson(CLASS_1, 1, 1, 1L, 1, 1)
                .build().baseline().get(0).entryId();
        // 周一第 1 节移到周二第 2 节（同周跨日）。
        PlanSnapshot snapshot = PlanFixtures.builder()
                .adminClass(CLASS_1, "高一1班", 45, null)
                .teacher(1, "张老师", "语文")
                .course(1, "语文", null)
                .room(1, "教室1", "NORMAL", 50)
                .lesson(CLASS_1, 1, 1, 1L, 1, 1)
                .datedSubstitution(entryId, MONDAY, MONDAY.plusDays(1),
                        PlanFixtures.periodId(2, 2), 2, 2, 1L, 1L, "SWAP")
                .build();

        // 来源日：原槽被移空。
        assertThat(snapshot.scheduleOn(MONDAY).lessons()).isEmpty();
        // 落点日：课出现在目标槽，教师跟课走。
        DaySchedule targetDay = snapshot.scheduleOn(MONDAY.plusDays(1));
        assertThat(targetDay.lessons()).hasSize(1);
        assertThat(targetDay.lessons().get(0).entryId()).isEqualTo(entryId);
        assertThat(targetDay.lessons().get(0).periodId()).isEqualTo(PlanFixtures.periodId(2, 2));
        assertThat(targetDay.lessons().get(0).teacherId()).isEqualTo(1L);
        // 下周同一基线槽位：独立的常规实例，不受本次调课影响。
        DaySchedule nextWeek = snapshot.scheduleOn(MONDAY.plusDays(7));
        assertThat(nextWeek.lessons()).hasSize(1);
        assertThat(nextWeek.lessons().get(0).periodId()).isEqualTo(PlanFixtures.periodId(1, 1));
    }

    @Test
    void sameSlotInDifferentWeeksResolvesToItsOwnInstance() {
        long entryId = PlanFixtures.builder()
                .adminClass(CLASS_1, "高一1班", 45, null)
                .teacher(1, "张老师", "语文")
                .course(1, "语文", null)
                .room(1, "教室1", "NORMAL", 50)
                .lesson(CLASS_1, 1, 1, 1L, 1, 1)
                .build().baseline().get(0).entryId();
        PlanSnapshot snapshot = PlanFixtures.builder()
                .adminClass(CLASS_1, "高一1班", 45, null)
                .teacher(1, "张老师", "语文")
                .teacher(2, "李老师", "数学")
                .teacher(3, "王老师", "英语")
                .course(1, "语文", null)
                .room(1, "教室1", "NORMAL", 50)
                .lesson(CLASS_1, 1, 1, 1L, 1, 1)
                .datedSubstitution(entryId, MONDAY, null, null, 1, 1, 1L, 2L, "COVER")
                .datedSubstitution(entryId, MONDAY.plusDays(7), null, null, 1, 1, 1L, 3L, "COVER")
                .build();

        // 同一基线槽位在两个不同周的日期各自命中自己的实例，互不覆盖。
        assertThat(snapshot.scheduleOn(MONDAY).lessons().get(0).teacherId()).isEqualTo(2L);
        assertThat(snapshot.scheduleOn(MONDAY.plusDays(7)).lessons().get(0).teacherId()).isEqualTo(3L);
        // 第三周：没有任何实例命中，回到基线。
        assertThat(snapshot.scheduleOn(MONDAY.plusDays(14)).lessons().get(0).teacherId()).isEqualTo(1L);
    }

    @Test
    void legacySubstitutionKeepsOverridingFollowingWeeks() {
        long entryId = PlanFixtures.builder()
                .adminClass(CLASS_1, "高一1班", 45, null)
                .teacher(1, "张老师", "语文")
                .course(1, "语文", null)
                .room(1, "教室1", "NORMAL", 50)
                .lesson(CLASS_1, 1, 1, 1L, 1, 1)
                .build().baseline().get(0).entryId();
        PlanSnapshot snapshot = PlanFixtures.builder()
                .adminClass(CLASS_1, "高一1班", 45, null)
                .teacher(1, "张老师", "语文")
                .teacher(2, "李老师", "数学")
                .course(1, "语文", null)
                .room(1, "教室1", "NORMAL", 50)
                .lesson(CLASS_1, 1, 1, 1L, 1, 1)
                // 旧格式历史行（lessonDate 为空）：行为必须与改动前一致。
                .substitution(entryId, MONDAY, null, 1, 1, 1L, 2L, "COVER")
                .build();

        assertThat(snapshot.scheduleOn(MONDAY).lessons().get(0).teacherId()).isEqualTo(2L);
        assertThat(snapshot.scheduleOn(MONDAY.plusDays(7)).lessons().get(0).teacherId()).isEqualTo(2L);
        assertThat(snapshot.scheduleOn(MONDAY.plusDays(28)).lessons().get(0).teacherId()).isEqualTo(2L);
    }
}
