package com.paiban.scheduling.web;

import com.paiban.identity.domain.Permissions;
import com.paiban.scheduling.service.ConstraintMigrationService;
import com.paiban.scheduling.web.ConstraintMigrationDtos.MigrationPreviewDto;
import com.paiban.scheduling.web.ConstraintMigrationDtos.MigrationReportDto;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ticket 11: preview / run / report of the legacy 26-to-19 rule migration. Preview is
 * a dry run; run is idempotent; report reads the latest stored run. All three need
 * {@code constraint:manage}, like every other write to the rule catalogue.
 *
 * <p>{@code semesterId} may be omitted: the service then resolves the current semester
 * the same way the rest of the rule catalogue does, and answers 400 when no semester is
 * current yet (ticket 11 review — the parameter used to reach the lookup unchecked).
 */
@RestController
@RequestMapping("/api/constraint-migration")
public class ConstraintMigrationController {

    private final ConstraintMigrationService service;

    public ConstraintMigrationController(ConstraintMigrationService service) {
        this.service = service;
    }

    @GetMapping("/preview")
    @PreAuthorize("hasAuthority('" + Permissions.CONSTRAINT_MANAGE + "')")
    public MigrationPreviewDto preview(@RequestParam(required = false) Long semesterId) {
        return service.preview(semesterId);
    }

    @PostMapping("/run")
    @PreAuthorize("hasAuthority('" + Permissions.CONSTRAINT_MANAGE + "')")
    public MigrationReportDto run(@RequestParam(required = false) Long semesterId,
                                  Authentication authentication) {
        return service.run(semesterId, authentication == null ? null : authentication.getName());
    }

    /** 204 when the semester has never been migrated, so the page can show its empty state. */
    @GetMapping("/report")
    @PreAuthorize("hasAuthority('" + Permissions.CONSTRAINT_MANAGE + "')")
    public ResponseEntity<MigrationReportDto> report(
            @RequestParam(required = false) Long semesterId) {
        MigrationReportDto report = service.report(semesterId);
        return report == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(report);
    }
}
