package com.paiban.ai.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiProviderRepository extends JpaRepository<AiProvider, Long> {

    Optional<AiProvider> findByName(String name);

    List<AiProvider> findAllByOrderByIdAsc();
}
