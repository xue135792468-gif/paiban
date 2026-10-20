package com.paiban.notification.web;

import java.time.Instant;
import java.util.List;

public final class NotificationDtos {

    private NotificationDtos() {}

    public record NotificationDto(Long id, String templateCode, String title, String content,
                                  String link, boolean read, Instant createdAt, Instant readAt) {}

    public record NotificationPage(List<NotificationDto> content, long total, int page, int size) {}

    public record UnreadCount(long count) {}
}
