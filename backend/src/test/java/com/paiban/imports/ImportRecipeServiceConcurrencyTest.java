package com.paiban.imports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paiban.common.deletion.RecentDeletionWriter;
import com.paiban.common.web.ApiException;
import com.paiban.imports.ImportDtos.RecipePatchRequest;
import com.paiban.imports.ImportDtos.SaveRecipeRequest;
import com.paiban.imports.manual.ManualSheetParser;
import com.paiban.imports.recipe.ImportRecipe;
import com.paiban.imports.recipe.ImportRecipeRepository;
import com.paiban.imports.recipe.ImportRecipeService;
import com.paiban.imports.recipe.ImportRecipeVersionRepository;
import com.paiban.imports.recipe.RecipeSampleStorage;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;

/**
 * The races a real database cannot be made to produce on demand: two saves of the same
 * name both pass the "does it exist" pre-check and only the unique index notices.
 * The repository hands out the collision, and the service must turn it into a 409 the
 * user can act on instead of a 500.
 */
class ImportRecipeServiceConcurrencyTest {

    private static final byte[] SAMPLE = ("班级|星期|节次,课程,教师\n"
            + "八（1）|星期一|1,语文,张三\n").getBytes(StandardCharsets.UTF_8);

    private final ImportRecipeRepository recipes = mock(ImportRecipeRepository.class);
    private final ImportRecipeVersionRepository versions = mock(ImportRecipeVersionRepository.class);
    private final RecipeSampleStorage sampleStorage = mock(RecipeSampleStorage.class);
    private ImportRecipeService service;

    @BeforeEach
    void setUp() {
        service = new ImportRecipeService(
                recipes,
                versions,
                new ObjectMapper(),
                new ManualSheetParser(),
                sampleStorage,
                mock(RecentDeletionWriter.class));
        when(sampleStorage.validate(any()))
                .thenReturn(new RecipeSampleStorage.PendingSample(SAMPLE, "sample.csv", "csv", "sha"));
        when(recipes.save(any())).thenThrow(
                new DataIntegrityViolationException("duplicate key value violates unique constraint"));
    }

    @Test
    void concurrentSaveOfTheSameNameIsAConflictNotFiveHundred() {
        when(recipes.findByName("并发配方")).thenReturn(Optional.empty());

        ApiException thrown = catchThrowableOfType(
                () -> service.save(
                        new SaveRecipeRequest("并发配方", "LONG", Map.of(), columns(), Map.of(),
                                expected(), null, null, null, null),
                        new MockMultipartFile("sampleFile", "sample.csv", "text/csv", SAMPLE),
                        "admin"),
                ApiException.class);

        assertThat(thrown).isNotNull();
        assertThat(thrown.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(thrown.getMessage()).contains("已被占用");
    }

    @Test
    void renameCollisionWhileUpdatingIsAConflictToo() {
        ImportRecipe existing = liveRecipe();
        when(recipes.findById(7L)).thenReturn(Optional.of(existing));
        when(recipes.existsByNameAndDeletedAtIsNull("新名字")).thenReturn(false);

        ApiException thrown = catchThrowableOfType(
                () -> service.update(7L, new RecipePatchRequest("新名字", null, null, null, null, null),
                        null, "admin"),
                ApiException.class);

        assertThat(thrown).isNotNull();
        assertThat(thrown.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(thrown.getMessage()).contains("已被他人修改");
    }

    // ---------------------------------------------------------------- fixtures

    private ImportRecipe liveRecipe() {
        ImportRecipe recipe = new ImportRecipe();
        recipe.setName("老名字");
        recipe.setLayout("LONG");
        recipe.setFingerprintJson("{}");
        recipe.setColumnsJson(new ObjectMapper().valueToTree(columns()).toString());
        recipe.setOptionsJson("{}");
        recipe.setExpectedJson("{}");
        recipe.setCreatedBy("admin");
        return recipe;
    }

    private Map<String, Object> columns() {
        Map<String, Object> columns = new LinkedHashMap<>();
        columns.put("sheetName", "CSV");
        columns.put("headerRow", 0);
        columns.put("delimiter", "|");
        columns.put("keyColumn", 0);
        columns.put("keyParts", List.of("className", "dayOfWeek", "periodNo"));
        columns.put("courseColumn", 1);
        columns.put("teacherColumn", 2);
        columns.put("ignoreBlankRows", true);
        return columns;
    }

    private Map<String, Object> expected() {
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("slotCount", 1);
        expected.put("lessonCount", 1);
        expected.put("emptySlotCount", 0);
        expected.put("teacherCount", 1);
        expected.put("courseCount", 1);
        return expected;
    }
}
