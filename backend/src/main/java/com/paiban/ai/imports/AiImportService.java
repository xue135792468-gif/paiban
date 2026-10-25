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
import com.paiban.ai.imports.ImportDtos.CreateItem;
import com.paiban.ai.imports.ImportDtos.FailedBatchDto;
import com.paiban.ai.imports.ImportDtos.ImportProgressDto;
import com.paiban.ai.imports.ImportDtos.IssueDto;
import com.paiban.ai.imports.ImportDtos.MappingDto;
import com.paiban.ai.imports.ImportDtos.MappingItem;
import com.paiban.ai.imports.ImportDtos.MappingRequest;
import com.paiban.ai.imports.ImportDtos.PreviewDto;
import com.paiban.ai.imports.ImportDtos.RowDto;
import com.paiban.ai.imports.ImportDtos.RowErrorDto;
import com.paiban.ai.imports.ImportDtos.TimetableSummaryDto;
import com.paiban.ai.imports.workbook.WorkbookImportFlow;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

/**
 * Multi-channel AI import: spreadsheet/text/image in, a target-shaped preview out, and
 * a human-in-the-loop correction step before anything is persisted. The target decides
 * which fields the AI is asked for, which columns the preview shows and which rows a
 * commit would write.
 */
@Service
public class AiImportService {

    private static final int MAX_ROWS = 5000;
    private static final int DATA_IMPORT_MAX_TOKENS = 8192;
    private static final int TIMETABLE_HEADER_ROWS = 2;
    private static final int TIMETABLE_CHUNK_ROWS = 1;
    private static final int TIMETABLE_CHUNK_THRESHOLD = 3;
    /** What a parse says when the import pool would not take the job (queue full). */
    private static final String QUEUE_REFUSED = "系统繁忙，解析任务排队失败，请稍后重试";
    private static final String LOST_VERSION = "导入状态已被后台更新，请刷新后重试";
    /** What a stop that caught a parse before its first finished batch leaves behind. */
    private static final String STOPPED_WITH_NOTHING = "已停止解析，没有任何已完成的批次";
    /** The only reason an abandoned session carries; the request has no body to supply one. */
    private static final String ABANDON_REASON = "用户放弃导入";
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
    private final TimetableImportService timetableImports;
    private final ImportMappingService nameMappings;
    private final TaskExecutor importExecutor;
    private final TransactionTemplate transactionTemplate;
    /** Reads and writes {@code batches_json}; the one place batches are folded together. */
    private final BatchStore batchStore;
    /** The phase-11 workbook pipeline: browse with tools, merge by slot, commit categories. */
    private final WorkbookImportFlow workbook;
    /** Configured-AI and timetable-edit gates shared with the workbook pipeline. */
    private final ImportPermissions permissions;
    /**
     * Live stop requests, keyed by session. In memory on purpose: a stop only matters to
     * the background thread of this process; a restart is covered by the startup recovery.
     */
    private final ConcurrentHashMap<Long, AtomicBoolean> stopFlags = new ConcurrentHashMap<>();

    public AiImportService(
            AiGateway gateway,
            AiImportLogRepository logs,
            AiImportParser parser,
            TeacherRepository teachers,
            SettingsService settings,
            UploadStorage storage,
            ObjectMapper objectMapper,
            UserService users,
            TimetableImportService timetableImports,
            ImportMappingService nameMappings,
            WorkbookImportFlow workbook,
            ImportPermissions permissions,
            @Qualifier("importExecutor") TaskExecutor importExecutor,
            PlatformTransactionManager transactionManager) {
        this.gateway = gateway;
        this.logs = logs;
        this.parser = parser;
        this.teachers = teachers;
        this.settings = settings;
        this.storage = storage;
        this.objectMapper = objectMapper;
        this.users = users;
        this.timetableImports = timetableImports;
        this.nameMappings = nameMappings;
        this.workbook = workbook;
        this.permissions = permissions;
        this.importExecutor = importExecutor;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.batchStore = new BatchStore(objectMapper);
    }

    // ---------------------------------------------------------------- channels

    public PreviewDto startExcel(MultipartFile file, String username) {
        return startExcel(file, username, ImportTarget.TEACHER);
    }

    public PreviewDto startExcel(MultipartFile file, String username, ImportTarget target) {
        ImportTarget effective = target == null ? ImportTarget.TEACHER : target;
        if (effective == ImportTarget.WORKBOOK) {
            // No target is chosen for a real workbook: the model decides what each sheet is.
            return workbook.start(file, username);
        }
        requireConfigured();
        UploadStorage.StoredFile stored = storage.storeSpreadsheet(file);
        AiImportLog log = newLog("excel", stored, username, effective);
        try {
            GridData grid = readGrid(stored.path());
            if (effective == ImportTarget.TIMETABLE
                    && dataRowCount(grid.rows()) > TIMETABLE_CHUNK_THRESHOLD) {
                return startBatchedParse(log, grid, effective);
            }
            String prompt = AiImportPrompts.gridPrompt(effective, grid.sheetName(), grid.rows(), objectMapper);
            return runPreview(log, bundle(prompt, List.of(), effective), null, effective);
        } catch (RuntimeException ex) {
            markFailed(log, ex.getMessage());
            throw ex;
        }
    }

    public PreviewDto startText(String text, String username) {
        return startText(text, username, ImportTarget.TEACHER);
    }

    public PreviewDto startText(String text, String username, ImportTarget target) {
        ImportTarget effective = target == null ? ImportTarget.TEACHER : target;
        requireConfigured();
        String source = text == null ? "" : text.trim();
        if (source.isEmpty()) {
            throw ApiException.badRequest("请粘贴需要解析的文本");
        }
        AiImportLog log = newLog("text", null, username, effective);
        log.setSourceText(source);
        try {
            String prompt = AiImportPrompts.textPrompt(effective, source);
            return runPreview(log, bundle(prompt, List.of(), effective), null, effective);
        } catch (RuntimeException ex) {
            markFailed(log, ex.getMessage());
            throw ex;
        }
    }

    public PreviewDto startImage(MultipartFile file, String username) {
        return startImage(file, username, ImportTarget.TEACHER);
    }

    public PreviewDto startImage(MultipartFile file, String username, ImportTarget target) {
        ImportTarget effective = target == null ? ImportTarget.TEACHER : target;
        // Image import is out of scope for this phase beyond the teacher list.
        if (effective != ImportTarget.TEACHER) {
            throw ApiException.badRequest("图片导入暂只支持教师");
        }
        requireConfigured();
        UploadStorage.StoredFile stored = storage.storeImage(file);
        AiImportLog log = newLog("image", stored, username, effective);
        try {
            PromptBundle bundle = imageBundle(stored, effective);
            return runPreview(log, bundle, bundle.originalPrompt(), effective);
        } catch (RuntimeException ex) {
            markFailed(log, ex.getMessage());
            throw ex;
        }
    }

    // ------------------------------------------------------------- session ops

