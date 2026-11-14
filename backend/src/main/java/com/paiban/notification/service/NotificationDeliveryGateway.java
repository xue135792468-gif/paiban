package com.paiban.notification.service;

import com.paiban.notification.channel.CompositeNotificationChannel;
import com.paiban.notification.channel.NotificationChannel;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Network delivery runs through this proxy with no ambient transaction. Channel
 * implementations may still open their own short transactions for database logs.
 */
@Service
public class NotificationDeliveryGateway {

    private final CompositeNotificationChannel channel;

    public NotificationDeliveryGateway(CompositeNotificationChannel channel) {
        this.channel = channel;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public boolean deliver(NotificationDelivery delivery) {
        return channel.send(new NotificationChannel.Message(
                delivery.templateCode(),
                delivery.targets(),
                delivery.variables(),
                delivery.title(),
                delivery.content(),
                delivery.link()));
    }
}
