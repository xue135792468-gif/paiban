package com.paiban.ai.imports;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paiban.ai.AiGateway;
import com.paiban.ai.AiImage;
import com.paiban.ai.AiMessage;
import com.paiban.ai.AiOptions;
import com.paiban.ai.AiRequest;
import com.paiban.ai.AiResult;
import com.paiban.ai.AiTaskType;
import com.paiban.ai.imports.ImportDtos.ColumnDto;
import com.paiban.ai.imports.ImportDtos.CommitResultDto;
import com.paiban.ai.imports.ImportDtos.MappingDto;
import com.paiban.ai.imports.ImportDtos.PreviewDto;
import com.paiban.ai.imports.ImportDtos.RowDto;
import com.paiban.ai.imports.ImportDtos.RowErrorDto;
import com.paiban.common.excel.ExcelTableReader;
import com.paiban.common.settings.SettingsService;
import com.paiban.common.web.ApiException;
import com.paiban.common.web.ImportValidationException;
import com.paiban.foundation.domain.Teacher;
import com.paiban.foundation.domain.TeacherRepository;
import com.paiban.identity.domain.AppUser;
import com.paiban.identity.domain.Permissions;
import com.paiban.identity.service.UserService;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * Multi-channel AI import: spreadsheet/text/image in, teacher preview out, and a
 * human-in-the-loop correction step before anything reaches the teacher table.
 */
@Service
public class AiImportService {

    private static final int MAX_ROWS = 500;
    private static final Pattern PHONE = Pattern.compile("^[0-9+\\-\\s()]{5,30}$");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final AiGateway gateway;
    private final AiImportLogRepository logs;
    private final AiImportParser parser;
    private final TeacherRepository teachers;
    private final SettingsService settings;
    private final UploadStorage storage;
    private final ObjectMapper objectMapper;
    private final UserService users;

    public AiImportService(
            AiGateway gateway,
            AiImportLogRepository logs,
            AiImportParser parser,
            TeacherRepository teachers,
            SettingsService settings,
            UploadStorage storage,
            ObjectMapper objectMapper,
            UserService users) {
        this.gateway = gateway;
        this.logs = logs;
        this.parser = parser;
        this.teachers = teachers;
        this.settings = settings;
        this.storage = storage;
        this.objectMapper = objectMapper;
        this.users = users;
    }

    // ---------------------------------------------------------------- channels

    public PreviewDto startExcel(MultipartFile file, String username) {
        requireConfigured();
        UploadStorage.StoredFile stored = storage.storeSpreadsheet(file);
        AiImportLog log = newLog("excel", stored, username);
        try {
            TableData table = readSpreadsheet(stored.path());
            String prompt = AiImportPrompts.tablePrompt(ImportTarget.TEACHER, table.headers(), table.rows(), objectMapper);
            return runPreview(log, bundle(prompt, List.of()), null);
        } catch (RuntimeException ex) {
            markFailed(log, ex.getMessage());
            throw ex;
        }
    }

    public PreviewDto startText(String text, String username) {
        requireConfigured();
        String source = text == null ? "" : text.trim();
        if (source.isEmpty()) {
            throw ApiException.badRequest("请粘贴需要解析的文本");
        }
        AiImportLog log = newLog("text", null, username);
        log.setSourceText(source);
        try {
            String prompt = AiImportPrompts.textPrompt(ImportTarget.TEACHER, source);
            return runPreview(log, bundle(prompt, List.of()), null);
        } catch (RuntimeException ex) {
            markFailed(log, ex.getMessage());
            throw ex;
        }
    }

    public PreviewDto startImage(MultipartFile file, String username) {
        requireConfigured();
        UploadStorage.StoredFile stored = storage.storeImage(file);
        AiImportLog log = newLog("image", stored, username);
        try {
            PromptBundle bundle = imageBundle(stored);
            return runPreview(log, bundle, bundle.originalPrompt());
        } catch (RuntimeException ex) {
            markFailed(log, ex.getMessage());
            throw ex;
        }
    }

    // ------------------------------------------------------------- session ops

    public PreviewDto getSession(long id, String username) {
        AiImportLog log = requireSession(id, username);
        return preview(log);
    }

