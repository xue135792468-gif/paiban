package com.paiban.notification.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

public final class NotificationDeliveryLogDtos {

    private NotificationDeliveryLogDtos() {}

    public record NotificationDeliveryLogPage(
            List<NotificationDeliveryLogItem> items,
            long total,
            int page,
            int size) {}

    public record NotificationDeliveryLogItem(
            Long id,
            String channel,
            String platform,
            String recipient,
            String recipientLabel,
            String templateCode,
            String content,
            boolean success,
            String createdAt,
            @JsonInclude(JsonInclude.Include.ALWAYS) String errorMessage) {}
}
