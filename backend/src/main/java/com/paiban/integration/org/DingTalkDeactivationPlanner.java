package com.paiban.integration.org;

import com.paiban.foundation.domain.Teacher;
import com.paiban.foundation.domain.TeacherRepository;
import com.paiban.identity.domain.AppUser;
import com.paiban.identity.domain.AppUserRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 票 04：只读计算「将停用」候选。
 *
 * <p>候选 = 已绑定钉钉（教师档案有 {@code dingtalkUserId}）且账号启用，但本次<b>完整原始目录</b>
 * 里看不到该 UID 的账号。这里的「缺失」只看原始目录的 UID 集合：导入时的部门/用户排除不参与，
 * 因而不会因为管理员少导几个人就制造停用候选。
 *
 * <p>扫描不完整（部门局部失败、坏游标、协议结构缺失）时绝不计算候选，只返回警告。
 * {@code Teacher.active} 是远端离职的教师档案指标，不等于账号停用，判断只使用账号的 enabled 状态。
 */
@Component
public class DingTalkDeactivationPlanner {

    private final AppUserRepository accounts;
    private final TeacherRepository teachers;

    public DingTalkDeactivationPlanner(AppUserRepository accounts, TeacherRepository teachers) {
        this.accounts = accounts;
        this.teachers = teachers;
    }

    public DeactivationPlan plan(DingTalkDirectory directory) {
        if (directory == null) {
            return new DeactivationPlan(false, "目录扫描结果缺失，不能判断缺失账号", List.of());
        }
        if (!directory.complete()) {
            return new DeactivationPlan(false,
                    "扫描不完整：" + directory.incompleteReason() + "，本次不生成停用候选", List.of());
        }
        Set<String> visibleUserIds = directory.users().stream()
                .map(com.paiban.integration.domain.DingUser::userId)
                .filter(StringUtils::hasText)
                .collect(Collectors.toSet());

        List<AppUser> boundEnabled = accounts.findAll().stream()
                .filter(AppUser::isEnabled)
                .filter(user -> user.getTeacherId() != null)
                .toList();
        if (boundEnabled.isEmpty()) {
            return new DeactivationPlan(true, null, List.of());
        }
        Map<Long, Teacher> teachersById = teachers.findAllById(
                        boundEnabled.stream().map(AppUser::getTeacherId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(Teacher::getId, teacher -> teacher));

        List<Candidate> candidates = new ArrayList<>();
        for (AppUser account : boundEnabled) {
            Teacher teacher = teachersById.get(account.getTeacherId());
            if (teacher == null || teacher.getDeletedAt() != null) {
                continue;
            }
            String dingtalkUserId = teacher.getDingtalkUserId();
            if (!StringUtils.hasText(dingtalkUserId) || visibleUserIds.contains(dingtalkUserId)) {
                continue;
            }
            candidates.add(new Candidate(
                    account.getId(),
                    account.getUsername(),
                    account.getDisplayName(),
                    teacher.getId(),
                    teacher.getName(),
                    dingtalkUserId));
        }
        candidates.sort(Comparator.comparing(Candidate::accountId));
        return new DeactivationPlan(true, null, candidates);
    }

    /** 一个待停用候选；{@code accountId} 是稳定账号 ID，后续确认按它锁定目标。 */
    public record Candidate(
            Long accountId,
            String accountName,
            String displayName,
            Long teacherId,
            String teacherName,
            String dingtalkUserId) {}

    public record DeactivationPlan(
            boolean scanComplete, String warning, List<Candidate> candidates) {

        public DeactivationPlan {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }
    }
}
