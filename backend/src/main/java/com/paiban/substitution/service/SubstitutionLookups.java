package com.paiban.substitution.service;

import com.paiban.common.web.ApiException;
import com.paiban.foundation.domain.TeacherRepository;
import com.paiban.substitution.domain.Substitution;
import com.paiban.substitution.domain.SubstitutionRepository;
import com.paiban.substitution.domain.SubstitutionRequest;
import com.paiban.substitution.domain.SubstitutionRequestRepository;
import java.time.LocalDate;
import java.util.Optional;

/** Shared existence checks; kept out of the services so they cannot drift. */
final class SubstitutionLookups {

    private SubstitutionLookups() {}

    static SubstitutionRequest requireRequest(SubstitutionRequestRepository requests, Long requestId) {
        return requests.findById(requestId)
                .orElseThrow(() -> ApiException.notFound("调代申请不存在: " + requestId));
    }

    static void requireTeacher(TeacherRepository teachers, Long teacherId) {
        if (!teachers.existsById(teacherId)) {
            throw ApiException.notFound("教师不存在: " + teacherId);
        }
    }

    /**
     * 授课实例（条目 + 授课日期）的当前生效行（票 07 修订链的基准）：
     * 优先命中同日的 COVER 行（对当前教师的小修订），否则取落点正好是该日期的
     * SWAP 行（实例被调到该日）。SWAP 行的 effectiveDate 是来源日，所以落点匹配
     * 不能按 effectiveDate 查；实例仍在基线槽时返回空——此时没有基准申请。
     */
    static Optional<Substitution> currentInstanceRow(
            SubstitutionRepository substitutions, Long entryId, LocalDate lessonDate) {
        Optional<Substitution> cover = substitutions
                .findFirstByEntryIdAndEffectiveDateAndStatus(entryId, lessonDate, Substitution.EFFECTIVE)
                .filter(row -> !Substitution.TYPE_SWAP.equals(row.getType()));
        if (cover.isPresent()) {
            return cover;
        }
        return substitutions.findByEntryId(entryId).stream()
                .filter(row -> Substitution.EFFECTIVE.equals(row.getStatus()))
                .filter(row -> Substitution.TYPE_SWAP.equals(row.getType()))
                .filter(row -> lessonDate.equals(row.getTargetLessonDate()))
                .findFirst();
    }
}
