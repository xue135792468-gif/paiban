package com.paiban.ai.history;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lowagie.text.Document;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.paiban.ai.domain.AiCallLog;
import com.paiban.ai.domain.AiCallLogRepository;
import com.paiban.ai.history.AiHistoryDtos.CallDetail;
import com.paiban.ai.history.AiHistoryDtos.Candidate;
import com.paiban.ai.history.AiHistoryDtos.HistoryItem;
import com.paiban.ai.history.AiHistoryDtos.HistoryPage;
import com.paiban.ai.history.AiHistoryDtos.ImportDetail;
import com.paiban.ai.history.AiHistoryDtos.SubstitutionContext;
import com.paiban.ai.imports.AiImportLog;
import com.paiban.ai.imports.AiImportLogRepository;
import com.paiban.common.web.ApiException;
import com.paiban.foundation.domain.AdminClassRepository;
import com.paiban.foundation.domain.CourseRepository;
import com.paiban.foundation.domain.Teacher;
import com.paiban.foundation.domain.TeacherRepository;
import com.paiban.identity.domain.AppUser;
import com.paiban.identity.domain.AppUserRepository;
import com.paiban.substitution.domain.Substitution;
import com.paiban.substitution.domain.SubstitutionRepository;
import com.paiban.substitution.domain.SubstitutionRequest;
import com.paiban.substitution.domain.SubstitutionRequestRepository;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only audit view over both AI tables: import sessions and gateway calls.
 * Listing filters in memory because the volume is bounded by human-triggered AI use,
 * and the JSON payloads are only parsed for the detail endpoint.
 */
@Service
public class AiHistoryService {

    private static final String TYPE_IMPORT = "import";
    private static final String TYPE_CALL = "call";
    private static final String SUBJECT_SUBSTITUTION = "SUBSTITUTION_REQUEST";
    private static final int MAX_PAGE_SIZE = 100;

    private final AiImportLogRepository imports;
    private final AiCallLogRepository calls;
    private final AppUserRepository users;
    private final TeacherRepository teachers;
    private final CourseRepository courses;
    private final AdminClassRepository adminClasses;
    private final SubstitutionRequestRepository substitutionRequests;
    private final SubstitutionRepository substitutions;
    private final ObjectMapper objectMapper;
    private final String pdfFontPath;

    public AiHistoryService(
            AiImportLogRepository imports,
            AiCallLogRepository calls,
            AppUserRepository users,
            TeacherRepository teachers,
            CourseRepository courses,
            AdminClassRepository adminClasses,
            SubstitutionRequestRepository substitutionRequests,
            SubstitutionRepository substitutions,
            ObjectMapper objectMapper,
            @Value("${paiban.export.pdf-font-path:}") String pdfFontPath) {
        this.imports = imports;
        this.calls = calls;
        this.users = users;
        this.teachers = teachers;
        this.courses = courses;
        this.adminClasses = adminClasses;
        this.substitutionRequests = substitutionRequests;
        this.substitutions = substitutions;
        this.objectMapper = objectMapper;
        this.pdfFontPath = pdfFontPath;
    }

    // ------------------------------------------------------------------- list

    @Transactional(readOnly = true)
    public HistoryPage list(
            LocalDate from,
            LocalDate to,
            Long userId,
            String username,
            String taskType,
            Long providerId,
            String type,
            int page,
            int size) {
        List<HistoryItem> filtered = filterItems(from, to, userId, username, taskType, providerId, type);
        int safePage = Math.max(0, page);
        int safeSize = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
        int start = Math.min(filtered.size(), safePage * safeSize);
        int end = Math.min(filtered.size(), start + safeSize);
        return new HistoryPage(filtered.subList(start, end), filtered.size(), safePage, safeSize);
    }

