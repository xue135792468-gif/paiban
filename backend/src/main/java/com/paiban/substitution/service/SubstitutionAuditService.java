package com.paiban.substitution.service;

import com.paiban.foundation.domain.AuditLog;
import com.paiban.foundation.domain.AuditLogRepository;
import com.paiban.notification.service.NotificationService;
import com.paiban.substitution.domain.SubstitutionPlan;
import com.paiban.substitution.domain.SubstitutionPlanRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Failure-path auditing runs in its own transaction: the approve call rejects the
 * whole plan and rolls back, but the audit row and the alarm must survive so an
 * operator can see what was refused and why.
 */
@Service
public class SubstitutionAuditService {

    private static final Logger log = LoggerFactory.getLogger(SubstitutionAuditService.class);

    private final AuditLogRepository auditLogs;
    private final SubstitutionPlanRepository plans;
    private final NotificationService notifications;

    public SubstitutionAuditService(
            AuditLogRepository auditLogs,
            SubstitutionPlanRepository plans,
            NotificationService notifications) {
        this.auditLogs = auditLogs;
        this.plans = plans;
        this.notifications = notifications;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void planFailure(Long planId, String status, String actor, String action,
                            String detail, boolean alert, Long requesterTeacherId) {
        plans.findById(planId).ifPresent(plan -> plan.setStatus(status));
        record(actor, action, "SUBSTITUTION_PLAN", detail, alert);
        if (requesterTeacherId != null) {
            notifications.substitutionPlanFailed(requesterTeacherId, detail);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failure(String actor, String action, String entityType, String detail, boolean alert) {
        record(actor, action, entityType, detail, alert);
    }

    private void record(String actor, String action, String entityType, String detail, boolean alert) {
        String safe = detail == null ? "" : detail;
        if (safe.length() > 1000) {
            safe = safe.substring(0, 1000);
        }
        auditLogs.save(new AuditLog(actor, action, entityType, safe, 0, 1));
        log.error("substitution plan rejected: {}", safe);
        if (alert) {
            notifications.substitutionVerifyAlert(safe);
        }
    }

    /** Marks a plan expired/rejected without an alarm; used for stale-plan conflicts. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void expirePlan(Long planId, String status, String actor, String action, String detail) {
        plans.findById(planId).ifPresent(plan -> plan.setStatus(status));
        record(actor, action, "SUBSTITUTION_PLAN", detail, false);
    }
}
