package com.paiban.scheduling.service;

import com.paiban.scheduling.domain.ConstraintConfig;
import com.paiban.scheduling.domain.ConstraintType;
import com.paiban.scheduling.domain.ConstraintType.Param;
import com.paiban.scheduling.solver.ConstraintSettings;
import com.paiban.scheduling.web.ConstraintDtos.ConstraintConfigDto;
import com.paiban.scheduling.web.ConstraintDtos.ConstraintConfigRequest;
import com.paiban.scheduling.web.ConstraintDtos.ConstraintPreviewDto;
import com.paiban.scheduling.web.ConstraintDtos.ConstraintSaveAuditDto;
import com.paiban.scheduling.web.ConstraintDtos.ConstraintStateDto;
import com.paiban.scheduling.web.ConstraintDtos.ConstraintTypeDto;
import com.paiban.scheduling.web.ConstraintDtos.ParamDefDto;
import com.paiban.scheduling.web.ConstraintDtos.ProposalDecision;
import com.paiban.scheduling.web.ConstraintDtos.TemplateApplyPreview;
import com.paiban.scheduling.web.ConstraintDtos.TemplateApplyRequest;
import com.paiban.scheduling.web.ConstraintDtos.TemplateDto;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * The constraint module's entry point: the type catalogue plus one facade over
 * {@link ConstraintConfigService} (planning, saving, version, settings) and
 * {@link ConstraintTemplateService} (templates).
 *
 * <p>Every existing caller — the REST controller, the solver, draft validation,
 * publication readiness, substitution and the CLI — keeps this single dependency,
 * while the behaviour lives in the two focused services.
 */
@Service
public class ConstraintService {

    private final ConstraintConfigService configService;
    private final ConstraintTemplateService templateService;

    public ConstraintService(
            ConstraintConfigService configService,
            ConstraintTemplateService templateService) {
        this.configService = configService;
        this.templateService = templateService;
    }

    // ---------------------------------------------------------------- catalogue

    public List<ConstraintTypeDto> types() {
        return List.of(ConstraintType.values()).stream()
                .map(type -> new ConstraintTypeDto(
                        type.name(),
                        type.getDisplayName(),
                        type.getCategory().name(),
                        type.getCategory().getLabel(),
                        type.getDescription(),
                        type.isDefaultHard(),
                        type.getDefaultWeight(),
                        type.isEntryBacked(),
                        type.isSlotGrouped(),
                        type.thresholdScope() == null ? null : type.thresholdScope().objectField(),
                        type.thresholdScope() == null ? null : type.thresholdScope().valueField(),
                        type.getParams().stream().map(this::toDto).toList()))
                .toList();
    }

    // ------------------------------------------------------------- configuration

    public List<ConstraintConfigDto> list(Long semesterId) {
        return configService.list(semesterId);
    }

    public ConstraintStateDto state(Long semesterId) {
        return configService.state(semesterId);
    }

    public List<ConstraintConfigDto> save(
            Long semesterId, List<ConstraintConfigRequest> requests) {
        return configService.save(semesterId, requests);
    }

    public List<ConstraintConfigDto> save(
            Long semesterId, List<ConstraintConfigRequest> requests, Long baseVersion) {
        return configService.save(semesterId, requests, baseVersion);
    }

    public List<ConstraintConfigDto> save(
            Long semesterId, List<ConstraintConfigRequest> requests, Long baseVersion,
            Long runId, List<ProposalDecision> decisions, String actor) {
        return configService.save(semesterId, requests, baseVersion, runId, decisions, actor);
    }

    public ConstraintPreviewDto preview(
            Long semesterId, List<ConstraintConfigRequest> requests) {
        return configService.preview(semesterId, requests);
    }

    public List<ConstraintSaveAuditDto> audits(Long semesterId, boolean all, String actor) {
        return configService.audits(semesterId, all, actor);
    }

    public List<ConstraintConfig> active(Long semesterId) {
        return configService.active(semesterId);
    }

    public boolean hasConfigs(Long semesterId) {
        return configService.hasConfigs(semesterId);
    }

    /** The one settings snapshot every evaluation entry point builds. */
    public ConstraintSettings settings(Long semesterId) {
        return configService.settings(semesterId);
    }

    // ----------------------------------------------------------------- templates

    public TemplateDto saveTemplate(String name, Long semesterId) {
        return templateService.saveTemplate(name, semesterId);
    }

    public List<TemplateDto> listTemplates() {
        return templateService.listTemplates();
    }

    public List<ConstraintConfigDto> applyTemplate(
            Long templateId, Long semesterId, TemplateApplyRequest request) {
        return templateService.applyTemplate(templateId, semesterId, request);
    }

    public List<ConstraintConfigDto> applyTemplate(
            Long templateId, Long semesterId, TemplateApplyRequest request, Long baseVersion) {
        return templateService.applyTemplate(templateId, semesterId, request, baseVersion);
    }

    public List<ConstraintConfigDto> applyTemplate(
            Long templateId, Long semesterId, TemplateApplyRequest request, Long baseVersion,
            String actor) {
        return templateService.applyTemplate(templateId, semesterId, request, baseVersion, actor);
    }

    public TemplateApplyPreview previewTemplate(
            Long templateId, Long semesterId, TemplateApplyRequest request) {
        return templateService.previewTemplate(templateId, semesterId, request);
    }

    public void deleteTemplate(Long id) {
        templateService.deleteTemplate(id);
    }

    private ParamDefDto toDto(Param param) {
        return new ParamDefDto(
                param.name(),
                param.label(),
                param.type().name(),
                param.required(),
                param.min(),
                param.max(),
                param.defaultValue(),
                param.options());
    }
}