    public PreviewDto updateRows(long id, List<RowDto> rows, String username) {
        AiImportLog log = requireSession(id, username);
        requireEditable(log);
        List<RowDto> normalized = normalize(rows, threshold());
        Payload existing = readPayload(log);
        savePayload(log, existing.mappings(), normalized, existing.explanation(), AiImportLog.EDITED);
        log.setConfirmedJson(log.getPreviewJson());
        touch(log);
        return preview(log);
    }

    public PreviewDto continueWithAi(long id, String username) {
        AiImportLog log = requireSession(id, username);
        requireEditable(log);
        Payload existing = readPayload(log);
        List<RowDto> current = existing.rows();
        PromptBundle original = bundleFor(log);
        String continuePrompt = AiImportPrompts.continuePrompt(
                ImportTarget.TEACHER, original.originalPrompt(), current, objectMapper);
        PromptBundle bundle = new PromptBundle(
                List.of(new AiMessage("system", AiImportPrompts.system(ImportTarget.TEACHER)),
                        new AiMessage("user", continuePrompt)),
                original.images(),
                original.originalPrompt());
        AiImportParser.Parsed parsed = callAi(bundle, log);
        List<RowDto> merged = merge(current, parsed.rows());
        savePayload(log, parsed.mappings(), merged, parsed.explanation(), AiImportLog.EDITED);
        log.setConfirmedJson(log.getPreviewJson());
        touch(log);
        return preview(log);
    }

    public PreviewDto reparse(long id, String username) {
        AiImportLog log = requireSession(id, username);
        if (AiImportLog.COMMITTED.equals(log.getStatus())) {
            throw ApiException.conflict("已入库的导入不能再重新解析");
        }
        PromptBundle bundle = bundleFor(log);
        return runPreview(log, bundle, bundle.originalPrompt());
    }

    public CommitResultDto commit(long id, String username) {
        AiImportLog log = requireSession(id, username);
        requireEditable(log);
        List<RowDto> rows = readPayload(log).rows();
        if (rows.isEmpty()) {
            throw ApiException.badRequest("没有可入库的数据");
        }
        validate(rows);
        List<Teacher> entities = new ArrayList<>();
        for (RowDto row : rows) {
            Teacher teacher = new Teacher();
            teacher.setName(value(row, "name"));
            teacher.setEmployeeNo(blankToNull(value(row, "employeeNo")));
            teacher.setPhone(blankToNull(value(row, "phone")));
            teacher.setEmail(blankToNull(value(row, "email")));
            teacher.setSubject(blankToNull(value(row, "subject")));
            teacher.setActive(true);
            teacher.setCreatedAt(Instant.now());
            entities.add(teacher);
        }
        teachers.saveAll(entities);
        log.setStatus(AiImportLog.COMMITTED);
        log.setImportedCount(entities.size());
        log.setResultJson(writeJson(Map.of("imported", entities.size())));
        touch(log);
        return new CommitResultDto(entities.size(), List.of());
    }

    public byte[] file(long id, String username) {
        AiImportLog log = requireSession(id, username);
        return storage.read(log.getFilePath());
    }

    public String fileName(long id, String username) {
        return requireSession(id, username).getFileName();
    }

    // ------------------------------------------------------------------ AI I/O

    private PreviewDto runPreview(AiImportLog log, PromptBundle bundle, String originalPrompt) {
        AiImportParser.Parsed parsed = callAi(bundle, log);
        savePayload(log, parsed.mappings(), parsed.rows(), parsed.explanation(), AiImportLog.PREVIEW);
        log.setConfirmedJson(null);
        log.setResultJson(null);
        if (originalPrompt != null && log.getSourceText() == null && log.getFilePath() == null) {
            log.setSourceText(originalPrompt);
        }
        touch(log);
        return preview(log);
    }

