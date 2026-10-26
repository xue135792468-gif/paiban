package com.paiban.scheduling.web;

import com.paiban.identity.domain.Permissions;
import com.paiban.scheduling.service.ConstraintService;
import com.paiban.scheduling.web.ConstraintDtos.ConstraintConfigDto;
import com.paiban.scheduling.web.ConstraintDtos.ConstraintTypeDto;
import com.paiban.scheduling.web.ConstraintDtos.SaveConstraintsRequest;
import com.paiban.scheduling.web.ConstraintDtos.SaveTemplateRequest;
import com.paiban.scheduling.web.ConstraintDtos.TemplateApplyPreview;
import com.paiban.scheduling.web.ConstraintDtos.TemplateApplyRequest;
import com.paiban.scheduling.web.ConstraintDtos.TemplateDto;
import jakarta.validation.Valid;
import java.util.List;
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
@RequestMapping("/api")
public class ConstraintController {

    private final ConstraintService service;

    public ConstraintController(ConstraintService service) {
        this.service = service;
    }

    @GetMapping("/constraint-types")
    public List<ConstraintTypeDto> types() {
        return service.types();
    }

    @GetMapping("/constraints")
    public List<ConstraintConfigDto> list(@RequestParam(required = false) Long semesterId) {
        return service.list(semesterId);
    }

    @PutMapping("/constraints")
    @PreAuthorize("hasAuthority('" + Permissions.CONSTRAINT_MANAGE + "')")
    public List<ConstraintConfigDto> save(
            @RequestParam(required = false) Long semesterId,
            @Valid @RequestBody SaveConstraintsRequest request) {
        return service.save(semesterId, request.configs() == null ? List.of() : request.configs());
    }

    @GetMapping("/constraint-templates")
    public List<TemplateDto> templates() {
        return service.listTemplates();
    }

    @PostMapping("/constraint-templates")
    @PreAuthorize("hasAuthority('" + Permissions.CONSTRAINT_MANAGE + "')")
    public TemplateDto saveTemplate(
            @RequestParam(required = false) Long semesterId,
            @Valid @RequestBody SaveTemplateRequest request) {
        return service.saveTemplate(request.name(), semesterId);
    }

    /**
     * Applies a template to a semester. The optional body names the class ids the
     * template carries so a class of another semester can be rebound explicitly; without
     * it an invalid class reference refuses the whole apply.
     *
     * <p>The refusal is a 409 whose body carries {@code message} plus the same two lists
     * {@link #preview} returns — {@code errors} and {@code invalidReferences} — because a
     * refusal is a business answer the page has to render row by row, not a generic
     * failure. Keeping the business body on the 409 (instead of flattening everything
     * into one string) is what lets the panel run a single parse for both calls.
     */
    @PostMapping("/constraint-templates/{id}/apply")
    @PreAuthorize("hasAuthority('" + Permissions.CONSTRAINT_MANAGE + "')")
    public List<ConstraintConfigDto> apply(
            @PathVariable Long id,
            @RequestParam(required = false) Long semesterId,
            @RequestBody(required = false) TemplateApplyRequest request) {
        return service.applyTemplate(id, semesterId, request);
    }

    /**
     * Dry run for the template panel: nothing is written. Reports the same
     * {@code errors} / {@code invalidReferences} lists the apply refusal carries.
     */
    @PostMapping("/constraint-templates/{id}/preview")
    @PreAuthorize("hasAuthority('" + Permissions.CONSTRAINT_MANAGE + "')")
    public TemplateApplyPreview preview(
            @PathVariable Long id,
            @RequestParam(required = false) Long semesterId,
            @RequestBody(required = false) TemplateApplyRequest request) {
        return service.previewTemplate(id, semesterId, request);
    }

    @DeleteMapping("/constraint-templates/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.CONSTRAINT_MANAGE + "')")
    public void deleteTemplate(@PathVariable Long id) {
        service.deleteTemplate(id);
    }
}
