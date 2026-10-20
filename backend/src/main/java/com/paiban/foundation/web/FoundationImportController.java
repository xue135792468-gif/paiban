package com.paiban.foundation.web;

import com.paiban.foundation.service.FoundationExcelService;
import com.paiban.foundation.service.FoundationImportService;
import com.paiban.foundation.web.ImportDtos.ImportReport;
import com.paiban.identity.domain.Permissions;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class FoundationImportController {

    private static final MediaType XLSX =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final FoundationImportService importService;
    private final FoundationExcelService excelService;

    public FoundationImportController(
            FoundationImportService importService, FoundationExcelService excelService) {
        this.importService = importService;
        this.excelService = excelService;
    }

    @GetMapping("/api/import/{entity}/template")
    @PreAuthorize("hasAuthority('" + Permissions.FOUNDATION_MANAGE + "')")
    public ResponseEntity<byte[]> template(@PathVariable String entity) {
        String displayName = importService.spec(entity).displayName();
        return download(excelService.template(entity), displayName + "导入模板.xlsx");
    }

    @PostMapping(value = "/api/import/{entity}/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('" + Permissions.FOUNDATION_MANAGE + "')")
    public ImportReport preview(
            @PathVariable String entity,
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) Long semesterId) {
        return importService.preview(entity, file, semesterId);
    }

    @PostMapping(value = "/api/import/{entity}/commit", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('" + Permissions.FOUNDATION_MANAGE + "')")
    public ImportReport commit(
            @PathVariable String entity,
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) Long semesterId,
            Principal principal) {
        return importService.commit(entity, file, semesterId, principal == null ? "system" : principal.getName());
    }

    @GetMapping("/api/export/{entity}")
    @PreAuthorize("hasAuthority('" + Permissions.FOUNDATION_MANAGE + "')")
    public ResponseEntity<byte[]> export(
            @PathVariable String entity, @RequestParam(required = false) Long semesterId) {
        return download(excelService.export(entity, semesterId), "基础数据-" + entity + ".xlsx");
    }

    private ResponseEntity<byte[]> download(byte[] content, String filename) {
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(filename, StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(XLSX)
                .body(content);
    }
}
