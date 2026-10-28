package com.paiban.scheduling.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paiban.common.web.ApiException;
import com.paiban.scheduling.domain.ConstraintTemplate;
import com.paiban.scheduling.domain.ConstraintTemplateRepository;
import com.paiban.scheduling.solver.ConstraintSettings;
import com.paiban.scheduling.web.ConstraintDtos.ConstraintConfigDto;
import com.paiban.scheduling.web.ConstraintDtos.ConstraintConfigRequest;
import com.paiban.scheduling.web.ConstraintDtos.ConstraintEntryRequest;
import com.paiban.scheduling.web.ConstraintDtos.TemplateApplyPreview;
import com.paiban.scheduling.web.ConstraintDtos.TemplateApplyRequest;
import com.paiban.scheduling.web.ConstraintDtos.TemplateDto;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Constraint templates: a named value snapshot of a semester's configuration that can
 * be saved, listed, previewed, applied to another semester and deleted.
 *
 * <p>A template carries no row identity: entry ids name rows of the semester it was
 * taken from, so an apply plans fresh rows and rewrites class references explicitly.
 * Planning, persistence and the shared config version all go through
 * {@link ConstraintConfigService}, which is what keeps an apply and a manual save
 * reporting the same refusals.
 */
@Service
public class ConstraintTemplateService {

    /**
     * {@code constraint_template.configs_json} is VARCHAR(65535) since V29 (ticket 10/05:
     * 200 teacher-slot entries are already ~12k characters, so the original 8000 could
     * not hold a real template). The limit is still enforced explicitly — an oversized
     * template is refused, never truncated.
     */
    static final int TEMPLATE_JSON_LIMIT = 65535;

    private final ConstraintTemplateRepository templates;
    private final ConstraintConfigService configService;
    private final ObjectMapper objectMapper;

