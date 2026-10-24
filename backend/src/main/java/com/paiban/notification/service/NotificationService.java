package com.paiban.notification.service;

import com.paiban.common.event.TimetablePublishedEvent;
import com.paiban.common.settings.SettingsService;
import com.paiban.common.web.ApiException;
import com.paiban.foundation.domain.AdminClass;
import com.paiban.foundation.domain.AdminClassRepository;
import com.paiban.foundation.service.SemesterService;
import com.paiban.identity.domain.AppUser;
import com.paiban.identity.service.UserService;
import com.paiban.notification.channel.CompositeNotificationChannel;
import com.paiban.notification.channel.NotificationChannel;
import com.paiban.notification.domain.Notification;
import com.paiban.notification.domain.NotificationRepository;
import com.paiban.notification.web.NotificationDtos.NotificationDto;
import com.paiban.notification.web.NotificationDtos.NotificationPage;
import com.paiban.substitution.domain.Substitution;
import com.paiban.substitution.domain.SubstitutionRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

@Service
public class NotificationService {

    private final CompositeNotificationChannel channel;
    private final NotificationRepository notifications;
    private final UserService users;
    private final AdminClassRepository adminClasses;
    private final SettingsService settings;
    private final SemesterService semesters;
    private final TemplateEngine templateEngine;

    public NotificationService(
            CompositeNotificationChannel channel,
            NotificationRepository notifications,
            UserService users,
            AdminClassRepository adminClasses,
            SettingsService settings,
            SemesterService semesters,
            @Qualifier("notificationTemplateEngine") TemplateEngine templateEngine) {
        this.channel = channel;
        this.notifications = notifications;
        this.users = users;
        this.adminClasses = adminClasses;
        this.settings = settings;
        this.semesters = semesters;
        this.templateEngine = templateEngine;
    }

    public void notify(String templateCode, List<Long> recipientUserIds, Map<String, Object> variables, String link) {
        if (recipientUserIds == null || recipientUserIds.isEmpty()) {
            return;
        }
        NotificationTemplates.Template template = NotificationTemplates.get(templateCode);
        if (template == null) {
            throw ApiException.badRequest("未知通知模板: " + templateCode);
        }
        Context context = new Context();
        context.setVariables(variables);
        String title = templateEngine.process(template.title(), context);
        String content = templateEngine.process(template.body(), context);
        Set<Long> recipients = new LinkedHashSet<>(recipientUserIds);
        NotificationChannel.Message message = new NotificationChannel.Message(
                templateCode, List.copyOf(recipients), variables, title, content, link);
        channel.send(message);
    }

    @Transactional
    public void substitutionEffective(
            SubstitutionRequest request, Substitution substitution, Map<String, Object> variables) {
        List<Long> originalUsers = userIds(users.usersByTeacherId(request.getOriginalTeacherId()));
        List<Long> substituteUsers = userIds(users.usersByTeacherId(substitution.getSubstituteTeacherId()));
        String scope = settings.notificationScope();
        List<Long> headTeacherUsers = new ArrayList<>();
        List<Long> adminUsers = new ArrayList<>();
        // ADR-0014: parties (original + substitute + head teacher) are always notified.
        adminClasses.findById(request.getAdminClassId())
                .map(AdminClass::getHeadTeacherId)
                .ifPresent(headTeacherId -> headTeacherUsers.addAll(userIds(users.usersByTeacherId(headTeacherId))));
        if ("with-admin".equals(scope)) {
            adminUsers.addAll(userIds(users.adminUsers()));
        }

        String link = "/substitutions?requestId=" + request.getId();
        notify(NotificationTemplates.SUBSTITUTION_EFFECTIVE_ORIGINAL, originalUsers, variables, link);
        notify(NotificationTemplates.SUBSTITUTION_EFFECTIVE_SUBSTITUTE, substituteUsers, variables, link);
        notify(NotificationTemplates.SUBSTITUTION_EFFECTIVE_HEAD_TEACHER, headTeacherUsers, variables, link);
        notify(NotificationTemplates.SUBSTITUTION_EFFECTIVE_ADMIN, adminUsers, variables, link);
    }

    @Transactional
    public void substitutionRejected(SubstitutionRequest request, Map<String, Object> variables) {
        notify(
                NotificationTemplates.SUBSTITUTION_REJECTED,
                userIds(users.usersByTeacherId(request.getOriginalTeacherId())),
                variables,
                "/substitutions?requestId=" + request.getId());
    }

