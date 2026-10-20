package com.paiban.integration.notification;

import com.paiban.common.settings.SettingsService;
import com.paiban.foundation.domain.Teacher;
import com.paiban.foundation.domain.TeacherRepository;
import com.paiban.identity.domain.AppUser;
import com.paiban.identity.service.UserService;
import com.paiban.integration.feishu.FeishuClient;
import com.paiban.integration.feishu.FeishuMessage;
import com.paiban.notification.channel.NotificationChannel;
import com.paiban.notification.domain.NotificationLog;
import com.paiban.notification.domain.NotificationLogRepository;
import java.time.Instant;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Sends work notifications through Feishu. Returns false on any failure so the
 * composite channel degrades to the in-system channel (ADR-0005).
 */
@Component
@Order(5)
public class FeishuNotificationChannel implements NotificationChannel {

    private final SettingsService settings;
    private final FeishuClient client;
    private final UserService users;
    private final TeacherRepository teachers;
    private final NotificationLogRepository logs;

    public FeishuNotificationChannel(
            SettingsService settings,
            FeishuClient client,
            UserService users,
            TeacherRepository teachers,
            NotificationLogRepository logs) {
        this.settings = settings;
        this.client = client;
        this.users = users;
        this.teachers = teachers;
        this.logs = logs;
    }

    @Override
    public String code() {
        return "feishu";
    }

    @Override
    public boolean send(Message message) {
        if (!settings.feishuEnabled()) {
            return false;
        }
        boolean card = message.templateCode() != null && message.templateCode().startsWith("SUBSTITUTION_");
        int attempted = 0;
        int sent = 0;
        for (Long userId : message.recipientUserIds()) {
            AppUser user = users.get(userId);
            if (user.getTeacherId() == null) {
                continue;
            }
            Teacher teacher = teachers.findById(user.getTeacherId()).orElse(null);
            if (teacher == null || !hasBinding(teacher)) {
                continue;
            }
            attempted++;
            FeishuMessage feishuMessage = new FeishuMessage(
                    teacher.getFeishuOpenId(),
                    teacher.getFeishuUserId(),
                    message.title(),
                    message.content(),
                    message.link(),
                    card);
            boolean success = false;
            for (int attempt = 1; attempt <= 3 && !success; attempt++) {
                success = client.sendMessage(feishuMessage);
                if (!success && attempt < 3) {
                    try {
                        Thread.sleep(100L * attempt);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            log(recipient(teacher), message, success);
            if (success) {
                sent++;
            }
        }
        return attempted > 0 && sent == attempted;
    }

    private boolean hasBinding(Teacher teacher) {
        return (teacher.getFeishuOpenId() != null && !teacher.getFeishuOpenId().isBlank())
                || (teacher.getFeishuUserId() != null && !teacher.getFeishuUserId().isBlank());
    }

    private String recipient(Teacher teacher) {
        return teacher.getFeishuOpenId() != null && !teacher.getFeishuOpenId().isBlank()
                ? teacher.getFeishuOpenId()
                : teacher.getFeishuUserId();
    }

    private void log(String recipient, Message message, boolean success) {
        NotificationLog entry = new NotificationLog();
        entry.setChannel("feishu");
        entry.setPlatform("feishu");
        entry.setRecipient(recipient);
        entry.setTemplateCode(message.templateCode());
        entry.setContent(message.content());
        entry.setSuccess(success);
        entry.setCreatedAt(Instant.now());
        logs.save(entry);
    }
}
