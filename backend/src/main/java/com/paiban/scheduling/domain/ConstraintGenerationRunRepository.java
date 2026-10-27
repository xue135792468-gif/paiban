package com.paiban.scheduling.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ConstraintGenerationRunRepository
        extends JpaRepository<ConstraintGenerationRun, Long> {

    Optional<ConstraintGenerationRun> findByUserIdAndClientRunId(Long userId, String clientRunId);

    List<ConstraintGenerationRun> findByUserIdAndSemesterIdOrderByStartedAtDesc(
            Long userId, Long semesterId, Pageable pageable);

    List<ConstraintGenerationRun> findBySemesterIdOrderByStartedAtDesc(
            Long semesterId, Pageable pageable);

    /**
     * Completes a run only if it is still RUNNING. Zero rows means the user cancelled
     * (or it timed out) while the provider was answering; the caller then records the
     * late answer without touching the status or the proposals.
     */
    @Modifying(clearAutomatically = true)
    @Query("update ConstraintGenerationRun r set r.status = :status, r.rawResponse = :raw, "
            + "r.modelContent = :content, "
            + "r.proposalsJson = :proposals, r.errorCode = :errorCode, "
            + "r.errorMessage = :errorMessage, r.providerName = :providerName, "
            + "r.modelName = :modelName, r.inputTokens = :inputTokens, "
            + "r.outputTokens = :outputTokens, r.latencyMs = :latencyMs, r.finishedAt = :now "
            + "where r.id = :id and r.status = 'RUNNING'")
    int finishRunning(
            @Param("id") Long id,
            @Param("status") String status,
            @Param("raw") String rawResponse,
            @Param("content") String modelContent,
            @Param("proposals") String proposalsJson,
            @Param("errorCode") String errorCode,
            @Param("errorMessage") String errorMessage,
            @Param("providerName") String providerName,
            @Param("modelName") String modelName,
            @Param("inputTokens") Integer inputTokens,
            @Param("outputTokens") Integer outputTokens,
            @Param("latencyMs") Long latencyMs,
            @Param("now") Instant now);

    /** Named completion object used by the service; the query stays positional inside. */
    default int finishRunning(ConstraintRunCompletion completion) {
        return finishRunning(
                completion.runId(),
                completion.status(),
                completion.rawResponse(),
                completion.modelContent(),
                completion.proposalsJson(),
                completion.errorCode(),
                completion.errorMessage(),
                completion.providerName(),
                completion.modelName(),
                completion.inputTokens(),
                completion.outputTokens(),
                completion.latencyMs(),
                Instant.now());
    }

    /** Stores an answer that arrived after the user stopped waiting; never overwrites. */
    @Modifying(clearAutomatically = true)
    @Query("update ConstraintGenerationRun r set r.lateResponse = :raw, "
            + "r.inputTokens = coalesce(r.inputTokens, :inputTokens), "
            + "r.outputTokens = coalesce(r.outputTokens, :outputTokens), "
            + "r.latencyMs = coalesce(r.latencyMs, :latencyMs), r.finishedAt = :now "
            + "where r.id = :id and r.status in ('CANCELLED', 'TIMEOUT') "
            + "and r.lateResponse is null")
    int recordLateResponse(
            @Param("id") Long id,
            @Param("raw") String rawResponse,
            @Param("inputTokens") Integer inputTokens,
            @Param("outputTokens") Integer outputTokens,
            @Param("latencyMs") Long latencyMs,
            @Param("now") Instant now);

    /** Marks a still-running run as cancelled; zero rows means it already finished. */
    @Modifying(clearAutomatically = true)
    @Query("update ConstraintGenerationRun r set r.status = 'CANCELLED', r.finishedAt = :now "
            + "where r.id = :id and r.status = 'RUNNING'")
    int cancelRunning(@Param("id") Long id, @Param("now") Instant now);

    /**
     * A process restart leaves RUNNING rows behind. After the 60-second window plus a
     * minute of grace they are reported as timed out instead of looking alive forever.
     */
    @Modifying(clearAutomatically = true)
    @Query("update ConstraintGenerationRun r set r.status = 'TIMEOUT', "
            + "r.errorCode = 'TIMEOUT', r.errorMessage = :message, r.finishedAt = :now "
            + "where r.id = :id and r.status = 'RUNNING'")
    int expireRunning(
            @Param("id") Long id, @Param("message") String message, @Param("now") Instant now);
}
