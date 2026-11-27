package com.paiban.substitution.service;

import com.paiban.foundation.domain.AuditLog;
import com.paiban.foundation.domain.AuditLogRepository;
import com.paiban.substitution.domain.SubstitutionPlan;
import com.paiban.substitution.domain.SubstitutionPlanRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Failure-path auditing runs in its own transaction so the audit row survives
 * when the rejected business action rolls back.
 */
@Service
public class SubstitutionAuditService {

    private static final Logger log = LoggerFactory.getLogger(SubstitutionAuditService.class);

    private final AuditLogRepository auditLogs;
    private final SubstitutionPlanRepository plans;

    public SubstitutionAuditService(
            AuditLogRepository auditLogs,
            SubstitutionPlanRepository plans) {
        this.auditLogs = auditLogs;
        this.plans = plans;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void planFailure(Long planId, String status, String actor, String action,
                            String detail) {
        plans.findById(planId).ifPresent(plan -> plan.setStatus(status));
        record(actor, action, "SUBSTITUTION_PLAN", detail);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failure(String actor, String action, String entityType, String detail) {
        record(actor, action, entityType, detail);
    }

    private void record(String actor, String action, String entityType, String detail) {
        String safe = detail == null ? "" : detail;
        if (safe.length() > 1000) {
            safe = safe.substring(0, 1000);
        }
        auditLogs.save(new AuditLog(actor, action, entityType, safe, 0, 1));
        log.error("substitution plan rejected: {}", safe);
    }

    /** Marks a plan expired/rejected; used for stale-plan conflicts. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void expirePlan(Long planId, String status, String actor, String action, String detail) {
        plans.findById(planId).ifPresent(plan -> plan.setStatus(status));
        record(actor, action, "SUBSTITUTION_PLAN", detail);
    }
}
