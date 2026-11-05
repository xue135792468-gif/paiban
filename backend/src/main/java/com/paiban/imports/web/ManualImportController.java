package com.paiban.imports.web;

import com.paiban.common.web.ApiException;
import com.paiban.identity.domain.Permissions;
import com.paiban.imports.ImportDtos.AiRecipeSuggestionDto;
import com.paiban.imports.ImportDtos.CommitResultDto;
import com.paiban.imports.ImportDtos.InspectDto;
import com.paiban.imports.ImportDtos.ManualPreviewDto;
import com.paiban.imports.manual.ManualImportService;
import com.paiban.imports.recipe.AiRecipeSuggestionService;
import java.io.IOException;
import java.security.Principal;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * The manual import path. It never touches {@code /api/ai/import} or the AI session
 * state machine, so it stays available with no AI provider configured — that is the
 * whole point of ticket 05. The AI suggestion endpoint (07) is optional help for
 * choosing columns; when AI is unavailable it answers 200 with {@code available=false}.
 */
@RestController
@RequestMapping("/api/imports/manual")
public class ManualImportController {

    private final ManualImportService manualImports;
    private final AiRecipeSuggestionService aiRecipes;

    public ManualImportController(
            ManualImportService manualImports, AiRecipeSuggestionService aiRecipes) {
        this.manualImports = manualImports;
        this.aiRecipes = aiRecipes;
    }

    @PostMapping(value = "/inspect", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('" + Permissions.FOUNDATION_MANAGE + "')")
    public InspectDto inspect(@RequestParam("file") MultipartFile file) {
        return manualImports.inspect(file);
    }

    @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('" + Permissions.FOUNDATION_MANAGE + "')")
    public ManualPreviewDto preview(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "mapping", required = false) String mapping,
            @RequestParam(value = "semesterId", required = false) Long semesterId) {
        return manualImports.preview(file, mapping, semesterId);
    }

    @PostMapping(value = "/ai-suggest", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('" + Permissions.FOUNDATION_MANAGE + "')")
    public AiRecipeSuggestionDto aiSuggest(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "sheetName", required = false) String sheetName,
            @RequestParam(value = "headerRow", required = false) Integer headerRow) {
        try {
            return aiRecipes.suggest(
                    file.getBytes(), file.getOriginalFilename(), sheetName, headerRow);
        } catch (IOException ex) {
            throw ApiException.badRequest("读取文件失败：" + ex.getMessage());
        }
    }

    @PostMapping(value = "/commit", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('" + Permissions.FOUNDATION_MANAGE + "')")
    public CommitResultDto commit(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "mapping", required = false) String mapping,
            @RequestParam(value = "options", required = false) String options,
            Principal principal) {
        return manualImports.commit(
                file, mapping, options, principal == null ? "system" : principal.getName());
    }
}
