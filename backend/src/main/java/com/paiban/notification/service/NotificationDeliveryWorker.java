package com.paiban.notification.service;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
public class NotificationDeliveryWorker {

    private final NotificationDeliveryGateway gateway;
    private final NotificationFailureRecorder failures;

    public NotificationDeliveryWorker(
            NotificationDeliveryGateway gateway,
            NotificationFailureRecorder failures) {
        this.gateway = gateway;
        this.failures = failures;
    }

    @Async("notificationExecutor")
    public void deliver(NotificationDelivery delivery) {
        try {
            gateway.deliver(delivery);
        } catch (RuntimeException ex) {
            try {
                failures.record(delivery, "DELIVERY_EXCEPTION");
            } catch (RuntimeException ignored) {
                // A log-store outage must not turn a committed approval into an HTTP failure.
            }
        }
    }
}
