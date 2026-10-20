package com.paiban.ai.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiModelRepository extends JpaRepository<AiModel, Long> {

    List<AiModel> findByProviderIdOrderByIdAsc(Long providerId);

    Optional<AiModel> findByIdAndProviderId(Long id, Long providerId);

    Optional<AiModel> findByProviderIdAndModelName(Long providerId, String modelName);
}
