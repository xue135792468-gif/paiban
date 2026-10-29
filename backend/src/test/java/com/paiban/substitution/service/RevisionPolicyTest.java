package com.paiban.substitution.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.paiban.substitution.domain.Substitution;
import com.paiban.substitution.plan.PlanMove;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 修订状态机的纯判定（票 04，规格第 6/7 节）：
 * <ul>
 *   <li>修订类型：目标教师 = 基线教师 → 恢复基线；否则为更换代课人。</li>
 *   <li>统计口径：只有「当前生效、非恢复行」的代课覆盖才计入代入/代出——
 *       A→B→C 后 A 被代 1、C 代课 1、B 不计；恢复 A 后代入/代出均为 0。</li>
 * </ul>
 * 不依赖 Spring：这条规则同时被统计查询与修订预览使用，单测钉住语义。
 */
class RevisionPolicyTest {

    @Test
    void swapRevisionKindSeparatesFreshRescheduleFromRevision() {
        // 票 07：管理调课的修订类别要能区分「新调课」与「修订调课」——
        // 首次（无基准申请）是新调课；以当前生效安排为基准的再次调课是修订调课。
        assertThat(RevisionPolicy.swapRevisionKind(null))
                .isEqualTo(RevisionPolicy.RESCHEDULE);
        assertThat(RevisionPolicy.swapRevisionKind(42L))
                .isEqualTo(RevisionPolicy.RESCHEDULE_REVISION);
    }

    @Test
    void baselineSourceDateInheritsTheEffectiveSwapRowOfTheSameInstance() {
        // 票 07 净差行不变量：SWAP 生效行的 lessonDate 恒为实例的基线来源日。
        // 再次调课（from = 当前落点 周五）必须继承首次 SWAP 行的基线来源日（周三），
        // 这样批准后基准行转 SUPERSEDED 时，「来源日移出」的语义由净差行接管。
        Substitution baseRow = swapRow(LocalDate.of(2025, 9, 3), LocalDate.of(2025, 9, 5));
        PlanMove revision = move(LocalDate.of(2025, 9, 5));

        assertThat(RevisionPolicy.baselineSourceDate(List.of(baseRow), revision))
                .isEqualTo(LocalDate.of(2025, 9, 3));

        // 当前实际 = 基线槽（没有触及该日期的 SWAP 行）：来源日就是本次的 from。
        assertThat(RevisionPolicy.baselineSourceDate(List.of(baseRow),
                move(LocalDate.of(2025, 9, 10)))).isEqualTo(LocalDate.of(2025, 9, 10));
        assertThat(RevisionPolicy.baselineSourceDate(List.of(), move(LocalDate.of(2025, 9, 10))))
                .isEqualTo(LocalDate.of(2025, 9, 10));
    }

    @Test
    void moveThatLandsBackOnTheBaselineSlotIsNetZero() {
        // 恢复基线环：落点 = 基线来源日 + 基线课节（helper 的 move 落在周三 301）→ 净差为零。
        PlanMove restore = move(LocalDate.of(2025, 9, 5));
        assertThat(RevisionPolicy.restoresBaseline(restore,
                LocalDate.of(2025, 9, 3), 301L)).isTrue();
        // 落点与基线日期或课节不同：净差非零，照常写行。
        assertThat(RevisionPolicy.restoresBaseline(restore,
                LocalDate.of(2025, 9, 5), 301L)).isFalse();
        assertThat(RevisionPolicy.restoresBaseline(restore,
                LocalDate.of(2025, 9, 3), 302L)).isFalse();
    }

    private static Substitution swapRow(LocalDate lessonDate, LocalDate targetLessonDate) {
        Substitution row = new Substitution();
        row.setStatus(Substitution.EFFECTIVE);
        row.setType(Substitution.TYPE_SWAP);
        row.setLessonDate(lessonDate);
        row.setTargetLessonDate(targetLessonDate);
        return row;
    }

    private static PlanMove move(LocalDate fromDate) {
        return new PlanMove(10L, 1L, 1L, 1L, null,
                501L, 5, 1, fromDate, 301L, 3, 1, LocalDate.of(2025, 9, 3), 0);
    }

    @Test
    void substituteEqualToBaselineTeacherMeansRestore() {
        assertThat(RevisionPolicy.revisionKind(7L, 7L))
                .isEqualTo(RevisionPolicy.RESTORE_BASELINE);
        assertThat(RevisionPolicy.revisionKind(8L, 7L))
                .isEqualTo(RevisionPolicy.CHANGE_SUBSTITUTE);
        assertThat(RevisionPolicy.revisionKind(null, 7L))
                .isEqualTo(RevisionPolicy.CHANGE_SUBSTITUTE);
    }

    @Test
    void restoreIsPossibleOnlyWhenCurrentTeacherDiffersFromBaseline() {
        assertThat(RevisionPolicy.restorable(5L, 7L)).isTrue();
        assertThat(RevisionPolicy.restorable(7L, 7L)).isFalse();
    }

    @Test
    void onlyCurrentEffectiveNonRestoreCoversCountForStats() {
        // A→B 的普通代课生效行：A 被代、B 代课，各计 1。
        Substitution cover = row(Substitution.EFFECTIVE, 10L, 20L);
        assertThat(RevisionPolicy.countsAsCurrentCover(cover)).isTrue();

        // 恢复基线行（代课教师 = 基线教师 = 原教师字段）：不计入任何一方。
        Substitution restore = row(Substitution.EFFECTIVE, 10L, 10L);
        assertThat(RevisionPolicy.countsAsCurrentCover(restore)).isFalse();

        // 被后续修订替代的旧行：退出当前有效安排，不再计数。
        Substitution superseded = row(Substitution.SUPERSEDED, 10L, 20L);
        assertThat(RevisionPolicy.countsAsCurrentCover(superseded)).isFalse();

        // 调课行不属于代入/代出口径（调课与代课口径分离）。
        Substitution swap = row(Substitution.EFFECTIVE, 10L, 10L);
        swap.setType(Substitution.TYPE_SWAP);
        assertThat(RevisionPolicy.countsAsCurrentCover(swap)).isFalse();
    }

    private static Substitution row(String status, Long originalTeacherId, Long substituteTeacherId) {
        Substitution substitution = new Substitution();
        substitution.setStatus(status);
        substitution.setType(Substitution.TYPE_COVER);
        substitution.setOriginalTeacherId(originalTeacherId);
        substitution.setSubstituteTeacherId(substituteTeacherId);
        return substitution;
    }
}
