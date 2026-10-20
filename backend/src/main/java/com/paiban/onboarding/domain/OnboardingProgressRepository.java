package com.paiban.onboarding.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OnboardingProgressRepository extends JpaRepository<OnboardingProgress, Long> {

    Optional<OnboardingProgress> findByUserId(Long userId);

    void deleteByUserId(Long userId);
}
