package com.paiban.notification.channel;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paiban.notification.domain.Notification;
import com.paiban.notification.domain.NotificationLog;
import com.paiban.notification.domain.NotificationLogRepository;
import com.paiban.notification.domain.NotificationRepository;
import com.paiban.notification.service.NotificationRecipientResolver;
import java.time.Instant;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** In-system notification centre; always available, used as the fallback channel. */
@Component
@Order(100)
public class LocalNotificationChannel implements NotificationChannel {

    /** Matches notification_log.recipient's VARCHAR(200); the id list can be long in big schools. */
    private static final int RECIPIENT_MAX_LENGTH = 200;

    private final NotificationRepository notifications;
    private final NotificationLogRepository logs;
    private final ObjectMapper objectMapper;
    private final NotificationRecipientResolver recipients;

    public LocalNotificationChannel(
            NotificationRepository notifications,
            NotificationLogRepository logs,
            ObjectMapper objectMapper,
            NotificationRecipientResolver recipients) {
        this.notifications = notifications;
        this.logs = logs;
        this.objectMapper = objectMapper;
        this.recipients = recipients;
    }

    @Override
    public String code() {
        return "local";
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean send(Message message) {
        try {
            String variables = objectMapper.writeValueAsString(message.variables());
            NotificationRecipientResolver.ResolvedTargets resolved = recipients.resolve(message.targets());
            if (resolved.localUserIds().isEmpty()) {
                log(message, "local", false,
                        "没有可用的本地收件账号",
                        message.targets().stream()
                                .map(NotificationRecipientResolver::failureRecipient)
                                .toList());
                return false;
            }
            for (Long recipient : resolved.localUserIds()) {
                Notification notification = new Notification();
                notification.setRecipientUserId(recipient);
                notification.setTemplateCode(message.templateCode());
                notification.setVariablesJson(variables);
                notification.setTitle(message.title());
                notification.setContent(message.content());
                notification.setLink(message.link());
                notification.setCreatedAt(Instant.now());
                notifications.save(notification);
            }
            log(message, "local", true, null, resolved.localUserIds());
            return true;
        } catch (JsonProcessingException | RuntimeException ex) {
            log(message, "local", false, "本地通知写入失败", message.targets().stream()
                    .map(NotificationRecipientResolver::failureRecipient)
                    .toList());
            return false;
        }
    }

    private void log(
            Message message,
            String channel,
            boolean success,
            String error,
            java.util.List<?> recipients) {
        NotificationLog entry = new NotificationLog();
        entry.setChannel(channel);
        entry.setPlatform("system");
        entry.setRecipient(truncate(recipients.toString()));
        entry.setTemplateCode(message.templateCode());
        entry.setContent(message.content());
        entry.setSuccess(success);
        entry.setErrorMessage(error);
        entry.setCreatedAt(Instant.now());
        logs.save(entry);
    }

    private static String truncate(String value) {
        if (value == null || value.length() <= RECIPIENT_MAX_LENGTH) {
            return value;
        }
        return value.substring(0, RECIPIENT_MAX_LENGTH);
    }
}
