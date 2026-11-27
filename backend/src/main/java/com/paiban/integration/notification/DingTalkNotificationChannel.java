package com.paiban.integration.notification;

import com.paiban.common.settings.SettingsService;
import com.paiban.integration.dingtalk.DingTalkClient;
import com.paiban.notification.channel.NotificationChannel;
import com.paiban.notification.domain.NotificationLog;
import com.paiban.notification.domain.NotificationLogRepository;
import com.paiban.notification.service.NotificationRecipientResolver;
import com.paiban.notification.service.NotificationRecipientResolver.DingTalkRecipient;
import java.time.Instant;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Sends work notifications through DingTalk. Returns false on any failure so
 * the composite channel degrades to the in-system channel (ADR-0005).
 */
@Component
@Order(10)
public class DingTalkNotificationChannel implements NotificationChannel {

    private final SettingsService settings;
    private final DingTalkClient client;
    private final NotificationRecipientResolver recipients;
    private final NotificationLogRepository logs;

    public DingTalkNotificationChannel(
            SettingsService settings,
            DingTalkClient client,
            NotificationRecipientResolver recipients,
            NotificationLogRepository logs) {
        this.settings = settings;
        this.client = client;
        this.recipients = recipients;
        this.logs = logs;
    }

    @Override
    public String code() {
        return "dingtalk";
    }

    @Override
    public boolean send(Message message) {
        if (!settings.dingtalkEnabled() || !settings.isActivePlatform(SettingsService.PLATFORM_DINGTALK)) {
            return false;
        }
        NotificationRecipientResolver.ResolvedTargets resolved = recipients.resolve(message.targets());
        int attempted = 0;
        int sent = 0;
        for (DingTalkRecipient recipient : resolved.dingtalkRecipients()) {
            attempted++;
            boolean success = false;
            for (int attempt = 1; attempt <= 3 && !success; attempt++) {
                success = client.sendWorkNotification(
                        recipient.userId(), message.title(), message.content(), message.link());
                if (!success && attempt < 3) {
                    try {
                        Thread.sleep(100L * attempt);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            log(recipient.userId(), message, success);
            if (success) {
                sent++;
            }
        }
        if (attempted == 0) {
            for (com.paiban.notification.service.NotificationDelivery.Target target : message.targets()) {
                log(NotificationRecipientResolver.failureRecipient(target), message, false);
            }
        }
        return attempted > 0 && sent == attempted;
    }

    private void log(String recipient, Message message, boolean success) {
        NotificationLog entry = new NotificationLog();
        entry.setChannel("dingtalk");
        entry.setPlatform("dingtalk");
        entry.setRecipient(recipient);
        entry.setTemplateCode(message.templateCode());
        entry.setContent(message.content());
        entry.setSuccess(success);
        entry.setCreatedAt(Instant.now());
        logs.save(entry);
    }
}
