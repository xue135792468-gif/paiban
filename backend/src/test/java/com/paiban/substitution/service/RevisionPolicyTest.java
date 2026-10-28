package com.paiban.substitution.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.paiban.substitution.domain.Substitution;
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
