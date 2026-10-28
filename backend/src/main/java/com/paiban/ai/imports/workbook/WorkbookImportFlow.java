package com.paiban.ai.imports.workbook;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paiban.ai.imports.AiImportLog;
import com.paiban.ai.imports.AiImportLogRepository;
import com.paiban.ai.imports.CsvTableReader;
import com.paiban.ai.imports.ImportDtos;
import com.paiban.ai.imports.ImportDtos.CategoryResultDto;
import com.paiban.ai.imports.ImportDtos.ColumnDto;
import com.paiban.ai.imports.ImportDtos.CommitResultDto;
import com.paiban.ai.imports.ImportDtos.FailedBatchDto;
import com.paiban.ai.imports.ImportDtos.ImportProgressDto;
import com.paiban.ai.imports.ImportDtos.IssueDto;
import com.paiban.ai.imports.ImportDtos.MappingDto;
import com.paiban.ai.imports.ImportDtos.PreviewDto;
import com.paiban.ai.imports.ImportDtos.RowDto;
import com.paiban.ai.imports.ImportDtos.RowErrorDto;
import com.paiban.ai.imports.ImportDtos.SheetSummaryDto;
import com.paiban.ai.imports.ImportDtos.TeacherWarningDto;
import com.paiban.ai.imports.ImportDtos.TimetableSummaryDto;
import com.paiban.ai.imports.ImportDtos.ToolTraceDto;
import com.paiban.ai.imports.ImportDtos.WorkbookSummaryDto;
import com.paiban.ai.imports.ImportMappingService;
import com.paiban.ai.imports.ImportMappingService.CourseDraft;
import com.paiban.ai.imports.ImportMappingService.EntityOutcome;
import com.paiban.ai.imports.ImportMappingService.TeacherDraft;
import com.paiban.ai.imports.ImportPermissions;
import com.paiban.ai.imports.ImportTarget;
import com.paiban.ai.imports.SlotText;
import com.paiban.ai.imports.SuspiciousTeacherNames;
import com.paiban.ai.imports.TimetableImportService;
import com.paiban.ai.imports.UploadStorage;
import com.paiban.ai.imports.workbook.WorkbookBatchStore.State;
import com.paiban.ai.imports.workbook.WorkbookExtractor.BatchPlan;
import com.paiban.ai.imports.workbook.WorkbookExtractor.BatchResult;
import com.paiban.ai.imports.workbook.WorkbookPayload.CategorySummary;
import com.paiban.ai.imports.workbook.WorkbookPayload.SheetSummary;
import com.paiban.ai.imports.workbook.WorkbookPlan.SheetCategory;
import com.paiban.ai.imports.workbook.WorkbookPlan.SheetPlan;
import com.paiban.ai.imports.workbook.WorkbookRunPlan.SheetMeta;
import com.paiban.common.excel.ExcelTableReader;
import com.paiban.common.excel.ExcelTableReader.Grid;
import com.paiban.common.settings.SettingsService;
import com.paiban.common.web.ApiException;
import com.paiban.foundation.domain.PeriodRepository;
import com.paiban.foundation.service.SemesterService;
import com.paiban.foundation.web.SemesterDtos.SemesterDto;
import com.paiban.identity.domain.AppUser;
import com.paiban.identity.service.UserService;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

/**
 * The phase-11 workbook import: one upload, a model that browses the file with read-only
 * tools, a deterministic merge by timetable slot, and one confirmed commit that writes
 * teachers, courses, classes and a DRAFT timetable in dependency order.
 *
 * <p>Planning and extraction run on the dedicated import executor. The upload answers as
 * soon as the file has been profiled; the confirmed plan and every finished batch are
 * persisted, so a refresh resumes the progress, a retry replays only the failed ranges,
 * and a stop keeps what was already read.
 *
 * <p>What stays deterministic: skipping instruction sheets, finding headers, splitting
 * packed cells, merging slots and every database write. What the model decides: which sheet
 * is which kind of data, and which column feeds which field.
 */
@Component
public class WorkbookImportFlow {

    static final String TARGET = ImportTarget.WORKBOOK.code();
    private static final Logger LOG = LoggerFactory.getLogger(WorkbookImportFlow.class);
    private static final String TEACHER = "teacher";
    private static final String COURSE = "course";
    private static final String CLASS = "class";
    private static final String TIMETABLE = "timetable";
    private static final String SEMESTER_MISSING = "请先设置当前学期，再确认入库";
    private static final String PERIODS_MISSING =
            "当前学期还没有课节，请先生成默认作息（上午 4 + 下午 4，周一至周五）";
    private static final String UNCONFIRMED = "结构未确认，请人工核对";
    private static final String QUEUE_REFUSED = "系统繁忙，解析任务排队失败，请稍后重试";
    private static final String LOST_VERSION = "导入状态已被后台更新，请刷新后重试";
    private static final String STOPPED_WITH_NOTHING = "已停止解析，没有任何已完成的批次";
    private static final String ABANDON_REASON = "用户放弃导入";
    private static final String PLANNING = "正在识别工作簿结构";
    private static final String EXTRACTING = "结构已确认，正在分批读取数据";

    private final ImportPermissions permissions;
    private final UploadStorage storage;
    private final AiImportLogRepository logs;
    private final ObjectMapper objectMapper;
    private final WorkbookPlanner planner;
    private final WorkbookExtractor extractor;
    private final ImportMappingService mappings;
    private final TimetableImportService timetableImports;
    private final SemesterService semesters;
    private final PeriodRepository periods;
    private final UserService users;
    private final SettingsService settings;
    private final TaskExecutor importExecutor;
    private final TransactionTemplate transactionTemplate;
    /** Reads and writes the workbook batch plan and every finished range. */
    private final WorkbookBatchStore batchStore;

    public WorkbookImportFlow(
            ImportPermissions permissions,
            UploadStorage storage,
            AiImportLogRepository logs,
            ObjectMapper objectMapper,
            WorkbookPlanner planner,
            WorkbookExtractor extractor,
            ImportMappingService mappings,
            TimetableImportService timetableImports,
            SemesterService semesters,
            PeriodRepository periods,
            UserService users,
            SettingsService settings,
            @Qualifier("importExecutor") TaskExecutor importExecutor,
            PlatformTransactionManager transactionManager) {
        this.permissions = permissions;
        this.storage = storage;
        this.logs = logs;
        this.objectMapper = objectMapper;
        this.planner = planner;
        this.extractor = extractor;
        this.mappings = mappings;
        this.timetableImports = timetableImports;
        this.semesters = semesters;
        this.periods = periods;
        this.users = users;
        this.settings = settings;
        this.importExecutor = importExecutor;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.batchStore = new WorkbookBatchStore(objectMapper);
    }

    // ------------------------------------------------------------------- entry

    public PreviewDto start(MultipartFile file, String username) {
        permissions.requireConfigured();
        UploadStorage.StoredFile stored = storage.storeSpreadsheet(file);
        AiImportLog log = newLog(stored, username);
        List<ProfiledSheet> profiles;
        try {
            // Reading and profiling are deterministic and fast; keeping them synchronous
            // means a broken file still gets the specific 400 the upload page shows.
            profiles = profiles(log);
        } catch (RuntimeException ex) {
            markFailed(log, message(ex));
            throw ex;
        }
        return beginRun(log, profiles, 1);
    }

    /** Re-reads the stored file with the same pipeline; the previous run is replaced. */
    public PreviewDto reparse(AiImportLog log) {
        if (AiImportLog.COMMITTED.equals(log.getStatus())) {
            throw ApiException.conflict("已入库的导入不能再重新解析");
        }
        if (log.getFilePath() == null) {
            throw ApiException.badRequest("这次导入没有原始文件，无法重新解析");
        }
        List<ProfiledSheet> profiles = profiles(log);
        // A re-plan is a new structure generation: every batch of the old plan is invalid.
        return beginRun(log, profiles, log.getPlanVersion() + 1);
    }

