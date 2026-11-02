package com.paiban.scheduling.web;

import com.paiban.identity.domain.Permissions;
import com.paiban.scheduling.service.DraftCyclePlanService;
import com.paiban.scheduling.web.DraftCyclePlanDtos.ApplyRequest;
import com.paiban.scheduling.web.DraftCyclePlanDtos.ApplyResponse;
import com.paiban.scheduling.web.DraftCyclePlanDtos.SearchRequest;
import com.paiban.scheduling.web.DraftCyclePlanDtos.SearchResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class DraftCyclePlanController {

    private final DraftCyclePlanService service;

    public DraftCyclePlanController(DraftCyclePlanService service) {
        this.service = service;
    }

    /** Searches up to three verified plans; never changes the timetable or its version. */
    @PostMapping("/timetable-drafts/{id}/cycle-plans/search")
    @PreAuthorize("hasAuthority('" + Permissions.TIMETABLE_EDIT + "')")
    public SearchResponse search(
            @PathVariable Long id, @Valid @RequestBody SearchRequest request) {
        return service.search(id, request);
    }

    /** Applies one one-time token as a single atomic change list. */
    @PostMapping("/timetable-drafts/{id}/cycle-plans/apply")
    @PreAuthorize("hasAuthority('" + Permissions.TIMETABLE_EDIT + "')")
    public ApplyResponse apply(
            @PathVariable Long id, @Valid @RequestBody ApplyRequest request) {
        return service.apply(id, request);
    }
}
