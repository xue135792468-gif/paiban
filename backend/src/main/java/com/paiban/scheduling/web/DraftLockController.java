package com.paiban.scheduling.web;

import com.paiban.identity.domain.Permissions;
import com.paiban.scheduling.service.DraftLockService;
import com.paiban.scheduling.web.DraftLockDtos.LockLessonsRequest;
import com.paiban.scheduling.web.DraftLockDtos.LockResult;
import jakarta.validation.Valid;
import java.security.Principal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class DraftLockController {

    private final DraftLockService service;

    public DraftLockController(DraftLockService service) {
        this.service = service;
    }

    @PutMapping("/timetable-drafts/{id}/lessons/locks")
    @PreAuthorize("hasAuthority('" + Permissions.TIMETABLE_EDIT + "')")
    public LockResult setLocks(
            @PathVariable Long id,
            @Valid @RequestBody LockLessonsRequest request,
            Principal principal) {
        return service.setLocked(
                id,
                request.lessonIds(),
                Boolean.TRUE.equals(request.locked()),
                request.expectedVersion(),
                principal == null ? "system" : principal.getName());
    }
}