    /**
     * Saves the run identity and answers with the {@code PARSING} session before a single
     * model call goes out. A pool that refuses the job settles the row instead of leaving
     * it claiming to parse with nobody working on it.
     */
    private PreviewDto beginRun(AiImportLog log, List<ProfiledSheet> profiles, int planVersion) {
        String runId = UUID.randomUUID().toString();
        log.setStatus(AiImportLog.PARSING);
        log.setPlanJson(null);
        log.setPlanVersion(planVersion);
        log.setRunId(runId);
        log.setTotalBatches(0);
        log.setCompletedBatches(0);
        log.setCurrentBatch(null);
        log.setBatchesJson(null);
        log.setPreviewJson(null);
        log.setConfirmedJson(null);
        log.setResultJson(null);
        log.setAiRawResponse(null);
        log.setToolTraceJson(null);
        log.setAiExplanation(PLANNING);
        log.setRowCount(0);
        log.setImportedCount(0);
        log.setUpdatedAt(Instant.now());
        save(log);
        // The response describes the state at dispatch time, not wherever the worker has
        // got to by the time the entity is serialised.
        PreviewDto accepted = preview(log);
        long sessionId = log.getId();
        try {
            importExecutor.execute(() -> runPlan(sessionId, runId, profiles));
        } catch (RejectedExecutionException ex) {
            markRunFailed(sessionId, runId, QUEUE_REFUSED);
            throw ApiException.conflict(QUEUE_REFUSED);
        }
        return accepted;
    }

    // --------------------------------------------------------------- background

    /** Planner rounds, then extraction batches, all on the import executor. */
    private void runPlan(long sessionId, String runId, List<ProfiledSheet> profiles) {
        try {
            AiImportLog current = logs.findById(sessionId).orElse(null);
            if (current == null || !owns(current, runId)) {
                return;
            }
            WorkbookPlanner.Outcome outcome;
            try {
                outcome = planner.plan(profiles, round -> recordPlanningRound(sessionId, round));
            } catch (RuntimeException ex) {
                markRunFailed(sessionId, runId, message(ex));
                return;
            }
            if (!stillOwns(sessionId, runId)) {
                // Stopped or re-planned while the tool loop was running: every round is
                // already in the audit, but the plan they produced must not be published.
                return;
            }
            current = logs.findById(sessionId).orElse(null);
            if (current == null) {
                return;
            }
            WorkbookExtractor.Planning planning =
                    extractor.planBatches(profiles, outcome.plan(), current.getPlanVersion());
            WorkbookRunPlan run = runPlan(outcome, profiles, current.getPlanVersion(), planning);
            if (planning.batches().isEmpty()) {
                finishWithoutBatches(sessionId, runId, run, outcome.trace());
                return;
            }
            savePlan(sessionId, runId, run, planning, outcome.trace());
            AiImportLog saved = logs.findById(sessionId).orElse(null);
            if (saved == null || !owns(saved, runId)) {
                return;
            }
            if (saved.getPlanJson() == null || saved.getTotalBatches() <= 0) {
                // The plan write was lost (a storage failure): do not start batches that
                // have no persisted plan to merge into.
                failRun(sessionId, runId, "解析计划保存失败，请重新规划");
                return;
            }
            runBatches(sessionId, runId,
                    planning.batches().stream().map(BatchPlan::key).toList());
        } catch (RuntimeException ex) {
            // Storage or an unexpected runtime failure must not leave a forever-PARSING row.
            LOG.warn("工作簿导入规划/调度失败 session={}", sessionId, ex);
            failRun(sessionId, runId, "解析中断：" + message(ex));
        }
    }

    private void savePlan(
            long sessionId,
            String runId,
            WorkbookRunPlan run,
            WorkbookExtractor.Planning planning,
            List<WorkbookPlanner.Trace> trace) {
        writeQuietly(() -> {
            AiImportLog current = logs.findById(sessionId).orElse(null);
            if (current == null || !owns(current, runId)) {
                return;
            }
            List<State> batches = planning.batches().stream()
                    .map(batch -> State.pending(work(batch)))
                    .toList();
            current.setPlanJson(writeJson(run));
            current.setPlanVersion(run.version());
            current.setTotalBatches(batches.size());
            current.setCompletedBatches(0);
            current.setBatchesJson(batchStore.write(batches));
            current.setToolTraceJson(writeJson(trace(trace)));
            current.setPreviewJson(writeJson(payload(run, batches, "")));
            current.setAiExplanation(EXTRACTING);
            current.setRowCount(0);
            current.setUpdatedAt(Instant.now());
            logs.save(current);
        });
    }

    private void finishWithoutBatches(
            long sessionId, String runId, WorkbookRunPlan run, List<WorkbookPlanner.Trace> trace) {
        writeQuietly(() -> {
            AiImportLog current = logs.findById(sessionId).orElse(null);
            if (current == null || !owns(current, runId)) {
                return;
            }
            WorkbookPayload payload = payload(run, List.of(), "");
            current.setPlanJson(writeJson(run));
            current.setPlanVersion(run.version());
            current.setRunId(null);
            current.setTotalBatches(0);
            current.setCompletedBatches(0);
            current.setBatchesJson(batchStore.write(List.of()));
            current.setToolTraceJson(writeJson(trace(trace)));
            setPreviewFields(current, payload);
            current.setStatus(AiImportLog.PREVIEW);
            current.setUpdatedAt(Instant.now());
            logs.save(current);
        });
    }

    /**
     * Serial background extraction: one AI call per stored batch, each result written
     * before the next. A failed batch is recorded and the run continues; the session only
     * settles once every batch of this run has been attempted.
     */
    private void runBatches(long sessionId, String runId, List<String> keys) {
        try {
            for (int i = 0; i < keys.size(); i++) {
                String key = keys.get(i);
                if (!stillOwns(sessionId, runId)) {
                    return;
                }
                AiImportLog current = logs.findById(sessionId).orElse(null);
                if (current == null || !owns(current, runId)) {
                    return;
                }
                WorkbookRunPlan run = readPlan(current);
                if (run == null) {
                    failRun(sessionId, runId, "导入计划丢失，请重新规划");
                    return;
                }
                List<State> batches = batchStore.read(current.getBatchesJson());
                State state = batchStore.find(batches, key);
                if (state == null || !WorkbookBatchStore.PENDING.equals(state.status)) {
                    continue;
                }
                beginBatch(sessionId, runId, state);
                WorkbookBatchStore.Outcome outcome;
                String rawResponse = null;
                try {
                    BatchResult result = extractor.extractBatch(batchOf(state));
                    rawResponse = result.rawResponse();
                    outcome = result.rows().isEmpty()
                            ? WorkbookBatchStore.Outcome.failed("模型没有返回这一批的数据")
                            : WorkbookBatchStore.Outcome.parsed(result.rows());
                } catch (RuntimeException ex) {
                    outcome = WorkbookBatchStore.Outcome.failed(message(ex));
                }
                persistBatch(sessionId, runId, run, key, outcome, rawResponse);
            }
            settleRun(sessionId, runId);
            if (stillOwns(sessionId, runId)) {
                // Every selected batch was attempted, yet the row is still PARSING: a state
                // write was lost. Settle it as retryable instead of waiting for a restart.
                failRun(sessionId, runId, "解析状态写入失败，请重试未完成批次");
            }
        } catch (RuntimeException ex) {
            LOG.warn("工作簿导入取值失败 session={}", sessionId, ex);
            failRun(sessionId, runId, "解析中断：" + message(ex));
        }
    }

    private void beginBatch(long sessionId, String runId, State state) {
        writeQuietly(() -> logs.findById(sessionId).ifPresent(current -> {
            if (!owns(current, runId) || !AiImportLog.PARSING.equals(current.getStatus())) {
                return;
            }
            current.setCurrentBatch(state.label());
            current.setUpdatedAt(Instant.now());
            logs.save(current);
        }));
    }

