package com.paiban.foundation.web;

import com.paiban.foundation.service.LifecycleService;
import com.paiban.foundation.web.LifecycleDtos.RecentDeletionDto;
import com.paiban.foundation.web.LifecycleDtos.RestoreResultDto;
import com.paiban.identity.domain.Permissions;
import java.security.Principal;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The 7-day recycle bin. Default reads only open batches (not restored, not
 * expired); the “已恢复/已过期” history is opt-in.
 */
@RestController
@RequestMapping("/api/recent-deletions")
public class RecentDeletionController {

    private final LifecycleService lifecycle;

    public RecentDeletionController(LifecycleService lifecycle) {
        this.lifecycle = lifecycle;
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('" + Permissions.FOUNDATION_MANAGE + "','"
            + Permissions.SEMESTER_MANAGE + "','" + Permissions.ASSIGNMENT_MANAGE + "')")
    public List<RecentDeletionDto> list(
            @RequestParam(defaultValue = "false") boolean includeClosed) {
        return lifecycle.recent(includeClosed);
    }

    @PostMapping("/{batchId}/restore")
    @PreAuthorize("hasAnyAuthority('" + Permissions.FOUNDATION_MANAGE + "','"
            + Permissions.SEMESTER_MANAGE + "','" + Permissions.ASSIGNMENT_MANAGE + "')")
    public RestoreResultDto restore(@PathVariable String batchId, Principal principal) {
        return lifecycle.restore(batchId, principal == null ? null : principal.getName());
    }
}
