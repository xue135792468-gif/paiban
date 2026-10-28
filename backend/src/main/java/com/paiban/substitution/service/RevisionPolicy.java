package com.paiban.substitution.service;

import com.paiban.substitution.domain.Substitution;
import java.util.Objects;

/**
 * 修订链的纯判定（票 04，ADR-0030 的修订关系语义）。不依赖 Spring，
 * 被「修订预览的类型判定」与「代入/代出统计口径」共用，单测钉住同一实现。
 *
 * <p>口径（规格第 7 节）：代入/代出按「当前有效授课安排 vs 基线教师」比较，
 * 不按累计事件行数计数。修订生效后基准行进入 {@code SUPERSEDED} 状态退出
 * 当前有效安排；恢复基线行（代课教师 = 基线教师）对任何一方都净变化为零，
 * 不计入代入/代出——A→B→C 后 A 被代 1、C 代课 1、B 为 0；恢复 A 后均为 0。
 */
public final class RevisionPolicy {

    /** 修订类型：把当前代课人换成另一位教师。 */
    public static final String CHANGE_SUBSTITUTE = "CHANGE_SUBSTITUTE";

    /** 修订类型：恢复基线原任课教师（一次经预览、校验的再次调整，不删除历史）。 */
    public static final String RESTORE_BASELINE = "RESTORE_BASELINE";

    private RevisionPolicy() {}

    /** 修订类型由目标教师与基线教师的关系决定：相同即恢复基线。 */
    public static String revisionKind(Long substituteTeacherId, Long baselineTeacherId) {
        return substituteTeacherId != null && substituteTeacherId.equals(baselineTeacherId)
                ? RESTORE_BASELINE
                : CHANGE_SUBSTITUTE;
    }

    /** 恢复基线只在「当前实际教师 ≠ 基线教师」时有意义；未代课时无从恢复。 */
    public static boolean restorable(Long currentTeacherId, Long baselineTeacherId) {
        return currentTeacherId != null && baselineTeacherId != null
                && !currentTeacherId.equals(baselineTeacherId);
    }

    /**
     * 该生效行是否计入代入/代出：必须是当前生效（EFFECTIVE）的代课覆盖（COVER），
     * 且不是恢复基线行（代课教师与基线原教师相同，净变化为零）。
     */
    public static boolean countsAsCurrentCover(Substitution row) {
        if (row == null || !Substitution.EFFECTIVE.equals(row.getStatus())) {
            return false;
        }
        if (!Substitution.TYPE_COVER.equals(row.getType())) {
            return false;
        }
        return !Objects.equals(row.getSubstituteTeacherId(), row.getOriginalTeacherId());
    }
}
