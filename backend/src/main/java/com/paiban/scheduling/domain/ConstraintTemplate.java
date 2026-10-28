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

@Entity
@Table(name = "constraint_template")
@Getter
@Setter
@NoArgsConstructor
public class ConstraintTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String name;

    /**
     * The whole snapshot as JSON. Widened to VARCHAR(65535) in V29 because a template
     * carries every entry of a semester; {@code ConstraintTemplateService.TEMPLATE_JSON_LIMIT}
     * refuses anything bigger instead of truncating.
     */
    @Column(name = "configs_json", nullable = false, length = 65535)
    private String configsJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
