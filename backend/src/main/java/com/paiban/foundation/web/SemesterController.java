package com.paiban.foundation.web;

import com.paiban.common.deletion.DeleteMode;
import com.paiban.common.deletion.EntityType;
import com.paiban.identity.domain.Permissions;
import com.paiban.foundation.service.LifecycleService;
import com.paiban.foundation.service.SemesterService;
import com.paiban.foundation.web.LifecycleDtos.DeleteImpactDto;
import com.paiban.foundation.web.LifecycleDtos.DeleteResultDto;
import com.paiban.foundation.web.SemesterDtos.GenerateDefaultPeriodsRequest;
import com.paiban.foundation.web.SemesterDtos.PeriodDto;
import com.paiban.foundation.web.SemesterDtos.SemesterDto;
import com.paiban.foundation.web.SemesterDtos.SavePeriodsRequest;
import com.paiban.foundation.web.SemesterDtos.SaveSemesterRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/semesters")
public class SemesterController {

    private final SemesterService service;
    private final LifecycleService lifecycle;

    public SemesterController(SemesterService service, LifecycleService lifecycle) {
        this.service = service;
        this.lifecycle = lifecycle;
    }

    @GetMapping
    public List<SemesterDto> list() {
        return service.list();
    }

    @GetMapping("/current")
    public ResponseEntity<SemesterDto> current() {
        Optional<SemesterDto> current = service.current();
        return current.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/{id}")
    public SemesterDto get(@PathVariable Long id) {
        return service.get(id);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('" + Permissions.SEMESTER_MANAGE + "')")
    public SemesterDto create(@Valid @RequestBody SaveSemesterRequest request) {
        return service.create(request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.SEMESTER_MANAGE + "')")
    public SemesterDto update(@PathVariable Long id, @Valid @RequestBody SaveSemesterRequest request) {
        return service.update(id, request);
    }

    @GetMapping("/{id}/delete-impact")
    @PreAuthorize("hasAuthority('" + Permissions.SEMESTER_MANAGE + "')")
    public DeleteImpactDto deleteImpact(@PathVariable Long id) {
        return lifecycle.impact(EntityType.SEMESTER, id);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.SEMESTER_MANAGE + "')")
    public DeleteResultDto delete(
            @PathVariable Long id, @RequestParam(required = false) String mode) {
        return lifecycle.delete(EntityType.SEMESTER, id, DeleteMode.parse(mode));
    }

    @PostMapping("/{id}/disable")
    @PreAuthorize("hasAuthority('" + Permissions.SEMESTER_MANAGE + "')")
    public void disable(@PathVariable Long id) {
        lifecycle.disable(EntityType.SEMESTER, id);
    }

    @PostMapping("/{id}/enable")
    @PreAuthorize("hasAuthority('" + Permissions.SEMESTER_MANAGE + "')")
    public void enable(@PathVariable Long id) {
        lifecycle.enable(EntityType.SEMESTER, id);
    }

    @PostMapping("/{id}/set-current")
    @PreAuthorize("hasAuthority('" + Permissions.SEMESTER_MANAGE + "')")
    public SemesterDto setCurrent(@PathVariable Long id) {
        return service.setCurrent(id);
    }

    @GetMapping("/{id}/periods")
    public List<PeriodDto> periods(@PathVariable Long id) {
        return service.listPeriods(id);
    }

    @PutMapping("/{id}/periods")
    @PreAuthorize("hasAuthority('" + Permissions.SEMESTER_MANAGE + "')")
    public List<PeriodDto> savePeriods(@PathVariable Long id, @Valid @RequestBody SavePeriodsRequest request) {
        return service.replacePeriods(id, request.slots());
    }

    @PostMapping("/{id}/periods/default")
    @PreAuthorize("hasAuthority('" + Permissions.SEMESTER_MANAGE + "')")
    public List<PeriodDto> generateDefaultPeriods(
            @PathVariable Long id, @RequestBody(required = false) GenerateDefaultPeriodsRequest request) {
        return service.generateDefaultPeriods(id, request);
    }
}
