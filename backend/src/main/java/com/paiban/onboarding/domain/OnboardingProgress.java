package com.paiban.onboarding.domain;

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
@Table(name = "onboarding_progress")
@Getter
@Setter
@NoArgsConstructor
public class OnboardingProgress {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    @Column(name = "current_step", nullable = false)
    private int currentStep;

    @Column(name = "completed_steps_json", length = 2000)
    private String completedStepsJson;

    @Column(name = "data_json", length = 4000)
    private String dataJson;

    @Column(nullable = false)
    private boolean skipped;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