    public PreviewDto getSession(long id, String username) {
        AiImportLog log = requireSession(id, username);
        return isWorkbook(log) ? workbook.preview(log) : preview(log);
    }

    private boolean isWorkbook(AiImportLog log) {
        return ImportTarget.WORKBOOK.code().equals(log.getTargetType());
    }

    @Transactional
    public PreviewDto updateRows(long id, List<RowDto> rows, String username) {
        AiImportLog log = requireSession(id, username);
        requireEditable(log);
        requireNotWorkbook(log);
        ImportTarget target = targetOf(log);
        List<RowDto> normalized = normalize(rows, threshold(), target);
        Payload existing = readPreviewPayload(log, target);
        saveConfirmed(log, existing.mappings(), normalized, existing.explanation(),
                existing.suggestedTarget(), AiImportLog.EDITED);
        return preview(log);
    }

    /**
     * Applies the user's name mappings to the working rows, records them for
     * traceability, and returns the preview with the issues recalculated. The raw AI
     * answer is never rewritten.
     */
    @Transactional
    public PreviewDto applyMapping(long id, MappingRequest request, String username) {
        AiImportLog log = requireSession(id, username);
        requireEditable(log);
        requireNotWorkbook(log);
        ImportTarget target = targetOf(log);
        if (target != ImportTarget.TIMETABLE) {
            throw ApiException.badRequest("该导入目标不支持名称映射");
        }
        requireTimetableEdit(username);
        Payload working = readWorkingPayload(log, target);
        if (working.rows().isEmpty()) {
            throw ApiException.badRequest("没有可映射的数据");
        }
        List<MappingItem> items = request == null || request.mappings() == null
                ? List.of()
                : request.mappings();
        List<CreateItem> creates = request == null || request.creates() == null
                ? List.of()
                : request.creates();
        List<MappingDto> merged = new ArrayList<>(working.mappings());
        merged.addAll(nameMappings.apply(working.rows(), items, creates));
        saveConfirmed(log, merged, working.rows(), working.explanation(), working.suggestedTarget(),
                AiImportLog.EDITED);
        return preview(log);
    }

    public PreviewDto continueWithAi(long id, String username) {
        AiImportLog log = requireSession(id, username);
        requireEditable(log);
        requireNotWorkbook(log);
        ImportTarget target = targetOf(log);
        Payload existing = readWorkingPayload(log, target);
        List<RowDto> current = existing.rows();
        PromptBundle original = bundleFor(log, target);
        String continuePrompt = AiImportPrompts.continuePrompt(
                target, original.originalPrompt(), current, objectMapper);
        PromptBundle bundle = new PromptBundle(
                List.of(new AiMessage("system", AiImportPrompts.system(target)),
                        new AiMessage("user", continuePrompt)),
                original.images(),
                original.originalPrompt());
        // The continuation answers for the whole table, so no batch plan survives it.
        resetBatchProgress(log);
        AiImportParser.Parsed parsed = callAi(bundle, log, target);
        List<RowDto> merged = merge(current, parsed.rows(), target);
        savePreview(log, parsed.mappings(), parsed.rows(), parsed.explanation(),
                parsed.suggestedTarget(), AiImportLog.EDITED);
        saveConfirmed(log, parsed.mappings(), merged, parsed.explanation(),
                parsed.suggestedTarget(), AiImportLog.EDITED);
        return preview(log);
    }

    public PreviewDto reparse(long id, String username) {
        AiImportLog log = requireSession(id, username);
        if (AiImportLog.ABANDONED.equals(log.getStatus())) {
            throw ApiException.conflict("该导入已放弃，不能再重新解析");
        }
        if (AiImportLog.COMMITTED.equals(log.getStatus())) {
            throw ApiException.conflict("已入库的导入不能再重新解析");
        }
        if (AiImportLog.PARSING.equals(log.getStatus())) {
            throw ApiException.conflict("解析还没完成，请等进度走完再重新解析");
        }
        if (AiImportLog.PARTIAL.equals(log.getStatus())) {
            // A full re-parse would throw away the batches that did parse.
            throw ApiException.conflict("这次导入只解析了一部分班级，请先重试失败批次或放弃导入");
        }
        ImportTarget target = targetOf(log);
        if (target == ImportTarget.WORKBOOK) {
            return workbook.reparse(log);
        }
        if (target == ImportTarget.TIMETABLE && log.getFilePath() != null) {
            GridData grid = readGrid(log.getFilePath());
            if (dataRowCount(grid.rows()) > TIMETABLE_CHUNK_THRESHOLD) {
                // A large table is a batch plan: re-parsing it in one call would bring back
                // the truncated answer the batch plan exists to avoid.
                return startBatchedParse(log, grid, target);
            }
        }
        PromptBundle bundle = bundleFor(log, target);
        return runPreview(log, bundle, bundle.originalPrompt(), target);
    }

    /**
     * Writes the session's working rows into their target. Teacher rows become
     * teacher records; timetable rows become a DRAFT timetable in the current
     * semester and return its id.
     */
    public CommitResultDto commit(long id, String username) {
        AiImportLog log = requireSession(id, username);
        requireEditable(log);
        ImportTarget target = targetOf(log);
        if (target == ImportTarget.WORKBOOK) {
            // Each category is its own transaction, so this one must not wrap them all.
            return workbook.commit(log, username);
        }
        return transactionTemplate.execute(status -> commitSingleTarget(log, target, username));
    }

