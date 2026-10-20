package com.paiban.foundation.web;

import com.paiban.identity.domain.Permissions;
import com.paiban.foundation.service.SemesterService;
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
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/semesters")
public class SemesterController {

    private final SemesterService service;

    public SemesterController(SemesterService service) {
        this.service = service;
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

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.SEMESTER_MANAGE + "')")
    public void delete(@PathVariable Long id) {
        service.delete(id);
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
