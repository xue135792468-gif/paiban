package com.paiban.scheduling.web;

import com.paiban.identity.domain.Permissions;
import com.paiban.scheduling.service.DraftChangeService;
import com.paiban.scheduling.web.DraftChangeDtos.ChangeRequest;
import com.paiban.scheduling.web.DraftChangeDtos.ChangeResult;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class DraftChangeController {

    private final DraftChangeService service;

    public DraftChangeController(DraftChangeService service) {
        this.service = service;
    }

    /**
     * Applies one atomic editor action. Business refusals (a change that would add a
     * conflict) answer 200 with {@code saved=false}; stale versions, stale source rows,
     * stale rules and locks answer 409; malformed or foreign input answers 400.
     */
    @PostMapping("/timetable-drafts/{id}/changes")
    @PreAuthorize("hasAuthority('" + Permissions.TIMETABLE_EDIT + "')")
    public ChangeResult apply(
            @PathVariable Long id, @Valid @RequestBody ChangeRequest request) {
        return service.apply(id, request);
    }
}
