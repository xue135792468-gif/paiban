package com.paiban.ai.imports;

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

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