    private List<HistoryItem> filterItems(
            LocalDate from,
            LocalDate to,
            Long userId,
            String username,
            String taskType,
            Long providerId,
            String type) {
        String typeFilter = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
        String taskFilter = taskType == null ? "" : taskType.trim();
        String userFilter = username == null ? "" : username.trim();
        Instant fromInstant = from == null ? null : from.atStartOfDay(ZoneId.systemDefault()).toInstant();
        Instant toInstant = to == null ? null : to.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant();

        return allItems().stream()
                .filter(item -> typeFilter.isBlank() || typeFilter.equals(item.type()))
                .filter(item -> taskFilter.isBlank() || taskFilter.equals(item.taskType()))
                .filter(item -> userId == null || Objects.equals(userId, item.userId()))
                .filter(item -> userFilter.isBlank()
                        || (item.username() != null && item.username().contains(userFilter)))
                .filter(item -> providerId == null || providerId.equals(item.providerId()))
                .filter(item -> fromInstant == null || !instant(item.createdAt()).isBefore(fromInstant))
                .filter(item -> toInstant == null || instant(item.createdAt()).isBefore(toInstant))
                .toList();
    }

    private List<HistoryItem> allItems() {
        Map<Long, String> usernames = new HashMap<>();
        for (AppUser user : users.findAll()) {
            usernames.put(user.getId(), user.getUsername());
        }
        List<HistoryItem> items = new ArrayList<>();
        for (AiImportLog log : imports.findAll()) {
            items.add(new HistoryItem(
                    TYPE_IMPORT,
                    log.getId(),
                    "data_import",
                    log.getUsername(),
                    log.getCreatedAt().toString(),
                    importSummary(log),
                    AiImportLog.COMMITTED.equals(log.getStatus()),
                    null,
                    null,
                    log.getId(),
                    log.getUserId(),
                    null));
        }
        for (AiCallLog log : calls.findAll()) {
            items.add(new HistoryItem(
                    TYPE_CALL,
                    log.getId(),
                    log.getTaskType(),
                    log.getUserId() == null ? null : usernames.get(log.getUserId()),
                    log.getCreatedAt().toString(),
                    callSummary(log),
                    log.isSuccess(),
                    log.getProviderName(),
                    log.getModelName(),
                    log.getSubjectId(),
                    log.getUserId(),
                    log.getProviderId()));
        }
        items.sort(Comparator.comparing((HistoryItem item) -> instant(item.createdAt())).reversed());
        return items;
    }

    private String importSummary(AiImportLog log) {
        String source = log.getFileName() == null ? "粘贴文本" : log.getFileName();
        return switch (log.getStatus()) {
            case AiImportLog.COMMITTED -> source + " · 入库 " + log.getImportedCount() + " 条";
            case AiImportLog.FAILED -> source + " · 解析失败";
            case AiImportLog.EDITED -> source + " · 已微调 " + log.getRowCount() + " 行";
            default -> source + " · 预览 " + log.getRowCount() + " 行";
        };
    }

    private String callSummary(AiCallLog log) {
        String task = "substitution_recommend".equals(log.getTaskType()) ? "调代推荐" : "数据导入";
        return task + " · " + (log.isSuccess() ? "成功" : "失败");
    }

    private Instant instant(String value) {
        try {
            return Instant.parse(value);
        } catch (RuntimeException ex) {
            return Instant.EPOCH;
        }
    }

    // ----------------------------------------------------------------- detail

    @Transactional(readOnly = true)
    public ImportDetail importDetail(Long id) {
        AiImportLog log = imports.findById(id)
                .orElseThrow(() -> ApiException.notFound("导入记录不存在: " + id));
        return new ImportDetail(
                log.getId(),
                log.getImportType(),
                log.getTargetType(),
                log.getUsername(),
                log.getFileName(),
                log.getFileSize(),
                log.getStatus(),
                log.getRowCount(),
                log.getImportedCount(),
                log.getAiExplanation(),
                parseJson(log.getPreviewJson()),
                parseJson(log.getConfirmedJson()),
                parseJson(log.getResultJson()),
                log.getAiRawResponse(),
                log.getCreatedAt().toString(),
                log.getUpdatedAt().toString());
    }

