package com.paiban.substitution.service;

import com.paiban.common.web.ApiException;
import com.paiban.foundation.domain.Period;
import com.paiban.foundation.domain.PeriodRepository;
import com.paiban.foundation.domain.TeacherRepository;
import com.paiban.identity.domain.AppUser;
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

/** Shared existence checks; kept out of the services so they cannot drift. */
final class SubstitutionLookups {

    private SubstitutionLookups() {}

    static SubstitutionRequest requireRequest(SubstitutionRequestRepository requests, Long requestId) {
        return requests.findById(requestId)
                .orElseThrow(() -> ApiException.notFound("调代申请不存在: " + requestId));
    }

    /**
     * 「是不是本人」的唯一判据：先认真实操作账号（票 05 起新申请都写 operatorUserId），
     * 旧行没有操作账号时才回退到「非空的申请人教师关系」。
     * 两个 null 永远不会相等——账号没绑教师不会因此变成任何申请的主人。
     */
    static boolean isOwn(SubstitutionRequest request, AppUser user) {
        if (request.getOperatorUserId() != null) {
            return request.getOperatorUserId().equals(user.getId());
        }
        return user.getTeacherId() != null
                && user.getTeacherId().equals(request.getRequesterTeacherId());
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
                .filter(request -> SubstitutionRequest.SUBMITTED.equals(request.getStatus()))
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
}
