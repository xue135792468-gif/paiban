package com.paiban.ai.domain;

import com.paiban.ai.AiTaskType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
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
        name = "ai_task_config",
        uniqueConstraints = @UniqueConstraint(name = "uk_ai_task_config_task", columnNames = "task_type"))
@Getter
@Setter
@NoArgsConstructor
public class AiTaskConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Convert(converter = AiTaskTypeConverter.class)
    @Column(name = "task_type", nullable = false, length = 50)
    private AiTaskType taskType;

    /** unified / custom */
    @Column(nullable = false, length = 20)
    private String mode;

    @Column(name = "provider_id")
    private Long providerId;

    @Column(name = "model_id")
    private Long modelId;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