    @Transactional(readOnly = true)
    public CallDetail callDetail(Long id) {
        AiCallLog log = calls.findById(id)
                .orElseThrow(() -> ApiException.notFound("调用记录不存在: " + id));
        AppUser user = log.getUserId() == null ? null : users.findById(log.getUserId()).orElse(null);
        return new CallDetail(
                log.getId(),
                log.getTaskType(),
                user == null ? null : user.getUsername(),
                log.getProviderName(),
                log.getModelName(),
                log.isSuccess(),
                log.getErrorCode(),
                log.getErrorMessage(),
                log.getInputTokens(),
                log.getOutputTokens(),
                log.getLatencyMs(),
                parseJson(log.getRequestJson()),
                parseJson(log.getResponseJson()),
                log.getCreatedAt().toString(),
                substitutionContext(log));
    }

    private SubstitutionContext substitutionContext(AiCallLog log) {
        if (!SUBJECT_SUBSTITUTION.equals(log.getSubjectType()) || log.getSubjectId() == null) {
            return null;
        }
        SubstitutionRequest request = substitutionRequests.findById(log.getSubjectId()).orElse(null);
        if (request == null) {
            return null;
        }
        String originalTeacher = teachers.findById(request.getOriginalTeacherId())
                .map(Teacher::getName).orElse(null);
        String courseName = courses.findById(request.getCourseId())
                .map(course -> course.getName()).orElse(null);
        String className = adminClasses.findById(request.getAdminClassId())
                .map(adminClass -> adminClass.getName()).orElse(null);
        String finalSubstitute = substitutions.findByRequestId(request.getId()).stream()
                .filter(substitution -> Substitution.EFFECTIVE.equals(substitution.getStatus()))
                .findFirst()
                .flatMap(substitution -> teachers.findById(substitution.getSubstituteTeacherId()))
                .map(Teacher::getName)
                .orElse(null);
        return new SubstitutionContext(
                request.getId(),
                originalTeacher,
                courseName,
                className,
                request.getEffectiveDate() == null ? null : request.getEffectiveDate().toString(),
                request.getPeriodNo(),
                request.getStatus(),
                finalSubstitute,
                recommendations(log.getResponseJson()));
    }

    private List<Candidate> recommendations(String responseJson) {
        JsonNode root = jsonNode(responseJson);
        if (root == null) {
            return List.of();
        }
        JsonNode array = root.isArray() ? root : root.path("recommendations");
        if (!array.isArray()) {
            return List.of();
        }
        List<Candidate> result = new ArrayList<>();
        for (JsonNode node : array) {
            result.add(new Candidate(
                    node.hasNonNull("teacherId") ? node.get("teacherId").asLong() : null,
                    node.path("name").asText(null),
                    node.path("reason").asText(null),
                    node.hasNonNull("score") ? node.get("score").asInt() : null));
        }
        return result;
    }

    private Object parseJson(String value) {
        JsonNode node = jsonNode(value);
        return node == null ? value : node;
    }

