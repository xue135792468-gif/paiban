package com.paiban.scheduling.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paiban.common.web.ApiException;
import com.paiban.foundation.service.SemesterService;
import com.paiban.identity.domain.Permissions;
import com.paiban.identity.service.UserService;
import com.paiban.scheduling.domain.ConstraintGenerationRun;
import com.paiban.scheduling.domain.ConstraintGenerationRunRepository;
import com.paiban.scheduling.domain.ConstraintSaveAudit;
import com.paiban.scheduling.domain.ConstraintSaveAuditRepository;
import com.paiban.scheduling.web.ConstraintDtos.ConstraintConfigDto;
import com.paiban.scheduling.web.ConstraintDtos.ConstraintSaveAuditDto;
import com.paiban.scheduling.web.ConstraintDtos.ProposalDecision;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Confirmation audit for effective-configuration saves. The save transaction also
 * writes the exact previous and final configuration, the user's proposal decisions and
 * the computed diff; reading them back is a separate concern from planning a save.
 */
@Service
public class ConstraintAuditService {

    public record AuditContext(Long userId, String actor, Long runId) {}

    private final ConstraintSaveAuditRepository audits;
    private final ConstraintGenerationRunRepository generationRuns;
    private final UserService users;
    private final SemesterService semesters;
    private final ObjectMapper objectMapper;

    public ConstraintAuditService(
            ConstraintSaveAuditRepository audits,
            ConstraintGenerationRunRepository generationRuns,
            UserService users,
            SemesterService semesters,
            ObjectMapper objectMapper) {
        this.audits = audits;
        this.generationRuns = generationRuns;
        this.users = users;
        this.semesters = semesters;
        this.objectMapper = objectMapper;
    }

    /**
     * Validates the generation run a save claims to come from before any write: it must
     * belong to this semester, and a non-admin may only save decisions about their own
     * run. A manual save (no run) is always allowed.
     */
    public AuditContext prepare(Long semesterId, Long runId, String actor) {
        Long userId = userIdOf(actor);
        if (runId == null) {
            return new AuditContext(userId, actor, null);
        }
        if (userId == null) {
            throw ApiException.unauthorized("无法确认当前用户，不能关联生成记录");
        }
        ConstraintGenerationRun run = generationRuns.findById(runId)
                .orElseThrow(() -> ApiException.badRequest("生成记录不存在: " + runId));
        if (!semesterId.equals(run.getSemesterId())) {
            throw ApiException.badRequest("生成记录属于其他学期，不能用于本次保存");
        }
        if (!isAdmin() && !userId.equals(run.getUserId())) {
            throw ApiException.forbidden("不能保存他人的生成记录");
        }
        return new AuditContext(userId, actor, runId);
    }

    /** The final configuration and the decisions are written in the save transaction. */
    public void record(
            Long semesterId, long version, AuditContext audit,
            List<ProposalDecision> decisions, List<ConstraintConfigDto> previous,
            List<ConstraintConfigDto> result) {
        ConstraintSaveAudit row = new ConstraintSaveAudit();
        row.setSemesterId(semesterId);
        row.setVersion(version);
        row.setUserId(audit.userId());
        row.setActor(audit.actor());
        row.setRunId(audit.runId());
        row.setDecisionsJson(toJson(decisions == null ? List.of() : decisions));
        row.setPreviousConfigJson(toJson(previous));
        row.setFinalConfigJson(toJson(result));
        row.setDiffJson(toJson(ConstraintConfigDiff.diff(previous, result)));
        row.setCreatedAt(Instant.now());
        audits.save(row);
    }

    /** The audit list; a non-admin can only see their own saves. */
    public List<ConstraintSaveAuditDto> audits(Long semesterId, boolean all, String actor) {
        Long resolved = resolveSemester(semesterId);
        Long userId = userIdOf(actor);
        if (all && !isAdmin()) {
            throw ApiException.forbidden("只有设置管理员可以查看全部保存记录");
        }
        PageRequest page = PageRequest.of(0, 50);
        List<ConstraintSaveAudit> rows = all
                ? audits.findBySemesterIdOrderByCreatedAtDesc(resolved, page)
                : audits.findBySemesterIdAndUserIdOrderByCreatedAtDesc(resolved, userId, page);
        return rows.stream().map(this::toAuditDto).toList();
    }

    public Long userIdOf(String actor) {
        if (actor == null || !StringUtils.hasText(actor)) {
            return null;
        }
        try {
            return users.getByUsername(actor).getId();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    public boolean isAdmin() {
        return users.hasPermission(Permissions.SETTINGS_MANAGE);
    }

    private Long resolveSemester(Long semesterId) {
        return semesterId != null
                ? semesters.require(semesterId).getId()
                : semesters.requireCurrent().getId();
    }

    private ConstraintSaveAuditDto toAuditDto(ConstraintSaveAudit row) {
        List<ProposalDecision> decisions;
        List<ConstraintConfigDto> configs;
        try {
            decisions = row.getDecisionsJson() == null ? List.of()
                    : objectMapper.readValue(row.getDecisionsJson(),
                    new TypeReference<List<ProposalDecision>>() {});
        } catch (JsonProcessingException ex) {
            decisions = List.of();
        }
        try {
            configs = objectMapper.readValue(row.getFinalConfigJson(),
                    new TypeReference<List<ConstraintConfigDto>>() {});
        } catch (JsonProcessingException ex) {
            configs = List.of();
        }
        List<ConstraintConfigDto> previous;
        try {
            previous = row.getPreviousConfigJson() == null ? List.of()
                    : objectMapper.readValue(row.getPreviousConfigJson(),
                    new TypeReference<List<ConstraintConfigDto>>() {});
        } catch (JsonProcessingException ex) {
            previous = List.of();
        }
        Map<String, Object> diff;
        try {
            diff = row.getDiffJson() == null ? Map.of()
                    : objectMapper.readValue(row.getDiffJson(),
                    new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException ex) {
            diff = Map.of();
        }
        return new ConstraintSaveAuditDto(row.getId(), row.getSemesterId(), row.getVersion(),
                row.getRunId(), row.getActor(), row.getCreatedAt(), decisions, configs,
                previous, diff);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("序列化约束审计失败", ex);
        }
    }
}
