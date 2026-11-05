package com.paiban.imports.recipe;

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
 * One reusable import recipe: how a known spreadsheet layout maps onto timetable slots.
 *
 * <p>The recipe is declarative on purpose — a layout, a header fingerprint, column
 * roles, cleaning options and the expected counts. Nothing here is executable, so a
 * recipe saved by 05 and one authored by the AI in 07 have the same shape and the
 * same safety properties.
 */
@Entity
@Table(name = "import_recipe")
@Getter
@Setter
@NoArgsConstructor
public class ImportRecipe {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 120, unique = true)
    private String name;

    /** LONG for one row per slot, GRID for a class-by-day grid (07 may add GRID). */
    @Column(nullable = false, length = 16)
    private String layout;

    @Column(name = "fingerprint_json", nullable = false, columnDefinition = "TEXT")
    private String fingerprintJson;

    @Column(name = "columns_json", nullable = false, columnDefinition = "TEXT")
    private String columnsJson;

    @Column(name = "options_json", nullable = false, columnDefinition = "TEXT")
    private String optionsJson;

    @Column(name = "expected_json", columnDefinition = "TEXT")
    private String expectedJson;

    @Column(name = "sample_json", columnDefinition = "TEXT")
    private String sampleJson;

    @Column(nullable = false)
    private int version = 1;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "created_by", nullable = false, length = 64)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    /** Last time a preview or commit applied this recipe; 06 sorts by it. */
    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    /** Ticket 03 soft-delete semantics; 06 restores by clearing this column. */
    @Column(name = "deleted_at")
    private Instant deletedAt;
}
