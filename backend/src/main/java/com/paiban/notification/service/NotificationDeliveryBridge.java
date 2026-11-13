package com.paiban.notification.service;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Registered inside a business transaction, this bridge only enqueues after commit.
 * Queue rejection is recorded separately and never escapes into the completed request.
 */
@Component
public class NotificationDeliveryBridge {

    private final NotificationDeliveryWorker worker;
    private final NotificationFailureRecorder failures;

    public NotificationDeliveryBridge(
            NotificationDeliveryWorker worker,
            NotificationFailureRecorder failures) {
        this.worker = worker;
        this.failures = failures;
    }

    @TransactionalEventListener(
            phase = TransactionPhase.AFTER_COMMIT,
            fallbackExecution = true)
    public void afterCommit(NotificationDeliveryRequested event) {
        try {
            worker.deliver(event.delivery());
        } catch (RuntimeException ex) {
            try {
                failures.record(
                        event.delivery(),
                        "DELIVERY_QUEUE_REJECTED",
                        "通知队列繁忙，发送未开始");
            } catch (RuntimeException ignored) {
                // Keep the business response independent from notification infrastructure.
            }
        }
    }
}
