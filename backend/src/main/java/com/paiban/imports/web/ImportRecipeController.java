package com.paiban.imports.web;

import com.paiban.identity.domain.Permissions;
import com.paiban.imports.ImportDtos.RecipeDto;
import com.paiban.imports.ImportDtos.RecipeMatchDto;
import com.paiban.imports.ImportDtos.SaveRecipeRequest;
import com.paiban.imports.recipe.ImportRecipeService;
import java.security.Principal;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Import recipes. 05 only needs list/save/match so the manual page can remember a
 * layout; the management page (06) reuses the same read shape.
 */
@RestController
@RequestMapping("/api/import-recipes")
public class ImportRecipeController {

    private final ImportRecipeService recipes;

    public ImportRecipeController(ImportRecipeService recipes) {
        this.recipes = recipes;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + Permissions.FOUNDATION_MANAGE + "')")
    public List<RecipeDto> list() {
        return recipes.list();
    }

    @PostMapping
    @PreAuthorize("hasAuthority('" + Permissions.FOUNDATION_MANAGE + "')")
    public RecipeDto save(@RequestBody SaveRecipeRequest request, Principal principal) {
        return recipes.save(request, principal == null ? "system" : principal.getName());
    }

    @PostMapping(value = "/match", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('" + Permissions.FOUNDATION_MANAGE + "')")
    public RecipeMatchDto match(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "mapping", required = false) String mapping) {
        return recipes.match(file, mapping);
    }
}
