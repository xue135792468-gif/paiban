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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
        if (!settings.feishuEnabled() || !settings.isActivePlatform(SettingsService.PLATFORM_FEISHU)) {
            return false;
        }
        boolean card = message.templateCode() != null && message.templateCode().startsWith("SUBSTITUTION_");
        String body = card ? cardBody(message) : message.content();
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
                    body,
                    message.link(),
                    card,
                    message.templateCode());
            boolean success = false;
            for (int attempt = 1; attempt <= 3 && !success; attempt++) {
                success = client.sendMessage(feishuMessage);
                if (!success && attempt < 3) {
                    try {
                        Thread.sleep(Math.min(2000L, 200L * (1L << (attempt - 1))));
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

    /**
     * Renders the interactive-card body from the notification variables. Only
     * fields present in the template are emitted; when no known field is present
     * the rendered in-system content is used as-is.
     */
    private String cardBody(Message message) {
        Map<String, Object> variables = message.variables();
        if (variables == null || variables.isEmpty()) {
            return message.content();
        }
        List<String> lines = new ArrayList<>();
        appendLine(lines, "课程", text(variables.get("courseName")));
        appendLine(lines, "时间", time(variables));
        appendLine(lines, "班级", text(variables.get("className")));
        appendLine(lines, "原教师", text(variables.get("originalTeacher")));
        appendLine(lines, "代课教师", text(variables.get("substituteTeacher")));
        appendLine(lines, "教室", text(variables.get("roomName")));
        appendLine(lines, "审批意见", text(variables.get("comment")));
        return lines.isEmpty() ? message.content() : String.join("\n", lines);
    }

    private String time(Map<String, Object> variables) {
        String date = text(variables.get("date"));
        String periodLabel = text(variables.get("periodLabel"));
        if (date == null) {
            return periodLabel;
        }
        return periodLabel == null ? date : date + " " + periodLabel;
    }

    private void appendLine(List<String> lines, String label, String value) {
        if (value != null) {
            lines.add("**" + label + "**：" + value);
        }
    }

    private String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
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
