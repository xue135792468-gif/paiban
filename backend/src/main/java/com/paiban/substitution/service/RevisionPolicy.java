package com.paiban.substitution.service;

import com.paiban.substitution.domain.Substitution;
import java.util.Objects;

/**
 * 代入/代出统计口径：按「当前有效授课安排 vs 基线教师」比较，不按累计事件行数计数。
 * 票 15 删除修订链后，本类只保留这一条读模型谓词。
 */
public final class RevisionPolicy {

    private RevisionPolicy() {}

    /** 该生效行是否计入代入/代出：当前生效（EFFECTIVE）且代课教师与基线原教师不同。 */
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
