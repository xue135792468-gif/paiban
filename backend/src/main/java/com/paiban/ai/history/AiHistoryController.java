package com.paiban.ai.history;

import com.paiban.ai.history.AiHistoryDtos.CallDetail;
import com.paiban.ai.history.AiHistoryDtos.HistoryPage;
import com.paiban.ai.history.AiHistoryDtos.ImportDetail;
import com.paiban.identity.domain.Permissions;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai/history")
@PreAuthorize("hasAuthority('" + Permissions.SETTINGS_MANAGE + "')")
public class AiHistoryController {

    private final AiHistoryService service;

    public AiHistoryController(AiHistoryService service) {
        this.service = service;
    }

    @GetMapping
    public HistoryPage list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String taskType,
            @RequestParam(required = false) Long providerId,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.list(from, to, userId, username, taskType, providerId, type, page, size);
    }

    @GetMapping("/imports/{id}")
    public ImportDetail importDetail(@PathVariable Long id) {
        return service.importDetail(id);
    }

    @GetMapping("/calls/{id}")
    public CallDetail callDetail(@PathVariable Long id) {
        return service.callDetail(id);
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export(
            @RequestParam(defaultValue = "xlsx") String format,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String taskType,
            @RequestParam(required = false) Long providerId,
            @RequestParam(required = false) String type) {
        byte[] bytes = service.export(format, from, to, userId, username, taskType, providerId, type);
        String normalized = "pdf".equalsIgnoreCase(format) ? "pdf" : "xlsx";
        MediaType contentType = "pdf".equals(normalized)
                ? MediaType.APPLICATION_PDF
                : MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        String filename = "ai-history-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "." + normalized;
        return ResponseEntity.ok()
                .contentType(contentType)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(bytes);
    }
}