    public ConstraintTemplateService(
            ConstraintTemplateRepository templates,
            ConstraintConfigService configService,
            ObjectMapper objectMapper) {
        this.templates = templates;
        this.configService = configService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public TemplateDto saveTemplate(String name, Long semesterId) {
        Long resolved = configService.resolveSemester(semesterId);
        List<ConstraintConfigRequest> snapshot = configService.snapshotOf(resolved);
        String json = configService.toJson(snapshot);
        if (json.length() > TEMPLATE_JSON_LIMIT) {
            throw ApiException.badRequest("模板内容为 " + json.length() + " 字符，超过 "
                    + TEMPLATE_JSON_LIMIT + " 字符上限；请减少条目或拆分模板");
        }
        ConstraintTemplate template = templates.findByName(name).orElseGet(ConstraintTemplate::new);
        template.setName(name);
        template.setConfigsJson(json);
        if (template.getCreatedAt() == null) {
            template.setCreatedAt(Instant.now());
        }
        return toDto(templates.save(template), snapshot.size(), entryCountOf(snapshot));
    }

    @Transactional(readOnly = true)
    public List<TemplateDto> listTemplates() {
        return templates.findAllByOrderByCreatedAtDesc().stream()
                .map(template -> {
                    List<ConstraintConfigRequest> snapshot = readSnapshot(template);
                    return toDto(template, snapshot.size(), entryCountOf(snapshot));
                })
                .toList();
    }

    /**
     * Replaces the whole semester with the template's configuration. Class references
     * belong to one semester, so they are either rebound explicitly (the caller names
     * the old → new ids) or the apply is refused with every offending row listed.
     * Teachers and courses are global assets and stay as they are when they still
     * exist. Nothing is ever matched by name and no invalid id reaches the database.
     *
     * <p>The refusal is one 409 whose body carries the same two lists the preview
     * returns ({@code errors} and {@code invalidReferences}), so the page parses one
     * shape no matter which of the two it called (see the controller).
     */
    @Transactional
    public List<ConstraintConfigDto> applyTemplate(
            Long templateId, Long semesterId, TemplateApplyRequest request) {
        return applyTemplate(templateId, semesterId, request, null);
    }

    /**
     * Replaces the whole semester with the template's configuration. Class references
     * belong to one semester, so they are either rebound explicitly (the caller names
     * the old → new ids) or the apply is refused with every offending row listed.
     * Teachers and courses are global assets and stay as they are when they still
     * exist. Nothing is ever matched by name and no invalid id reaches the database.
     *
     * <p>The refusal is one 409 whose body carries the same two lists the preview
     * returns ({@code errors} and {@code invalidReferences}), so the page parses one
     * shape no matter which of the two it called (see the controller). Like a manual
     * save, an apply advances the shared version and honours {@code baseVersion}.
     */
    @Transactional
    public List<ConstraintConfigDto> applyTemplate(
            Long templateId, Long semesterId, TemplateApplyRequest request, Long baseVersion) {
        return applyTemplate(templateId, semesterId, request, baseVersion, null);
    }

    @Transactional
    public List<ConstraintConfigDto> applyTemplate(
            Long templateId, Long semesterId, TemplateApplyRequest request, Long baseVersion,
            String actor) {
        Long resolved = configService.resolveSemester(semesterId);
        long base = configService.requireVersion(resolved, baseVersion);
        List<ConstraintConfigRequest> snapshot = prepareSnapshot(
                readSnapshot(requireTemplate(templateId)), request);
        ConstraintConfigService.Planning planning =
                configService.plan(resolved, snapshot, Map.of());
        List<String> errors = configService.refusalErrors(planning);
        if (!errors.isEmpty()) {
            throw ApiException.conflict(
                    "模板无法套用到当前学期：" + String.join("；", errors),
                    configService.refusalBody(planning));
        }
        List<ConstraintConfigDto> result = configService.commitPlan(resolved, base, planning);
        // Applying a template also changes the effective configuration; the caller's
        // stale unsaved draft must not come back after it.
        configService.clearWorkspaceDraft(actor, resolved);
        return result;
    }

    /**
     * The dry run behind the template panel: how much the apply would restore and what
     * stands in its way. It never writes, so the page can show the report before the
     * current configuration is replaced.
     */
    @Transactional(readOnly = true)
    public TemplateApplyPreview previewTemplate(
            Long templateId, Long semesterId, TemplateApplyRequest request) {
        Long resolved = configService.resolveSemester(semesterId);
        List<ConstraintConfigRequest> snapshot = prepareSnapshot(
                readSnapshot(requireTemplate(templateId)), request);
        ConstraintConfigService.Planning planning =
                configService.plan(resolved, snapshot, Map.of());
        int entryCount = planning.configs().stream()
                .mapToInt(config -> config.entries().size())
                .sum();
        return new TemplateApplyPreview(snapshot.size(), entryCount,
                planning.references(), planning.errors());
    }

    @Transactional
    public void deleteTemplate(Long id) {
        if (!templates.existsById(id)) {
            throw ApiException.notFound("约束模板不存在: " + id);
        }
        templates.deleteById(id);
    }

    private TemplateDto toDto(ConstraintTemplate template, int configCount, int entryCount) {
        return new TemplateDto(
                template.getId(), template.getName(), template.getCreatedAt(), configCount, entryCount);
    }

    /**
     * Entries a snapshot will restore. An entry-backed type is however many rows it
     * carries; the legacy single-params shape is one entry (and none when the map is
     * empty, which is what "not configured" looks like for a switch type).
     */
    private int entryCountOf(List<ConstraintConfigRequest> snapshot) {
        return snapshot.stream().mapToInt(this::entryCountOf).sum();
    }

    private int entryCountOf(ConstraintConfigRequest config) {
        if (config.entries() != null) {
            return config.entries().size();
        }
        return config.params() == null || config.params().isEmpty() ? 0 : 1;
    }

    /**
     * The snapshot as the apply/insert path needs it: entry ids are dropped and class
     * ids are rewritten with the caller's explicit old → new mapping.
     *
     * <p>A template is a value snapshot, not an identity — its entry ids name rows of
     * the semester it was taken from, so the apply inserts fresh rows instead of trying
     * to update rows of another semester. A class id the caller did not name keeps its
     * original value, so it is reported as invalid instead of being guessed from a
     * same-named class of the target semester.
     */
    private List<ConstraintConfigRequest> prepareSnapshot(
            List<ConstraintConfigRequest> snapshot, TemplateApplyRequest request) {
        Map<Long, Long> mapping = request == null || request.classMapping() == null
                ? Map.of() : request.classMapping();
        List<ConstraintConfigRequest> prepared = new ArrayList<>();
        for (ConstraintConfigRequest config : snapshot) {
            List<ConstraintEntryRequest> entries = config.entries() == null
                    ? null
                    : config.entries().stream()
                            .map(entry -> new ConstraintEntryRequest(
                                    null, rebindClass(mapping, entry.params()),
                                    entry.enabled(), entry.hard(), entry.weight(), entry.groupKey()))
                            .toList();
            prepared.add(new ConstraintConfigRequest(config.type(),
                    rebindClass(mapping, config.params()), config.weight(), config.hard(),
                    config.enabled(), entries));
        }
        return prepared;
    }

    private Map<String, Object> rebindClass(Map<Long, Long> mapping, Map<String, Object> params) {
        if (params == null) {
            return null;
        }
        Long classId = ConstraintSettings.longValue(params.get("classId"));
        Long mapped = classId == null ? null : mapping.get(classId);
        if (mapped == null || mapped.equals(classId)) {
            return params;
        }
        Map<String, Object> copy = new java.util.LinkedHashMap<>(params);
        copy.put("classId", mapped);
        return copy;
    }

    private ConstraintTemplate requireTemplate(Long templateId) {
        return templates.findById(templateId)
                .orElseThrow(() -> ApiException.notFound("约束模板不存在: " + templateId));
    }

    private List<ConstraintConfigRequest> readSnapshot(ConstraintTemplate template) {
        try {
            return objectMapper.readValue(
                    template.getConfigsJson(), new TypeReference<List<ConstraintConfigRequest>>() {});
        } catch (JsonProcessingException ex) {
            throw ApiException.badRequest("约束模板内容损坏: " + template.getName());
        }
    }
}
