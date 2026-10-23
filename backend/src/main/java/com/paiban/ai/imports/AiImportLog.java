package com.paiban.ai.imports;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Persisted AI import session. The row is the audit trail and the working state:
 * preview, user corrections and the final result all live here.
 */
@Entity
@Table(name = "ai_import_log")
@Getter
@Setter
@NoArgsConstructor
public class AiImportLog {

    public static final String PREVIEW = "PREVIEW";
    public static final String EDITED = "EDITED";
    public static final String COMMITTED = "COMMITTED";
    public static final String FAILED = "FAILED";
    /** A large upload is being parsed in the background; the page polls for progress. */
    public static final String PARSING = "PARSING";
    /**
     * Some batches parsed and some did not: the finished ones are kept and previewable,
     * but the import is locked until the failed classes are retried or abandoned.
     */
    public static final String PARTIAL = "PARTIAL";
    /**
     * The user gave up on an unfinished import. The row stays as an audit record of what
     * was parsed, but nothing is written and the session can never move forward again.
     */
    public static final String ABANDONED = "ABANDONED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @Column(length = 64)
    private String username;

    @Column(name = "import_type", nullable = false, length = 20)
    private String importType;

    @Column(name = "target_type", nullable = false, length = 30)
    private String targetType;

    @Column(name = "file_name", length = 255)
    private String fileName;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "file_path", length = 500)
    private String filePath;

    @Column(name = "source_text", columnDefinition = "TEXT")
    private String sourceText;

    @Column(name = "preview_json", columnDefinition = "TEXT")
    private String previewJson;

    @Column(name = "confirmed_json", columnDefinition = "TEXT")
    private String confirmedJson;

    @Column(name = "result_json", columnDefinition = "TEXT")
    private String resultJson;

    @Column(name = "ai_explanation", columnDefinition = "TEXT")
    private String aiExplanation;

    @Column(name = "ai_raw_response", columnDefinition = "TEXT")
    private String aiRawResponse;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "row_count", nullable = false)
    private int rowCount;

    @Column(name = "imported_count", nullable = false)
    private int importedCount;

    /** 0 when the session was parsed in one call (small tables, text, images). */
    @Column(name = "total_batches", nullable = false)
    private int totalBatches;

    @Column(name = "completed_batches", nullable = false)
    private int completedBatches;

    /** Label (usually the class name) of the batch being parsed; null when idle. */
    @Column(name = "current_batch", length = 180)
    private String currentBatch;

    /** One entry per batch: its status, source rows and parsed result. */
    @Column(name = "batches_json", columnDefinition = "TEXT")
    private String batchesJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    /**
     * Optimistic lock. The background parser and a stop/abandon request can hold the same
     * row at the same time; without this the last writer wins and an abandoned session
     * could be written back to {@code PARSING} by a batch that was still in flight.
     */
    @Version
    @Column(nullable = false)
    private Long version;
}
