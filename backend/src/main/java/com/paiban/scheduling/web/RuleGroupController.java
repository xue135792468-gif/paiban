package com.paiban.scheduling.web;

import com.paiban.identity.domain.Permissions;
import com.paiban.scheduling.service.RuleGroupService;
import com.paiban.scheduling.web.RuleGroupDtos.RuleGroupDto;
import com.paiban.scheduling.web.RuleGroupDtos.RuleGroupImpactDto;
import com.paiban.scheduling.web.RuleGroupDtos.RuleGroupRequest;
import java.util.List;
import java.util.Map;
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

/**
 * Teacher / course / time groups (ticket 04). Reading also serves the rule page's
 * dropdowns, so it accepts the three management permissions; writing stays with the
 * rule manager because a group changes the effective rules.
 */
@RestController
@RequestMapping("/api/rule-groups")
public class RuleGroupController {

    private final RuleGroupService service;

    public RuleGroupController(RuleGroupService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('" + Permissions.CONSTRAINT_MANAGE + "','"
            + Permissions.FOUNDATION_MANAGE + "','" + Permissions.SEMESTER_MANAGE + "')")
    public List<RuleGroupDto> list(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Long semesterId) {
        return service.list(type, semesterId);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('" + Permissions.CONSTRAINT_MANAGE + "')")
    public RuleGroupDto create(@RequestBody RuleGroupRequest request) {
        return service.create(request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.CONSTRAINT_MANAGE + "')")
    public RuleGroupDto update(@PathVariable Long id, @RequestBody RuleGroupRequest request) {
        return service.update(id, request);
    }

    @GetMapping("/{id}/delete-impact")
    @PreAuthorize("hasAuthority('" + Permissions.CONSTRAINT_MANAGE + "')")
    public RuleGroupImpactDto deleteImpact(@PathVariable Long id) {
        return service.deleteImpact(id);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.CONSTRAINT_MANAGE + "')")
    public Map<String, Object> delete(
            @PathVariable Long id,
            @RequestParam(required = false) String mode) {
        return service.delete(id, mode);
    }
}
