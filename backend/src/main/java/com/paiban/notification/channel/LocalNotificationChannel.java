package com.paiban.notification.channel;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paiban.notification.domain.Notification;
import com.paiban.notification.domain.NotificationLog;
import com.paiban.notification.domain.NotificationLogRepository;
import com.paiban.notification.domain.NotificationRepository;
import java.time.Instant;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** In-system notification centre; always available, used as the fallback channel. */
@Component
@Order(100)
public class LocalNotificationChannel implements NotificationChannel {

    private final NotificationRepository notifications;
    private final NotificationLogRepository logs;
    private final ObjectMapper objectMapper;

    public LocalNotificationChannel(
            NotificationRepository notifications,
            NotificationLogRepository logs,
            ObjectMapper objectMapper) {
        this.notifications = notifications;
        this.logs = logs;
        this.objectMapper = objectMapper;
    }

    @Override
    public String code() {
        return "local";
    }

    @Override
    public boolean send(Message message) {
        try {
            String variables = objectMapper.writeValueAsString(message.variables());
            for (Long recipient : message.recipientUserIds()) {
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
            log(message, "local", true, null);
            return true;
        } catch (JsonProcessingException | RuntimeException ex) {
            log(message, "local", false, ex.getMessage());
            return false;
        }
    }

    private void log(Message message, String channel, boolean success, String error) {
        NotificationLog entry = new NotificationLog();
        entry.setChannel(channel);
        entry.setRecipient(message.recipientUserIds().toString());
        entry.setTemplateCode(message.templateCode());
        entry.setContent(message.content());
        entry.setSuccess(success);
        entry.setErrorMessage(error);
        entry.setCreatedAt(Instant.now());
        logs.save(entry);
    }
}
