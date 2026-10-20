package com.paiban.notification.channel;

import java.util.List;
import java.util.Map;

public interface NotificationChannel {

    String code();

    boolean send(Message message);

    record Message(String templateCode,
                   List<Long> recipientUserIds,
                   Map<String, Object> variables,
                   String title,
                   String content,
                   String link) {}
}
