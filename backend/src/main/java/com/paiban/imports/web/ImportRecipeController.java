package com.paiban.imports.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paiban.common.web.ApiException;
import com.paiban.identity.domain.Permissions;
import com.paiban.imports.ImportDtos.RecipeDto;
import com.paiban.imports.ImportDtos.RecipeMatchDto;
import com.paiban.imports.ImportDtos.RecipePatchRequest;
import com.paiban.imports.ImportDtos.RecipeTestDto;
import com.paiban.imports.ImportDtos.RecipeVersionDto;
import com.paiban.imports.ImportDtos.SaveRecipeRequest;
import com.paiban.imports.recipe.ImportRecipeService;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Import recipes. The manual page (05) saves and matches; the data-management page (06)
 * lists, inspects, tests, versions, rolls back and soft-deletes. Saving always carries
 * the sample spreadsheet so the server can re-run the mapping before writing.
 */
@RestController
@RequestMapping("/api/import-recipes")
public class ImportRecipeController {

    private static final Logger log = LoggerFactory.getLogger(ImportRecipeController.class);

    private final ImportRecipeService recipes;
    private final ObjectMapper objectMapper;

    public ImportRecipeController(ImportRecipeService recipes, ObjectMapper objectMapper) {
        this.recipes = recipes;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + Permissions.FOUNDATION_MANAGE + "')")
    public List<RecipeDto> list() {
        return recipes.list();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.FOUNDATION_MANAGE + "')")
    public RecipeDto detail(@PathVariable Long id) {
        return recipes.detail(id);
    }

    /**
     * Multipart save: a {@code request} JSON part plus the sample file. The sample is
     * required for a new recipe and re-used from disk for an update that does not
     * upload a new one.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('" + Permissions.FOUNDATION_MANAGE + "')")
    public RecipeDto save(
            @RequestParam("request") String requestJson,
            @RequestParam(value = "sampleFile", required = false) MultipartFile sampleFile,
            Principal principal) {
        return recipes.save(
                read(requestJson, SaveRecipeRequest.class), sampleFile, actor(principal));
    }

    /** Mapping/name/enabled edit; an optional sample file replaces the stored sample. */
    @PatchMapping(value = "/{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('" + Permissions.FOUNDATION_MANAGE + "')")
    public RecipeDto update(
            @PathVariable Long id,
            @RequestParam("request") String requestJson,
            @RequestParam(value = "sampleFile", required = false) MultipartFile sampleFile,
            Principal principal) {
        return recipes.update(id, read(requestJson, RecipePatchRequest.class), sampleFile, actor(principal));
    }

    @PostMapping("/{id}/test")
    @PreAuthorize("hasAuthority('" + Permissions.FOUNDATION_MANAGE + "')")
    public RecipeTestDto test(@PathVariable Long id) {
        return recipes.test(id);
    }

    @GetMapping("/{id}/versions")
    @PreAuthorize("hasAuthority('" + Permissions.FOUNDATION_MANAGE + "')")
    public List<RecipeVersionDto> versions(@PathVariable Long id) {
        return recipes.listVersions(id);
    }

    @PostMapping("/{id}/rollback/{version}")
    @PreAuthorize("hasAuthority('" + Permissions.FOUNDATION_MANAGE + "')")
    public RecipeDto rollback(
            @PathVariable Long id, @PathVariable int version, Principal principal) {
        return recipes.rollback(id, version, actor(principal));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.FOUNDATION_MANAGE + "')")
    public Map<String, Object> delete(@PathVariable Long id) {
        return recipes.delete(id);
    }

    @PostMapping(value = "/match", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('" + Permissions.FOUNDATION_MANAGE + "')")
    public RecipeMatchDto match(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "mapping", required = false) String mapping) {
        return recipes.match(file, mapping);
    }

    private <T> T read(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception ex) {
            // The parser message names internal classes and offsets; log it, answer in Chinese.
            log.warn("配方请求体无法解析", ex);
            throw ApiException.badRequest("配方内容格式不正确，请检查后重试");
        }
    }

    private String actor(Principal principal) {
        return principal == null ? "system" : principal.getName();
    }
}
