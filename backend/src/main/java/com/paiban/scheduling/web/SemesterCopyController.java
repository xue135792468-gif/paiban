package com.paiban.scheduling.web;

import com.paiban.foundation.web.SemesterDtos.CopyResultDto;
import com.paiban.identity.domain.Permissions;
import com.paiban.scheduling.service.SemesterCopyService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/semesters")
public class SemesterCopyController {

    private final SemesterCopyService service;

    public SemesterCopyController(SemesterCopyService service) {
        this.service = service;
    }

    /** Copies periods, grades, classes and assignments from another semester. */
    @PostMapping("/{id}/copy-from/{sourceId}")
    @PreAuthorize("hasAuthority('" + Permissions.SEMESTER_MANAGE + "')")
    public CopyResultDto copy(@PathVariable Long id, @PathVariable Long sourceId) {
        return service.copy(sourceId, id);
    }
}
