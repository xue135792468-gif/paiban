package com.paiban.ai.imports;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiImportLogRepository extends JpaRepository<AiImportLog, Long> {

    List<AiImportLog> findByUsernameOrderByCreatedAtDesc(String username);
}