    private JsonNode jsonNode(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(value);
        } catch (Exception ex) {
            return null;
        }
    }

    // ----------------------------------------------------------------- export

    @Transactional(readOnly = true)
    public byte[] export(
            String format,
            LocalDate from,
            LocalDate to,
            Long userId,
            String username,
            String taskType,
            Long providerId,
            String type) {
        List<HistoryItem> items = filterItems(from, to, userId, username, taskType, providerId, type);
        String normalized = format == null ? "xlsx" : format.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "pdf" -> pdf(items);
            case "xlsx" -> xlsx(items);
            default -> throw ApiException.badRequest("导出格式只能是 xlsx 或 pdf");
        };
    }

    private byte[] xlsx(List<HistoryItem> items) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("AI 操作历史");
            String[] headers = {"时间", "类型", "任务", "操作人", "供应商", "模型", "结果", "摘要"};
            Row header = sheet.createRow(0);
            var bold = workbook.createFont();
            bold.setBold(true);
            var style = workbook.createCellStyle();
            style.setFont(bold);
            for (int i = 0; i < headers.length; i++) {
                var cell = header.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(style);
                sheet.setColumnWidth(i, 22 * 256);
            }
            int rowIndex = 1;
            for (HistoryItem item : items) {
                Row row = sheet.createRow(rowIndex++);
                row.createCell(0).setCellValue(item.createdAt());
                row.createCell(1).setCellValue(TYPE_IMPORT.equals(item.type()) ? "导入" : "调用");
                row.createCell(2).setCellValue(item.taskType());
                row.createCell(3).setCellValue(nullSafe(item.username()));
                row.createCell(4).setCellValue(nullSafe(item.providerName()));
                row.createCell(5).setCellValue(nullSafe(item.modelName()));
                row.createCell(6).setCellValue(item.success() ? "成功" : "失败");
                row.createCell(7).setCellValue(nullSafe(item.summary()));
            }
            workbook.write(out);
            return out.toByteArray();
        } catch (Exception ex) {
            throw new IllegalStateException("导出 Excel 失败", ex);
        }
    }

    private byte[] pdf(List<HistoryItem> items) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            BaseFont baseFont = resolveFont();
            Font titleFont = new Font(baseFont, 16, Font.BOLD);
            Font headFont = new Font(baseFont, 9, Font.BOLD);
            Font bodyFont = new Font(baseFont, 8, Font.NORMAL);
            Document document = new Document(PageSize.A4.rotate());
            PdfWriter.getInstance(document, out);
            document.open();
            document.add(new Paragraph("AI 操作历史", titleFont));
            document.add(new Paragraph("导出时间：" + Instant.now(), bodyFont));
            document.add(new Paragraph(" "));
            PdfPTable table = new PdfPTable(new float[] {2.2f, 1.1f, 1.5f, 1.3f, 1.5f, 1.5f, 1f, 3f});
            table.setWidthPercentage(100);
            for (String header : List.of("时间", "类型", "任务", "操作人", "供应商", "模型", "结果", "摘要")) {
                PdfPCell cell = new PdfPCell(new Phrase(header, headFont));
                cell.setPadding(4);
                table.addCell(cell);
            }
            for (HistoryItem item : items) {
                for (String value : List.of(
                        item.createdAt(),
                        TYPE_IMPORT.equals(item.type()) ? "导入" : "调用",
                        item.taskType(),
                        nullSafe(item.username()),
                        nullSafe(item.providerName()),
                        nullSafe(item.modelName()),
                        item.success() ? "成功" : "失败",
                        nullSafe(item.summary()))) {
                    PdfPCell cell = new PdfPCell(new Phrase(value, bodyFont));
                    cell.setPadding(3);
                    table.addCell(cell);
                }
            }
            document.add(table);
            document.close();
            return out.toByteArray();
        } catch (Exception ex) {
            throw new IllegalStateException("导出 PDF 失败", ex);
        }
    }

    private BaseFont resolveFont() {
        List<String> candidates = new ArrayList<>();
        if (pdfFontPath != null && !pdfFontPath.isBlank()) {
            candidates.add(pdfFontPath);
        }
        candidates.add("C:/Windows/Fonts/msyh.ttc,0");
        candidates.add("C:/Windows/Fonts/simsun.ttc,0");
        candidates.add("C:/Windows/Fonts/simhei.ttf");
        candidates.add("/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc,0");
        candidates.add("/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc,0");
        for (String candidate : candidates) {
            String path = candidate.contains(",") ? candidate.substring(0, candidate.indexOf(',')) : candidate;
            if (!new File(path).exists()) {
                continue;
            }
            try {
                return BaseFont.createFont(candidate, BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
            } catch (Exception ignored) {
                // try the next candidate
            }
        }
        try {
            return BaseFont.createFont(BaseFont.HELVETICA, BaseFont.WINANSI, BaseFont.NOT_EMBEDDED);
        } catch (Exception ex) {
            throw new IllegalStateException("无法加载 PDF 字体", ex);
        }
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
