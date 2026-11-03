package com.paiban.imports.recipe;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ImportRecipeVersionRepository extends JpaRepository<ImportRecipeVersion, Long> {

    List<ImportRecipeVersion> findByRecipeIdOrderByVersionDesc(Long recipeId);

    Optional<ImportRecipeVersion> findByRecipeIdAndVersion(Long recipeId, int version);
}
