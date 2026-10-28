package com.paiban.substitution.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubstitutionConversionItemRepository extends JpaRepository<SubstitutionConversionItem, Long> {

    List<SubstitutionConversionItem> findByConversionIdOrderByIdAsc(Long conversionId);
}
