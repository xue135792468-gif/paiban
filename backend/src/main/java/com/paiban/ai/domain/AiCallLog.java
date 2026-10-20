package com.paiban.ai.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One row per {@code AiGateway} call, successful or not. Never stores an API key
 * and truncates both JSON payloads at 20000 characters.
 */
@Entity
@Table(name = "ai_call_log")
@Getter
@Setter
@NoArgsConstructor
public class AiCallLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_type", nullable = false, length = 50)
    private String taskType;

    @Column(name = "provider_id")
    private Long providerId;

    @Column(name = "provider_name", length = 100)
    private String providerName;

    @Column(name = "model_id")
    private Long modelId;

    @Column(name = "model_name", length = 200)
    private String modelName;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "subject_type", length = 50)
    private String subjectType;

    @Column(name = "subject_id")
    private Long subjectId;

    @Column(nullable = false)
    private boolean success;

    @Column(name = "error_code", length = 50)
    private String errorCode;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "input_tokens")
    private Integer inputTokens;

    @Column(name = "output_tokens")
    private Integer outputTokens;

    @Column(name = "latency_ms")
    private Long latencyMs;

    /** Large text; kept as a plain string so H2 and PostgreSQL agree under ddl-auto=validate. */
    @Column(name = "request_json", columnDefinition = "TEXT")
    private String requestJson;

    @Column(name = "response_json", columnDefinition = "TEXT")
    private String responseJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
