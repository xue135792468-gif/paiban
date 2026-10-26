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
 * One editable parameter set of a constraint type (ADR-0028). The type-level
 * switches stay on {@link ConstraintConfig}; an entry only carries its object and
 * conditions. The id is the stable identity the editor sends back on update.
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
}
