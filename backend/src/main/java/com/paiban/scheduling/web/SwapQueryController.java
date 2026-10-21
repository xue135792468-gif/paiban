package com.paiban.scheduling.web;

import com.paiban.identity.domain.Permissions;
import com.paiban.scheduling.service.SwapCandidateService;
import com.paiban.scheduling.web.SwapQueryDtos.MoveTargetsResponse;
import com.paiban.scheduling.web.SwapQueryDtos.SwapCandidatesResponse;
import com.paiban.scheduling.web.SwapQueryDtos.SwapType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only endpoints for the manual adjustment panel. They stay out of
 * {@link SchedulingController} so the write-side swap work can evolve separately.
 */
@RestController
@RequestMapping("/api/timetable-drafts")
public class SwapQueryController {

    private final SwapCandidateService service;

    public SwapQueryController(SwapCandidateService service) {
        this.service = service;
    }

    @GetMapping("/{id}/lessons/{lessonId}/swap-candidates")
    @PreAuthorize("hasAnyAuthority('" + Permissions.TIMETABLE_EDIT + "','" + Permissions.SCHEDULING_VIEW
            + "','" + Permissions.TIMETABLE_PUBLISH + "')")
    public SwapCandidatesResponse swapCandidates(
            @PathVariable Long id,
            @PathVariable Long lessonId,
            @RequestParam(defaultValue = "ALL") SwapType type,
            @RequestParam(required = false) Long courseId,
            @RequestParam(required = false) Long gradeId,
            @RequestParam(required = false) Long classId,
            @RequestParam(required = false) Long teacherId,
            @RequestParam(defaultValue = "false") boolean includeUnavailable) {
        return service.swapCandidates(
                id,
                lessonId,
                type,
                new SwapCandidateService.CandidateFilters(courseId, gradeId, classId, teacherId),
                includeUnavailable);
    }

    @GetMapping("/{id}/lessons/{lessonId}/move-targets")
    @PreAuthorize("hasAnyAuthority('" + Permissions.TIMETABLE_EDIT + "','" + Permissions.SCHEDULING_VIEW
            + "','" + Permissions.TIMETABLE_PUBLISH + "')")
    public MoveTargetsResponse moveTargets(@PathVariable Long id, @PathVariable Long lessonId) {
        return service.moveTargets(id, lessonId);
    }
}
