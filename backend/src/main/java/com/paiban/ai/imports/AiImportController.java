package com.paiban.ai.imports;

import com.paiban.ai.imports.ImportDtos.ApplyMissingPeriodsRequest;
import com.paiban.ai.imports.ImportDtos.ApplyMissingPeriodsResult;
import com.paiban.ai.imports.ImportDtos.CommitResultDto;
import com.paiban.ai.imports.ImportDtos.MissingPeriodsDto;
import com.paiban.ai.imports.ImportDtos.LookupDto;
import com.paiban.ai.imports.ImportDtos.MappingRequest;
import com.paiban.ai.imports.ImportDtos.PreviewDto;
import com.paiban.ai.imports.ImportDtos.RowsUpdateRequest;
import com.paiban.ai.imports.ImportDtos.TextImportRequest;
import com.paiban.common.web.ApiException;
import com.paiban.identity.domain.Permissions;
import jakarta.validation.Valid;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/ai/import")
@PreAuthorize("hasAuthority('" + Permissions.FOUNDATION_MANAGE + "')")
public class AiImportController {

    private final AiImportService service;
    private final ImportMappingService mappings;
    private final MissingPeriodsService missingPeriods;

    public AiImportController(
            AiImportService service,
            ImportMappingService mappings,
            MissingPeriodsService missingPeriods) {
        this.service = service;
        this.mappings = mappings;
        this.missingPeriods = missingPeriods;
    }

    @GetMapping("/lookup")
    public List<LookupDto> lookup(
            @RequestParam("type") String type,
            @RequestParam(value = "q", required = false, defaultValue = "") String query) {
        return mappings.lookup(type, query);
    }

    /**
     * Spreadsheets do not need a target: without one the whole workbook is read and the
     * model decides what each sheet is. An explicit target keeps the single-target parse
     * available (text and images still require one).
     */
    @PostMapping(value = "/excel", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public PreviewDto excel(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "target", required = false) String target,
            Principal principal) {
        ImportTarget effective = target == null || target.isBlank()
                ? ImportTarget.WORKBOOK
                : targetOf(target);
        return service.startExcel(file, principal.getName(), effective);
    }

    @PostMapping("/text")
    public PreviewDto text(
            @Valid @RequestBody TextImportRequest request,
            @RequestParam(value = "target", required = false, defaultValue = "teacher") String target,
            Principal principal) {
        return service.startText(request.text(), principal.getName(), targetOf(target));
    }

    @PostMapping(value = "/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public PreviewDto image(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "target", required = false, defaultValue = "teacher") String target,
            Principal principal) {
        return service.startImage(file, principal.getName(), targetOf(target));
    }

    private ImportTarget targetOf(String code) {
        try {
            return ImportTarget.fromCode(code);
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest(ex.getMessage());
        }
    }

    @GetMapping("/sessions/{id}")
    public PreviewDto session(@PathVariable Long id, Principal principal) {
        return service.getSession(id, principal.getName());
    }

    @PutMapping("/sessions/{id}/rows")
    public PreviewDto rows(
            @PathVariable Long id,
            @Valid @RequestBody RowsUpdateRequest request,
            Principal principal) {
        return service.updateRows(id, request.rows(), principal.getName());
    }

    @PostMapping("/sessions/{id}/mapping")
    public PreviewDto mapping(
            @PathVariable Long id,
            @Valid @RequestBody MappingRequest request,
            Principal principal) {
        return service.applyMapping(id, request, principal.getName());
    }

    @PostMapping("/sessions/{id}/continue")
    public PreviewDto continueWithAi(@PathVariable Long id, Principal principal) {
        return service.continueWithAi(id, principal.getName());
    }

    /** Stops a background parse; only a {@code PARSING} session accepts it. */
    @PostMapping("/sessions/{id}/stop")
    public PreviewDto stop(@PathVariable Long id, Principal principal) {
        return service.stop(id, principal.getName());
    }

    /** Re-runs the failed batches of a partial parse; only a {@code PARTIAL} session accepts it. */
    @PostMapping("/sessions/{id}/retry")
    public PreviewDto retry(@PathVariable Long id, Principal principal) {
        return service.retry(id, principal.getName());
    }

    /** Abandons an unfinished parse; only a {@code PARSING} or {@code PARTIAL} session accepts it. */
    @PostMapping("/sessions/{id}/abandon")
    public PreviewDto abandon(@PathVariable Long id, Principal principal) {
        return service.abandon(id, principal.getName());
    }

    @PostMapping("/sessions/{id}/reparse")
    public PreviewDto reparse(@PathVariable Long id, Principal principal) {
        return service.reparse(id, principal.getName());
    }

    @PostMapping("/sessions/{id}/commit")
    public CommitResultDto commit(@PathVariable Long id, Principal principal) {
        return service.commit(id, principal.getName());
    }

    /**
     * The periods this saved preview uses that the current semester does not have, with a
     * computed time where one is defensible. Read-only and derived from {@code preview_json}:
     * no re-parse, no model call.
     */
    @GetMapping("/sessions/{id}/missing-periods")
    public MissingPeriodsDto missingPeriods(@PathVariable Long id, Principal principal) {
        return missingPeriods.plan(id, principal.getName());
    }

    /**
     * Appends the confirmed slots. Writing the period table is institutional data, so this
     * needs the import permission and {@code semester:manage} (the class-level annotation is
     * replaced by this one, hence both are named).
     */
    @PostMapping("/sessions/{id}/missing-periods")
    @PreAuthorize("hasAuthority('" + Permissions.FOUNDATION_MANAGE + "')"
            + " and hasAuthority('" + Permissions.SEMESTER_MANAGE + "')")
    public ApplyMissingPeriodsResult fillMissingPeriods(
            @PathVariable Long id,
            @Valid @RequestBody ApplyMissingPeriodsRequest request,
            Principal principal) {
        return missingPeriods.apply(id, principal.getName(), request);
    }

    @GetMapping("/sessions/{id}/file")
    public ResponseEntity<byte[]> file(@PathVariable Long id, Principal principal) {
        byte[] bytes = service.file(id, principal.getName());
        String name = service.fileName(id, principal.getName());
        MediaType type = mediaType(name);
        String encoded = URLEncoder.encode(name == null ? "import" : name, StandardCharsets.UTF_8)
                .replace("+", "%20");
        return ResponseEntity.ok()
                .contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename*=UTF-8''" + encoded)
                .body(bytes);
    }

    private MediaType mediaType(String name) {
        String lower = name == null ? "" : name.toLowerCase();
        if (lower.endsWith(".png")) {
            return MediaType.IMAGE_PNG;
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return MediaType.IMAGE_JPEG;
        }
        if (lower.endsWith(".webp")) {
            return MediaType.parseMediaType("image/webp");
        }
        if (lower.endsWith(".xlsx")) {
            return MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        }
        if (lower.endsWith(".xls")) {
            return MediaType.parseMediaType("application/vnd.ms-excel");
        }
        return MediaType.APPLICATION_OCTET_STREAM;
    }
}
