package com.paiban.notification.service;

import com.paiban.notification.domain.NotificationLog;
import com.paiban.notification.domain.NotificationLogRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Persists fixed, non-vendor failure rows in an isolated short transaction. */
@Service
public class NotificationFailureRecorder {

    private final NotificationLogRepository logs;

    public NotificationFailureRecorder(NotificationLogRepository logs) {
        this.logs = logs;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(NotificationDelivery delivery, String errorCode) {
        List<NotificationLog> rows = delivery.targets().stream()
                .map(target -> {
                    NotificationLog row = new NotificationLog();
                    row.setChannel("notification");
                    row.setPlatform("system");
                    row.setRecipient(NotificationRecipientResolver.failureRecipient(target));
                    row.setTemplateCode(delivery.templateCode());
                    row.setContent(delivery.content());
                    row.setSuccess(false);
                    row.setErrorMessage(errorCode);
                    row.setCreatedAt(Instant.now());
                    return row;
                })
                .toList();
        logs.saveAll(rows);
    }
}
