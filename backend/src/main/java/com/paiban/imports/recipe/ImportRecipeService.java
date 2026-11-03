package com.paiban.imports.recipe;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paiban.common.web.ApiException;
import com.paiban.imports.ImportDtos.ManualMapping;
import com.paiban.imports.ImportDtos.RecipeDifferenceDto;
import com.paiban.imports.ImportDtos.RecipeDto;
import com.paiban.imports.ImportDtos.RecipeMatchDto;
import com.paiban.imports.ImportDtos.SaveRecipeRequest;
import com.paiban.imports.manual.ManualSuggestion;
import com.paiban.imports.manual.SourceSheets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Recipes are the "no AI required" memory of the importer: after one successful manual
 * mapping, the next file with the same shape is recognized and prefilled. Matching is a
 * pure fingerprint comparison (EXACT / SIMILAR / NONE), the same rule 06's management
 * page and 07's AI-authored recipes use.
 */
@Service
public class ImportRecipeService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ImportRecipeRepository recipes;
    private final ImportRecipeVersionRepository versions;
    private final ObjectMapper objectMapper;

    public ImportRecipeService(
            ImportRecipeRepository recipes,
            ImportRecipeVersionRepository versions,
            ObjectMapper objectMapper) {
        this.recipes = recipes;
        this.versions = versions;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<RecipeDto> list() {
        return recipes.findByDeletedAtIsNullOrderByLastUsedAtDescNameAsc().stream()
                .map(this::toDto)
                .toList();
    }

    /**
     * Saves the current mapping under {@code name}. Saving an existing name updates that
     * recipe in place and writes a new version snapshot, so 06 can roll back.
     */
    @Transactional
    public RecipeDto save(SaveRecipeRequest request, String username) {
        if (request == null || !StringUtils.hasText(request.name())) {
            throw ApiException.badRequest("请填写配方名称");
        }
        if (request.columns() == null || request.columns().isEmpty()) {
            throw ApiException.badRequest("配方缺少列映射");
        }
        String name = request.name().trim();
        ImportRecipe recipe = recipes.findByName(name).orElse(null);
        if (recipe != null && recipe.getDeletedAt() != null) {
            throw ApiException.conflict("同名配方已存在且已被删除，请换一个名称");
        }
        boolean created = recipe == null;
        if (created) {
            recipe = new ImportRecipe();
            recipe.setName(name);
            recipe.setCreatedBy(username);
            recipe.setVersion(1);
        } else {
            recipe.setVersion(recipe.getVersion() + 1);
        }
        recipe.setLayout(layoutOf(request.layout(), request.fingerprint()));
        recipe.setFingerprintJson(write(request.fingerprint() == null ? Map.of() : request.fingerprint()));
        recipe.setColumnsJson(write(request.columns()));
        recipe.setOptionsJson(write(request.options() == null ? Map.of() : request.options()));
        recipe.setExpectedJson(request.expected() == null ? null : write(request.expected()));
        recipe.setSampleJson(request.sample() == null ? null : write(request.sample()));
        recipe.setEnabled(true);
        ImportRecipe saved = recipes.save(recipe);
        versions.save(snapshot(saved, username));
        return toDto(saved);
    }

    /**
     * Classifies the uploaded sheet against every enabled recipe. The first EXACT wins;
     * otherwise the first SIMILAR (same sheet and column count) is returned with a
     * concrete list of what changed. Applying a match records {@code last_used_at}.
     */
    @Transactional
    public RecipeMatchDto match(org.springframework.web.multipart.MultipartFile file, String mappingJson) {
        List<SourceSheets.Sheet> sheets = SourceSheets.read(file);
        ManualMapping mapping = null;
        if (StringUtils.hasText(mappingJson)) {
            try {
                mapping = objectMapper.readValue(mappingJson, ManualMapping.class);
            } catch (JsonProcessingException ex) {
                throw ApiException.badRequest("列映射格式不正确：" + ex.getMessage());
            }
        }
        return match(SourceSheets.select(sheets, mapping == null ? null : mapping.sheetName()), mapping);
    }

    @Transactional
    public RecipeMatchDto match(SourceSheets.Sheet sheet, ManualMapping requestedMapping) {
        ManualMapping probe = requestedMapping != null && requestedMapping.hasLayout()
                ? requestedMapping
                : ManualSuggestion.suggest(sheet);
        ImportFingerprint fingerprint = ImportFingerprint.of(sheet, probe);
        ImportRecipe exact = null;
        ImportRecipe similar = null;
        List<RecipeDifferenceDto> similarDifferences = List.of();
        for (ImportRecipe recipe : recipes.findByDeletedAtIsNullAndEnabledTrueOrderByLastUsedAtDescNameAsc()) {
            ImportFingerprint saved = readFingerprint(recipe);
            if (same(fingerprint, saved)) {
                exact = recipe;
                break;
            }
            if (similar == null
                    && saved.sheetName().equals(fingerprint.sheetName())
                    && saved.columnCount() == fingerprint.columnCount()) {
                similar = recipe;
                similarDifferences = differences(saved, fingerprint);
            }
        }
        if (exact != null) {
            markUsed(exact);
            return new RecipeMatchDto("EXACT", exact.getId(), exact.getName(), List.of(), mappingOf(exact));
        }
        if (similar != null) {
            markUsed(similar);
            return new RecipeMatchDto(
                    "SIMILAR", similar.getId(), similar.getName(), similarDifferences, mappingOf(similar));
        }
        return RecipeMatchDto.none();
    }

    /** Records that a preview or commit applied the recipe; 06 sorts by this. */
    @Transactional
    public void markUsed(Long recipeId) {
        recipes.findById(recipeId).ifPresent(recipe -> {
            recipe.setLastUsedAt(Instant.now());
            recipes.save(recipe);
        });
    }

    private void markUsed(ImportRecipe recipe) {
        recipe.setLastUsedAt(Instant.now());
        recipes.save(recipe);
    }

    private boolean same(ImportFingerprint left, ImportFingerprint right) {
        return left.sheetName().equals(right.sheetName())
                && left.headerRow() == right.headerRow()
                && left.columnCount() == right.columnCount()
                && left.headers().equals(right.headers())
                && left.delimiter().equals(right.delimiter())
                && left.layout().equals(right.layout());
    }

    /** What changed without disqualifying the recipe: header texts, delimiter, layout, header row. */
    private List<RecipeDifferenceDto> differences(ImportFingerprint saved, ImportFingerprint current) {
        List<RecipeDifferenceDto> differences = new ArrayList<>();
        int shared = Math.min(saved.headers().size(), current.headers().size());
        for (int index = 0; index < shared; index++) {
            String before = saved.headers().get(index);
            String after = current.headers().get(index);
            if (!before.equals(after)) {
                differences.add(new RecipeDifferenceDto(
                        "RENAMED", String.valueOf(index + 1),
                        "表头「" + before + "」改为「" + after + "」"));
            }
        }
        for (int index = shared; index < current.headers().size(); index++) {
            String added = current.headers().get(index);
            differences.add(new RecipeDifferenceDto(
                    "ADDED", added, "当前文件新增列「" + added + "」"));
        }
        for (int index = shared; index < saved.headers().size(); index++) {
            String removed = saved.headers().get(index);
            differences.add(new RecipeDifferenceDto(
                    "REMOVED", removed, "配方里的列「" + removed + "」在当前文件中不存在"));
        }
        if (!saved.delimiter().equals(current.delimiter())) {
            differences.add(new RecipeDifferenceDto(
                    "RENAMED", "分隔符",
                    "分隔符由「" + saved.delimiter() + "」改为「" + current.delimiter() + "」"));
        }
        if (saved.headerRow() != current.headerRow()) {
            differences.add(new RecipeDifferenceDto(
                    "RENAMED", "表头行",
                    "表头行由第 " + (saved.headerRow() + 1) + " 行改为第 "
                            + (current.headerRow() + 1) + " 行"));
        }
        if (!saved.layout().equals(current.layout())) {
            differences.add(new RecipeDifferenceDto(
                    "RENAMED", "布局",
                    "布局由 " + saved.layout() + " 改为 " + current.layout()));
        }
        return List.copyOf(differences);
    }

    private ImportRecipeVersion snapshot(ImportRecipe recipe, String username) {
        ImportRecipeVersion version = new ImportRecipeVersion();
        version.setRecipeId(recipe.getId());
        version.setVersion(recipe.getVersion());
        version.setSnapshotJson(write(toDto(recipe)));
        version.setCreatedBy(username);
        return version;
    }

    private RecipeDto toDto(ImportRecipe recipe) {
        return new RecipeDto(
                recipe.getId(),
                recipe.getName(),
                recipe.getLayout(),
                readMap(recipe.getFingerprintJson()),
                readMap(recipe.getColumnsJson()),
                readMap(recipe.getOptionsJson()),
                readMap(recipe.getExpectedJson()),
                readValue(recipe.getSampleJson()),
                recipe.getVersion(),
                recipe.isEnabled(),
                recipe.getCreatedBy(),
                recipe.getCreatedAt(),
                recipe.getLastUsedAt());
    }

    private ManualMapping mappingOf(ImportRecipe recipe) {
        try {
            return objectMapper.readValue(recipe.getColumnsJson(), ManualMapping.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("配方列映射无法解析：" + recipe.getName(), ex);
        }
    }

    private ImportFingerprint readFingerprint(ImportRecipe recipe) {
        return ImportFingerprint.fromMap(readMap(recipe.getFingerprintJson()));
    }

    private String layoutOf(String requested, Map<String, Object> fingerprint) {
        if (StringUtils.hasText(requested)) {
            return requested;
        }
        Object fromFingerprint = fingerprint == null ? null : fingerprint.get("layout");
        return fromFingerprint == null ? ImportFingerprint.LAYOUT_LONG : fromFingerprint.toString();
    }

    private Map<String, Object> readMap(String json) {
        if (!StringUtils.hasText(json)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (JsonProcessingException ex) {
            return Map.of();
        }
    }

    private Object readValue(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (JsonProcessingException ex) {
            return null;
        }
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("配方内容无法序列化", ex);
        }
    }
}
