package com.paiban.substitution.service;

import com.paiban.substitution.domain.Substitution;
import java.util.List;
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

    /**
     * 修订类型（票 07）：管理调课的首次移动（实例当前实际安排 = 基线，没有基准申请）。
     * 与修订调课分开建档，历史里能区分「第一次把它挪走」与「把已挪走的课再挪一次」。
     */
    public static final String RESCHEDULE = "RESCHEDULE";

    /** 修订类型（票 07）：以当前生效安排为基准的再次调课（调过课再调、代课后调课）。 */
    public static final String RESCHEDULE_REVISION = "RESCHEDULE_REVISION";

    private RevisionPolicy() {}

    /** 修订类型由目标教师与基线教师的关系决定：相同即恢复基线。 */
    public static String revisionKind(Long substituteTeacherId, Long baselineTeacherId) {
        return substituteTeacherId != null && substituteTeacherId.equals(baselineTeacherId)
                ? RESTORE_BASELINE
                : CHANGE_SUBSTITUTE;
    }

    /** 管理调课的修订类型由基准申请决定：有基准 = 修订调课，无基准 = 首次新调课。 */
    public static String swapRevisionKind(Long baseRequestId) {
        return baseRequestId == null ? RESCHEDULE : RESCHEDULE_REVISION;
    }

    /**
     * 该移动的实例「基线来源日」（票 07 净差行不变量）：所有 SWAP 生效行的
     * {@code lessonDate} 恒等于实例的基线来源日（首次调课的来源日，修订行继承）。
     * 再次调课的 from 是当前落点，须回溯到触及该落点的 SWAP 行取其来源日；
     * 实例仍在基线槽（无触及行）时来源日就是本次的 from。
     */
    public static java.time.LocalDate baselineSourceDate(List<Substitution> effectiveSwapRows,
                                                         com.paiban.substitution.plan.PlanMove move) {
        return effectiveSwapRows.stream()
                .filter(row -> move.fromDate().equals(row.getTargetLessonDate()))
                .map(Substitution::getLessonDate)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(move.fromDate());
    }

    /**
     * 该移动是否「净差为零」：落点完全回到基线槽（基线来源日 + 基线课节）。
     * 恢复基线的环成员不写生效行——基准行转 SUPERSEDED 后实例自然回基线，
     * 硬写「周三→周三」的行反而会把基线课移出（读模型按来源日移出解释）。
     */
    public static boolean restoresBaseline(com.paiban.substitution.plan.PlanMove move,
                                           java.time.LocalDate baselineDate, Long baselinePeriodId) {
        return move.toDate().equals(baselineDate)
                && java.util.Objects.equals(baselinePeriodId, Long.valueOf(move.toPeriodId()));
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
