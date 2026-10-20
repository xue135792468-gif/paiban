package com.paiban.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paiban.ai.AiImage;
import com.paiban.ai.AiMessage;
import com.paiban.ai.AiRequest;
import com.paiban.ai.AiResult;
import com.paiban.ai.domain.AiCallLog;
import com.paiban.ai.domain.AiCallLogRepository;
import com.paiban.identity.domain.AppUser;
import com.paiban.identity.service.UserService;
import java.time.Instant;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Writes one {@code ai_call_log} row per gateway call.
 *
 * <p>Deliberately defensive: the row carries the request and response payloads
 * truncated at 20000 characters and never the API key, and any failure while
 * auditing is swallowed so it cannot break the business call.
 */
@Component
public class AiCallLogWriter {

    static final int MAX_JSON_LENGTH = 20000;
    private static final int MAX_MESSAGE_LENGTH = 1000;

    private final AiCallLogRepository callLogs;
    private final UserService users;
    private final ObjectMapper objectMapper;

    public AiCallLogWriter(AiCallLogRepository callLogs, UserService users, ObjectMapper objectMapper) {
        this.callLogs = callLogs;
        this.users = users;
        this.objectMapper = objectMapper;
    }

    public void record(AiRequest request, AiTarget target, AiResult result) {
        try {
            AiCallLog row = new AiCallLog();
            row.setTaskType(request.taskType().value());
            if (target != null) {
                row.setProviderId(target.provider().getId());
                row.setProviderName(target.provider().getName());
                row.setModelId(target.model().getId());
                row.setModelName(target.model().getModelName());
            }
            row.setUserId(currentUserId());
            row.setSubjectType(request.subjectType());
            row.setSubjectId(request.subjectId());
            row.setSuccess(result.success());
            row.setErrorCode(result.errorCode());
            row.setErrorMessage(truncate(result.errorMessage(), MAX_MESSAGE_LENGTH));
            row.setInputTokens(result.inputTokens());
            row.setOutputTokens(result.outputTokens());
            row.setLatencyMs(result.latencyMs());
            row.setRequestJson(truncate(requestJson(request), MAX_JSON_LENGTH));
            row.setResponseJson(truncate(result.rawResponse(), MAX_JSON_LENGTH));
            row.setCreatedAt(Instant.now());
            callLogs.save(row);
        } catch (RuntimeException ex) {
            // Auditing must never surface into the business flow.
        }
    }

    /** A call so malformed that it has no task type still leaves an audit trail. */
    public void recordInvalid(String message) {
        try {
            AiCallLog row = new AiCallLog();
            row.setTaskType("invalid");
            row.setSuccess(false);
            row.setErrorCode("INVALID_REQUEST");
            row.setErrorMessage(truncate(message, MAX_MESSAGE_LENGTH));
            row.setLatencyMs(0L);
            row.setCreatedAt(Instant.now());
            callLogs.save(row);
        } catch (RuntimeException ex) {
            // Auditing must never surface into the business flow.
        }
    }

    /** Prompt shape only: no base URL, no key, no image bytes. */
    private String requestJson(AiRequest request) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("taskType", request.taskType().value());
        if (request.subjectType() != null) {
            root.put("subjectType", request.subjectType());
        }
        if (request.subjectId() != null) {
            root.put("subjectId", request.subjectId());
        }
        if (request.options() != null) {
            if (request.options().temperature() != null) {
                root.put("temperature", request.options().temperature());
            }
            if (request.options().maxTokens() != null) {
                root.put("maxTokens", request.options().maxTokens());
            }
            root.put("jsonMode", request.options().jsonMode());
        }
        ArrayNode messages = root.putArray("messages");
        for (AiMessage message : request.messages() == null ? List.<AiMessage>of() : request.messages()) {
            messages.addObject().put("role", message.role()).put("content", message.content());
        }
        List<AiImage> images = request.images() == null ? List.of() : request.images();
        if (!images.isEmpty()) {
            ArrayNode nodes = root.putArray("images");
            for (AiImage image : images) {
                nodes.addObject()
                        .put("mimeType", image.mimeType())
                        .put("bytes", image.data() == null ? 0 : image.data().length);
            }
        }
        return root.toString();
    }

    /** Best-effort resolution; a missing or unknown principal is logged as null. */
    private Long currentUserId() {
        try {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication == null || !authentication.isAuthenticated()) {
                return null;
            }
            String username = authentication.getName();
            if (username == null || username.isBlank() || "anonymousUser".equals(username)) {
                return null;
            }
            AppUser user = users.getByUsername(username);
            return user == null ? null : user.getId();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