    private AiImportParser.Parsed callAi(PromptBundle bundle, AiImportLog log) {
        AiRequest request = new AiRequest(
                AiTaskType.DATA_IMPORT, bundle.messages(), bundle.images(), AiOptions.asJson());
        AiResult result = bundle.images().isEmpty() ? gateway.chat(request) : gateway.chatWithVision(request);
        log.setAiRawResponse(result.rawResponse());
        if (!result.success()) {
            log.setAiExplanation("AI 调用失败：" + (result.errorMessage() == null ? "未知错误" : result.errorMessage()));
            log.setStatus(AiImportLog.FAILED);
            touch(log);
            throw ApiException.badRequest("AI 解析失败：" + (result.errorMessage() == null ? "请检查 AI 供应商配置" : result.errorMessage()));
        }
        try {
            return parser.parse(result.content(), ImportTarget.TEACHER, threshold());
        } catch (IllegalArgumentException ex) {
            log.setAiExplanation(ex.getMessage());
            log.setStatus(AiImportLog.FAILED);
            touch(log);
            throw ApiException.badRequest(ex.getMessage());
        }
    }

    private PromptBundle bundleFor(AiImportLog log) {
        String type = log.getImportType();
        if ("image".equals(type)) {
            return imageBundle(new UploadStorage.StoredFile(
                    log.getFilePath(), log.getFileName(), log.getFileSize(), null));
        }
        if ("text".equals(type)) {
            String prompt = AiImportPrompts.textPrompt(ImportTarget.TEACHER, log.getSourceText());
            return bundle(prompt, List.of());
        }
        TableData table = readSpreadsheet(log.getFilePath());
        String prompt = AiImportPrompts.tablePrompt(ImportTarget.TEACHER, table.headers(), table.rows(), objectMapper);
        return bundle(prompt, List.of());
    }

    private PromptBundle bundle(String userPrompt, List<AiImage> images) {
        return new PromptBundle(
                List.of(new AiMessage("system", AiImportPrompts.system(ImportTarget.TEACHER)),
                        new AiMessage("user", userPrompt)),
                images,
                userPrompt);
    }

    private PromptBundle imageBundle(UploadStorage.StoredFile stored) {
        byte[] bytes = storage.read(stored.path());
        String mime = stored.contentType() == null ? mimeFor(stored.path()) : stored.contentType();
        String prompt = AiImportPrompts.imagePrompt(ImportTarget.TEACHER);
        return new PromptBundle(
                List.of(new AiMessage("system", AiImportPrompts.system(ImportTarget.TEACHER)),
                        new AiMessage("user", prompt)),
                List.of(new AiImage(mime, bytes)),
                prompt);
    }

    private record PromptBundle(List<AiMessage> messages, List<AiImage> images, String originalPrompt) {}

    // ------------------------------------------------------------- persistence

    private AiImportLog newLog(String importType, UploadStorage.StoredFile stored, String username) {
        AiImportLog log = new AiImportLog();
        log.setImportType(importType);
        log.setTargetType(ImportTarget.TEACHER.code());
        log.setUsername(username);
        AppUser user = users.getByUsername(username);
        log.setUserId(user == null ? null : user.getId());
        if (stored != null) {
            log.setFileName(stored.fileName());
            log.setFileSize(stored.size());
            log.setFilePath(stored.path());
        }
        log.setStatus(AiImportLog.PREVIEW);
        log.setRowCount(0);
        log.setImportedCount(0);
        log.setCreatedAt(Instant.now());
        log.setUpdatedAt(Instant.now());
        return logs.save(log);
    }

