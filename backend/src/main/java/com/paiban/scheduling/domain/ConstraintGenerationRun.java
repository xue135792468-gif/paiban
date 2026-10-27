package com.paiban.scheduling.domain;

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
 * One natural-language generation run (ticket 14/08).
 *
 * <p>{@code clientRunId} is the browser-generated identity of one click: a repeated
 * request with the same id returns this row instead of calling the model again. The
 * raw answer, the normalized proposals and the failure/cancellation all live on this
 * row, so the generic (truncating) AI call log never has to stand in for the business
 * record.
 */
@Entity
@Table(name = "constraint_generation_run")
@Getter
@Setter
@NoArgsConstructor
public class ConstraintGenerationRun {

    public static final String RUNNING = "RUNNING";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String FAILED = "FAILED";
    public static final String CANCELLED = "CANCELLED";
    public static final String TIMEOUT = "TIMEOUT";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_run_id", nullable = false, length = 64)
    private String clientRunId;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "semester_id", nullable = false)
    private Long semesterId;

    @Column(name = "task_type", nullable = false, length = 50)
    private String taskType;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "input_text", nullable = false, columnDefinition = "TEXT")
    private String inputText;

    /** The names/timetable/catalogue actually sent, for the audit trail. */
    @Column(name = "context_json", columnDefinition = "TEXT")
    private String contextJson;

    /** The provider's complete HTTP body, never truncated. */
    @Column(name = "raw_response", columnDefinition = "TEXT")
    private String rawResponse;

    /** The assistant content the parser read (the JSON answer itself). */
    @Column(name = "model_content", columnDefinition = "TEXT")
    private String modelContent;

    /** An answer that arrived after the run was cancelled or timed out. */
    @Column(name = "late_response", columnDefinition = "TEXT")
    private String lateResponse;

    @Column(name = "proposals_json", columnDefinition = "TEXT")
    private String proposalsJson;

    @Column(name = "error_code", length = 50)
    private String errorCode;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "provider_name", length = 200)
    private String providerName;

    @Column(name = "model_name", length = 200)
    private String modelName;

    @Column(name = "input_tokens")
    private Integer inputTokens;

    @Column(name = "output_tokens")
    private Integer outputTokens;

    @Column(name = "latency_ms")
    private Long latencyMs;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt = Instant.now();

    @Column(name = "finished_at")
    private Instant finishedAt;
}
