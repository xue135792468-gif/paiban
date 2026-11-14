package com.paiban.identity.service;

import com.paiban.common.web.ApiException;
import com.paiban.foundation.domain.Teacher;
import com.paiban.identity.domain.AppUserRepository;
import com.paiban.identity.domain.Permissions;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 03-C：谁可以把钉钉身份「首次」写到教师档案上。
 *
 * <p>这条规则只回答两个问题，其它模块都复用它，不各自复制管理员判定：
 * <ul>
 *   <li>{@link #requiresExplicitFirstBinding(Teacher)}：这份<b>尚未绑定</b>的档案是不是
 *       关联了管理能力账号。是的话，唯一可信的首次绑定路径是超级管理员在教师档案里显式
 *       填写 UID；通讯录同步的 key 命中、显式同名合并、钉钉事件都不得替它绑上。</li>
 *   <li>{@link #validateManualBinding(Teacher, String)}：人工改 UID 需要
 *       {@code integration:manage}（预置角色里只有超级管理员持有）。没提交该字段、或者
 *       规范化前后相同，都属于普通档案编辑，不能误拒。</li>
 * </ul>
 *
 * <p>保护口径是<b>能力</b>而不是角色名：ADR-0006 要求只在初始化时用预置角色，
 * 权限判断不能写角色字符串；而 {@code resolveRoles} / {@code listRoles} 至今仍接受数据库里的
 * 遗留自定义角色（V60 也只删了 {@code head_teacher}）。只认 {@code super_admin} / {@code admin}
 * 会让「名字不是 admin、却持有 {@code foundation:manage}」的老角色漏保护，所以这里问的是
 * 「档案关联的账号是否持有管理类权限点」。
 *
 * <p>其它口径同样只看事实：不看教师姓名、不看 {@code Teacher.active}（那是排课资格）；
 * 停用中的管理账号一样受保护，否则可以先弱绑定、等日后再启用冒用。
 */
@Component
public class DingTalkBindingPolicy {

    /**
     * 让档案进入「须显式确认」的<b>管理类</b>权限点。这一组只从 {@link Permissions} 取常量，
     * 端点授权仍由各 Controller 的 {@code @PreAuthorize} 负责。
     *
     * <p>刻意不含老师侧权限：{@code timetable:view}、{@code timetable:view:own}、
     * {@code assignment:view:own}、{@code substitution:submit}、{@code substitution:view:own}
     * —— 只有这些权限的普通老师仍按原规格自动配对。{@code export:timetable} 同样不算管理能力。
     */
    public static final List<String> MANAGEMENT_PERMISSIONS = List.of(
            Permissions.USER_MANAGE,
            Permissions.INTEGRATION_MANAGE,
            Permissions.FOUNDATION_MANAGE,
            Permissions.SCHOOL_MANAGE,
            Permissions.SEMESTER_MANAGE,
            Permissions.ASSIGNMENT_MANAGE,
            Permissions.CONSTRAINT_MANAGE,
            Permissions.SCHEDULING_RUN,
            Permissions.SCHEDULING_VIEW,
            Permissions.TIMETABLE_EDIT,
            Permissions.TIMETABLE_PUBLISH,
            Permissions.SUBSTITUTION_APPROVE,
            Permissions.SUBSTITUTION_VIEW,
            Permissions.SETTINGS_MANAGE,
            Permissions.ONBOARDING_MANAGE,
            Permissions.AUDIT_ACCOUNT_VIEW);

    private final AppUserRepository accounts;
    private final UserService users;

    public DingTalkBindingPolicy(AppUserRepository accounts, UserService users) {
        this.accounts = accounts;
        this.users = users;
    }

    /**
     * 未绑定钉钉、但关联了管理员账号的档案：任何自动来源都不得给它首次绑定。
     *
     * <p>已经绑定过的档案不再是「首次绑定」，返回 false —— 它的重绑仍受
     * {@link #validateManualBinding} 和「非空 UID 不得被事件改绑」约束。
     */
    public boolean requiresExplicitFirstBinding(Teacher teacher) {
        if (teacher == null || teacher.getId() == null) {
            return false;
        }
        if (StringUtils.hasText(teacher.getDingtalkUserId())) {
            return false;
        }
        return accounts.existsByTeacherIdAndRolesPermissionsCodeIn(
                teacher.getId(), MANAGEMENT_PERMISSIONS);
    }

    /**
     * 人工写 UID 的唯一校验入口，<b>必须在写任何教师字段之前</b>调用，保证拒绝时不留半写。
     *
     * @param current  改动前的教师（新建时为 {@code null}）
     * @param requested 请求里的 {@code dingtalkUserId}：{@code null} 表示字段未提交；
     *                  空白表示清空绑定
     */
    public void validateManualBinding(Teacher current, String requested) {
        if (requested == null) {
            return; // 编辑表单没带这个字段：保持原绑定，属于普通档案编辑
        }
        String next = StringUtils.hasText(requested) ? requested.trim() : null;
        String existing = current == null || !StringUtils.hasText(current.getDingtalkUserId())
                ? null
                : current.getDingtalkUserId().trim();
        if (Objects.equals(next, existing)) {
            return; // 规范化后没变化：普通编辑不能误拒
        }
        if (!users.hasPermission(Permissions.INTEGRATION_MANAGE)) {
            throw ApiException.forbidden("只有超级管理员可以修改教师的钉钉绑定");
        }
    }
}
