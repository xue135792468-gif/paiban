package com.paiban.scheduling.web;

import com.paiban.identity.domain.Permissions;
import com.paiban.scheduling.service.ConstraintProposalService;
import com.paiban.scheduling.web.ConstraintProposalDtos.AvailabilityDto;
import com.paiban.scheduling.web.ConstraintProposalDtos.GenerateRequest;
import com.paiban.scheduling.web.ConstraintProposalDtos.RunDetailDto;
import com.paiban.scheduling.web.ConstraintProposalDtos.RunDto;
import com.paiban.scheduling.web.ConstraintProposalDtos.ResolveProposalRequest;
import com.paiban.scheduling.web.ConstraintProposalDtos.RunSummaryDto;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only natural-language proposals (ticket 14/08). Generating never writes the
 * effective configuration; the later confirmation ticket builds on these runs.
 */
@RestController
@RequestMapping("/api/constraint-proposals")
@PreAuthorize("hasAuthority('" + Permissions.CONSTRAINT_MANAGE + "')")
public class ConstraintProposalController {

    private final ConstraintProposalService service;

    public ConstraintProposalController(ConstraintProposalService service) {
        this.service = service;
    }

    /** Whether the page can call a model, and why not when it cannot. */
    @GetMapping("/availability")
    public AvailabilityDto availability() {
        return service.availability();
    }

    @PostMapping
    public RunDto generate(
            @RequestParam Long semesterId, @Valid @RequestBody GenerateRequest request) {
        return service.generate(semesterId, request);
    }

    /** Stops waiting. A late provider answer is recorded but never fills the page. */
    @PostMapping("/{id}/cancel")
    public RunDto cancel(@PathVariable Long id) {
        return service.cancel(id);
    }

    /**
     * The page's cancel button, which knows the click identity but not yet the numeric
     * run id: the synchronous generate call has not returned while the model waits.
     */
    @PostMapping("/cancel")
    public RunDto cancelPending(@RequestParam String clientRunId) {
        return service.cancelByClientRunId(clientRunId);
    }

    @GetMapping
    public List<RunSummaryDto> list(
            @RequestParam Long semesterId,
            @RequestParam(defaultValue = "mine") String scope) {
        return service.list(semesterId, "all".equalsIgnoreCase(scope));
    }

    @GetMapping("/{id}")
    public RunDetailDto detail(@PathVariable Long id) {
        return service.detail(id);
    }

    /**
     * Completes one proposal after the user picks a homonym or fills a missing
     * condition. This is an edit, not a new model call.
     */
    @PostMapping("/{id}/resolve")
    public com.paiban.scheduling.web.ConstraintProposalDtos.ConstraintProposalDto resolve(
            @PathVariable Long id, @Valid @RequestBody ResolveProposalRequest request) {
        return service.resolve(id, request);
    }
}