    private void savePayload(
            AiImportLog log, List<MappingDto> mappings, List<RowDto> rows, String explanation, String status) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("mappings", mappings);
        payload.put("rows", rows);
        payload.put("explanation", explanation == null ? "" : explanation);
        log.setPreviewJson(writeJson(payload));
        log.setAiExplanation(explanation);
        log.setStatus(status);
        log.setRowCount(rows.size());
        touch(log);
    }

    private Payload readPayload(AiImportLog log) {
        if (log.getPreviewJson() == null || log.getPreviewJson().isBlank()) {
            return new Payload(List.of(), List.of(), "");
        }
        try {
            JsonNode root = objectMapper.readTree(log.getPreviewJson());
            List<MappingDto> mappings = new ArrayList<>();
            for (JsonNode item : root.path("mappings")) {
                mappings.add(new MappingDto(
                        item.path("source").asText(""),
                        item.path("target").asText(""),
                        item.path("note").asText("")));
            }
            // Reuse the AI parser so confidence/low-confidence stay consistent with the threshold.
            String normalized = objectMapper.writeValueAsString(Map.of(
                    "mappings", mappings,
                    "rows", root.path("rows"),
                    "explanation", root.path("explanation").asText("")));
            AiImportParser.Parsed parsed = parser.parse(normalized, ImportTarget.TEACHER, threshold());
            return new Payload(parsed.mappings(), parsed.rows(), parsed.explanation());
        } catch (Exception ex) {
            return new Payload(List.of(), List.of(), "");
        }
    }

    private PreviewDto preview(AiImportLog log) {
        Payload payload = readPayload(log);
        return new PreviewDto(
                log.getId(),
                log.getTargetType(),
                columns(),
                payload.rows(),
                payload.mappings(),
                payload.explanation(),
                threshold(),
                log.getStatus());
    }

    private record Payload(List<MappingDto> mappings, List<RowDto> rows, String explanation) {}

    private List<ColumnDto> columns() {
        return ImportTarget.TEACHER.fields().stream()
                .map(field -> new ColumnDto(field.field(), field.label(), field.required()))
                .toList();
    }

    private void markFailed(AiImportLog log, String message) {
        log.setStatus(AiImportLog.FAILED);
        log.setAiExplanation(message);
        touch(log);
    }

    private void touch(AiImportLog log) {
        log.setUpdatedAt(Instant.now());
        logs.save(log);
    }

    private AiImportLog requireSession(long id, String username) {
        AiImportLog log = logs.findById(id).orElseThrow(() -> ApiException.notFound("导入会话不存在: " + id));
        AppUser user = users.getByUsername(username);
        boolean admin = user != null && users.hasPermission(user, Permissions.FOUNDATION_MANAGE);
        if (!Objects.equals(log.getUsername(), username) && !admin) {
            throw ApiException.forbidden("不能访问他人的导入记录");
        }
        return log;
    }

    private void requireEditable(AiImportLog log) {
        if (AiImportLog.COMMITTED.equals(log.getStatus())) {
            throw ApiException.conflict("该导入已入库，不能再修改");
        }
        if (AiImportLog.FAILED.equals(log.getStatus())) {
            throw ApiException.conflict("该导入解析失败，请打回重析");
        }
    }

    private void requireConfigured() {
        if (!gateway.configured(AiTaskType.DATA_IMPORT)) {
            throw ApiException.badRequest("请先在设置中配置 AI 供应商与模型");
        }
    }

    // ------------------------------------------------------------ source data

    private TableData readSpreadsheet(String path) {
        String name = path == null ? "" : path.toLowerCase();
        byte[] bytes = storage.read(path);
        try {
            if (name.endsWith(".csv")) {
                CsvTableReader.Table table = CsvTableReader.read(bytes);
                return new TableData(table.headers(), table.rows());
            }
            ExcelTableReader.Table table = ExcelTableReader.read(new ByteArrayInputStream(bytes));
            List<Map<String, String>> rows = table.rows().stream()
                    .map(ExcelTableReader.TableRow::values)
                    .toList();
            return new TableData(table.headers(), rows);
        } catch (Exception ex) {
            throw ApiException.badRequest("无法读取表格文件: " + ex.getMessage());
        }
    }

    private record TableData(List<String> headers, List<Map<String, String>> rows) {}

    // -------------------------------------------------------------- validation

    private void validate(List<RowDto> rows) {
        List<ImportValidationException.RowError> errors = new ArrayList<>();
        Set<String> employeeNos = new HashSet<>();
        Set<String> nameSubjects = new HashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            RowDto row = rows.get(i);
            int line = i + 1;
            String name = value(row, "name");
            String employeeNo = value(row, "employeeNo");
            String phone = value(row, "phone");
            String email = value(row, "email");
            String subject = value(row, "subject");

            if (name.isBlank()) {
                errors.add(new ImportValidationException.RowError(line, "name", "姓名不能为空"));
            }
            if (!employeeNo.isBlank()) {
                if (!employeeNos.add(employeeNo)) {
                    errors.add(new ImportValidationException.RowError(line, "employeeNo", "工号在本次导入中重复"));
                } else if (teachers.existsByEmployeeNo(employeeNo)) {
                    errors.add(new ImportValidationException.RowError(line, "employeeNo", "工号已存在"));
                }
            } else if (!name.isBlank()) {
                String key = name + "|" + subject;
                if (!nameSubjects.add(key)) {
                    errors.add(new ImportValidationException.RowError(line, "name", "姓名与学科在本次导入中重复"));
                } else {
                    boolean exists = teachers.findByName(name).stream()
                            .anyMatch(teacher -> Objects.equals(teacher.getSubject(), blankToNull(subject)));
                    if (exists) {
                        errors.add(new ImportValidationException.RowError(line, "name", "同名同科教师已存在"));
                    }
                }
            }
            if (!phone.isBlank() && !PHONE.matcher(phone).matches()) {
                errors.add(new ImportValidationException.RowError(line, "phone", "电话格式不正确"));
            }
            if (!email.isBlank() && !EMAIL.matcher(email).matches()) {
                errors.add(new ImportValidationException.RowError(line, "email", "邮箱格式不正确"));
            }
        }
        if (!errors.isEmpty()) {
            throw new ImportValidationException("数据校验失败，请修正后重试", errors);
        }
    }

    // ------------------------------------------------------------------ helpers

    private List<RowDto> normalize(List<RowDto> rows, double threshold) {
        if (rows == null || rows.isEmpty()) {
            throw ApiException.badRequest("请至少保留一行数据");
        }
        if (rows.size() > MAX_ROWS) {
            throw ApiException.badRequest("一次最多导入 " + MAX_ROWS + " 行");
        }
        List<RowDto> result = new ArrayList<>();
        for (RowDto row : rows) {
            Map<String, String> values = new LinkedHashMap<>();
            Map<String, Double> confidence = new LinkedHashMap<>();
            List<String> low = new ArrayList<>();
            for (String field : ImportTarget.TEACHER.fieldNames()) {
                String value = row.values() == null ? "" : row.values().getOrDefault(field, "");
                value = value == null ? "" : value.trim();
                double score = row.confidence() == null || row.confidence().get(field) == null
                        ? 1.0
                        : row.confidence().get(field);
                score = Math.max(0, Math.min(1, score));
                values.put(field, value);
                confidence.put(field, score);
                if (score < threshold) {
                    low.add(field);
                }
            }
            result.add(new RowDto(values, confidence, low));
        }
        return result;
    }

    private List<RowDto> merge(List<RowDto> userRows, List<RowDto> aiRows) {
        int count = Math.max(userRows.size(), aiRows.size());
        List<RowDto> merged = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            RowDto user = i < userRows.size() ? userRows.get(i) : null;
            RowDto ai = i < aiRows.size() ? aiRows.get(i) : null;
            Map<String, String> values = new LinkedHashMap<>();
            Map<String, Double> confidence = new LinkedHashMap<>();
            List<String> low = new ArrayList<>();
            for (String field : ImportTarget.TEACHER.fieldNames()) {
                String userValue = user == null ? "" : user.values().getOrDefault(field, "");
                String aiValue = ai == null ? "" : ai.values().getOrDefault(field, "");
                String value = userValue == null || userValue.isBlank() ? aiValue : userValue;
                double score = userValue != null && !userValue.isBlank()
                        ? 1.0
                        : ai == null || ai.confidence().get(field) == null ? 1.0 : ai.confidence().get(field);
                values.put(field, value == null ? "" : value);
                confidence.put(field, score);
                if (score < threshold()) {
                    low.add(field);
                }
            }
            merged.add(new RowDto(values, confidence, low));
        }
        return merged;
    }

    private String value(RowDto row, String field) {
        if (row == null || row.values() == null) {
            return "";
        }
        String value = row.values().get(field);
        return value == null ? "" : value.trim();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private double threshold() {
        return settings.aiImportConfidenceThreshold();
    }

    private String mimeFor(String path) {
        String name = path == null ? "" : path.toLowerCase();
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (name.endsWith(".webp")) {
            return "image/webp";
        }
        return "image/png";
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("保存导入数据失败", ex);
        }
    }
}
