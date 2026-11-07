package com.paiban.scheduling.web;

import com.paiban.identity.domain.Permissions;
import com.paiban.scheduling.service.ConstraintCopyService;
import com.paiban.scheduling.service.ConstraintService;
import com.paiban.scheduling.service.RuleImpactService;
import com.paiban.scheduling.service.RuleLifecycleService;
import com.paiban.scheduling.web.ConstraintDtos.ConstraintConfigDto;
import com.paiban.scheduling.web.ConstraintDtos.ConstraintPreviewDto;
import com.paiban.scheduling.web.ConstraintDtos.ConstraintPreviewRequest;
import com.paiban.scheduling.web.ConstraintDtos.ConstraintSaveAuditDto;
import com.paiban.scheduling.web.ConstraintDtos.ConstraintStateDto;
import com.paiban.scheduling.web.ConstraintDtos.ConstraintTypeDto;
import com.paiban.scheduling.web.ConstraintDtos.CopyRulesRequest;
import com.paiban.scheduling.web.ConstraintDtos.CopyRulesResult;
import com.paiban.scheduling.web.ConstraintDtos.RuleImpactDto;
import com.paiban.scheduling.web.ConstraintDtos.RuleImpactRequest;
import com.paiban.scheduling.web.ConstraintDtos.SaveConstraintsRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
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
    private final ConstraintCopyService copyService;
    private final RuleImpactService impact;
    private final RuleLifecycleService rules;

    public ConstraintController(
            ConstraintService service, ConstraintCopyService copyService,
            RuleImpactService impact, RuleLifecycleService rules) {
        this.service = service;
        this.copyService = copyService;
        this.impact = impact;
        this.rules = rules;
    }

    @GetMapping("/constraint-types")
    public List<ConstraintTypeDto> types() {
        return service.types();
    }

    @GetMapping("/constraints")
    public List<ConstraintConfigDto> list(@RequestParam(required = false) Long semesterId) {
        return service.visibleList(semesterId);
    }

    /**
     * The effective configuration plus the version a writer must send back as its base.
     * The plain {@code /constraints} list stays for readers that only need the rows.
     */
    @GetMapping("/constraints/state")
    public ConstraintStateDto state(@RequestParam(required = false) Long semesterId) {
        return service.state(semesterId);
    }

    /**
     * {@code baseVersion} makes the save conditional: a version that no longer matches
     * returns 409 with the latest configuration instead of overwriting it. A request
     * without it is accepted only while the stored data has no per-requirement shape.
     */
    @PutMapping("/constraints")
    @PreAuthorize("hasAuthority('" + Permissions.CONSTRAINT_MANAGE + "')")
    public List<ConstraintConfigDto> save(
            @RequestParam(required = false) Long semesterId,
            @RequestParam(required = false) Long baseVersion,
            @Valid @RequestBody SaveConstraintsRequest request,
            Authentication authentication) {
        return service.save(
                semesterId, request.configs() == null ? List.of() : request.configs(), baseVersion,
                request.runId(), request.decisions(),
                authentication == null ? null : authentication.getName());
    }

    /**
     * Copies one semester's whole non-system rule set onto another (ticket 08). A
     * {@code baseVersion} that no longer matches the target returns 409. Cards whose
     * references cannot be resolved are copied paused and reported in {@code issues}.
     */
    @PostMapping("/constraints/copy-rules")
    @PreAuthorize("hasAuthority('" + Permissions.CONSTRAINT_MANAGE + "')")
    public CopyRulesResult copyRules(
            @Valid @RequestBody CopyRulesRequest request, Authentication authentication) {
        return copyService.copy(
                request, authentication == null ? null : authentication.getName());
    }

    /**
     * Dry run used by "添加到约束表": validates the merged configuration without
     * writing or advancing the version, so a bad merge is refused before the page's
     * draft changes.
     */
    @PostMapping("/constraints/preview")
    @PreAuthorize("hasAuthority('" + Permissions.CONSTRAINT_MANAGE + "')")
    public ConstraintPreviewDto preview(
            @RequestParam(required = false) Long semesterId,
            @Valid @RequestBody ConstraintPreviewRequest request) {
        return service.preview(
                semesterId, request.configs() == null ? List.of() : request.configs());
    }

    /**
     * The rule page's "what would this touch?" dry run: affected classes, teachers and
     * slots, the lessons of the newest draft inside that scope, and the hard conflicts
     * of the shared checker. Nothing is written.
     */
    @PostMapping("/rules/impact")
    @PreAuthorize("hasAuthority('" + Permissions.CONSTRAINT_MANAGE + "')")
    public RuleImpactDto impact(@Valid @RequestBody RuleImpactRequest request) {
        return impact.impact(
                request.semesterId(),
                request.configs() == null ? List.of() : request.configs());
    }

    /**
     * Saved effective configurations with the proposal decisions that produced them.
     * {@code scope=all} needs the settings administrator; the default is "mine".
     */
    @GetMapping("/constraint-audits")
    @PreAuthorize("hasAuthority('" + Permissions.CONSTRAINT_MANAGE + "')")
    public List<ConstraintSaveAuditDto> audits(
            @RequestParam(required = false) Long semesterId,
            @RequestParam(defaultValue = "mine") String scope,
            Authentication authentication) {
        return service.audits(semesterId, "all".equalsIgnoreCase(scope),
                authentication == null ? null : authentication.getName());
    }

    /**
     * Deletes one rule card (ticket 04). {@code cardKey} is the card id (the group-key
     * prefix); leaving it out deletes the whole type row, which is what the switch cards
     * and the advanced page use. The rows are paused and soft-deleted, so the 7-day
     * recycle bin can revive exactly this card without touching the others.
     */
    @DeleteMapping("/rules/{configId}")
    @PreAuthorize("hasAuthority('" + Permissions.CONSTRAINT_MANAGE + "')")
    public Map<String, Object> deleteRule(
            @PathVariable Long configId,
            @RequestParam(required = false) String cardKey,
            @RequestParam(required = false) List<Long> entryIds) {
        return rules.delete(configId, cardKey, entryIds);
    }
}
