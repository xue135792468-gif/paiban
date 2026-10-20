package com.paiban.ai.domain;

import com.paiban.ai.AiTaskType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiTaskConfigRepository extends JpaRepository<AiTaskConfig, Long> {

    Optional<AiTaskConfig> findByTaskType(AiTaskType taskType);

    List<AiTaskConfig> findAllByOrderByIdAsc();

    void deleteByProviderId(Long providerId);

    void deleteByModelId(Long modelId);
}