    private CommitResultDto commitSingleTarget(AiImportLog log, ImportTarget target, String username) {
        Payload working = readWorkingPayload(log, target);
        List<RowDto> rows = working.rows();
        if (rows.isEmpty()) {
            throw ApiException.badRequest("没有可入库的数据");
        }
        if (target.kind() == ImportTarget.Kind.GRID) {
            if (target != ImportTarget.TIMETABLE) {
                throw ApiException.badRequest("导入目标 " + target.code() + " 的入库功能尚未实现");
            }
            return commitTimetable(log, working, rows, username);
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
        saveConfirmed(log, working.mappings(), rows, working.explanation(),
                working.suggestedTarget(), AiImportLog.COMMITTED);
        log.setImportedCount(entities.size());
        log.setResultJson(writeJson(Map.of("imported", entities.size(), "rows", rows)));
        touch(log);
        return new CommitResultDto(
                entities.size(), List.of(), null, List.of(), List.of(), List.of());
    }

    private CommitResultDto commitTimetable(
            AiImportLog log, Payload working, List<RowDto> rows, String username) {
        requireTimetableEdit(username);
        TimetableImportService.Result result = timetableImports.importRows(rows, null, username);
        if (!result.errors().isEmpty()) {
            List<ImportValidationException.RowError> rowErrors = result.errors().stream()
                    .map(error -> new ImportValidationException.RowError(
                            error.row(), error.field(), error.message(), error.candidates()))
                    .toList();
            throw new ImportValidationException("数据校验失败，请修正后重试", rowErrors);
        }
        saveConfirmed(log, working.mappings(), rows, working.explanation(),
                working.suggestedTarget(), AiImportLog.COMMITTED);
        log.setImportedCount(result.imported());
        log.setResultJson(writeJson(Map.of("draftId", result.draftId(), "lessons", result.imported())));
        touch(log);
        return new CommitResultDto(
                result.imported(), List.of(), result.draftId(), result.warnings(), List.of(), List.of());
    }

    private void requireTimetableEdit(String username) {
        permissions.requireTimetableEdit(username);
    }

    /**
     * Stops a background parse. The finished batches stay as the session's preview; the
     * batches that were not parsed yet are dropped, while {@code total_batches} keeps the
     * original plan so the page can still say "已完成 5/14". A class that already failed
     * before the stop is kept as a failed batch, so the session comes back
     * {@code PARTIAL} rather than a preview that looks complete. When the stop lands
     * before the first batch finished there is nothing to preview, so the session is
     * {@code FAILED} - an empty preview that could still be committed would claim a
     * result that does not exist. Stopping an already finished session is the caller's
     * mistake, not a silent success.
     */
    public PreviewDto stop(long id, String username) {
        AiImportLog log = requireSession(id, username);
        if (!AiImportLog.PARSING.equals(log.getStatus())) {
            throw ApiException.conflict("当前导入不在解析中，无法停止");
        }
        stopFlags.computeIfAbsent(id, key -> new AtomicBoolean()).set(true);
        writeTransaction(() -> stopParse(id));
        return preview(logs.findById(id)
                .orElseThrow(() -> ApiException.notFound("导入会话不存在: " + id)));
    }

    /** The stop itself; a lost version race is retried by {@link #writeTransaction}. */
    private void stopParse(long id) {
        // The background thread may have finished the last batch in the meantime; re-read
        // so a natural finish is never overwritten with a "stopped" preview.
        AiImportLog current = logs.findById(id).orElse(null);
        if (current == null || !AiImportLog.PARSING.equals(current.getStatus())) {
            // Answering 200 here would claim a stop that did not happen.
            throw ApiException.conflict("解析已结束，无法停止");
        }
        List<BatchStore.State> plan = batchStore.read(current.getBatchesJson());
        int planned = plan.size();
        plan.removeIf(batch -> BatchStore.PENDING.equals(batch.status));
        int dropped = planned - plan.size();
        BatchStore.Merged merged = batchStore.merge(plan);
        boolean anyDone = plan.stream().anyMatch(BatchStore.State::done);
        int failures = batchStore.failedCount(plan);
        int total = current.getTotalBatches() > 0 ? current.getTotalBatches() : plan.size();
        current.setBatchesJson(batchStore.write(plan));
        current.setCompletedBatches(batchStore.doneCount(plan));
        current.setCurrentBatch(null);
        if (!anyDone) {
            // No finished batch means no preview: the rows would be empty while the page
            // still offered them for import.
            current.setPreviewJson(null);
            current.setAiExplanation(STOPPED_WITH_NOTHING);
            current.setRowCount(0);
            current.setStatus(AiImportLog.FAILED);
            current.setUpdatedAt(Instant.now());
            logs.save(current);
            return;
        }
        String explanation = withFailures(
                withFailures(merged.explanation(), stoppedNote(dropped)),
                batchStore.failureSummary(plan, total));
        current.setPreviewJson(batchStore.payloadJson(
                merged.mappings(), merged.rows(), explanation, merged.suggestedTarget()));
        current.setAiExplanation(explanation);
        current.setRowCount(merged.rows().size());
        current.setStatus(failures > 0 ? AiImportLog.PARTIAL : AiImportLog.PREVIEW);
        current.setUpdatedAt(Instant.now());
        logs.save(current);
    }

    /**
     * Retries the batches a partial parse could not read. Only the failed classes go back
     * to the AI - their source rows are replayed from {@code batches_json} - so a class
     * that already parsed is never asked again, and the merged preview cannot duplicate
     * it. The work runs in the background like the first pass: the page is answered with
     * the {@code PARSING} state of this round and polls from there. The batch plan
     * ({@code total_batches}) keeps the original figure, so the counters keep reading
     * "已完成 3/4 批" instead of restarting at zero.
     */
    public PreviewDto retry(long id, String username) {
        AiImportLog log = requireSession(id, username);
        if (!AiImportLog.PARTIAL.equals(log.getStatus())) {
            throw ApiException.conflict(AiImportLog.PARSING.equals(log.getStatus())
                    ? "解析还没完成，请等进度走完再重试失败批次"
                    : "当前导入没有可重试失败批次，只有“部分结果”的导入才能重试");
        }
        List<BatchStore.State> stored = batchStore.read(log.getBatchesJson());
        List<BatchStore.Work> failed = new ArrayList<>();
        for (BatchStore.State batch : stored) {
            if (batch.failed()) {
                failed.add(new BatchStore.Work(
                        batch.index, batch.label, List.copyOf(batch.sourceRows)));
            }
        }
        if (failed.isEmpty()) {
            throw ApiException.conflict("这次导入没有失败的批次，不需要重试");
        }
        ImportTarget target = targetOf(log);
        int total = log.getTotalBatches() > 0 ? log.getTotalBatches() : stored.size();
        BatchContext context = retryContext(log, total);
        writeTransaction(() -> {
            AiImportLog current = logs.findById(id).orElse(null);
            if (current == null || !AiImportLog.PARTIAL.equals(current.getStatus())) {
                throw ApiException.conflict("导入状态已变化，请刷新后重试");
            }
            current.setStatus(AiImportLog.PARSING);
            current.setCurrentBatch(null);
            current.setUpdatedAt(Instant.now());
            logs.save(current);
        });
        PreviewDto accepted = preview(logs.findById(id)
                .orElseThrow(() -> ApiException.notFound("导入会话不存在: " + id)));
        dispatchBatches(log.getId(), target, context, failed, AiImportLog.PARTIAL);
        return accepted;
    }

    /**
     * The header context a retry needs, taken from the stored upload when it is still
     * there. The rows to parse come from {@code batches_json}, so losing the file only
     * costs the header context; a partial import must stay retryable.
     */
    private BatchContext retryContext(AiImportLog log, int total) {
        try {
            if (log.getFilePath() != null) {
                GridData grid = readGrid(log.getFilePath());
                int headers = Math.min(headerCount(grid.rows()), grid.rows().size());
                return new BatchContext(
                        grid.sheetName(), List.copyOf(grid.rows().subList(0, headers)), total);
            }
        } catch (RuntimeException ex) {
            // Upload gone or unreadable: keep going with the rows already recorded.
        }
        return new BatchContext("", List.of(), total);
    }

    /**
     * Gives up on a session that is still parsing or only partly parsed. The stop flag
     * ends the background run (no further AI call goes out), and the record is kept for
     * the history: what was parsed stays on the row for audit, but the session never
     * moves forward again and nothing is written to the business tables.
     */
    public PreviewDto abandon(long id, String username) {
        AiImportLog log = requireSession(id, username);
        requireAbandonable(log);
        AtomicBoolean stop = stopFlags.get(id);
        if (stop != null) {
            // Only an in-memory flag exists for a run of this process; a finished run has
            // already removed it and needs no stop.
            stop.set(true);
        }
        writeTransaction(() -> {
            AiImportLog current = logs.findById(id).orElse(null);
            if (current == null) {
                return;
            }
            // The background thread may have finished while we were stopping it; a
            // session that already reached a final state cannot be abandoned.
            requireAbandonable(current);
            current.setStatus(AiImportLog.ABANDONED);
            current.setCurrentBatch(null);
            current.setAiExplanation(ABANDON_REASON);
            current.setUpdatedAt(Instant.now());
            logs.save(current);
        });
        return preview(logs.findById(id)
                .orElseThrow(() -> ApiException.notFound("导入会话不存在: " + id)));
    }

    private void requireAbandonable(AiImportLog log) {
        if (AiImportLog.PARSING.equals(log.getStatus()) || AiImportLog.PARTIAL.equals(log.getStatus())) {
            return;
        }
        throw ApiException.conflict("只有解析中或部分结果的导入可以放弃");
    }

    public byte[] file(long id, String username) {
        AiImportLog log = requireSession(id, username);
        return storage.read(log.getFilePath());
    }

    public String fileName(long id, String username) {
        return requireSession(id, username).getFileName();
    }

    // ------------------------------------------------------------------ AI I/O

    private PreviewDto runPreview(
            AiImportLog log, PromptBundle bundle, String originalPrompt, ImportTarget target) {
        // A one-shot parse answers for the whole table, so a batch plan from an earlier
        // pass is stale: leaving it would keep showing "已完成 3/4 批" over every row.
        resetBatchProgress(log);
        AiImportParser.Parsed parsed = callAi(bundle, log, target);
        savePreview(log, parsed.mappings(), parsed.rows(), parsed.explanation(),
                parsed.suggestedTarget(), AiImportLog.PREVIEW);
        log.setConfirmedJson(null);
        log.setResultJson(null);
        if (originalPrompt != null && log.getSourceText() == null && log.getFilePath() == null) {
            log.setSourceText(originalPrompt);
        }
        touch(log);
        return preview(log);
    }

    private AiImportParser.Parsed callAi(PromptBundle bundle, AiImportLog log, ImportTarget target) {
        AiCall call = callAiWithRaw(bundle, log, target);
        log.setAiRawResponse(call.rawResponse());
        return call.parsed();
    }

    /**
     * A full grade timetable is hundreds of lessons: one response cannot hold that many
     * output tokens, and the browser must not wait minutes for an answer. Split the grid
     * into one batch per data row (a row is usually one class), answer the upload at once
     * with a {@code PARSING} session, and let {@link #runBatches} fill it in batch by
     * batch, persisting each finished batch before the next call goes out.
     */
    private PreviewDto startBatchedParse(AiImportLog log, GridData grid, ImportTarget target) {
        List<List<String>> rows = grid.rows();
        int headerCount = headerCount(rows);
        List<List<String>> headers = rows.subList(0, headerCount);
        List<List<String>> data = new ArrayList<>();
        for (List<String> row : rows.subList(headerCount, rows.size())) {
            if (row.stream().anyMatch(cell -> cell != null && !cell.isBlank())) {
                data.add(row);
            }
        }
        if (data.isEmpty()) {
            throw ApiException.badRequest("表格里没有可解析的数据行");
        }
        List<BatchStore.Work> batches = new ArrayList<>();
        for (int start = 0; start < data.size(); start += TIMETABLE_CHUNK_ROWS) {
            List<List<String>> chunk =
                    data.subList(start, Math.min(start + TIMETABLE_CHUNK_ROWS, data.size()));
            batches.add(new BatchStore.Work(
                    batches.size() + 1, chunkLabel(chunk), List.copyOf(chunk)));
        }
        List<BatchStore.State> planned = new ArrayList<>();
        for (BatchStore.Work batch : batches) {
            planned.add(BatchStore.State.pending(batch));
        }
        // A fresh plan replaces whatever an earlier pass left on the row, including the
        // audit of that pass: this round accumulates its own answers.
        log.setStatus(AiImportLog.PARSING);
        log.setTotalBatches(batches.size());
        log.setCompletedBatches(0);
        log.setCurrentBatch(null);
        log.setRowCount(0);
        log.setPreviewJson(null);
        log.setConfirmedJson(null);
        log.setResultJson(null);
        log.setAiRawResponse(null);
        log.setBatchesJson(batchStore.write(planned));
        touch(log);
        // The response must describe the state at dispatch time, not wherever the
        // background thread has got to by the time it is serialised.
        PreviewDto accepted = preview(log);
        long sessionId = log.getId();
        BatchContext context = new BatchContext(grid.sheetName(), List.copyOf(headers), batches.size());
        dispatchBatches(sessionId, target, context, batches, AiImportLog.FAILED);
        return accepted;
    }

    /**
     * Starts a serial batch run on the import executor, with its own stop flag. A pool
     * that refuses the job (queue full, shutting down) would leave the session claiming
     * to parse with nobody working on it, so it is settled to {@code refusedStatus} at
     * once and the caller is told.
     */
    private void dispatchBatches(
            long sessionId,
            ImportTarget target,
            BatchContext context,
            List<BatchStore.Work> batches,
            String refusedStatus) {
        AtomicBoolean stopped = new AtomicBoolean();
        stopFlags.put(sessionId, stopped);
        try {
            importExecutor.execute(() -> runBatches(sessionId, target, context, batches, stopped));
        } catch (RejectedExecutionException ex) {
            stopFlags.remove(sessionId, stopped);
            writeTransaction(() -> {
                AiImportLog log = logs.findById(sessionId).orElse(null);
                if (log == null || !AiImportLog.PARSING.equals(log.getStatus())) {
                    return;
                }
                log.setStatus(refusedStatus);
                log.setCurrentBatch(null);
                log.setAiExplanation(QUEUE_REFUSED);
                log.setUpdatedAt(Instant.now());
                logs.save(log);
            });
            throw ApiException.conflict(QUEUE_REFUSED);
        }
    }

    /**
     * Serial background parse: one AI call per batch, each result written before the next.
     * A batch that fails is recorded and the run continues: the classes that did parse are
     * worth keeping, so the session ends {@code PARTIAL} instead of throwing them away.
     * A retry runs the same way, but its list holds only the batches that failed.
     */
    private void runBatches(
            long sessionId,
            ImportTarget target,
            BatchContext context,
            List<BatchStore.Work> plan,
            AtomicBoolean stopped) {
        try {
            for (int i = 0; i < plan.size(); i++) {
                BatchStore.Work work = plan.get(i);
                if (stopped.get() || !ownsRun(sessionId, stopped)) {
                    return;
                }
                beginBatch(sessionId, work, stopped);
                if (stopped.get() || !ownsRun(sessionId, stopped)) {
                    return;
                }
                BatchStore.Outcome outcome;
                String rawResponse = null;
                try {
                    String prompt = AiImportPrompts.chunkPrompt(
                            target, context.sheetName(), context.headers(), work.sourceRows(),
                            work.index() - 1, context.totalBatches(), objectMapper);
                    AiCall call = callAiForBatch(prompt, target);
                    rawResponse = call.rawResponse();
                    outcome = BatchStore.Outcome.parsed(call.parsed());
                } catch (RuntimeException ex) {
                    outcome = BatchStore.Outcome.failed(
                            ex.getMessage() == null ? "未知错误" : ex.getMessage());
                }
                persistBatch(sessionId, work, context.totalBatches(), outcome, rawResponse,
                        plan.size() - 1 - i, stopped);
            }
        } finally {
            stopFlags.remove(sessionId, stopped);
        }
    }

    /**
     * Whether this thread still owns the session's run. A retry replaces the stop flag, so
     * a batch that was in flight when the previous run was stopped or superseded cannot
     * write its late result into the new round.
     */
    private boolean ownsRun(long sessionId, AtomicBoolean stopped) {
        return stopFlags.get(sessionId) == stopped;
    }

    /** One batch's AI call. A failure belongs to this batch, not to the whole session yet. */
    private AiCall callAiForBatch(String prompt, ImportTarget target) {
        AiRequest request = new AiRequest(
                AiTaskType.DATA_IMPORT,
                List.of(new AiMessage("system", AiImportPrompts.system(target)),
                        new AiMessage("user", prompt)),
                List.of(),
                new AiOptions(null, DATA_IMPORT_MAX_TOKENS, true));
        AiResult result = gateway.chat(request);
        if (!result.success()) {
            throw ApiException.badRequest("AI 解析失败：" + (result.errorMessage() == null
                    ? "请检查 AI 供应商配置" : result.errorMessage()));
        }
        return new AiCall(parser.parse(result.content(), target, threshold()), result.rawResponse());
    }

    /** Marks which class is in flight, so a poll can say "正在解析 八（2）". */
    private void beginBatch(long sessionId, BatchStore.Work work, AtomicBoolean stopped) {
        writeQuietly(() -> logs.findById(sessionId).ifPresent(log -> {
            if (!AiImportLog.PARSING.equals(log.getStatus()) || !ownsRun(sessionId, stopped)) {
                return;
            }
            log.setCurrentBatch(work.label());
            log.setUpdatedAt(Instant.now());
            logs.save(log);
        }));
    }

    /**
     * Writes one finished batch and everything derived from it in a single short
     * transaction: the batch entry, the counters, and the preview merged from the
     * batches that are already {@code DONE}. {@code remaining} is how many batches of
     * this run are still to come, so the status only leaves {@code PARSING} once the
     * last one of the run has been attempted (one batch for a retry, all of them for
     * a first parse).
     */
    private void persistBatch(
            long sessionId,
            BatchStore.Work work,
            int totalBatches,
            BatchStore.Outcome outcome,
            String rawResponse,
            int remaining,
            AtomicBoolean stopped) {
        // A lost version race is re-read and reconsidered: by then the session may have
        // left PARSING, which the retry handles like any other late batch.
        writeQuietly(() -> {
            AiImportLog log = logs.findById(sessionId).orElse(null);
            if (log == null || !ownsRun(sessionId, stopped)) {
                return;
            }
            if (!AiImportLog.PARSING.equals(log.getStatus())) {
                // Stopped (or abandoned, or recovered) while this batch was in flight: the
                // session already has its final preview, so the result must not be folded
                // in - but the AI call really happened and its answer stays in the audit.
                recordLateAnswer(log, rawResponse);
                return;
            }
            List<BatchStore.State> plan = batchStore.read(log.getBatchesJson());
            batchStore.replace(plan, work, outcome, rawResponse);
            BatchStore.Merged merged = batchStore.merge(plan);
            int failures = batchStore.failedCount(plan);
            String explanation =
                    withFailures(merged.explanation(), batchStore.failureSummary(plan, totalBatches));
            log.setBatchesJson(batchStore.write(plan));
            log.setCompletedBatches(batchStore.doneCount(plan));
            log.setCurrentBatch(null);
            log.setPreviewJson(batchStore.payloadJson(
                    merged.mappings(), merged.rows(), explanation, merged.suggestedTarget()));
            log.setAiExplanation(explanation);
            log.setRowCount(merged.rows().size());
            List<String> rawAnswers = batchStore.rawAnswers(plan);
            if (!rawAnswers.isEmpty()) {
                log.setAiRawResponse(writeJson(rawAnswers));
            }
            boolean finished = remaining <= 0;
            log.setStatus(!finished
                    ? AiImportLog.PARSING
                    : failures > 0 ? AiImportLog.PARTIAL : AiImportLog.PREVIEW);
            log.setUpdatedAt(Instant.now());
            logs.save(log);
        });
    }

    /**
     * A batch that lands after the session settled still cost a real AI call, so its answer
     * goes into the audit column. Everything else stays exactly as the stop/abandon left
     * it: the batch list, the preview, the counters and the status.
     */
    private void recordLateAnswer(AiImportLog log, String rawResponse) {
        if (rawResponse == null || rawResponse.isBlank()) {
            return;
        }
        log.setAiRawResponse(appendRawAnswer(log.getAiRawResponse(), rawResponse));
        log.setUpdatedAt(Instant.now());
        logs.save(log);
    }

    /** Adds one answer to the {@code ai_raw_response} array without touching the others. */
    private String appendRawAnswer(String stored, String rawResponse) {
        List<String> answers = new ArrayList<>();
        if (stored != null && !stored.isBlank()) {
            try {
                answers.addAll(objectMapper.readValue(
                        stored, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {}));
            } catch (Exception ex) {
                answers.add(stored);
            }
        }
        if (!answers.contains(rawResponse)) {
            answers.add(rawResponse);
        }
        return writeJson(answers);
    }

    /**
     * A stopped parse says how many planned batches it gave up on: the counters still show
     * the plan, so the rows on screen cannot tell on their own where the rest went.
     */
    private String stoppedNote(int dropped) {
        return dropped > 0 ? "已停止解析，剩余 " + dropped + " 批未解析" : "已停止解析";
    }

    /** A partial preview's explanation says what it does hold and what is missing. */
    private String withFailures(String explanation, String failureSummary) {
        String base = explanation == null ? "" : explanation.trim();
        if (failureSummary == null || failureSummary.isBlank()) {
            return base;
        }
        return base.isBlank() ? failureSummary : base + " " + failureSummary;
    }

    /**
     * A restart leaves rows in PARSING that nothing is working on any more. The batches
     * that never ran are recorded as failures, so a session with finished batches comes
     * back {@code PARTIAL} (retryable) rather than a preview that could be published with
     * classes missing.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedSessions() {
        for (AiImportLog candidate : logs.findByStatus(AiImportLog.PARSING)) {
            transactionTemplate.executeWithoutResult(status -> {
                AiImportLog log = logs.findById(candidate.getId()).orElse(null);
                if (log == null || !AiImportLog.PARSING.equals(log.getStatus())) {
                    return;
                }
                List<BatchStore.State> plan = batchStore.read(log.getBatchesJson());
                for (BatchStore.State batch : plan) {
                    if (BatchStore.PENDING.equals(batch.status)) {
                        batch.status = BatchStore.FAILED;
                        batch.reason = "服务重启中断了这一批，请重试";
                    }
                }
                BatchStore.Merged merged = batchStore.merge(plan);
                int completed = batchStore.doneCount(plan);
                int failures = batchStore.failedCount(plan);
                int total = log.getTotalBatches() > 0 ? log.getTotalBatches() : plan.size();
                log.setBatchesJson(batchStore.write(plan));
                log.setCompletedBatches(completed);
                log.setCurrentBatch(null);
                if (completed > 0) {
                    String explanation =
                            withFailures(merged.explanation(), batchStore.failureSummary(plan, total));
                    log.setStatus(failures > 0 ? AiImportLog.PARTIAL : AiImportLog.PREVIEW);
                    log.setPreviewJson(batchStore.payloadJson(
                            merged.mappings(), merged.rows(), explanation, merged.suggestedTarget()));
                    log.setAiExplanation(explanation);
                    log.setRowCount(merged.rows().size());
                } else {
                    log.setStatus(AiImportLog.FAILED);
                    log.setAiExplanation("服务重启中断了这次解析，请重新上传文件");
                }
                log.setUpdatedAt(Instant.now());
                logs.save(log);
            });
        }
    }

    /**
     * What every prompt of one batch run needs besides the batch's own rows: the sheet's
     * header context, and how many batches the file was planned as. A retry reuses the
     * original plan so its prompt still says "第 2/4 批".
     */
    private record BatchContext(String sheetName, List<List<String>> headers, int totalBatches) {}


    private int dataRowCount(List<List<String>> rows) {
        int header = headerCount(rows);
        int count = 0;
        for (int i = header; i < rows.size(); i++) {
            if (rows.get(i).stream().anyMatch(cell -> cell != null && !cell.isBlank())) {
                count++;
            }
        }
        return count;
    }

    /**
     * A timetable usually has a title, a day header and a period-number strip. The
     * strip is the reliable anchor: it starts with an empty cell and every other
     * cell is a numeral. Without it, fall back to two header rows.
     */
    private int headerCount(List<List<String>> rows) {
        int periodRow = findPeriodRow(rows);
        if (periodRow >= 0) {
            return periodRow + 1;
        }
        return Math.min(TIMETABLE_HEADER_ROWS, Math.max(1, rows.size() - 1));
    }

    private int findPeriodRow(List<List<String>> rows) {
        for (int i = 0; i < Math.min(rows.size(), 6); i++) {
            if (isPeriodRow(rows.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private boolean isPeriodRow(List<String> row) {
        if (row == null || row.size() < 2) {
            return false;
        }
        // The first cell is often a merged "班级" label, so it is not a reliable
        // signal; the period labels themselves are.
        int labels = 0;
        for (int i = 1; i < row.size(); i++) {
            String cell = row.get(i);
            if (cell == null || cell.isBlank()) {
                continue;
            }
            if (!cell.trim().matches("[一二三四五六七八九十0-9]+")) {
                return false;
            }
            labels++;
        }
        return labels >= 2;
    }

    private String chunkLabel(List<List<String>> chunk) {
        if (chunk.isEmpty()) {
            return "";
        }
        String first = chunk.get(0).stream()
                .filter(cell -> cell != null && !cell.isBlank())
                .findFirst()
                .orElse("");
        return first.length() <= 30 ? first : first.substring(0, 30);
    }

    private AiCall callAiWithRaw(PromptBundle bundle, AiImportLog log, ImportTarget target) {
        AiRequest request = new AiRequest(
                AiTaskType.DATA_IMPORT, bundle.messages(), bundle.images(),
                new AiOptions(null, DATA_IMPORT_MAX_TOKENS, true));
        AiResult result = bundle.images().isEmpty() ? gateway.chat(request) : gateway.chatWithVision(request);
        if (!result.success()) {
            log.setAiRawResponse(result.rawResponse());
            log.setAiExplanation("AI 调用失败：" + (result.errorMessage() == null ? "未知错误" : result.errorMessage()));
            log.setStatus(AiImportLog.FAILED);
            touch(log);
            throw ApiException.badRequest("AI 解析失败：" + (result.errorMessage() == null ? "请检查 AI 供应商配置" : result.errorMessage()));
        }
        try {
            return new AiCall(parser.parse(result.content(), target, threshold()), result.rawResponse());
        } catch (IllegalArgumentException ex) {
            log.setAiRawResponse(result.rawResponse());
            log.setAiExplanation(ex.getMessage());
            log.setStatus(AiImportLog.FAILED);
            touch(log);
            throw ApiException.badRequest(ex.getMessage());
        }
    }

    private record AiCall(AiImportParser.Parsed parsed, String rawResponse) {}

    private PromptBundle bundleFor(AiImportLog log, ImportTarget target) {
        String type = log.getImportType();
        if ("image".equals(type)) {
            return imageBundle(new UploadStorage.StoredFile(
                    log.getFilePath(), log.getFileName(), log.getFileSize(), null), target);
        }
        if ("text".equals(type)) {
            String prompt = AiImportPrompts.textPrompt(target, log.getSourceText());
            return bundle(prompt, List.of(), target);
        }
        GridData grid = readGrid(log.getFilePath());
        String prompt = AiImportPrompts.gridPrompt(target, grid.sheetName(), grid.rows(), objectMapper);
        return bundle(prompt, List.of(), target);
    }

    private PromptBundle bundle(String userPrompt, List<AiImage> images, ImportTarget target) {
        return new PromptBundle(
                List.of(new AiMessage("system", AiImportPrompts.system(target)),
                        new AiMessage("user", userPrompt)),
                images,
                userPrompt);
    }

    private PromptBundle imageBundle(UploadStorage.StoredFile stored, ImportTarget target) {
        byte[] bytes = storage.read(stored.path());
        String mime = stored.contentType() == null ? mimeFor(stored.path()) : stored.contentType();
        String prompt = AiImportPrompts.imagePrompt(target);
        return new PromptBundle(
                List.of(new AiMessage("system", AiImportPrompts.system(target)),
                        new AiMessage("user", prompt)),
                List.of(new AiImage(mime, bytes)),
                prompt);
    }

    private record PromptBundle(List<AiMessage> messages, List<AiImage> images, String originalPrompt) {}

    // ------------------------------------------------------------- persistence

    private AiImportLog newLog(
            String importType, UploadStorage.StoredFile stored, String username, ImportTarget target) {
        AiImportLog log = new AiImportLog();
        log.setImportType(importType);
        log.setTargetType(target.code());
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

    /** The original AI answer; never overwritten by user edits or a continuation merge. */
    private void savePreview(
            AiImportLog log,
            List<MappingDto> mappings,
            List<RowDto> rows,
            String explanation,
            String suggestedTarget,
            String status) {
        log.setPreviewJson(batchStore.payloadJson(mappings, rows, explanation, suggestedTarget));
        log.setAiExplanation(explanation);
        log.setStatus(status);
        log.setRowCount(rows.size());
        touch(log);
    }

    /** The user's working copy: edits, the merged continuation result, or the committed rows. */
    private void saveConfirmed(
            AiImportLog log,
            List<MappingDto> mappings,
            List<RowDto> rows,
            String explanation,
            String suggestedTarget,
            String status) {
        log.setConfirmedJson(batchStore.payloadJson(mappings, rows, explanation, suggestedTarget));
        log.setStatus(status);
        log.setRowCount(rows.size());
        touch(log);
    }

    private Payload readWorkingPayload(AiImportLog log, ImportTarget target) {
        String json = log.getConfirmedJson() == null || log.getConfirmedJson().isBlank()
                ? log.getPreviewJson()
                : log.getConfirmedJson();
        return parsePayload(json, target);
    }

    private Payload readPreviewPayload(AiImportLog log, ImportTarget target) {
        return parsePayload(log.getPreviewJson(), target);
    }

    private Payload parsePayload(String json, ImportTarget target) {
        if (json == null || json.isBlank()) {
            return new Payload(List.of(), List.of(), "", "");
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            List<MappingDto> mappings = new ArrayList<>();
            for (JsonNode item : root.path("mappings")) {
                mappings.add(new MappingDto(
                        item.path("source").asText(""),
                        item.path("target").asText(""),
                        item.path("note").asText("")));
            }
            // Reuse the AI parser so confidence/low-confidence stay consistent with the threshold.
            String normalized = objectMapper.writeValueAsString(Map.of(
                    "suggestedTarget", root.path("suggestedTarget").asText(""),
                    "mappings", mappings,
                    "rows", root.path("rows"),
                    "explanation", root.path("explanation").asText("")));
            AiImportParser.Parsed parsed = parser.parse(normalized, target, threshold());
            return new Payload(
                    parsed.mappings(), parsed.rows(), parsed.explanation(), parsed.suggestedTarget());
        } catch (Exception ex) {
            return new Payload(List.of(), List.of(), "", "");
        }
    }

    private PreviewDto preview(AiImportLog log) {
        ImportTarget target = targetOf(log);
        Payload payload = readWorkingPayload(log, target);
        List<BatchStore.State> plan = batchStore.read(log.getBatchesJson());
        boolean parsing = AiImportLog.PARSING.equals(log.getStatus());
        // While batches are still arriving the preview is a moving target: skip the
        // (query-heavy) matching so polling every two seconds stays cheap. An abandoned
        // session is a closed record, so its rows are not analysed either.
        boolean dormant = parsing || AiImportLog.ABANDONED.equals(log.getStatus());
        TimetableImportService.Analysis analysis =
                dormant ? null : analysis(target, payload.rows());
        return new PreviewDto(
                log.getId(),
                log.getTargetType(),
                payload.suggestedTarget().isEmpty() ? null : payload.suggestedTarget(),
                columns(target),
                payload.rows(),
                payload.mappings(),
                explanation(log, payload),
                threshold(),
                log.getStatus(),
                issues(analysis, payload.rows()),
                summary(analysis),
                progress(log, plan),
                failedBatches(log, plan),
                null,
                List.of(),
                List.of(),
                List.of());
    }

    /** Null for sessions that were never batched (small tables, text, images). */
    private ImportProgressDto progress(AiImportLog log, List<BatchStore.State> plan) {
        if (log.getTotalBatches() <= 0) {
            return null;
        }
        return new ImportProgressDto(
                log.getTotalBatches(), log.getCompletedBatches(), batchStore.failedCount(plan),
                log.getCurrentBatch());
    }

    private List<FailedBatchDto> failedBatches(AiImportLog log, List<BatchStore.State> plan) {
        int total = log.getTotalBatches() > 0 ? log.getTotalBatches() : plan.size();
        return plan.stream()
                .filter(BatchStore.State::failed)
                .map(batch -> new FailedBatchDto(
                        batch.index, batch.label, batchStore.batchFailureText(batch, total)))
                .toList();
    }

    /**
     * A failed parse never reaches a stored payload, so the reason lives only on the log.
     * Fall back to it when the payload has nothing to show.
     */
    private String explanation(AiImportLog log, Payload payload) {
        String stored = payload.explanation() == null ? "" : payload.explanation();
        boolean closed = AiImportLog.FAILED.equals(log.getStatus())
                || AiImportLog.ABANDONED.equals(log.getStatus());
        if (!stored.isBlank() || !closed) {
            return stored;
        }
        return log.getAiExplanation() == null ? stored : log.getAiExplanation();
    }

    private TimetableImportService.Analysis analysis(ImportTarget target, List<RowDto> rows) {
        if (target != ImportTarget.TIMETABLE || rows.isEmpty()) {
            return null;
        }
        return timetableImports.inspect(rows, null);
    }

    private TimetableSummaryDto summary(TimetableImportService.Analysis analysis) {
        if (analysis == null) {
            return null;
        }
        TimetableImportService.Summary summary = analysis.summary();
        return new TimetableSummaryDto(
                summary.lessons(), summary.unresolved(), summary.groups(),
                summary.newAssignments(), summary.updatedAssignments());
    }

    /**
     * Unmatched names in a timetable preview. The same matching the commit runs, but
     * read-only, so the user can resolve every issue before anything is written.
     * Page-level blockers (row 0) carry their reason in {@code message}.
     */
    private List<IssueDto> issues(TimetableImportService.Analysis analysis, List<RowDto> rows) {
        if (analysis == null) {
            return List.of();
        }
        List<String> requiredFields = ImportTarget.TIMETABLE.fields().stream()
                .filter(field -> field.required())
                .map(field -> field.field())
                .toList();
        List<IssueDto> issues = new ArrayList<>();
        Map<Integer, TimetableImportService.GradeHint> hints = new LinkedHashMap<>();
        for (TimetableImportService.GradeHint hint : analysis.gradeHints()) {
            hints.put(hint.row(), hint);
        }
        for (RowErrorDto error : analysis.errors()) {
            String value = error.row() >= 1 && error.row() <= rows.size()
                    ? value(rows.get(error.row() - 1), error.field())
                    : "";
            TimetableImportService.GradeHint hint =
                    "className".equals(error.field()) ? hints.get(error.row()) : null;
            issues.add(new IssueDto(
                    error.row(), error.field(), value, error.candidates(),
                    requiredFields.contains(error.field()),
                    error.row() == 0 ? error.message() : "",
                    hint == null ? null : hint.inferredGrade(),
                    hint == null ? List.of() : hint.gradeCandidates()));
        }
        return List.copyOf(issues);
    }

    private record Payload(
            List<MappingDto> mappings, List<RowDto> rows, String explanation, String suggestedTarget) {}

    private List<ColumnDto> columns(ImportTarget target) {
        return target.fields().stream()
                .map(field -> new ColumnDto(field.field(), field.label(), field.required()))
                .toList();
    }

    private ImportTarget targetOf(AiImportLog log) {
        try {
            return ImportTarget.fromCode(log.getTargetType());
        } catch (IllegalArgumentException ex) {
            // A stored target is data, not user input: never let it surface as a 500.
            throw ApiException.badRequest("导入目标类型无效: " + log.getTargetType());
        }
    }

    /**
     * Records a parse that never produced a preview. The row is re-read first: the failure
     * may already have been settled by another writer (the batch dispatcher fails the
     * session itself when the queue refuses the job), and a stale copy must not be written
     * back over it.
     */
    private void markFailed(AiImportLog log, String message) {
        writeQuietly(() -> logs.findById(log.getId()).ifPresent(current -> {
            current.setStatus(AiImportLog.FAILED);
            current.setAiExplanation(message);
            current.setUpdatedAt(Instant.now());
            logs.save(current);
        }));
    }

    private void touch(AiImportLog log) {
        log.setUpdatedAt(Instant.now());
        try {
            // A session is saved more than once per request (preview, then the user copy),
            // and each save merges a detached copy: the version has to travel with it.
            log.setVersion(logs.save(log).getVersion());
        } catch (OptimisticLockingFailureException ex) {
            throw ApiException.conflict(LOST_VERSION);
        }
    }

    /**
     * A whole-table parse leaves no batch plan behind: whatever an earlier pass wrote is
     * stale the moment every row comes back in one answer.
     */
    private void resetBatchProgress(AiImportLog log) {
        log.setTotalBatches(0);
        log.setCompletedBatches(0);
        log.setCurrentBatch(null);
        log.setBatchesJson(null);
        log.setAiRawResponse(null);
    }

    /**
     * Runs one short write transaction. A stop or abandon that lost the row to the
     * background thread reads it again and retries once; a second loss is reported as a
     * conflict instead of pretending the write happened.
     */
    private void writeTransaction(Runnable body) {
        try {
            transactionTemplate.executeWithoutResult(status -> body.run());
        } catch (OptimisticLockingFailureException ex) {
            try {
                transactionTemplate.executeWithoutResult(status -> body.run());
            } catch (OptimisticLockingFailureException again) {
                throw ApiException.conflict(LOST_VERSION);
            }
        }
    }

    /**
     * Runs one short write transaction for the background parser. Losing the race is not
     * an error there: the retry re-reads the row and the batch's own guard decides whether
     * the result still belongs to it.
     */
    private void writeQuietly(Runnable body) {
        try {
            transactionTemplate.executeWithoutResult(status -> body.run());
        } catch (OptimisticLockingFailureException ex) {
            try {
                transactionTemplate.executeWithoutResult(status -> body.run());
            } catch (OptimisticLockingFailureException again) {
                // Give up on this one write: the next batch or the startup recovery settles
                // the row, and a parse must never fail because two writers raced.
            }
        }
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

    /** A workbook preview has no name-mapping step: every entity is created on commit. */
    private void requireNotWorkbook(AiImportLog log) {
        if (isWorkbook(log)) {
            throw ApiException.badRequest("多表导入在预览中不再需要手工映射，确认后自动创建");
        }
    }

    private void requireEditable(AiImportLog log) {
        if (AiImportLog.ABANDONED.equals(log.getStatus())) {
            throw ApiException.conflict("该导入已放弃，不能再修改或入库");
        }
        if (AiImportLog.PARSING.equals(log.getStatus())) {
            throw ApiException.conflict("解析还没完成，请等进度走完再操作");
        }
        if (AiImportLog.PARTIAL.equals(log.getStatus())) {
            throw ApiException.conflict("这次导入只解析了一部分班级，请先重试失败批次或放弃导入");
        }
        if (AiImportLog.COMMITTED.equals(log.getStatus())) {
            throw ApiException.conflict("该导入已入库，不能再修改");
        }
        if (AiImportLog.FAILED.equals(log.getStatus())) {
            throw ApiException.conflict("该导入解析失败，请打回重析");
        }
    }

    private void requireConfigured() {
        permissions.requireConfigured();
    }

    // ------------------------------------------------------------ source data

    private GridData readGrid(String path) {
        String name = path == null ? "" : path.toLowerCase();
        byte[] bytes = storage.read(path);
        try {
            if (name.endsWith(".csv")) {
                // No header assumption: a title row before the header must reach the AI.
                return new GridData(fileName(path), CsvTableReader.readGrid(bytes));
            }
            ExcelTableReader.Grid grid = ExcelTableReader.readGrid(new ByteArrayInputStream(bytes));
            return new GridData(grid.sheetName(), grid.rows());
        } catch (Exception ex) {
            throw ApiException.badRequest("无法读取表格文件: " + ex.getMessage());
        }
    }

    private String fileName(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash < 0 ? path : path.substring(slash + 1);
    }

    /** One sheet as laid out: a name to show the AI, and the raw rows. */
    private record GridData(String sheetName, List<List<String>> rows) {}

    // -------------------------------------------------------------- validation

    private void validate(List<RowDto> rows) {
        List<ImportValidationException.RowError> errors = new ArrayList<>();
        Set<String> employeeNos = new HashSet<>();
        Set<String> nameSubjects = new HashSet<>();
        Set<String> existingEmployeeNos = new HashSet<>();
        Set<String> existingNameSubjects = new HashSet<>();
        for (Teacher teacher : teachers.findAll()) {
            if (teacher.getEmployeeNo() != null) {
                existingEmployeeNos.add(teacher.getEmployeeNo());
            }
            existingNameSubjects.add(teacher.getName() + "|" + (teacher.getSubject() == null ? "" : teacher.getSubject()));
        }
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
                } else if (existingEmployeeNos.contains(employeeNo)) {
                    errors.add(new ImportValidationException.RowError(line, "employeeNo", "工号已存在"));
                }
            } else if (!name.isBlank()) {
                String key = name + "|" + subject;
                if (!nameSubjects.add(key)) {
                    errors.add(new ImportValidationException.RowError(line, "name", "姓名与学科在本次导入中重复"));
                } else if (existingNameSubjects.contains(key)) {
                    errors.add(new ImportValidationException.RowError(line, "name", "同名同科教师已存在"));
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

    private List<RowDto> normalize(List<RowDto> rows, double threshold, ImportTarget target) {
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
            for (String field : target.fieldNames()) {
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

    private List<RowDto> merge(List<RowDto> userRows, List<RowDto> aiRows, ImportTarget target) {
        int count = Math.max(userRows.size(), aiRows.size());
        List<RowDto> merged = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            RowDto user = i < userRows.size() ? userRows.get(i) : null;
            RowDto ai = i < aiRows.size() ? aiRows.get(i) : null;
            Map<String, String> values = new LinkedHashMap<>();
            Map<String, Double> confidence = new LinkedHashMap<>();
            List<String> low = new ArrayList<>();
            for (String field : target.fieldNames()) {
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
