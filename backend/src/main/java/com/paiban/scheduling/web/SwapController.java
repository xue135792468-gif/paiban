package com.paiban.scheduling.web;

import com.paiban.identity.domain.Permissions;
import com.paiban.scheduling.service.SwapService;
import com.paiban.scheduling.web.SwapDtos.SwapRequest;
import com.paiban.scheduling.web.SwapDtos.SwapResult;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class SwapController {

    private final SwapService service;

    public SwapController(SwapService service) {
        this.service = service;
    }

    @PostMapping("/timetable-drafts/{id}/swap")
    @PreAuthorize("hasAuthority('" + Permissions.TIMETABLE_EDIT + "')")
    public SwapResult swap(@PathVariable Long id, @Valid @RequestBody SwapRequest request) {
        return service.swap(id, request.lessonId(), request.targetLessonId(),
                Boolean.TRUE.equals(request.swapRooms()));
    }
}