    private void persistBatch(
            long sessionId,
            String runId,
            WorkbookRunPlan run,
            String key,
            WorkbookBatchStore.Outcome outcome,
            String rawResponse) {
        writeQuietly(() -> {
            AiImportLog current = logs.findById(sessionId).orElse(null);
            if (current == null) {
                return;
            }
            if (!owns(current, runId) || !AiImportLog.PARSING.equals(current.getStatus())) {
                // Stopped, abandoned or superseded while the call was in flight: the answer
                // is a real audit event, but it must not change status, rows or preview.
                recordLateAnswer(current, key, rawResponse);
                return;
            }
            List<State> batches = batchStore.read(current.getBatchesJson());
            State batch = batchStore.find(batches, key);
            if (batch == null) {
                return;
            }
            batchStore.replace(batch, outcome, rawResponse);
            int done = batchStore.doneCount(batches);
            int failed = batchStore.failedCount(batches);
            boolean finished = batches.stream()
                    .noneMatch(item -> WorkbookBatchStore.PENDING.equals(item.status));
            String extra = failed > 0
                    ? batchStore.failureSummary(batches, current.getTotalBatches())
                    : "";
            WorkbookPayload payload = payload(run, batches, extra);
            current.setBatchesJson(batchStore.write(batches));
            current.setCompletedBatches(done);
            current.setCurrentBatch(null);
            setPreviewFields(current, payload);
            List<String> rawAnswers = allRawAnswers(current, batches);
            current.setAiRawResponse(rawAnswers.isEmpty() ? null : writeJson(rawAnswers));
            current.setStatus(!finished
                    ? AiImportLog.PARSING
                    : failed > 0 ? AiImportLog.PARTIAL : AiImportLog.PREVIEW);
            if (finished) {
                current.setRunId(null);
            }
            current.setUpdatedAt(Instant.now());
            logs.save(current);
        });
    }

    /**
     * Settles a run whose last selected batch was already finished by someone else (a late
     * batch of the first pass landing during a retry). Without this the session could stay
     * PARSING with nothing left to do.
     */
    private void settleRun(long sessionId, String runId) {
        writeQuietly(() -> {
            AiImportLog current = logs.findById(sessionId).orElse(null);
            if (current == null || !owns(current, runId)) {
                return;
            }
            List<State> batches = batchStore.read(current.getBatchesJson());
            if (batches.stream().anyMatch(item -> WorkbookBatchStore.PENDING.equals(item.status))) {
                return;
            }
            WorkbookRunPlan run = readPlan(current);
            if (run == null) {
                return;
            }
            int failed = batchStore.failedCount(batches);
            String extra = failed > 0
                    ? batchStore.failureSummary(batches, current.getTotalBatches())
                    : "";
            WorkbookPayload payload = payload(run, batches, extra);
            setPreviewFields(current, payload);
            current.setCompletedBatches(batchStore.doneCount(batches));
            current.setCurrentBatch(null);
            current.setRunId(null);
            current.setStatus(failed > 0 ? AiImportLog.PARTIAL : AiImportLog.PREVIEW);
            current.setUpdatedAt(Instant.now());
            logs.save(current);
        });
    }

    private void recordLateAnswer(AiImportLog current, String key, String rawResponse) {
        if (!StringUtils.hasText(rawResponse)) {
            return;
        }
        List<State> batches = batchStore.read(current.getBatchesJson());
        batchStore.recordLateAnswer(batches, key, rawResponse);
        current.setBatchesJson(batchStore.write(batches));
        List<String> rawAnswers = allRawAnswers(current, batches);
        current.setAiRawResponse(rawAnswers.isEmpty() ? null : writeJson(rawAnswers));
        current.setUpdatedAt(Instant.now());
        logs.save(current);
    }

    /**
     * Appends one finished planner round to the tool audit as it happens. A stop or a
     * planning failure therefore still leaves the rounds that really ran.
     */
    private void recordPlanningRound(long sessionId, WorkbookPlanner.Trace round) {
        writeQuietly(() -> logs.findById(sessionId).ifPresent(current -> {
            List<ToolTraceDto> all = new ArrayList<>(trace(current.getToolTraceJson()));
            all.addAll(trace(List.of(round)));
            current.setToolTraceJson(writeJson(all));
            current.setUpdatedAt(Instant.now());
            logs.save(current);
        }));
    }

    /**
     * Every answer this session ever received. Answers still attached to a batch are listed
     * from the batch record; anything that only survives in {@code ai_raw_response} (an
     * answer to a plan that was replaced, for example) is kept after them instead of being
     * overwritten by the next rebuild.
     */
    private List<String> allRawAnswers(AiImportLog log, List<State> batches) {
        List<String> answers = new ArrayList<>(batchStore.rawAnswers(batches));
        for (String stored : storedRawAnswers(log.getAiRawResponse())) {
            if (!answers.contains(stored)) {
                answers.add(stored);
            }
        }
        return answers;
    }

