package com.paiban.scheduling.web;

import com.paiban.identity.domain.Permissions;
import com.paiban.scheduling.service.ConstraintWorkspaceService;
import com.paiban.scheduling.web.ConstraintWorkspaceDtos.ConstraintWorkspaceDto;
import com.paiban.scheduling.web.ConstraintWorkspaceDtos.SaveWorkspaceRequest;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Unsaved work of the signed-in account; there is no cross-account access. */
@RestController
@RequestMapping("/api/constraint-workspace")
@PreAuthorize("hasAuthority('" + Permissions.CONSTRAINT_MANAGE + "')")
public class ConstraintWorkspaceController {

    private final ConstraintWorkspaceService service;

    public ConstraintWorkspaceController(ConstraintWorkspaceService service) {
        this.service = service;
    }

    @GetMapping
    public ConstraintWorkspaceDto get(@RequestParam Long semesterId) {
        return service.get(semesterId);
    }

    /** Autosave. A stale {@code baseRevision} returns 409 with the other device's copy. */
    @PutMapping
    public ConstraintWorkspaceDto save(
            @RequestParam Long semesterId, @Valid @RequestBody SaveWorkspaceRequest request) {
        return service.save(semesterId, request);
    }

    /** Discards the account's unfinished work in this semester. */
    @DeleteMapping
    public void delete(@RequestParam Long semesterId) {
        service.delete(semesterId);
    }
}
