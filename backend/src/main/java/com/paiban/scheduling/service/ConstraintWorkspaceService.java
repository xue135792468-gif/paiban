package com.paiban.scheduling.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paiban.common.web.ApiException;
import com.paiban.foundation.service.SemesterService;
import com.paiban.identity.domain.AppUser;
import com.paiban.identity.service.UserService;
import com.paiban.scheduling.domain.ConstraintWorkspace;
import com.paiban.scheduling.domain.ConstraintWorkspaceRepository;
import com.paiban.scheduling.web.ConstraintDtos.ConstraintConfigRequest;
import com.paiban.scheduling.web.ConstraintDtos.ConstraintPreviewDto;
import com.paiban.scheduling.web.ConstraintWorkspaceDtos.ConstraintWorkspaceDto;
import com.paiban.scheduling.web.ConstraintWorkspaceDtos.SaveWorkspaceRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-account, per-semester unsaved work (ticket 14/11).
 *
 * <p>Every read and write is scoped to the authenticated account: there is no admin
 * view of somebody else's unfinished work, because it is not a business record. The
 * workspace has its own revision so two devices of the same account cannot silently
 * overwrite each other. It never touches the effective configuration; the successful
 * save removes it.
 */
@Service
public class ConstraintWorkspaceService {

    private static final int MAX_PAYLOAD_BYTES = 1_000_000;

    private final ConstraintWorkspaceRepository workspaces;
    private final ConstraintService constraints;
    private final SemesterService semesters;
    private final UserService users;
    private final ObjectMapper mapper;

    public ConstraintWorkspaceService(
            ConstraintWorkspaceRepository workspaces,
            ConstraintService constraints,
            SemesterService semesters,
            UserService users,
            ObjectMapper mapper) {
        this.workspaces = workspaces;
        this.constraints = constraints;
        this.semesters = semesters;
        this.users = users;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public ConstraintWorkspaceDto get(Long semesterId) {
        Long resolved = semesters.require(semesterId).getId();
        Long userId = users.current().getId();
        return workspaces.findByUserIdAndSemesterId(userId, resolved)
                .map(row -> toDto(row))
                .orElseGet(() -> new ConstraintWorkspaceDto(
                        false, 0, 0, Map.of(), null, List.of(), List.of()));
    }

    @Transactional
    public ConstraintWorkspaceDto save(Long semesterId, SaveWorkspaceRequest request) {
        Long resolved = semesters.require(semesterId).getId();
        Long userId = users.current().getId();
        String payload = toJson(request.payload() == null ? Map.of() : request.payload());
        if (payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES) {
            throw ApiException.badRequest("未保存内容过大，无法暂存");
        }
        long baseRevision = request.baseRevision() == null ? 0 : request.baseRevision();
        long baseVersion = request.baseVersion() == null ? 0 : request.baseVersion();
        Instant now = Instant.now();

        ConstraintWorkspace existing = workspaces
                .findByUserIdAndSemesterId(userId, resolved).orElse(null);
        if (existing == null) {
            if (baseRevision != 0) {
                throw conflict(userId, resolved);
            }
            ConstraintWorkspace row = new ConstraintWorkspace();
            row.setUserId(userId);
            row.setSemesterId(resolved);
            row.setRevision(1L);
            row.setBaseVersion(baseVersion);
            row.setPayloadJson(payload);
            row.setUpdatedAt(now);
            try {
                workspaces.saveAndFlush(row);
            } catch (DataIntegrityViolationException race) {
                throw conflict(userId, resolved);
            }
            return toDto(row);
        }
        int updated = workspaces.updateIfRevision(
                userId, resolved, baseRevision, payload, baseVersion, now);
        if (updated == 0) {
            throw conflict(userId, resolved);
        }
        return toDto(workspaces.findByUserIdAndSemesterId(userId, resolved).orElseThrow());
    }

    @Transactional
    public void delete(Long semesterId) {
        Long resolved = semesters.require(semesterId).getId();
        Long userId = users.current().getId();
        workspaces.deleteByUserIdAndSemesterId(userId, resolved);
    }

    private ApiException conflict(Long userId, Long semesterId) {
        ConstraintWorkspaceDto latest = workspaces
                .findByUserIdAndSemesterId(userId, semesterId)
                .map(this::toDto)
                .orElse(new ConstraintWorkspaceDto(
                        false, 0, 0, Map.of(), null, List.of(), List.of()));
        return ApiException.conflict(
                "未保存的工作已在另一台设备更新；本地内容仍保留，请核对后再决定",
                Map.of("workspace", latest));
    }

    private ConstraintWorkspaceDto toDto(ConstraintWorkspace row) {
        Map<String, Object> payload = fromJson(row.getPayloadJson());
        List<ConstraintConfigRequest> configs = configsOf(payload);
        List<String> errors = List.of();
        List<com.paiban.scheduling.web.ConstraintDtos.TemplateReferenceIssue> references =
                List.of();
        if (!configs.isEmpty()) {
            try {
                ConstraintPreviewDto preview = constraints.preview(row.getSemesterId(), configs);
                errors = preview.errors();
                references = preview.invalidReferences();
            } catch (ApiException ex) {
                errors = List.of(ex.getMessage());
            }
        }
        return new ConstraintWorkspaceDto(true, row.getRevision(), row.getBaseVersion(),
                payload, row.getUpdatedAt(), errors, references);
    }

    private List<ConstraintConfigRequest> configsOf(Map<String, Object> payload) {
        Object raw = payload.get("configs");
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        try {
            return mapper.convertValue(list,
                    new TypeReference<List<ConstraintConfigRequest>>() {});
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    private Map<String, Object> fromJson(String json) {
        try {
            return mapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("序列化未保存工作失败", ex);
        }
    }

}
