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
 * One editable parameter set of a constraint type (ADR-0028, ticket-14 revision).
 *
 * <p>A row can pause itself and override the type's hard/soft choice and soft weight;
 * a null override means "inherit the type default", which is what keeps an upgraded
 * configuration meaning exactly what it meant before. {@code groupKey} is the stable
 * identity of a requirement that spans several rows (one per expanded slot); rows
 * without one are individual requirements.
 */
@Entity
@Table(name = "constraint_entry")
@Getter
@Setter
@NoArgsConstructor
public class ConstraintEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "semester_id", nullable = false)
    private Long semesterId;

    @Column(nullable = false, length = 64)
    private String type;

    @Column(name = "params_json", nullable = false, length = 4000)
    private String paramsJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private boolean enabled = true;

    /** Null means: inherit the type's hard/soft choice. */
    @Column(name = "hard")
    private Boolean hard;

    /** Null means: inherit the type's soft weight. */
    @Column(name = "weight")
    private Integer weight;

    /** Stable identity of a multi-row requirement; null means this row is its own. */
    @Column(name = "group_key", length = 64)
    private String groupKey;
}