    /**
     * One notification per involved teacher describing only their own move; the
     * head teacher and admins get the whole plan. This is deliberately not the
     * cover templates, which would tell a swapping teacher they are covering.
     */
    @Transactional
    public void swapEffective(SubstitutionRequest request,
                              Map<Long, String> detailsByTeacher,
                              String summary) {
        String link = "/substitutions?requestId=" + request.getId();
        String className = adminClasses.findById(request.getAdminClassId())
                .map(AdminClass::getName).orElse("");
        detailsByTeacher.forEach((teacherId, details) -> notify(
                NotificationTemplates.SUBSTITUTION_SWAP_EFFECTIVE,
                userIds(users.usersByTeacherId(teacherId)),
                Map.of("details", details, "className", className),
                link));
        List<Long> headTeacherUsers = new ArrayList<>();
        adminClasses.findById(request.getAdminClassId())
                .map(AdminClass::getHeadTeacherId)
                .ifPresent(headTeacherId -> headTeacherUsers.addAll(userIds(users.usersByTeacherId(headTeacherId))));
        notify(NotificationTemplates.SUBSTITUTION_SWAP_HEAD_TEACHER, headTeacherUsers,
                Map.of("details", summary, "className", className), link);
        if ("with-admin".equals(settings.notificationScope())) {
            notify(NotificationTemplates.SUBSTITUTION_SWAP_ADMIN, userIds(users.adminUsers()),
                    Map.of("details", summary, "className", className), link);
        }
    }

    /** Operational alarm: a plan failed verification and was rejected as a whole. */
    @Transactional
    public void substitutionVerifyAlert(String details) {
        notify(NotificationTemplates.SUBSTITUTION_VERIFY_ALERT, userIds(users.adminUsers()),
                Map.of("details", details), "/substitutions");
    }

    @EventListener
    @Transactional
    public void onTimetablePublished(TimetablePublishedEvent event) {
        List<Long> recipients = new ArrayList<>();
        users.allUsers().stream()
                .filter(user -> user.getTeacherId() != null || users.hasPermission(user, com.paiban.identity.domain.Permissions.TIMETABLE_VIEW))
                .forEach(user -> recipients.add(user.getId()));
        String semesterName = semesters.require(event.semesterId()).getName();
        notify(
                NotificationTemplates.TIMETABLE_PUBLISHED,
                recipients,
                Map.of("semesterName", semesterName),
                "/timetable");
    }

    @Transactional(readOnly = true)
    public NotificationPage page(String username, int page, int size) {
        AppUser user = users.getByUsername(username);
        Page<Notification> result = notifications.findByRecipientUserIdOrderByCreatedAtDesc(
                user.getId(), PageRequest.of(Math.max(0, page), Math.min(100, Math.max(1, size))));
        return new NotificationPage(
                result.getContent().stream().map(this::toDto).toList(),
                result.getTotalElements(),
                result.getNumber(),
                result.getSize());
    }

    @Transactional(readOnly = true)
    public List<NotificationDto> recent(String username) {
        AppUser user = users.getByUsername(username);
        return notifications.findTop10ByRecipientUserIdOrderByCreatedAtDesc(user.getId()).stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public long unreadCount(String username) {
        AppUser user = users.getByUsername(username);
        return notifications.countByRecipientUserIdAndReadFalse(user.getId());
    }

    @Transactional
    public void markRead(String username, Long notificationId) {
        AppUser user = users.getByUsername(username);
        Notification notification = notifications.findById(notificationId)
                .orElseThrow(() -> ApiException.notFound("通知不存在: " + notificationId));
        if (!notification.getRecipientUserId().equals(user.getId())) {
            throw ApiException.forbidden("不能操作他人的通知");
        }
        notification.setRead(true);
        notification.setReadAt(Instant.now());
    }

    @Transactional
    public void markAllRead(String username) {
        AppUser user = users.getByUsername(username);
        notifications.findByRecipientUserIdAndReadFalse(user.getId()).forEach(notification -> {
            notification.setRead(true);
            notification.setReadAt(Instant.now());
        });
    }

    private List<Long> userIds(List<AppUser> source) {
        return source.stream().map(AppUser::getId).toList();
    }

    private NotificationDto toDto(Notification notification) {
        return new NotificationDto(
                notification.getId(),
                notification.getTemplateCode(),
                notification.getTitle(),
                notification.getContent(),
                notification.getLink(),
                notification.isRead(),
                notification.getCreatedAt(),
                notification.getReadAt());
    }
}
