package com.paiban.imports.recipe;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paiban.common.deletion.DeletionRestoreHandler;
import com.paiban.common.deletion.RecentDeletionWriter;
import com.paiban.common.web.ApiException;
import com.paiban.imports.ImportDtos.ManualMapping;
import com.paiban.imports.ImportDtos.RecipeCountDifferenceDto;
import com.paiban.imports.ImportDtos.RecipeCounts;
import com.paiban.imports.ImportDtos.RecipeDifferenceDto;
import com.paiban.imports.ImportDtos.RecipeDto;
import com.paiban.imports.ImportDtos.RecipeMatchDto;
import com.paiban.imports.ImportDtos.RecipePatchRequest;
import com.paiban.imports.ImportDtos.RecipeTestDto;
import com.paiban.imports.ImportDtos.RecipeVersionDto;
import com.paiban.imports.ImportDtos.SaveRecipeRequest;
import com.paiban.imports.manual.ManualSheetParser;
import com.paiban.imports.manual.ManualSheetParser.Parsed;
import com.paiban.imports.manual.ManualSuggestion;
import com.paiban.imports.manual.SourceSheets;
import com.paiban.imports.manual.SourceSheets.Sheet;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

/**
 * Recipes are the "no AI required" memory of the importer: after one successful manual
 * mapping, the next file with the same shape is recognized and prefilled. Matching is a
 * pure fingerprint comparison (EXACT / SIMILAR / NONE), the same rule 06's management
 * page and 07's AI-authored recipes use.
 *
 * <p>Ticket 06 adds the management side: a recipe carries its sample file, every save
 * re-runs that sample and refuses on a count mismatch, every save writes a version
 * snapshot, and delete is the ticket-03 soft delete with a 7-day restore.
 */
@Service
public class ImportRecipeService implements DeletionRestoreHandler {

    private static final Logger log = LoggerFactory.getLogger(ImportRecipeService.class);

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private static final String ENTITY_TYPE = "IMPORT_RECIPE";

    private static final String KIND_LABEL = "导入配方";

    private static final int MAX_NAME_LENGTH = 120;

    private static final Pattern DELETED_SUFFIX =
            Pattern.compile("（已删除 [0-9a-fA-F]{8}）$");

    /** Display order and Chinese labels of the five counts a recipe promises. */
    private static final Map<String, String> COUNT_LABELS = new LinkedHashMap<>();

    static {
        COUNT_LABELS.put("slotCount", "槽数");
        COUNT_LABELS.put("lessonCount", "有课数");
        COUNT_LABELS.put("emptySlotCount", "空槽数");
        COUNT_LABELS.put("teacherCount", "教师数");
        COUNT_LABELS.put("courseCount", "课程数");
    }

    private final ImportRecipeRepository recipes;
    private final ImportRecipeVersionRepository versions;
    private final ObjectMapper objectMapper;
    private final ManualSheetParser parser;
    private final RecipeSampleStorage sampleStorage;
    private final RecentDeletionWriter deletionWriter;

    public ImportRecipeService(
            ImportRecipeRepository recipes,
            ImportRecipeVersionRepository versions,
            ObjectMapper objectMapper,
            ManualSheetParser parser,
            RecipeSampleStorage sampleStorage,
            RecentDeletionWriter deletionWriter) {
        this.recipes = recipes;
        this.versions = versions;
        this.objectMapper = objectMapper;
        this.parser = parser;
        this.sampleStorage = sampleStorage;
        this.deletionWriter = deletionWriter;
    }

    // ---------------------------------------------------------------- reads

