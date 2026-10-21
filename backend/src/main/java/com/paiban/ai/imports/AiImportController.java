package com.paiban.ai.imports;

import com.paiban.ai.imports.ImportDtos.CommitRequest;
import com.paiban.ai.imports.ImportDtos.CommitResultDto;
import com.paiban.ai.imports.ImportDtos.PreviewDto;
import com.paiban.ai.imports.ImportDtos.RowsUpdateRequest;
import com.paiban.ai.imports.ImportDtos.TextImportRequest;
import com.paiban.common.web.ApiException;
import com.paiban.identity.domain.Permissions;
import jakarta.validation.Valid;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
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

    public AiImportController(AiImportService service) {
        this.service = service;
    }

    @PostMapping(value = "/excel", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public PreviewDto excel(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "target", required = false, defaultValue = "teacher") String target,
            Principal principal) {
        return service.startExcel(file, principal.getName(), targetOf(target));
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

    @PostMapping("/sessions/{id}/continue")
    public PreviewDto continueWithAi(@PathVariable Long id, Principal principal) {
        return service.continueWithAi(id, principal.getName());
    }

    @PostMapping("/sessions/{id}/reparse")
    public PreviewDto reparse(@PathVariable Long id, Principal principal) {
        return service.reparse(id, principal.getName());
    }

    @PostMapping("/sessions/{id}/commit")
    public CommitResultDto commit(
            @PathVariable Long id,
            @RequestBody(required = false) CommitRequest request,
            Principal principal) {
        return service.commit(id, request == null ? null : request.semesterId(), principal.getName());
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
