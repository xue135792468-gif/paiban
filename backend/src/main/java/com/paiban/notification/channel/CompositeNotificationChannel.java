package com.paiban.notification.channel;

import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Tries channels in order (DingTalk first when enabled, local last) and stops at
 * the first success, implementing the graceful degradation required by ADR-0005.
 * Channels are resolved lazily to avoid a circular reference with this bean.
 */
@Component
public class CompositeNotificationChannel implements NotificationChannel {

    private final ObjectProvider<NotificationChannel> provider;

    public CompositeNotificationChannel(ObjectProvider<NotificationChannel> provider) {
        this.provider = provider;
    }

    @Override
    public String code() {
        return "composite";
    }

    @Override
    public boolean send(Message message) {
        List<NotificationChannel> channels = provider.orderedStream()
                .filter(channel -> channel != this)
                .toList();
        for (NotificationChannel channel : channels) {
            if (channel.send(message)) {
                return true;
            }
        }
        return false;
    }
}
