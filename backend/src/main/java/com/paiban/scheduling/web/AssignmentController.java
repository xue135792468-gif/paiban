package com.paiban.scheduling.web;

import com.paiban.foundation.web.ImportDtos.ImportReport;
import com.paiban.identity.domain.Permissions;
import com.paiban.scheduling.service.AssignmentExcelService;
import com.paiban.scheduling.service.AssignmentImportService;
import com.paiban.scheduling.service.AssignmentService;
import com.paiban.scheduling.web.AssignmentDtos.AssignmentDto;
import com.paiban.scheduling.web.AssignmentDtos.SaveAssignmentRequest;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
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
@RequestMapping("/api/assignments")
public class AssignmentController {

    private static final MediaType XLSX =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final AssignmentService service;
    private final AssignmentImportService importService;
    private final AssignmentExcelService excelService;

    public AssignmentController(
            AssignmentService service,
            AssignmentImportService importService,
            AssignmentExcelService excelService) {
        this.service = service;
        this.importService = importService;
        this.excelService = excelService;
    }

    @GetMapping
    public List<AssignmentDto> list(
            @RequestParam(required = false) Long semesterId,
            @RequestParam(required = false) Long classId,
            @RequestParam(required = false) Long teacherId,
            @RequestParam(required = false) Long courseId,
            Authentication authentication) {
        boolean canManageAll = authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals(Permissions.ASSIGNMENT_MANAGE));
        return service.listScoped(
                authentication.getName(), canManageAll, semesterId, classId, teacherId, courseId);
    }

    @GetMapping("/mine")
    public List<AssignmentDto> mine(Principal principal) {
        return service.mine(principal.getName());
    }

    @PostMapping
    @PreAuthorize("hasAuthority('" + Permissions.ASSIGNMENT_MANAGE + "')")
    public AssignmentDto create(@Valid @RequestBody SaveAssignmentRequest request) {
        return service.create(request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.ASSIGNMENT_MANAGE + "')")
    public AssignmentDto update(@PathVariable Long id, @Valid @RequestBody SaveAssignmentRequest request) {
        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.ASSIGNMENT_MANAGE + "')")
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }

    @PostMapping(value = "/import/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('" + Permissions.ASSIGNMENT_MANAGE + "')")
    public ImportReport preview(
            @RequestParam("file") MultipartFile file, @RequestParam(required = false) Long semesterId) {
        return importService.preview(file, semesterId);
    }

    @PostMapping(value = "/import/commit", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('" + Permissions.ASSIGNMENT_MANAGE + "')")
    public ImportReport commit(
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) Long semesterId,
            Principal principal) {
        return importService.commit(file, semesterId, principal == null ? "system" : principal.getName());
    }

    @GetMapping("/template")
    @PreAuthorize("hasAuthority('" + Permissions.ASSIGNMENT_MANAGE + "')")
    public ResponseEntity<byte[]> template() {
        return download(excelService.template(), "授课任务导入模板.xlsx");
    }

    @GetMapping("/export")
    @PreAuthorize("hasAuthority('" + Permissions.ASSIGNMENT_MANAGE + "')")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) Long semesterId) {
        return download(excelService.export(semesterId), "授课任务.xlsx");
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
