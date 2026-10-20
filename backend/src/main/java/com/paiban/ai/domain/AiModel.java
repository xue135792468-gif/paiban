package com.paiban.ai.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(
        name = "ai_model",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_ai_model_provider_name",
                columnNames = {"provider_id", "model_name"}))
@Getter
@Setter
@NoArgsConstructor
public class AiModel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "provider_id", nullable = false)
    private Long providerId;

    @Column(name = "model_name", nullable = false, length = 200)
    private String modelName;

    @Column(name = "display_name", nullable = false, length = 200)
    private String displayName;

    @Column(name = "vision_capable", nullable = false)
    private boolean visionCapable;

    @Column(nullable = false)
    private boolean enabled = true;

    /** Free-form OpenAI-style parameters, e.g. {"temperature":0.7,"max_tokens":4096}. */
    @Column(name = "params_json", length = 4000)
    private String paramsJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
