package com.paiban.substitution.service;

import com.paiban.common.web.ApiException;
import com.paiban.foundation.domain.Period;
import com.paiban.foundation.domain.PeriodRepository;
import com.paiban.foundation.domain.TeacherRepository;
import com.paiban.substitution.domain.Substitution;
import com.paiban.substitution.domain.SubstitutionRepository;
import com.paiban.substitution.domain.SubstitutionRequest;
import com.paiban.substitution.domain.SubstitutionRequestRepository;
import com.paiban.substitution.plan.PlanSnapshot;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HexFormat;
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

    /** 课节开始时间缺失即拒绝写入并列出缺失项（规格第 6 节，不用默认时间猜测）。 */
    static LocalTime periodStart(PeriodRepository periods, long periodId) {
        Period period = periods.findById(periodId)
                .orElseThrow(() -> ApiException.badRequest(
                        "无法确定开课时刻：课节 " + periodId + " 不存在，拒绝写入"));
        if (period.getStartTime() == null) {
            throw ApiException.badRequest(
                    "无法确定开课时刻：课节 " + periodId + " 缺少开始时间，拒绝写入");
        }
        return period.getStartTime();
    }

    static Instant min(Instant left, Instant right) {
        return left.isBefore(right) ? left : right;
    }

    /** 确认动作的幂等指纹（同凭证同参数重放，参数改变即冲突）。 */
    static String actionHash(String action, String comment) {
        String payload = action + "|" + (comment == null ? "" : comment);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required but unavailable", ex);
        }
    }

    /** 数据级旧格式检查：该条目存在 lesson_date 为空的 EFFECTIVE 行且覆盖该日期。 */
    static boolean hasLegacyCover(SubstitutionRepository substitutions, Long entryId,
                                  LocalDate lessonDate) {
        return substitutions.findByEntryIdAndStatusAndLessonDateIsNull(entryId, Substitution.EFFECTIVE)
                .stream()
                .anyMatch(row -> !row.getEffectiveDate().isAfter(lessonDate));
    }

    /** 同一实例同一时间最多一笔活跃修改；返回待处理申请 id 供界面引导。 */
    static Long activeRequestFor(SubstitutionRequestRepository requests, Long entryId,
                                 LocalDate lessonDate) {
        return requests.findByEntryIdAndEffectiveDate(entryId, lessonDate).stream()
                .filter(request -> SubstitutionRequest.SUBMITTED.equals(request.getStatus())
                        || SubstitutionRequest.PENDING_APPROVAL.equals(request.getStatus()))
                .map(SubstitutionRequest::getId)
                .findFirst()
                .orElse(null);
    }

    static String className(PlanSnapshot snapshot, long classId) {
        var view = snapshot.classes().get(classId);
        return view == null ? null : view.name();
    }

    static String courseName(PlanSnapshot snapshot, long courseId) {
        var view = snapshot.courses().get(courseId);
        return view == null ? null : view.name();
    }

    static String teacherName(PlanSnapshot snapshot, long teacherId) {
        var view = snapshot.teacher(teacherId);
        return view == null ? null : view.name();
    }

    static String roomName(PlanSnapshot snapshot, long roomId) {
        var view = snapshot.rooms().get(roomId);
        return view == null ? null : view.name();
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