    @Transactional(readOnly = true)
    public List<RecipeDto> list() {
        return recipes.findByDeletedAtIsNullOrderByLastUsedAtDescNameAsc().stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public RecipeDto detail(Long id) {
        return toDto(requireLive(id));
    }

    /**
     * Classifies the uploaded sheet against every enabled recipe. The first EXACT wins;
     * otherwise the first SIMILAR (same sheet name, or the same layout with enough
     * shared headers) is returned with a concrete list of what changed — including a
     * column that was added or removed, which changes the column count.
     *
     * <p>Matching is read-only on purpose: it never records {@code last_used_at}. A
     * caller that really applies the recipe (the manual preview/commit with no mapping
     * of its own) calls {@link #markUsed(Long)} itself, so an inspection probe or a
     * request that brought its own mapping cannot claim the recipe was used.
     */
    @Transactional(readOnly = true)
    public RecipeMatchDto match(MultipartFile file, String mappingJson) {
        List<SourceSheets.Sheet> sheets = SourceSheets.read(file);
        ManualMapping mapping = null;
        if (StringUtils.hasText(mappingJson)) {
            mapping = readMapping(mappingJson);
        }
        return match(SourceSheets.select(sheets, mapping == null ? null : mapping.sheetName()), mapping);
    }

    @Transactional(readOnly = true)
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
            if (similar == null && similarTo(saved, fingerprint)) {
                similar = recipe;
                similarDifferences = differences(saved, fingerprint);
            }
        }
        if (exact != null) {
            return new RecipeMatchDto(
                    "EXACT", exact.getId(), exact.getName(), List.of(),
                    readMap(exact.getExpectedJson()), mappingOf(exact));
        }
        if (similar != null) {
            return new RecipeMatchDto(
                    "SIMILAR", similar.getId(), similar.getName(), similarDifferences,
                    readMap(similar.getExpectedJson()), mappingOf(similar));
        }
        return RecipeMatchDto.none();
    }

    /** Re-runs the stored sample with the recipe's current mapping and expected counts. */
    @Transactional(readOnly = true)
    public RecipeTestDto test(Long id) {
        ImportRecipe recipe = requireLive(id);
        Map<String, Object> sample = readMap(recipe.getSampleJson());
        byte[] bytes = sampleStorage.read(sample);
        ManualMapping mapping = mappingOf(recipe);
        return analyze(bytes, sampleStorage.fileNameOf(sample), mapping,
                readMap(recipe.getExpectedJson())).test();
    }

    @Transactional(readOnly = true)
    public List<RecipeVersionDto> listVersions(Long id) {
        requireLive(id);
        return versions.findByRecipeIdOrderByVersionDesc(id).stream()
                .map(version -> new RecipeVersionDto(
                        version.getVersion(), version.getCreatedAt(), version.getCreatedBy(),
                        summaryOf(version.getSnapshotJson())))
                .toList();
    }

    // ---------------------------------------------------------------- save / update

    /**
     * Saves the current mapping under {@code name}. Saving an existing name updates that
     * recipe in place. The sample is re-run server-side first: a count mismatch is a 409
     * with itemised differences and nothing is written (no new version either).
     */
    @Transactional
    public RecipeDto save(SaveRecipeRequest request, MultipartFile sampleFile, String username) {
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
        boolean uploaded = sampleFile != null && !sampleFile.isEmpty();
        if (created && !uploaded) {
            throw ApiException.badRequest("保存配方必须上传样例文件，服务端会先用样例跑一遍");
        }
        if (created) {
            recipe = new ImportRecipe();
            recipe.setName(name);
            recipe.setCreatedBy(username);
        }
        ManualMapping mapping = mappingOf(request.columns());
        RecipeSampleStorage.PendingSample pending =
                uploaded ? sampleStorage.validate(sampleFile) : null;
        Map<String, Object> existingSample = created ? Map.of() : readMap(recipe.getSampleJson());
        byte[] bytes = pending != null ? pending.bytes() : sampleStorage.read(existingSample);
        String fileName = pending != null
                ? pending.fileName() : sampleStorage.fileNameOf(existingSample);
        Map<String, Object> target = expectedTarget(
                request.expected(), created ? null : readMap(recipe.getExpectedJson()));
        Analysis analysis = analyze(bytes, fileName, mapping, target);
        if (!analysis.test().pass()) {
            throw sampleMismatch(analysis.test());
        }

        recipe.setLayout(layoutOf(mapping));
        recipe.setFingerprintJson(write(analysis.fingerprint().toMap()));
        recipe.setColumnsJson(write(request.columns()));
        recipe.setOptionsJson(write(request.options() == null ? Map.of() : request.options()));
        recipe.setExpectedJson(write(analysis.test().expected()));
        // Only a new recipe starts enabled. Saving over an existing (possibly disabled)
        // recipe re-tests and updates it; it must not silently switch it back on.
        if (created) {
            recipe.setEnabled(true);
        }
        recipe.setVersion(created ? 1 : recipe.getVersion() + 1);
        ImportRecipe saved = saveGuarded(recipe, "配方名称已被占用，请刷新后重试");
        if (pending != null) {
            saved.setSampleJson(write(sampleStorage.store(saved.getId(), pending)));
            saved = saveGuarded(saved, "配方名称已被占用，请刷新后重试");
        }
        versions.save(snapshot(saved, username));
        return toDto(saved);
    }

    /**
     * Edits a recipe. Mapping/options/expected changes (or a new sample file) must pass
     * the sample test; name/enabled-only edits skip it. A new sample replaces the stored
     * file and is tested before the row changes.
     */
    @Transactional
    public RecipeDto update(Long id, RecipePatchRequest patch, MultipartFile sampleFile, String username) {
        ImportRecipe recipe = requireLive(id);
        if (patch == null) {
            throw ApiException.badRequest("缺少要修改的内容");
        }
        boolean uploaded = sampleFile != null && !sampleFile.isEmpty();
        boolean mappingChange = patch.columns() != null
                || patch.options() != null
                || patch.layout() != null
                || patch.expected() != null;
        boolean changed = false;

        if (patch.name() != null) {
            String name = patch.name().trim();
            if (!StringUtils.hasText(name)) {
                throw ApiException.badRequest("配方名称不能为空");
            }
            if (!name.equals(recipe.getName())) {
                if (recipes.existsByNameAndDeletedAtIsNull(name)) {
                    throw ApiException.conflict("配方名称已存在：" + name);
                }
                recipe.setName(name);
                changed = true;
            }
        }
        if (patch.enabled() != null && patch.enabled() != recipe.isEnabled()) {
            recipe.setEnabled(patch.enabled());
            changed = true;
        }

        RecipeSampleStorage.PendingSample pending = null;
        if (mappingChange || uploaded) {
            ManualMapping mapping = patch.columns() != null
                    ? mappingOf(patch.columns()) : mappingOf(recipe);
            pending = uploaded ? sampleStorage.validate(sampleFile) : null;
            Map<String, Object> existingSample = readMap(recipe.getSampleJson());
            byte[] bytes = pending != null ? pending.bytes() : sampleStorage.read(existingSample);
            String fileName = pending != null
                    ? pending.fileName() : sampleStorage.fileNameOf(existingSample);
            Map<String, Object> target = expectedTarget(
                    patch.expected(), readMap(recipe.getExpectedJson()));
            Analysis analysis = analyze(bytes, fileName, mapping, target);
            if (!analysis.test().pass()) {
                throw sampleMismatch(analysis.test());
            }
            recipe.setLayout(layoutOf(mapping));
            recipe.setFingerprintJson(write(analysis.fingerprint().toMap()));
            if (patch.columns() != null) {
                recipe.setColumnsJson(write(patch.columns()));
            }
            if (patch.options() != null) {
                recipe.setOptionsJson(write(patch.options()));
            }
            recipe.setExpectedJson(write(analysis.test().expected()));
            changed = true;
        }
        if (!changed) {
            return toDto(recipe);
        }
        recipe.setVersion(recipe.getVersion() + 1);
        ImportRecipe saved = saveGuarded(recipe, "配方已被他人修改，请刷新后重试");
        if (pending != null) {
            saved.setSampleJson(write(sampleStorage.store(saved.getId(), pending)));
            saved = saveGuarded(saved, "配方已被他人修改，请刷新后重试");
        }
        versions.save(snapshot(saved, username));
        return toDto(saved);
    }

    /**
     * Appends the snapshot as a new version (never rewrites history) and applies it to
     * the live recipe. The snapshot's own sample and expected must still pass, so a
     * rollback can never leave a recipe that fails its sample test.
     */
    @Transactional
    public RecipeDto rollback(Long id, int version, String username) {
        ImportRecipe recipe = requireLive(id);
        ImportRecipeVersion row = versions.findByRecipeIdAndVersion(id, version)
                .orElseThrow(() -> ApiException.notFound("配方版本不存在：" + version));
        JsonNode snapshot = readTree(row.getSnapshotJson());
        if (snapshot == null) {
            throw ApiException.conflict("该版本快照无法读取，无法回退");
        }
        Map<String, Object> columns = mapOf(snapshot.get("columns"));
        if (columns.isEmpty()) {
            throw ApiException.conflict("该版本没有可用的列映射，无法回退");
        }
        Map<String, Object> expected = mapOf(snapshot.get("expected"));
        Map<String, Object> sample = mapOf(snapshot.get("sample"));
        byte[] bytes = sampleStorage.read(sample);
        ManualMapping mapping = mappingOf(columns);
        Analysis analysis = analyze(bytes, sampleStorage.fileNameOf(sample), mapping, expected);
        if (!analysis.test().pass()) {
            throw sampleMismatch(analysis.test());
        }
        recipe.setLayout(layoutOf(mapping));
        recipe.setFingerprintJson(write(analysis.fingerprint().toMap()));
        recipe.setColumnsJson(write(columns));
        recipe.setOptionsJson(write(mapOf(snapshot.get("options"))));
        recipe.setExpectedJson(write(analysis.test().expected()));
        recipe.setSampleJson(write(sample));
        recipe.setVersion(recipe.getVersion() + 1);
        ImportRecipe saved = recipes.save(recipe);
        versions.save(snapshot(saved, username));
        return toDto(saved);
    }

    // ---------------------------------------------------------------- delete / restore

    /**
     * Soft delete: the row stays (so versions and audits keep resolving), the name gets a
     * “（已删除 …）” suffix so the original name becomes reusable, and the batch lands in
     * ticket 03's 7-day recycle bin.
     */
    @Transactional
    public Map<String, Object> delete(Long id) {
        ImportRecipe recipe = requireLive(id);
        String original = recipe.getName();
        String batchId = deletionWriter.record(ENTITY_TYPE, recipe.getId(), original, KIND_LABEL);
        recipe.setDeletedAt(Instant.now());
        recipe.setEnabled(false);
        recipe.setName(deletedName(original, batchId));
        recipes.save(recipe);
        return Map.of("batchId", batchId, "deletedCount", 1);
    }

    @Override
    public boolean supports(String entityType) {
        return ENTITY_TYPE.equalsIgnoreCase(entityType);
    }

    @Override
    @Transactional
    public void restore(Long entityId) {
        ImportRecipe recipe = recipes.findById(entityId)
                .orElseThrow(() -> ApiException.notFound("导入配方不存在: " + entityId));
        String restored = stripDeletedSuffix(recipe.getName());
        if (recipes.existsByNameAndDeletedAtIsNull(restored)) {
            throw ApiException.conflict("同名配方已存在，无法恢复：" + restored);
        }
        recipe.setName(restored);
        recipe.setDeletedAt(null);
        recipe.setEnabled(true);
        recipes.save(recipe);
    }

    /** Records that a preview or commit applied the recipe; 06 sorts by this. */
    @Transactional
    public void markUsed(Long recipeId) {
        if (recipeId == null) {
            return;
        }
        recipes.findById(recipeId).ifPresent(recipe -> {
            recipe.setLastUsedAt(Instant.now());
            recipes.save(recipe);
        });
    }

    // ---------------------------------------------------------------- sample test

    /** Sheet, computed fingerprint and the itemised test result, all from one parse. */
    private record Analysis(Sheet sheet, ImportFingerprint fingerprint, RecipeTestDto test) {}

    private Analysis analyze(
            byte[] bytes, String fileName, ManualMapping mapping, Map<String, Object> expected) {
        List<Sheet> sheets = SourceSheets.read(bytes, fileName);
        Sheet sheet = SourceSheets.select(sheets, mapping.sheetName());
        Parsed parsed = parser.parse(sheet, mapping, Set.of());
        RecipeCounts actual = new RecipeCounts(
                parsed.slotCount(), parsed.lessonCount(), parsed.emptySlotCount(),
                parsed.teacherNames().size(), parsed.courseNames().size());
        Map<String, Integer> expectedCounts = expectedCounts(expected);
        List<RecipeCountDifferenceDto> differences = compare(expectedCounts, actual);
        return new Analysis(
                sheet,
                ImportFingerprint.of(sheet, mapping),
                new RecipeTestDto(differences.isEmpty(), actual, expectedCounts, differences));
    }

    private Map<String, Integer> expectedCounts(Map<String, Object> expected) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        if (expected == null) {
            return counts;
        }
        for (String field : COUNT_LABELS.keySet()) {
            Integer value = intValue(expected.get(field));
            if (value != null) {
                counts.put(field, value);
            }
        }
        return counts;
    }

    private List<RecipeCountDifferenceDto> compare(
            Map<String, Integer> expected, RecipeCounts actual) {
        List<RecipeCountDifferenceDto> differences = new ArrayList<>();
        for (Map.Entry<String, String> field : COUNT_LABELS.entrySet()) {
            Integer wanted = expected.get(field.getKey());
            if (wanted == null) {
                continue;
            }
            Integer found = actualOf(field.getKey(), actual);
            if (!wanted.equals(found)) {
                differences.add(new RecipeCountDifferenceDto(
                        field.getKey(), field.getValue(), wanted, found));
            }
        }
        return List.copyOf(differences);
    }

    private Integer actualOf(String field, RecipeCounts actual) {
        return switch (field) {
            case "slotCount" -> actual.slotCount();
            case "lessonCount" -> actual.lessonCount();
            case "emptySlotCount" -> actual.emptySlotCount();
            case "teacherCount" -> actual.teacherCount();
            case "courseCount" -> actual.courseCount();
            default -> null;
        };
    }

    private Map<String, Object> expectedTarget(
            Map<String, Object> requested, Map<String, Object> fallback) {
        if (requested != null && !requested.isEmpty()) {
            return requested;
        }
        if (fallback != null && !fallback.isEmpty()) {
            return fallback;
        }
        throw ApiException.badRequest("保存配方前请先用样例文件测试，缺少预期结果");
    }

    private ApiException sampleMismatch(RecipeTestDto test) {
        String detail = test.differences().stream()
                .map(difference -> difference.label() + "预期 " + difference.expected()
                        + "、实际 " + difference.actual())
                .collect(Collectors.joining("；"));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("differences", test.differences());
        details.put("actual", test.actual());
        details.put("expected", test.expected());
        return new ApiException(
                HttpStatus.CONFLICT, "样例测试未通过，数字对不上：" + detail, details);
    }

    private Integer intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(value.toString());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    // ---------------------------------------------------------------- fingerprint matching

    private boolean same(ImportFingerprint left, ImportFingerprint right) {
        return left.sheetName().equals(right.sheetName())
                && left.headerRow() == right.headerRow()
                && left.columnCount() == right.columnCount()
                && left.headers().equals(right.headers())
                && left.delimiter().equals(right.delimiter())
                && left.layout().equals(right.layout());
    }

    /**
     * A recipe still applies when the same worksheet is uploaded (a workbook sheet name is
     * real identity), or when the same layout family brings at least half of the same
     * headers — a renamed sheet with the same columns is still the user's own recipe, and
     * so is the same file with one column added or removed.
     *
     * <p>CSV is the exception: every CSV is read as one synthetic sheet called {@code CSV},
     * so the name says nothing on its own and the headers have to agree.
     */
    private boolean similarTo(ImportFingerprint saved, ImportFingerprint current) {
        boolean sameLayout = saved.layout().equals(current.layout());
        boolean overlapping = sameLayout && headerOverlap(saved.headers(), current.headers()) >= 0.5;
        boolean sameSheet = StringUtils.hasText(saved.sheetName())
                && saved.sheetName().equalsIgnoreCase(current.sheetName());
        if (!sameSheet) {
            return overlapping;
        }
        return !SourceSheets.CSV_SHEET.equalsIgnoreCase(saved.sheetName()) || overlapping;
    }

    /** Shared header texts over the wider of the two column lists, 0 when either is empty. */
    private double headerOverlap(List<String> saved, List<String> current) {
        int left = saved == null ? 0 : saved.size();
        int right = current == null ? 0 : current.size();
        int wider = Math.max(left, right);
        if (wider == 0) {
            return 0;
        }
        Map<String, Integer> pool = new LinkedHashMap<>();
        for (String header : saved) {
            pool.merge(header, 1, Integer::sum);
        }
        int shared = 0;
        for (String header : current) {
            Integer left1 = pool.get(header);
            if (left1 != null && left1 > 0) {
                pool.put(header, left1 - 1);
                shared++;
            }
        }
        return (double) shared / wider;
    }

    /**
     * What changed without disqualifying the recipe. Headers are compared in column
     * order (the mapping is index-based, so order is part of the layout): an equal number
     * of columns makes any differing position a rename; a different number of columns is
     * reported as the columns the recipe has and the file no longer has (REMOVED) and the
     * ones the file added (ADDED), each with its position, so an inserted column does not
     * masquerade as a rename of everything after it.
     */
    private List<RecipeDifferenceDto> differences(ImportFingerprint saved, ImportFingerprint current) {
        List<RecipeDifferenceDto> differences = new ArrayList<>();
        List<String> before = saved.headers() == null ? List.of() : saved.headers();
        List<String> after = current.headers() == null ? List.of() : current.headers();
        if (before.size() == after.size()) {
            for (int index = 0; index < before.size(); index++) {
                if (!before.get(index).equals(after.get(index))) {
                    differences.add(new RecipeDifferenceDto(
                            "RENAMED", String.valueOf(index + 1),
                            "第 " + (index + 1) + " 列的表头由「" + before.get(index)
                                    + "」改为「" + after.get(index) + "」"));
                }
            }
        } else {
            Map<String, Deque<Integer>> addedPositions = new LinkedHashMap<>();
            for (int index = 0; index < after.size(); index++) {
                addedPositions.computeIfAbsent(after.get(index), key -> new ArrayDeque<>())
                        .addLast(index);
            }
            List<Integer> removedAt = new ArrayList<>();
            for (int index = 0; index < before.size(); index++) {
                Deque<Integer> seats = addedPositions.get(before.get(index));
                if (seats == null || seats.isEmpty()) {
                    removedAt.add(index);
                } else {
                    seats.pollFirst();
                }
            }
            for (int index : removedAt) {
                differences.add(new RecipeDifferenceDto(
                        "REMOVED", before.get(index),
                        "配方里的第 " + (index + 1) + " 列「" + before.get(index)
                                + "」在当前文件中不存在"));
            }
            List<Integer> addedAt = new ArrayList<>();
            for (Deque<Integer> seats : addedPositions.values()) {
                addedAt.addAll(seats);
            }
            addedAt.sort(Integer::compareTo);
            for (int index : addedAt) {
                differences.add(new RecipeDifferenceDto(
                        "ADDED", after.get(index),
                        "当前文件第 " + (index + 1) + " 列是新增的「" + after.get(index) + "」"));
            }
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

    // ---------------------------------------------------------------- helpers

    /**
     * The name column is unique, so two saves of the same name can both pass the
     * "does it exist" pre-check and only collide at insert time. That collision is a
     * conflict the user can fix by refreshing, never a 500.
     */
    private ImportRecipe saveGuarded(ImportRecipe recipe, String message) {
        try {
            return recipes.save(recipe);
        } catch (DataIntegrityViolationException ex) {
            log.warn("配方保存冲突: {}", recipe.getName(), ex);
            throw ApiException.conflict(message);
        }
    }

    /** Parses a client mapping; the parser's own message stays in the log. */
    private ManualMapping readMapping(String mappingJson) {
        try {
            return objectMapper.readValue(mappingJson, ManualMapping.class);
        } catch (JsonProcessingException ex) {
            log.warn("列映射解析失败", ex);
            throw ApiException.badRequest("列映射格式不正确，请重新选择列");
        }
    }

    private ImportRecipe requireLive(Long id) {
        ImportRecipe recipe = recipes.findById(id)
                .orElseThrow(() -> ApiException.notFound("导入配方不存在: " + id));
        if (recipe.getDeletedAt() != null) {
            throw ApiException.notFound("导入配方不存在: " + id);
        }
        return recipe;
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
        return mappingOf(readMap(recipe.getColumnsJson()));
    }

    private ManualMapping mappingOf(Map<String, Object> columns) {
        try {
            return objectMapper.convertValue(columns, ManualMapping.class);
        } catch (IllegalArgumentException ex) {
            log.warn("配方列映射无法解析", ex);
            throw ApiException.badRequest("配方里的列映射无法识别，请重新保存配方");
        }
    }

    private ImportFingerprint readFingerprint(ImportRecipe recipe) {
        return ImportFingerprint.fromMap(readMap(recipe.getFingerprintJson()));
    }

    private String layoutOf(ManualMapping mapping) {
        return mapping.keyColumn() != null
                ? ImportFingerprint.LAYOUT_LONG : ImportFingerprint.LAYOUT_GRID;
    }

    private String deletedName(String original, String batchId) {
        String suffix = "（已删除 " + batchId.substring(0, 8) + "）";
        int maxOriginal = Math.max(0, MAX_NAME_LENGTH - suffix.length());
        String base = original.length() > maxOriginal ? original.substring(0, maxOriginal) : original;
        return base + suffix;
    }

    private String stripDeletedSuffix(String name) {
        String restored = DELETED_SUFFIX.matcher(name).replaceFirst("").trim();
        return StringUtils.hasText(restored) ? restored : name;
    }

    private String summaryOf(String snapshotJson) {
        JsonNode snapshot = readTree(snapshotJson);
        if (snapshot == null) {
            return "历史快照";
        }
        String layout = snapshot.path("layout").asText("");
        JsonNode expected = snapshot.path("expected");
        StringBuilder summary = new StringBuilder();
        if (StringUtils.hasText(layout)) {
            summary.append(layout).append(" · ");
        }
        summary.append("预期 ")
                .append(expected.path("slotCount").asInt()).append(" 槽 / ")
                .append(expected.path("lessonCount").asInt()).append(" 有课 / ")
                .append(expected.path("emptySlotCount").asInt()).append(" 空槽");
        return summary.toString();
    }

    private JsonNode readTree(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException ex) {
            return null;
        }
    }

    private Map<String, Object> mapOf(JsonNode node) {
        if (node == null || node.isNull() || !node.isObject()) {
            return Map.of();
        }
        return objectMapper.convertValue(node, MAP_TYPE);
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
