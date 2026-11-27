package com.paiban.notification.channel;

import com.paiban.notification.service.NotificationDelivery;
import java.util.List;
import java.util.Map;

public interface NotificationChannel {

    String code();

    boolean send(Message message);

    record Message(String templateCode,
                   List<NotificationDelivery.Target> targets,
                   Map<String, Object> variables,
                   String title,
                   String content,
                   String link) {}
}
