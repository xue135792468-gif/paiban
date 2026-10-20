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

    @Column(name = "configs_json", nullable = false, length = 8000)
    private String configsJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
