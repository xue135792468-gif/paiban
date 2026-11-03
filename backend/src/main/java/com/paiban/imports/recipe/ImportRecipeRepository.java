package com.paiban.imports.recipe;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ImportRecipeRepository extends JpaRepository<ImportRecipe, Long> {

    /** Management/matching list: soft-deleted recipes disappear. */
    List<ImportRecipe> findByDeletedAtIsNullOrderByLastUsedAtDescNameAsc();

    List<ImportRecipe> findByDeletedAtIsNullAndEnabledTrueOrderByLastUsedAtDescNameAsc();

    Optional<ImportRecipe> findByNameAndDeletedAtIsNull(String name);

    Optional<ImportRecipe> findByName(String name);

    boolean existsByNameAndDeletedAtIsNull(String name);
}
