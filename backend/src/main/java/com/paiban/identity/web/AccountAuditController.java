package com.paiban.identity.web;

import com.paiban.identity.domain.Permissions;
import com.paiban.identity.service.AccountAuditService;
import com.paiban.identity.web.AccountAuditDtos.AccountActionOption;
import com.paiban.identity.web.AccountAuditDtos.AccountAuditPage;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 票 02：账号审计查看。只有持有「账号审计查看」权限的超级管理员可以访问；
 * 教务管理员与老师走这里一律 403（前端也不给入口）。
 */
@RestController
@RequestMapping("/api")
public class AccountAuditController {

    private final AccountAuditService accountAudits;

    public AccountAuditController(AccountAuditService accountAudits) {
        this.accountAudits = accountAudits;
    }

    @GetMapping("/account-audits")
    @PreAuthorize("hasAuthority('" + Permissions.AUDIT_ACCOUNT_VIEW + "')")
    public AccountAuditPage list(
            @RequestParam(name = "action", required = false) String action,
            @RequestParam(name = "targetUserId", required = false) Long targetUserId,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return accountAudits.page(action, targetUserId, page, size);
    }

    @GetMapping("/account-audits/actions")
    @PreAuthorize("hasAuthority('" + Permissions.AUDIT_ACCOUNT_VIEW + "')")
    public List<AccountActionOption> actions() {
        return accountAudits.actions();
    }
}