    private List<String> storedRawAnswers(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception ex) {
            // Older rows stored one answer as plain text rather than a JSON array.
            return List.of(json);
        }
    }

    // ------------------------------------------------------------ session ops

    /**
     * Stops a background run at a batch boundary. Finished ranges stay as the preview; the
     * ranges that never ran are kept as failed (not dropped), so the page can list what is
     * uncovered and a retry can replay exactly those. An in-flight call is not cancelled -
     * its late answer goes to the audit only.
     */
    public PreviewDto stop(AiImportLog log) {
        if (!AiImportLog.PARSING.equals(log.getStatus())) {
            throw ApiException.conflict("当前导入不在解析中，无法停止");
        }
        long id = log.getId();
        writeTransaction(() -> stopRun(id));
        return preview(reload(id));
    }

    private void stopRun(long id) {
        AiImportLog current = logs.findById(id).orElse(null);
        if (current == null || !AiImportLog.PARSING.equals(current.getStatus())) {
            throw ApiException.conflict("解析已结束，无法停止");
        }
        current.setRunId(null);
        current.setCurrentBatch(null);
        WorkbookRunPlan run = readPlan(current);
        List<State> plan = batchStore.read(current.getBatchesJson());
        int dropped = 0;
        for (State batch : plan) {
            if (WorkbookBatchStore.PENDING.equals(batch.status)) {
                batch.status = WorkbookBatchStore.FAILED;
                batch.reason = "已停止解析，这一批的结果未采用";
                dropped++;
            }
        }
        int done = batchStore.doneCount(plan);
        int total = current.getTotalBatches() > 0 ? current.getTotalBatches() : plan.size();
        current.setBatchesJson(batchStore.write(plan));
        current.setCompletedBatches(done);
        if (run == null) {
            // Stopped during planning: there is no confirmed plan to retry, so the page must
            // offer the explicit re-plan entry instead of a partial preview.
            current.setPreviewJson(null);
            current.setAiExplanation(STOPPED_WITH_NOTHING);
            current.setRowCount(0);
            current.setStatus(AiImportLog.FAILED);
            current.setUpdatedAt(Instant.now());
            logs.save(current);
            return;
        }
        String stopNote = done == 0
                ? STOPPED_WITH_NOTHING
                : dropped > 0 ? "已停止解析，剩余 " + dropped + " 批未完成" : "已停止解析";
        WorkbookPayload payload = payload(run, plan,
                withFailures(stopNote, batchStore.failureSummary(plan, total)));
        setPreviewFields(current, payload);
        // A confirmed plan keeps the unfinished ranges retryable even when no range finished
        // before the stop; PARTIAL also blocks the commit.
        current.setStatus(AiImportLog.PARTIAL);
        current.setUpdatedAt(Instant.now());
        logs.save(current);
    }

    /**
     * Retries only the failed/uncovered ranges of the confirmed plan. Successful batches
     * are never sent to the model again, and the plan version does not change, so a late
     * answer from the first pass can only land in the audit history.
     */
    public PreviewDto retry(AiImportLog log) {
        if (!AiImportLog.PARTIAL.equals(log.getStatus())) {
            throw ApiException.conflict(AiImportLog.PARSING.equals(log.getStatus())
                    ? "解析还没完成，请等进度走完再重试失败批次"
                    : "当前导入没有可重试失败批次，只有“部分结果”的导入才能重试");
        }
        WorkbookRunPlan run = readPlan(log);
        if (run == null) {
            throw ApiException.conflict("这次导入没有可用的确认计划，请重新规划");
        }
        List<State> stored = batchStore.read(log.getBatchesJson());
        List<State> failed = stored.stream()
                .filter(State::failed)
                // A legacy batch has no sheet/range/plan version; replaying it would be a
                // guess. Such a session has to go through the explicit re-plan path.
                .filter(batch -> StringUtils.hasText(batch.sheet)
                        && !batch.key.startsWith("legacy-"))
                .toList();
        if (failed.isEmpty()) {
            if (stored.stream().anyMatch(State::failed)) {
                throw ApiException.conflict("失败批次来自旧格式记录，无法按工作簿计划重试，请重新规划");
            }
            throw ApiException.conflict("这次导入没有失败的批次，不需要重试");
        }
        List<String> keys = failed.stream().map(batch -> batch.key).toList();
        String runId = UUID.randomUUID().toString();
        long id = log.getId();
        writeTransaction(() -> {
            AiImportLog current = logs.findById(id).orElse(null);
            if (current == null || !AiImportLog.PARTIAL.equals(current.getStatus())) {
                throw ApiException.conflict("导入状态已变化，请刷新后重试");
            }
            List<State> plan = batchStore.read(current.getBatchesJson());
            for (String key : keys) {
                State batch = batchStore.find(plan, key);
                if (batch != null) {
                    batchStore.resetForRetry(batch);
                }
            }
            current.setRunId(runId);
            current.setStatus(AiImportLog.PARSING);
            current.setCurrentBatch(null);
            current.setBatchesJson(batchStore.write(plan));
            current.setCompletedBatches(batchStore.doneCount(plan));
            current.setPreviewJson(writeJson(payload(
                    run, plan, "正在重试 " + keys.size() + " 个失败批次，已完成的批次不会重复调用 AI")));
            current.setAiExplanation("正在重试失败批次");
            current.setUpdatedAt(Instant.now());
            logs.save(current);
        });
        PreviewDto accepted = preview(reload(id));
        try {
            importExecutor.execute(() -> runBatches(id, runId, keys));
        } catch (RejectedExecutionException ex) {
            rejectRetry(id, keys, runId);
            throw ApiException.conflict(QUEUE_REFUSED);
        }
        return accepted;
    }

    private void rejectRetry(long id, List<String> keys, String runId) {
        writeTransaction(() -> {
            AiImportLog current = logs.findById(id).orElse(null);
            if (current == null || !runId.equals(current.getRunId())) {
                return;
            }
            List<State> plan = batchStore.read(current.getBatchesJson());
            for (String key : keys) {
                State batch = batchStore.find(plan, key);
                if (batch != null) {
                    batch.status = WorkbookBatchStore.FAILED;
                    batch.reason = QUEUE_REFUSED;
                }
            }
            current.setRunId(null);
            current.setStatus(AiImportLog.PARTIAL);
            current.setBatchesJson(batchStore.write(plan));
            current.setCompletedBatches(batchStore.doneCount(plan));
            current.setCurrentBatch(null);
            current.setAiExplanation(QUEUE_REFUSED);
            current.setUpdatedAt(Instant.now());
            logs.save(current);
        });
    }

    /**
     * Gives up on a session that is still parsing or only partly parsed. The record and the
     * audit stay, the preview stays readable, but the session can never move forward again
     * and nothing is written to the business tables.
     */
    public PreviewDto abandon(AiImportLog log) {
        requireAbandonable(log);
        long id = log.getId();
        writeTransaction(() -> {
            AiImportLog current = logs.findById(id).orElse(null);
            if (current == null) {
                return;
            }
            requireAbandonable(current);
            current.setRunId(null);
            current.setStatus(AiImportLog.ABANDONED);
            current.setCurrentBatch(null);
            current.setAiExplanation(ABANDON_REASON);
            current.setUpdatedAt(Instant.now());
            logs.save(current);
        });
        return preview(reload(id));
    }

    private void requireAbandonable(AiImportLog log) {
        if (AiImportLog.PARSING.equals(log.getStatus()) || AiImportLog.PARTIAL.equals(log.getStatus())) {
            return;
        }
        throw ApiException.conflict("只有解析中或部分结果的导入可以放弃");
    }

    /**
     * A restart leaves rows in PARSING that nothing is working on. A planned session becomes
     * PARTIAL with its unfinished ranges listed for retry; a session that never got a plan
     * becomes FAILED and offers the explicit re-plan entry. A row left in COMMITTING had a
     * confirm in flight, so it goes back to PREVIEW and the user confirms again. Nothing is
     * replayed automatically.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedSessions() {
        for (AiImportLog candidate : logs.findByStatus(AiImportLog.PARSING)) {
            if (!TARGET.equals(candidate.getTargetType())) {
                continue;
            }
            try {
                transactionTemplate.executeWithoutResult(status -> recoverOne(candidate));
            } catch (RuntimeException ex) {
                // A corrupt row must not abort startup for every other session; the row stays
                // PARSING and the failure is visible in the log.
                LOG.error("无法恢复中断的工作簿导入 session={}", candidate.getId(), ex);
            }
        }
        for (AiImportLog candidate : logs.findByStatus(AiImportLog.COMMITTING)) {
            if (!TARGET.equals(candidate.getTargetType())) {
                continue;
            }
            try {
                transactionTemplate.executeWithoutResult(status ->
                        settleCommitClaim(candidate.getId(), "服务重启中断了入库，请重新确认"));
            } catch (RuntimeException ex) {
                LOG.error("无法恢复未完成的入库 session={}", candidate.getId(), ex);
            }
        }
    }

    private void recoverOne(AiImportLog candidate) {
        AiImportLog current = logs.findById(candidate.getId()).orElse(null);
        if (current == null || !AiImportLog.PARSING.equals(current.getStatus())
                || !TARGET.equals(current.getTargetType())) {
            return;
        }
        current.setRunId(null);
        current.setCurrentBatch(null);
        WorkbookRunPlan run = readPlan(current);
        if (run == null) {
            current.setStatus(AiImportLog.FAILED);
            current.setAiExplanation("服务重启中断了结构识别，请重新规划");
            current.setUpdatedAt(Instant.now());
            logs.save(current);
            return;
        }
        List<State> plan;
        try {
            plan = batchStore.read(current.getBatchesJson());
        } catch (RuntimeException ex) {
            current.setStatus(AiImportLog.FAILED);
            current.setAiExplanation("批次记录损坏，请重新规划");
            current.setUpdatedAt(Instant.now());
            logs.save(current);
            return;
        }
        for (State batch : plan) {
            if (WorkbookBatchStore.PENDING.equals(batch.status)) {
                batch.status = WorkbookBatchStore.FAILED;
                batch.reason = "服务重启中断了这一批，请重试";
            }
        }
        int done = batchStore.doneCount(plan);
        int failed = batchStore.failedCount(plan);
        int total = current.getTotalBatches() > 0 ? current.getTotalBatches() : plan.size();
        String extra = withFailures(
                "服务重启中断了这次解析，未完成的批次可以重试",
                batchStore.failureSummary(plan, total));
        WorkbookPayload payload = payload(run, plan, extra);
        current.setBatchesJson(batchStore.write(plan));
        current.setCompletedBatches(done);
        setPreviewFields(current, payload);
        current.setStatus(failed > 0 ? AiImportLog.PARTIAL : AiImportLog.PREVIEW);
        current.setUpdatedAt(Instant.now());
        logs.save(current);
    }

    // ----------------------------------------------------------------- preview

    public PreviewDto preview(AiImportLog log) {
        WorkbookRunPlan run = readPlan(log);
        WorkbookPayload payload = readPayload(log);
        List<State> batches = batchStore.read(log.getBatchesJson());
        boolean parsing = AiImportLog.PARSING.equals(log.getStatus());
        boolean dormant = parsing || AiImportLog.ABANDONED.equals(log.getStatus());
        List<IssueDto> issues = dormant ? List.of() : issues(payload);
        WorkbookSummaryDto workbook = run == null
                ? null
                : new WorkbookSummaryDto(
                        run.plan().confirmed(),
                        payload.sheets().stream().map(this::sheetDto).toList(),
                        categoryDtos(payload.committed()));
        return new PreviewDto(
                log.getId(),
                TARGET,
                null,
                columns(),
                payload.rows(),
                mappings(payload),
                explanation(log, payload),
                settings.aiImportConfidenceThreshold(),
                log.getStatus(),
                issues,
                dormant ? null : summary(payload),
                progress(log, batches),
                failedBatches(log, batches),
                workbook,
                payload.warnings(),
                trace(log.getToolTraceJson()),
                payload.teacherWarnings());
    }

    private List<ColumnDto> columns() {
        return ImportTarget.WORKBOOK.fields().stream()
                .map(field -> new ColumnDto(field.field(), field.label(), field.required()))
                .toList();
    }

    private SheetSummaryDto sheetDto(SheetSummary sheet) {
        return new SheetSummaryDto(
                sheet.sheet(), sheet.category(), sheet.columns(), sheet.recognizedRows(),
                sheet.newRows(), sheet.skippedReason(), sheet.note());
    }

    /** The column decisions in the same shape the single-target preview shows. */
    private List<MappingDto> mappings(WorkbookPayload payload) {
        List<MappingDto> mappings = new ArrayList<>();
        for (SheetSummary sheet : payload.sheets()) {
            for (Map.Entry<String, String> column : sheet.columns().entrySet()) {
                mappings.add(new MappingDto(
                        column.getValue(), column.getKey(), "工作表「" + sheet.sheet() + "」"));
            }
        }
        return List.copyOf(mappings);
    }

    private TimetableSummaryDto summary(WorkbookPayload payload) {
        Map<String, Boolean> groups = new LinkedHashMap<>();
        for (RowDto row : payload.rows()) {
            String className = value(row, "className");
            String courseName = value(row, "courseName");
            if (StringUtils.hasText(className) && StringUtils.hasText(courseName)) {
                groups.put(className + "\u0001" + courseName, Boolean.TRUE);
            }
        }
        return new TimetableSummaryDto(
                payload.rows().size(), 0, groups.size(), groups.size(), 0);
    }

    private ImportProgressDto progress(AiImportLog log, List<State> batches) {
        boolean parsing = AiImportLog.PARSING.equals(log.getStatus());
        if (parsing && readPlan(log) == null) {
            // Planning has no honest batch count yet; showing 0/0 would be a fake total.
            return new ImportProgressDto(
                    0, 0, 0, log.getCurrentBatch(), ImportDtos.PHASE_PLANNING);
        }
        if (log.getTotalBatches() <= 0) {
            return null;
        }
        return new ImportProgressDto(
                log.getTotalBatches(),
                log.getCompletedBatches(),
                batchStore.failedCount(batches),
                log.getCurrentBatch(),
                ImportDtos.PHASE_EXTRACTING);
    }

    private List<FailedBatchDto> failedBatches(AiImportLog log, List<State> batches) {
        int total = log.getTotalBatches() > 0 ? log.getTotalBatches() : batches.size();
        return batches.stream()
                .filter(State::failed)
                .map(batch -> new FailedBatchDto(
                        batch.index, batch.label(), batchStore.failureText(batch, total)))
                .toList();
    }

    /**
     * Page-level blockers only: a missing semester, a semester without periods, and the row
     * errors the merge refused. Free periods and dropped duplicates are warnings, not blocks.
     * A committed session keeps whatever blocker metadata it had; nothing can be done then.
     */
    private List<IssueDto> issues(WorkbookPayload payload) {
        List<IssueDto> issues = new ArrayList<>();
        Long semesterId = currentSemesterId();
        // A teacher- or course-only workbook commits without a semester, so it is not blocked.
        boolean needsSemester = !payload.rows().isEmpty() || !payload.classes().isEmpty();
        if (needsSemester && semesterId == null) {
            issues.add(new IssueDto(0, "semester", "", List.of(), true, SEMESTER_MISSING, null, List.of()));
        } else if (needsSemester && periods.countBySemesterId(semesterId) == 0) {
            issues.add(new IssueDto(0, "periods", "", List.of(), true, PERIODS_MISSING, null, List.of()));
        }
        for (RowErrorDto error : payload.errors()) {
            issues.add(new IssueDto(
                    0, error.field(), "", error.candidates(), true, error.message(), null, List.of()));
        }
        return List.copyOf(issues);
    }

    /** A failed parse never reaches a stored payload, so fall back to the row's reason. */
    private String explanation(AiImportLog log, WorkbookPayload payload) {
        String stored = payload.explanation() == null ? "" : payload.explanation();
        boolean closed = AiImportLog.FAILED.equals(log.getStatus())
                || AiImportLog.ABANDONED.equals(log.getStatus());
        if (StringUtils.hasText(stored) || !closed) {
            return stored;
        }
        return log.getAiExplanation() == null ? stored : log.getAiExplanation();
    }

    // ------------------------------------------------------------------ commit

    /**
     * Writes the confirmed preview in dependency order: teachers, courses, classes, then the
     * timetable. Each category is its own transaction, so a failing timetable does not undo
     * the teachers; the session stays editable and a second click retries only what failed.
     *
     * <p>The row is claimed with a short {@code COMMITTING} write before any category is
     * touched. Two concurrent confirms both start from {@code PREVIEW}, but only the winner
     * of the version race gets past the claim, so the second one cannot build an extra draft.
     */
    public CommitResultDto commit(AiImportLog log, String username) {
        AiImportLog claimed = claimCommit(log.getId());
        try {
            return commitClaimed(claimed, username);
        } catch (RuntimeException ex) {
            releaseCommitClaim(claimed.getId(), ex);
            throw ex;
        }
    }

    private CommitResultDto commitClaimed(AiImportLog log, String username) {
        requireAllBatchesDone(log);
        WorkbookPayload payload = readPayload(log);
        if (payload.rows().isEmpty() && payload.teachers().isEmpty() && payload.courses().isEmpty()) {
            throw ApiException.badRequest("没有可入库的数据");
        }
        // A teacher- or course-only workbook needs neither a semester nor periods; only
        // rows/classes make the semester part of the write.
        Long semesterId = currentSemesterId();
        boolean needsSemester = !payload.rows().isEmpty() || !payload.classes().isEmpty();
        if (needsSemester && semesterId == null) {
            throw ApiException.badRequest(SEMESTER_MISSING);
        }
        if (!payload.rows().isEmpty() && periods.countBySemesterId(semesterId) == 0) {
            throw ApiException.badRequest(PERIODS_MISSING);
        }
        if (!payload.rows().isEmpty()) {
            requireTimetableEdit(username);
        }

        Map<String, CategorySummary> previous = payload.committed();
        Map<String, CategorySummary> committed = new LinkedHashMap<>();
        committed.put(TEACHER, write(() -> mappings.ensureTeachers(payload.teachers())));
        committed.put(COURSE, write(() -> mappings.ensureCourses(payload.courses())));
        if (!payload.classes().isEmpty()) {
            committed.put(CLASS, write(() -> mappings.ensureClasses(payload.classes())));
        }

        List<RowErrorDto> rowErrors = new ArrayList<>();
        Long draftId = null;
        int lessons = 0;
        if (!payload.rows().isEmpty()) {
            try {
                TimetableImportService.Result result =
                        timetableImports.importRows(payload.rows(), semesterId, username);
                if (result.errors().isEmpty()) {
                    draftId = result.draftId();
                    lessons = result.imported();
                    committed.put(TIMETABLE, new CategorySummary(lessons, 0, 0, List.of()));
                } else {
                    rowErrors.addAll(result.errors());
                    committed.put(TIMETABLE, new CategorySummary(
                            0, 0, rowsSkipped(result.errors()),
                            result.errors().stream().map(RowErrorDto::message).toList()));
                }
            } catch (RuntimeException ex) {
                committed.put(TIMETABLE, new CategorySummary(0, 0, 0, List.of(message(ex))));
            }
        }

        // A retry re-runs only what is missing, so its own counts are 0 for work already
        // done. The summary a user reads is the session's total, not the last attempt's.
        committed.replaceAll((category, current) ->
                mergeCounts(previous.get(category), current));
        WorkbookPayload saved = new WorkbookPayload(
                payload.rows(), payload.sheets(), payload.structureConfirmed(),
                payload.explanation(), payload.warnings(), payload.teacherWarnings(),
                payload.errors(), payload.teachers(), payload.courses(), payload.classes(), committed);
        log.setPreviewJson(writeJson(saved));
        log.setAiExplanation(payload.explanation());
        log.setRowCount(payload.rows().size());
        boolean allWritten = committed.values().stream().allMatch(category -> category.errors().isEmpty());
        int total = lessons > 0
                ? lessons
                : committed.values().stream()
                        .mapToInt(category -> category.created() + category.updated())
                        .sum();
        log.setImportedCount(total);
        log.setResultJson(writeJson(Map.of(
                "categories", committed,
                "draftId", draftId == null ? "" : draftId)));
        log.setStatus(allWritten ? AiImportLog.COMMITTED : AiImportLog.PREVIEW);
        log.setUpdatedAt(Instant.now());
        logs.save(log);
        return new CommitResultDto(
                total, List.copyOf(rowErrors), draftId, payload.warnings(), categoryDtos(committed),
                payload.teacherWarnings());
    }

    /**
     * Short transaction that turns the preview into {@code COMMITTING}. The flush makes the
     * optimistic version the arbiter, not a check-then-act gap: the second request either
     * sees the new status or loses the version race, and either way it writes nothing.
     */
    private AiImportLog claimCommit(Long id) {
        try {
            return transactionTemplate.execute(status -> {
                AiImportLog current = logs.findById(id)
                        .orElseThrow(() -> ApiException.notFound("导入会话不存在: " + id));
                requireCommitable(current);
                current.setStatus(AiImportLog.COMMITTING);
                current.setUpdatedAt(Instant.now());
                return logs.saveAndFlush(current);
            });
        } catch (org.springframework.dao.OptimisticLockingFailureException ex) {
            throw ApiException.conflict("另一次确认入库正在进行，请刷新后查看结果");
        }
    }

    private void requireCommitable(AiImportLog log) {
        if (AiImportLog.COMMITTED.equals(log.getStatus())) {
            throw ApiException.conflict("该导入已入库，不能再修改");
        }
        if (AiImportLog.COMMITTING.equals(log.getStatus())) {
            throw ApiException.conflict("这次导入正在入库，请刷新后查看结果");
        }
        if (AiImportLog.PARSING.equals(log.getStatus())
                || AiImportLog.PARTIAL.equals(log.getStatus())) {
            throw ApiException.conflict("这次导入还没有完整结果，请先处理失败批次");
        }
        if (AiImportLog.ABANDONED.equals(log.getStatus())) {
            throw ApiException.conflict("该导入已放弃，不能再修改或入库");
        }
        if (AiImportLog.FAILED.equals(log.getStatus())) {
            throw ApiException.conflict("该导入解析失败，请打回重析");
        }
    }

    /**
     * A failed write must not leave a claim nobody will finish: the user can confirm again.
     * Only the claim owner releases it; if this write is lost too, startup recovery settles
     * the row instead.
     */
    private void releaseCommitClaim(Long id, RuntimeException failure) {
        try {
            transactionTemplate.executeWithoutResult(status ->
                    settleCommitClaim(id, "入库未完成，可再次确认：" + message(failure)));
        } catch (RuntimeException ex) {
            LOG.error("无法释放入库占位 session={}", id, ex);
        }
    }

    /** Puts a claimed row back to PREVIEW; the claimant or startup recovery calls this. */
    private void settleCommitClaim(Long id, String explanation) {
        AiImportLog current = logs.findById(id).orElse(null);
        if (current == null || !AiImportLog.COMMITTING.equals(current.getStatus())) {
            return;
        }
        current.setStatus(AiImportLog.PREVIEW);
        current.setAiExplanation(explanation);
        current.setUpdatedAt(Instant.now());
        logs.save(current);
    }

    /** A workbook is never committed while any planned range is still missing. */
    private void requireAllBatchesDone(AiImportLog log) {
        if (log.getPlanVersion() <= 0 || log.getTotalBatches() <= 0) {
            return;
        }
        List<State> batches = batchStore.read(log.getBatchesJson());
        if (batches.size() < log.getTotalBatches()
                || batches.stream().anyMatch(batch -> !batch.done())) {
            throw ApiException.conflict("这次导入还有批次没有解析完成，请先重试失败批次");
        }
    }

    private List<CategoryResultDto> categoryDtos(Map<String, CategorySummary> committed) {
        return committed.entrySet().stream()
                .map(entry -> new CategoryResultDto(
                        entry.getKey(), entry.getValue().created(), entry.getValue().updated(),
                        entry.getValue().skipped(), entry.getValue().errors()))
                .toList();
    }

    private int rowsSkipped(List<RowErrorDto> errors) {
        return (int) errors.stream().map(RowErrorDto::row).distinct().count();
    }

    private CategorySummary write(Supplier<List<EntityOutcome>> action) {
        try {
            List<EntityOutcome> outcomes = action.get();
            int created = (int) outcomes.stream().filter(EntityOutcome::created).count();
            int updated = (int) outcomes.stream().filter(EntityOutcome::updated).count();
            int skipped = (int) outcomes.stream().filter(outcome -> outcome.error() != null).count();
            List<String> errors = outcomes.stream()
                    .filter(outcome -> outcome.error() != null)
                    .map(outcome -> outcome.error())
                    .toList();
            return new CategorySummary(created, updated, skipped, errors);
        } catch (RuntimeException ex) {
            return new CategorySummary(0, 0, 0, List.of(message(ex)));
        }
    }

    private CategorySummary mergeCounts(CategorySummary previous, CategorySummary current) {
        if (previous == null) {
            return current;
        }
        return new CategorySummary(
                previous.created() + current.created(),
                previous.updated() + current.updated(),
                current.skipped(),
                current.errors());
    }

    private void requireTimetableEdit(String username) {
        permissions.requireTimetableEdit(username);
    }

    // ----------------------------------------------------------------- payload

    /**
     * Folds every {@code DONE} range into the preview. The merge is always rebuilt from all
     * finished ranges, so a retry that fills a gap cannot accumulate duplicate rows: the
     * slot merge and the "full row wins" rule see the same ordered input as a first pass.
     */
    private WorkbookPayload payload(WorkbookRunPlan run, List<State> batches, String extra) {
        List<SlotMerger.Kept> keptInput = new ArrayList<>();
        Map<String, Integer> sheetRows = new LinkedHashMap<>();
        Map<String, List<String>> sheetTeachers = new LinkedHashMap<>();
        Map<String, List<String>> sheetCourses = new LinkedHashMap<>();
        List<TeacherDraft> teachers = new ArrayList<>();
        List<CourseDraft> courses = new ArrayList<>();
        List<String> warnings = new ArrayList<>(run.warnings());
        for (State batch : batches) {
            if (!batch.done()) {
                continue;
            }
            sheetRows.merge(batch.sheet, batch.rows.size(), Integer::sum);
            if (SheetCategory.TIMETABLE.code().equals(batch.category)) {
                for (int i = 0; i < batch.rows.size(); i++) {
                    keptInput.add(new SlotMerger.Kept(
                            batch.sheet, batch.startRow + i, batch.rows.get(i)));
                }
            } else if (SheetCategory.TEACHER.code().equals(batch.category)) {
                for (RowDto row : batch.rows) {
                    String name = value(row, "name");
                    teachers.add(new TeacherDraft(
                            name, value(row, "employeeNo"), value(row, "phone"),
                            value(row, "email"), value(row, "subject")));
                    if (StringUtils.hasText(name)) {
                        sheetTeachers.computeIfAbsent(batch.sheet, key -> new ArrayList<>()).add(name);
                    }
                }
            } else if (SheetCategory.COURSE.code().equals(batch.category)) {
                for (RowDto row : batch.rows) {
                    String name = value(row, "courseName").isEmpty()
                            ? value(row, "name")
                            : value(row, "courseName");
                    courses.add(new CourseDraft(name, value(row, "subject")));
                    if (StringUtils.hasText(name)) {
                        sheetCourses.computeIfAbsent(batch.sheet, key -> new ArrayList<>()).add(name);
                    }
                }
            }
        }
        SlotMerger.Merge merge = SlotMerger.merge(keptInput);
        // Timetable recognized counts come from the slot merge (it saw every finished row);
        // teacher/course sheets already carry their parsed row count.
        for (Map.Entry<String, SlotMerger.SheetStats> entry : merge.perSheet().entrySet()) {
            sheetRows.put(entry.getKey(), entry.getValue().recognized());
        }
        List<RowDto> rows = merge.kept().stream().map(SlotMerger.Kept::row).toList();

        // Teachers and courses also arrive through the timetable rows; the named sheets
        // supply the extra fields, the timetable only the names.
        Map<String, String> teacherNames = new LinkedHashMap<>();
        Map<String, String> courseNames = new LinkedHashMap<>();
        Map<String, String> classNames = new LinkedHashMap<>();
        for (RowDto row : rows) {
            String teacher = value(row, "teacherName");
            if (StringUtils.hasText(teacher)) {
                teacherNames.putIfAbsent(SlotText.normalizeName(teacher), teacher);
            }
            String course = value(row, "courseName");
            if (StringUtils.hasText(course)) {
                courseNames.putIfAbsent(SlotText.normalizeName(course), course);
            }
            String className = value(row, "className");
            if (StringUtils.hasText(className)) {
                classNames.putIfAbsent(SlotText.normalizeName(className), className);
            }
        }
        for (String name : teacherNames.values()) {
            teachers.add(new TeacherDraft(name, null, null, null, null));
        }
        for (String name : courseNames.values()) {
            courses.add(new CourseDraft(name, null));
        }

        if (!run.plan().confirmed()) {
            warnings.add(UNCONFIRMED);
        }
        if (merge.freeSlots() > 0) {
            warnings.add("空课位 " + merge.freeSlots() + " 个（没有课程与教师），已跳过");
        }
        if (merge.droppedIncomplete() > 0) {
            warnings.add("丢弃 " + merge.droppedIncomplete() + " 行（信息不全）");
        }
        if (merge.duplicates() > 0) {
            warnings.add("合并 " + merge.duplicates() + " 行（重复槽位）");
        }
        List<TeacherWarningDto> teacherWarnings =
                teacherWarnings(rows, sheetTeachers, courseNames, courses);
        List<SheetSummary> sheets =
                sheets(run, merge, sheetRows, sheetTeachers, sheetCourses);
        long contributing = sheets.stream()
                .filter(sheet -> SheetCategory.TIMETABLE.code().equals(sheet.category())
                        || SheetCategory.TEACHER.code().equals(sheet.category())
                        || SheetCategory.COURSE.code().equals(sheet.category()))
                .count();
        String base = (!StringUtils.hasText(run.plan().explanation())
                ? "已读取工作簿"
                : run.plan().explanation())
                + "（" + contributing + " 张有数据的工作表，" + rows.size() + " 节课）";
        String explanation = !StringUtils.hasText(extra) ? base : base + " " + extra;
        return new WorkbookPayload(
                rows, sheets, run.plan().confirmed(), explanation, warnings, teacherWarnings,
                merge.errors(), teachers, courses, List.copyOf(classNames.values()), Map.of());
    }

    /**
     * Suspicious teacher values, from the timetable rows and from the teacher sheets that
     * create teachers too. The course names compared against are the workbook's own (the
     * timetable course column and the course sheets), so the rule needs no extra query and
     * a preview never changes because someone edited the course list meanwhile.
     */
    private List<TeacherWarningDto> teacherWarnings(
            List<RowDto> rows,
            Map<String, List<String>> sheetTeachers,
            Map<String, String> courseNames,
            List<CourseDraft> courses) {
        List<SuspiciousTeacherNames.Occurrence> occurrences = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            String teacher = value(rows.get(i), "teacherName");
            if (StringUtils.hasText(teacher)) {
                occurrences.add(new SuspiciousTeacherNames.Occurrence(teacher, i + 1));
            }
        }
        for (List<String> names : sheetTeachers.values()) {
            for (String name : names) {
                // A name sheet carries no lesson: row 0 keeps it out of the count and the rows.
                occurrences.add(new SuspiciousTeacherNames.Occurrence(name, 0));
            }
        }
        List<String> knownCourses = new ArrayList<>(courseNames.values());
        for (CourseDraft course : courses) {
            if (StringUtils.hasText(course.name())) {
                knownCourses.add(course.name());
            }
        }
        return SuspiciousTeacherNames.inspect(occurrences, knownCourses);
    }

    /** One line per sheet, in the file's own order; skipped sheets keep their reason. */
    private List<SheetSummary> sheets(
            WorkbookRunPlan run,
            SlotMerger.Merge merge,
            Map<String, Integer> extractedRows,
            Map<String, List<String>> sheetTeachers,
            Map<String, List<String>> sheetCourses) {
        Map<String, SheetPlan> planned = new LinkedHashMap<>();
        for (SheetPlan sheetPlan : run.plan().sheets()) {
            planned.put(sheetPlan.sheet(), sheetPlan);
        }
        List<SheetSummary> sheets = new ArrayList<>();
        for (SheetMeta meta : run.sheets()) {
            if (meta.skippedReason() != null) {
                sheets.add(new SheetSummary(
                        meta.name(), SheetCategory.EXPLANATION.code(), Map.of(),
                        0, 0, meta.skippedReason(), ""));
                continue;
            }
            SheetPlan sheetPlan = planned.get(meta.name());
            SheetCategory category = sheetPlan == null ? SheetCategory.UNKNOWN : sheetPlan.category();
            Map<String, String> columns = sheetPlan == null ? Map.of() : sheetPlan.columns();
            String note = sheetPlan == null ? "" : sheetPlan.note();
            int recognized = extractedRows.getOrDefault(meta.name(), 0);
            int fresh;
            String skipReason = null;
            if (category == SheetCategory.TIMETABLE) {
                SlotMerger.SheetStats stats = merge.perSheet().get(meta.name());
                recognized = stats == null ? 0 : stats.recognized();
                fresh = stats == null ? 0 : stats.kept();
                skipReason = timetableSkipReason(stats);
            } else if (category == SheetCategory.TEACHER) {
                fresh = missingNames(sheetTeachers.getOrDefault(meta.name(), List.of()), "teacher");
            } else if (category == SheetCategory.COURSE) {
                fresh = missingNames(sheetCourses.getOrDefault(meta.name(), List.of()), "course");
            } else {
                recognized = extractedRows.getOrDefault(meta.name(), 0);
                fresh = 0;
                skipReason = category == SheetCategory.EXPLANATION
                        ? "判断为说明类，已跳过"
                        : !StringUtils.hasText(note) ? "未识别的表，已跳过" : note;
            }
            sheets.add(new SheetSummary(
                    meta.name(), category.code(), columns, recognized, fresh, skipReason, note));
        }
        return List.copyOf(sheets);
    }

    private String timetableSkipReason(SlotMerger.SheetStats stats) {
        if (stats == null) {
            return null;
        }
        List<String> notes = new ArrayList<>();
        if (stats.freeSlots() > 0) {
            notes.add("跳过空课位 " + stats.freeSlots() + " 个");
        }
        if (stats.droppedIncomplete() > 0) {
            notes.add("丢弃信息不全 " + stats.droppedIncomplete() + " 行");
        }
        if (stats.duplicates() > 0) {
            notes.add("合并重复 " + stats.duplicates() + " 行");
        }
        return notes.isEmpty() ? null : String.join("；", notes);
    }

    /** How many of these names the system does not have yet (the sheet's "new" count). */
    private int missingNames(List<String> names, String type) {
        try {
            return mappings.lookupNames(type, names.stream().filter(Objects::nonNull).toList())
                    .missing().size();
        } catch (RuntimeException ex) {
            return 0;
        }
    }

    // ---------------------------------------------------------------- plumbing

    private WorkbookRunPlan runPlan(
            WorkbookPlanner.Outcome outcome,
            List<ProfiledSheet> profiles,
            int version,
            WorkbookExtractor.Planning planning) {
        List<SheetMeta> sheets = new ArrayList<>();
        for (ProfiledSheet sheet : profiles) {
            sheets.add(new SheetMeta(
                    sheet.name(), sheet.skippedReason(),
                    sheet.skipped() ? 0 : sheet.dataRows().size()));
        }
        // Sheets without a usable column mapping are reported on the preview, not silently
        // dropped: the plan is still the source of truth for what was attempted.
        return new WorkbookRunPlan(
                version, outcome.plan(), List.copyOf(sheets), planning.warnings());
    }

    private List<ProfiledSheet> profiles(AiImportLog log) {
        List<Grid> grids = readWorkbook(storage.read(log.getFilePath()), log.getFilePath());
        return SheetProfiler.profile(grids);
    }

    private WorkbookBatchStore.Work work(BatchPlan batch) {
        return new WorkbookBatchStore.Work(
                batch.key(), batch.index(), batch.sheet(), batch.category().code(),
                batch.columns(), batch.startRow(), batch.planVersion(), batch.totalRows(),
                batch.rows());
    }

    private BatchPlan batchOf(State state) {
        return new BatchPlan(
                state.key, state.index, state.sheet,
                SheetCategory.from(state.category), state.columns, state.startRow, state.planVersion,
                state.totalRows, state.sourceRows);
    }

    private WorkbookRunPlan readPlan(AiImportLog log) {
        String json = log.getPlanJson();
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, WorkbookRunPlan.class);
        } catch (Exception ex) {
            LOG.warn("读取工作簿计划失败 session={}", log.getId(), ex);
            return null;
        }
    }

    private WorkbookPayload readPayload(AiImportLog log) {
        String json = log.getPreviewJson();
        if (!StringUtils.hasText(json)) {
            return emptyPayload();
        }
        try {
            return objectMapper.readValue(json, WorkbookPayload.class);
        } catch (Exception ex) {
            return emptyPayload();
        }
    }

    private WorkbookPayload emptyPayload() {
        return new WorkbookPayload(
                List.of(), List.of(), false, "", List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), Map.of());
    }

    private List<Grid> readWorkbook(byte[] bytes, String path) {
        String name = path == null ? "" : path.toLowerCase();
        try {
            if (name.endsWith(".csv")) {
                return List.of(new Grid(fileName(path), CsvTableReader.readGrid(bytes)));
            }
            return ExcelTableReader.readAllGrids(new ByteArrayInputStream(bytes));
        } catch (Exception ex) {
            throw ApiException.badRequest("无法读取表格文件: " + ex.getMessage());
        }
    }

    private AiImportLog newLog(UploadStorage.StoredFile stored, String username) {
        AiImportLog log = new AiImportLog();
        log.setImportType("excel");
        log.setTargetType(TARGET);
        log.setUsername(username);
        AppUser user = users.getByUsername(username);
        log.setUserId(user == null ? null : user.getId());
        log.setFileName(stored.fileName());
        log.setFileSize(stored.size());
        log.setFilePath(stored.path());
        log.setStatus(AiImportLog.PARSING);
        log.setRowCount(0);
        log.setImportedCount(0);
        log.setCreatedAt(Instant.now());
        log.setUpdatedAt(Instant.now());
        return logs.save(log);
    }

    private List<ToolTraceDto> trace(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<ToolTraceDto>>() {});
        } catch (Exception ex) {
            return List.of();
        }
    }

    private List<ToolTraceDto> trace(List<WorkbookPlanner.Trace> rounds) {
        return rounds.stream()
                .map(round -> new ToolTraceDto(
                        round.round(), round.tool(), round.args(), round.reply(), round.result()))
                .toList();
    }

    private Long currentSemesterId() {
        return semesters.current().map(SemesterDto::id).orElse(null);
    }

    private AiImportLog reload(long id) {
        return logs.findById(id).orElseThrow(() -> ApiException.notFound("导入会话不存在: " + id));
    }

    private boolean owns(AiImportLog log, String runId) {
        return AiImportLog.PARSING.equals(log.getStatus())
                && runId != null && runId.equals(log.getRunId());
    }

    private boolean stillOwns(long id, String runId) {
        AiImportLog current = logs.findById(id).orElse(null);
        return current != null && owns(current, runId);
    }

    /** A session that never got a plan is failed; a planned one stays retryable. */
    private void markRunFailed(long sessionId, String runId, String message) {
        writeQuietly(() -> {
            AiImportLog current = logs.findById(sessionId).orElse(null);
            if (current == null || !runId.equals(current.getRunId())) {
                return;
            }
            current.setRunId(null);
            current.setCurrentBatch(null);
            current.setStatus(AiImportLog.FAILED);
            current.setAiExplanation(message);
            current.setUpdatedAt(Instant.now());
            logs.save(current);
        });
    }

    /**
     * An unexpected stop of the worker: no batch may stay PENDING under a PARSING session.
     * With a stored plan the session becomes PARTIAL so the unfinished ranges can be retried;
     * without one it becomes FAILED and the page offers a re-plan.
     */
    private void failRun(long sessionId, String runId, String message) {
        try {
            writeQuietly(() -> {
                AiImportLog current = logs.findById(sessionId).orElse(null);
                if (current == null || !runId.equals(current.getRunId())) {
                    return;
                }
                current.setRunId(null);
                current.setCurrentBatch(null);
                WorkbookRunPlan run = readPlan(current);
                List<State> plan;
                try {
                    plan = batchStore.read(current.getBatchesJson());
                } catch (RuntimeException ex) {
                    current.setStatus(AiImportLog.FAILED);
                    current.setAiExplanation("批次记录损坏，请重新规划");
                    current.setUpdatedAt(Instant.now());
                    logs.save(current);
                    return;
                }
                for (State batch : plan) {
                    if (WorkbookBatchStore.PENDING.equals(batch.status)) {
                        batch.status = WorkbookBatchStore.FAILED;
                        batch.reason = message;
                    }
                }
                current.setBatchesJson(batchStore.write(plan));
                current.setCompletedBatches(batchStore.doneCount(plan));
                if (run == null) {
                    current.setStatus(AiImportLog.FAILED);
                    current.setAiExplanation(message);
                    current.setUpdatedAt(Instant.now());
                    logs.save(current);
                    return;
                }
                int failures = batchStore.failedCount(plan);
                String extra = withFailures(
                        message, batchStore.failureSummary(plan, current.getTotalBatches()));
                WorkbookPayload payload = payload(run, plan, extra);
                setPreviewFields(current, payload);
                current.setStatus(failures > 0 ? AiImportLog.PARTIAL : AiImportLog.PREVIEW);
                current.setUpdatedAt(Instant.now());
                logs.save(current);
            });
        } catch (RuntimeException ex) {
            LOG.error("无法标记中断的工作簿导入 session={}", sessionId, ex);
        }
    }

    private void markFailed(AiImportLog log, String message) {
        AiImportLog current = logs.findById(log.getId()).orElse(null);
        if (current == null) {
            return;
        }
        current.setStatus(AiImportLog.FAILED);
        current.setAiExplanation(message);
        current.setRunId(null);
        current.setCurrentBatch(null);
        current.setUpdatedAt(Instant.now());
        logs.save(current);
    }

    private void save(AiImportLog log) {
        AiImportLog saved = logs.save(log);
        log.setVersion(saved.getVersion());
    }

    /**
     * Runs one short write transaction. A stop or retry that lost the row to the background
     * thread reads it again and retries once; a second loss is reported as a conflict.
     */
    private void writeTransaction(Runnable body) {
        inTransaction(body, false);
    }

    /**
     * A background write losing a version race re-reads and re-applies once; a second loss
     * is dropped because the next batch or the startup recovery settles the row.
     */
    private void writeQuietly(Runnable body) {
        inTransaction(body, true);
    }

    private void inTransaction(Runnable body, boolean quiet) {
        try {
            transactionTemplate.executeWithoutResult(status -> body.run());
        } catch (org.springframework.dao.OptimisticLockingFailureException ex) {
            try {
                transactionTemplate.executeWithoutResult(status -> body.run());
            } catch (org.springframework.dao.OptimisticLockingFailureException again) {
                if (!quiet) {
                    throw ApiException.conflict(LOST_VERSION);
                }
                // The next batch (or the startup recovery) will settle the row.
            }
        }
    }

    private String withFailures(String base, String failureSummary) {
        String text = base == null ? "" : base.trim();
        if (!StringUtils.hasText(failureSummary)) {
            return text;
        }
        return !StringUtils.hasText(text) ? failureSummary : text + " " + failureSummary;
    }

    private String message(RuntimeException ex) {
        String message = ex.getMessage();
        return !StringUtils.hasText(message) ? "未知错误" : message;
    }

    private String fileName(String path) {
        if (!StringUtils.hasText(path)) {
            return "";
        }
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash < 0 ? path : path.substring(slash + 1);
    }

    /** The three fields every settled preview shares; the caller sets the status. */
    private void setPreviewFields(AiImportLog log, WorkbookPayload payload) {
        log.setPreviewJson(writeJson(payload));
        log.setAiExplanation(payload.explanation());
        log.setRowCount(payload.rows().size());
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("保存导入数据失败", ex);
        }
    }

    private String value(RowDto row, String field) {
        if (row == null || row.values() == null) {
            return "";
        }
        String value = row.values().get(field);
        return value == null ? "" : value.trim();
    }
}
