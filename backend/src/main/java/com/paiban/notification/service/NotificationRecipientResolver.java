package com.paiban.notification.service;

import com.paiban.foundation.domain.Teacher;
import com.paiban.foundation.domain.TeacherRepository;
import com.paiban.identity.domain.AppUser;
import com.paiban.identity.domain.AppUserRepository;
import com.paiban.notification.service.NotificationDelivery.Target;
import com.paiban.notification.service.NotificationDelivery.TargetKind;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Re-resolves recipients immediately before delivery, after the business transaction
 * has committed. The read transaction closes before network I/O starts.
 */
@Service
public class NotificationRecipientResolver {

    private final AppUserRepository users;
    private final TeacherRepository teachers;

    public NotificationRecipientResolver(AppUserRepository users, TeacherRepository teachers) {
        this.users = users;
        this.teachers = teachers;
    }

    public record DingTalkRecipient(Target target, String userId) {}

    public record ResolvedTargets(
            List<Long> localUserIds,
            List<DingTalkRecipient> dingtalkRecipients) {

        public ResolvedTargets {
            localUserIds = List.copyOf(localUserIds);
            dingtalkRecipients = List.copyOf(dingtalkRecipients);
        }
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public ResolvedTargets resolve(List<Target> targets) {
        LinkedHashSet<Long> localUserIds = new LinkedHashSet<>();
        Map<String, DingTalkRecipient> dingtalkByUid = new LinkedHashMap<>();
        for (Target target : new LinkedHashSet<>(targets)) {
            if (target.kind() == TargetKind.USER) {
                users.findById(target.id()).filter(AppUser::isEnabled).ifPresent(user -> {
                    localUserIds.add(user.getId());
                    addDingTalk(user, target, dingtalkByUid);
                });
            } else {
                resolveTeacher(target, localUserIds, dingtalkByUid);
            }
        }
        return new ResolvedTargets(List.copyOf(localUserIds), List.copyOf(dingtalkByUid.values()));
    }

    public static String failureRecipient(Target target) {
        return (target.kind() == TargetKind.USER ? "user:" : "teacher:") + target.id();
    }

    private void resolveTeacher(
            Target target,
            LinkedHashSet<Long> localUserIds,
            Map<String, DingTalkRecipient> dingtalkByUid) {
        Teacher teacher = teachers.findById(target.id())
                .filter(value -> value.getDeletedAt() == null && value.isActive())
                .orElse(null);
        if (teacher == null) {
            return;
        }
        List<AppUser> linked = users.findAllByTeacherId(teacher.getId());
        boolean anyEnabled = linked.stream().anyMatch(AppUser::isEnabled);
        if (!linked.isEmpty() && !anyEnabled) {
            return;
        }
        linked.stream().filter(AppUser::isEnabled).map(AppUser::getId).forEach(localUserIds::add);
        if (StringUtils.hasText(teacher.getDingtalkUserId())) {
            dingtalkByUid.putIfAbsent(
                    teacher.getDingtalkUserId(),
                    new DingTalkRecipient(target, teacher.getDingtalkUserId()));
        }
    }

    private void addDingTalk(
            AppUser user,
            Target target,
            Map<String, DingTalkRecipient> dingtalkByUid) {
        if (user.getTeacherId() == null) {
            return;
        }
        Teacher teacher = teachers.findById(user.getTeacherId())
                .filter(value -> value.getDeletedAt() == null && value.isActive())
                .orElse(null);
        if (teacher == null || !StringUtils.hasText(teacher.getDingtalkUserId())) {
            return;
        }
        dingtalkByUid.putIfAbsent(
                teacher.getDingtalkUserId(),
                new DingTalkRecipient(target, teacher.getDingtalkUserId()));
    }
}
