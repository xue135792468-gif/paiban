package com.paiban.scheduling.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paiban.ai.AiGateway;
import com.paiban.ai.AiMessage;
import com.paiban.ai.AiOptions;
import com.paiban.ai.AiRequest;
import com.paiban.ai.AiResult;
import com.paiban.ai.AiTaskType;
import com.paiban.common.settings.SettingsService;
import com.paiban.common.web.ApiException;
import com.paiban.foundation.domain.AdminClass;
import com.paiban.foundation.domain.AdminClassRepository;
import com.paiban.foundation.domain.Course;
import com.paiban.foundation.domain.CourseRepository;
import com.paiban.foundation.domain.Period;
import com.paiban.foundation.domain.PeriodRepository;
import com.paiban.foundation.domain.Teacher;
import com.paiban.foundation.domain.TeacherRepository;
import com.paiban.foundation.service.SemesterService;
import com.paiban.identity.domain.AppUser;
import com.paiban.identity.domain.Permissions;
import com.paiban.identity.service.UserService;
import com.paiban.scheduling.domain.ConstraintGenerationRun;
import com.paiban.scheduling.domain.ConstraintGenerationRunRepository;
import com.paiban.scheduling.domain.ConstraintRunCompletion;
import com.paiban.scheduling.service.ConstraintProposalNormalizer.ClassRef;
import com.paiban.scheduling.service.ConstraintProposalNormalizer.Context;
import com.paiban.scheduling.service.ConstraintProposalNormalizer.CourseRef;
import com.paiban.scheduling.service.ConstraintProposalNormalizer.TeacherRef;
import com.paiban.scheduling.web.ConstraintProposalDtos.AvailabilityDto;
import com.paiban.scheduling.web.ConstraintProposalDtos.ConstraintProposalDto;
import com.paiban.scheduling.web.ConstraintProposalDtos.GenerateRequest;
import com.paiban.scheduling.web.ConstraintProposalDtos.RunDetailDto;
import com.paiban.scheduling.web.ConstraintProposalDtos.RunDto;
import com.paiban.scheduling.web.ConstraintProposalDtos.ResolveProposalRequest;
import com.paiban.scheduling.web.ConstraintProposalDtos.RunSummaryDto;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

/**
 * Ticket 14/08: read-only natural-language proposals.
 *
 * <p>The run row is the whole business record: the user's text, the exact context sent,
 * the provider's complete answer and the normalized proposals. The model call runs
 * outside any transaction; completion is a conditional update on {@code RUNNING}, so a
 * cancelled request records the late answer without changing the page's result.
 */
@Service
public class ConstraintProposalService {

    static final int TASK_TIMEOUT_SECONDS = 60;
    static final int MAX_INPUT_LENGTH = 2000;
    private static final int MAX_PAGE = 50;

    private final AiGateway gateway;
    private final ConstraintGenerationRunRepository runs;
    private final ConstraintProposalParser parser;
    private final ConstraintProposalPrompt prompt;
    private final SemesterService semesters;
    private final TeacherRepository teachers;
    private final CourseRepository courses;
    private final AdminClassRepository adminClasses;
    private final PeriodRepository periods;
    private final SettingsService settings;
    private final UserService users;
    private final ObjectMapper mapper;
    /** The provider call is outside a transaction; the record writes are inside one. */
    private final TransactionTemplate transactions;

    public ConstraintProposalService(
            AiGateway gateway,
            ConstraintGenerationRunRepository runs,
            ConstraintProposalParser parser,
            ConstraintProposalPrompt prompt,
            SemesterService semesters,
            TeacherRepository teachers,
            CourseRepository courses,
            AdminClassRepository adminClasses,
            PeriodRepository periods,
            SettingsService settings,
            UserService users,
            ObjectMapper mapper,
            PlatformTransactionManager transactionManager) {
        this.gateway = gateway;
        this.runs = runs;
        this.parser = parser;
        this.prompt = prompt;
        this.semesters = semesters;
        this.teachers = teachers;
        this.courses = courses;
        this.adminClasses = adminClasses;
        this.periods = periods;
        this.settings = settings;
        this.users = users;
        this.mapper = mapper;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** The entry stays visible in the page even when it cannot call a model yet. */
    @Transactional(readOnly = true)
    public AvailabilityDto availability() {
        if (!settings.aiNaturalLanguageConstraints()) {
            return new AvailabilityDto(false, "FEATURE_DISABLED",
                    "自然语言生成约束未开启。请在「设置」中开启该特性并绑定模型；手动配置始终可用。");
        }
        if (!gateway.configured(AiTaskType.NATURAL_LANGUAGE_CONSTRAINTS)) {
            return new AvailabilityDto(false, "NOT_CONFIGURED",
                    "自然语言约束任务还没有可用的 AI 供应商与模型。请先在「AI 供应商」中配置；手动配置始终可用。");
        }
        return new AvailabilityDto(true, null, null);
    }

    public RunDto generate(Long semesterId, GenerateRequest request) {
        Long resolved = semesters.require(semesterId).getId();
        String text = request.text() == null ? "" : request.text().trim();
        if (text.isEmpty()) {
            throw ApiException.badRequest("请先输入排课要求");
        }
        if (text.length() > MAX_INPUT_LENGTH) {
            throw ApiException.badRequest("要求原文最多 " + MAX_INPUT_LENGTH + " 字");
        }
        AppUser user = users.current();
        // A repeated click is the same run: return it instead of paying for a second call.
        var existing = runs.findByUserIdAndClientRunId(user.getId(), request.clientRunId());
        if (existing.isPresent()) {
            return toDto(existing.get());
        }
        AvailabilityDto availability = availability();
        if (!availability.available()) {
            throw ApiException.badRequest(availability.message());
        }

        Context context = buildContext(resolved);
        String contextJson = toJson(contextJson(context));
        ConstraintGenerationRun run = new ConstraintGenerationRun();
        run.setClientRunId(request.clientRunId());
        run.setUserId(user.getId());
        run.setSemesterId(resolved);
        run.setTaskType(AiTaskType.NATURAL_LANGUAGE_CONSTRAINTS.value());
        run.setStatus(ConstraintGenerationRun.RUNNING);
        run.setInputText(text);
        run.setContextJson(contextJson);
        run.setStartedAt(Instant.now());
        try {
            run = runs.saveAndFlush(run);
        } catch (DataIntegrityViolationException race) {
            // Two clicks with the same identity in flight: the first one owns the call.
            return runs.findByUserIdAndClientRunId(user.getId(), request.clientRunId())
                    .map(this::toDto)
                    .orElseThrow(() -> ApiException.conflict("同一生成请求正在处理中，请稍后查看结果"));
        }

        long started = System.nanoTime();
        AiResult result;
        try {
            result = gateway.chat(new AiRequest(
                    AiTaskType.NATURAL_LANGUAGE_CONSTRAINTS,
                    List.of(AiMessage.system(prompt.system()),
                            AiMessage.user(prompt.user(contextJson, text))),
                    List.of(),
                    new AiOptions(null, 8192, true)
                            .withLimits(TASK_TIMEOUT_SECONDS, 1))
                    .withSubject("CONSTRAINT_GENERATION", run.getId()));
        } catch (RuntimeException ex) {
            // The gateway contract already turns provider failures into results; this is
            // a last guard so a bug cannot leave the run RUNNING forever.
            finishFailure(run.getId(), "PROVIDER_ERROR", ex.getMessage(), null, null,
                    elapsedMs(started));
            return reload(run.getId());
        }
        long latencyMs = elapsedMs(started);
        return complete(run.getId(), result, context, latencyMs);
    }

    private RunDto complete(
            Long runId, AiResult result, Context context, long latencyMs) {
        if (!result.success()) {
            String status = "TIMEOUT".equals(result.errorCode())
                    || latencyMs > TASK_TIMEOUT_SECONDS * 1000L
                    ? ConstraintGenerationRun.TIMEOUT
                    : ConstraintGenerationRun.FAILED;
            finish(new ConstraintRunCompletion(
                    runId, status, result.rawResponse(), result.content(), null,
                    result.errorCode() == null ? "AI_ERROR" : result.errorCode(),
                    result.errorMessage() == null ? "AI 调用失败" : result.errorMessage(),
                    result.providerName(), result.modelName(), result.inputTokens(),
                    result.outputTokens(), latencyMs));
            return reload(runId);
        }
        List<ConstraintProposalDto> proposals;
        try {
            List<JsonNode> nodes = parser.parse(result.content());
            proposals = ConstraintProposalNormalizer.normalize(
                    nodes, context, "nl-" + runId);
        } catch (IllegalArgumentException ex) {
            finish(new ConstraintRunCompletion(
                    runId, ConstraintGenerationRun.FAILED, result.rawResponse(), result.content(),
                    null, "INVALID_OUTPUT", ex.getMessage(), result.providerName(),
                    result.modelName(), result.inputTokens(), result.outputTokens(), latencyMs));
            return reload(runId);
        }
        String status = latencyMs > TASK_TIMEOUT_SECONDS * 1000L
                ? ConstraintGenerationRun.TIMEOUT : ConstraintGenerationRun.SUCCEEDED;
        finish(new ConstraintRunCompletion(
                runId, status, result.rawResponse(), result.content(), toJson(proposals),
                null, null, result.providerName(), result.modelName(), result.inputTokens(),
                result.outputTokens(), latencyMs));
        return reload(runId);
    }

    private void finishFailure(
            Long runId, String code, String message, String raw, String provider, long latencyMs) {
        finish(new ConstraintRunCompletion(
                runId, ConstraintGenerationRun.FAILED, raw, null, null, code,
                message == null ? "AI 调用失败" : message, provider, null, null, null,
                latencyMs));
    }

    /**
     * Writes the terminal state only when the run is still RUNNING. A cancelled or
     * timed-out run keeps its status and files the answer as a late response.
     */
    private void finish(ConstraintRunCompletion completion) {
        transactions.executeWithoutResult(tx -> {
            int updated = runs.finishRunning(completion);
            if (updated == 0) {
                runs.recordLateResponse(
                        completion.runId(), completion.rawResponse(), completion.inputTokens(),
                        completion.outputTokens(), completion.latencyMs(), Instant.now());
            }
        });
    }

    /**
     * Re-runs one proposal through the same normalization after the user names a
     * homonym or supplies a missing condition. The model is not called again; the raw
     * run stays untouched and the returned proposal is what the page adds.
     */
    @Transactional(readOnly = true)
    public ConstraintProposalDto resolve(Long runId, ResolveProposalRequest request) {
        ConstraintGenerationRun run = requireReadable(runId);
        if (!ConstraintGenerationRun.SUCCEEDED.equals(run.getStatus())
                || run.getModelContent() == null || !StringUtils.hasText(run.getModelContent())) {
            throw ApiException.badRequest("该生成记录没有可重新校验的模型输出，请重新生成");
        }
        JsonNode root;
        try {
            root = mapper.readTree(run.getModelContent());
        } catch (Exception ex) {
            throw ApiException.badRequest("该生成记录的模型输出无法解析，请重新生成");
        }
        JsonNode array = root.isArray() ? root : root.path("proposals");
        int index = proposalIndex(request.proposalId());
        if (!array.isArray() || index < 0 || index >= array.size()) {
            throw ApiException.badRequest("建议不存在: " + request.proposalId());
        }
        ObjectNode node = array.get(index).deepCopy();
        applyResolve(run.getSemesterId(), node, request);
        List<ConstraintProposalDto> normalized = ConstraintProposalNormalizer.normalize(
                List.of(node), buildContext(run.getSemesterId()), "resolved-" + runId);
        return normalized.get(0);
    }

    private int proposalIndex(String proposalId) {
        if (proposalId == null || !proposalId.startsWith("p")) {
            return -1;
        }
        try {
            return Integer.parseInt(proposalId.substring(1)) - 1;
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private void applyResolve(
            Long semesterId, ObjectNode node, ResolveProposalRequest request) {
        Context context = buildContext(semesterId);
        // The user supplied the missing piece: let the normalizer validate the whole
        // proposal again instead of keeping the model's earlier NEEDS_INPUT verdict.
        node.put("status", "READY");
        node.remove("reason");
        if (request.teacherId() != null) {
            node.put("teacherName", teacherName(context, request.teacherId()));
        }
        if (request.courseId() != null) {
            node.put("courseName", courseName(context, request.courseId()));
        }
        if (request.classId() != null) {
            node.put("className", className(context, request.classId()));
        }
        if (request.strength() != null && StringUtils.hasText(request.strength())) {
            node.put("strength", request.strength().trim().toUpperCase());
        }
        if (request.days() != null) {
            node.set("days", mapper.valueToTree(request.days()));
            node.put("allDays", false);
        }
        if (request.periods() != null) {
            node.set("periods", mapper.valueToTree(request.periods()));
        }
        if (request.section() != null) {
            node.put("section", request.section());
        }
        if (request.sectionOrdinals() != null) {
            node.set("sectionOrdinals", mapper.valueToTree(request.sectionOrdinals()));
        }
        if (request.values() != null) {
            node.set("values", mapper.valueToTree(request.values()));
        }
    }

    private String teacherName(Context context, Long id) {
        return context.teachers().stream().filter(teacher -> teacher.id() == id)
                .map(teacher -> teacher.name()).findFirst()
                .orElseThrow(() -> ApiException.badRequest("教师不存在: " + id));
    }

    private String courseName(Context context, Long id) {
        return context.courses().stream().filter(course -> course.id() == id)
                .map(course -> course.name()).findFirst()
                .orElseThrow(() -> ApiException.badRequest("课程不存在: " + id));
    }

    private String className(Context context, Long id) {
        return context.classes().stream().filter(adminClass -> adminClass.id() == id)
                .map(adminClass -> adminClass.name()).findFirst()
                .orElseThrow(() -> ApiException.badRequest("行政班不存在: " + id));
    }

    @Transactional
    public RunDto cancel(Long runId) {
        ConstraintGenerationRun run = requireReadable(runId);
        if (ConstraintGenerationRun.RUNNING.equals(run.getStatus())) {
            runs.cancelRunning(runId, Instant.now());
        }
        return reload(runId);
    }

    /**
     * Cancels the click that is still waiting. The page does not know the numeric id
     * until the synchronous generate call returns, so the browser-generated identity
     * is the only handle it has while the provider is still answering.
     */
    @Transactional
    public RunDto cancelByClientRunId(String clientRunId) {
        AppUser user = users.current();
        ConstraintGenerationRun run = runs
                .findByUserIdAndClientRunId(user.getId(), clientRunId)
                .orElseThrow(() -> ApiException.notFound("生成记录不存在: " + clientRunId));
        if (ConstraintGenerationRun.RUNNING.equals(run.getStatus())) {
            runs.cancelRunning(run.getId(), Instant.now());
        }
        return reload(run.getId());
    }

    @Transactional(readOnly = true)
    public List<RunSummaryDto> list(Long semesterId, boolean all) {
        Long resolved = semesters.require(semesterId).getId();
        AppUser user = users.current();
        if (all && !isAdmin()) {
            throw ApiException.forbidden("只有设置管理员可以查看全局生成记录");
        }
        var page = PageRequest.of(0, MAX_PAGE);
        List<ConstraintGenerationRun> rows = all
                ? runs.findBySemesterIdOrderByStartedAtDesc(resolved, page)
                : runs.findByUserIdAndSemesterIdOrderByStartedAtDesc(
                        user.getId(), resolved, page);
        return rows.stream().map(this::toSummary).toList();
    }

    @Transactional(readOnly = true)
    public RunDetailDto detail(Long runId) {
        ConstraintGenerationRun run = requireReadable(runId);
        return new RunDetailDto(toDto(run), run.getRawResponse(), run.getModelContent(),
                run.getLateResponse(), run.getContextJson());
    }

    // ------------------------------------------------------------------ records

    private ConstraintGenerationRun requireReadable(Long runId) {
        ConstraintGenerationRun run = runs.findById(runId)
                .orElseThrow(() -> ApiException.notFound("生成记录不存在: " + runId));
        AppUser user = users.current();
        if (!isAdmin() && !user.getId().equals(run.getUserId())) {
            throw ApiException.forbidden("不能查看或操作他人的生成记录");
        }
        return run;
    }

    private RunDto reload(Long runId) {
        ConstraintGenerationRun run = runs.findById(runId)
                .orElseThrow(() -> ApiException.notFound("生成记录不存在: " + runId));
        // A run left RUNNING by a process restart is reported as timed out once the
        // grace window has passed, instead of looking alive forever.
        if (ConstraintGenerationRun.RUNNING.equals(run.getStatus())
                && run.getStartedAt() != null
                && Duration.between(run.getStartedAt(), Instant.now()).getSeconds()
                > TASK_TIMEOUT_SECONDS + 60L) {
            transactions.executeWithoutResult(tx ->
                    runs.expireRunning(runId, "生成超时，请重试", Instant.now()));
            run = runs.findById(runId).orElseThrow();
        }
        return toDto(run);
    }

    private RunDto toDto(ConstraintGenerationRun run) {
        return new RunDto(
                run.getId(), run.getClientRunId(), run.getSemesterId(), run.getStatus(),
                run.getInputText(), run.getErrorCode(), run.getErrorMessage(),
                run.getProviderName(), run.getModelName(), run.getInputTokens(),
                run.getOutputTokens(), run.getLatencyMs(), run.getStartedAt(),
                run.getFinishedAt(), proposalsOf(run));
    }

    private List<ConstraintProposalDto> proposalsOf(ConstraintGenerationRun run) {
        if (run.getProposalsJson() == null || !StringUtils.hasText(run.getProposalsJson())) {
            return List.of();
        }
        try {
            return mapper.readValue(run.getProposalsJson(),
                    new TypeReference<List<ConstraintProposalDto>>() {});
        } catch (Exception ex) {
            return List.of();
        }
    }

    private RunSummaryDto toSummary(ConstraintGenerationRun run) {
        return new RunSummaryDto(run.getId(), run.getStatus(), run.getInputText(),
                run.getErrorCode(), run.getErrorMessage(), run.getStartedAt(),
                run.getFinishedAt(), proposalsOf(run).size());
    }

    // ------------------------------------------------------------------ context

    private Context buildContext(Long semesterId) {
        List<TeacherRef> teacherRefs = new ArrayList<>();
        for (Teacher teacher : teachers.findAll()) {
            teacherRefs.add(new TeacherRef(teacher.getId(), teacher.getName(), teacher.getSubject()));
        }
        teacherRefs.sort(java.util.Comparator.comparing(TeacherRef::id));
        List<CourseRef> courseRefs = new ArrayList<>();
        for (Course course : courses.findAll()) {
            courseRefs.add(new CourseRef(course.getId(), course.getName(), course.getSubject(),
                    course.getRequiredRoomType()));
        }
        courseRefs.sort(java.util.Comparator.comparing(CourseRef::id));
        List<ClassRef> classRefs = new ArrayList<>();
        for (AdminClass adminClass : adminClasses.findBySemesterIdOrderByGradeIdAscNameAsc(semesterId)) {
            classRefs.add(new ClassRef(adminClass.getId(), adminClass.getName()));
        }
        Map<Integer, Map<com.paiban.foundation.domain.PeriodSection, List<Integer>>> timetable =
                new TreeMap<>();
        for (Period period : periods.findBySemesterIdOrderByDayOfWeekAscPeriodNoAsc(semesterId)) {
            timetable
                    .computeIfAbsent(period.getDayOfWeek(), key -> new LinkedHashMap<>())
                    .computeIfAbsent(period.getSection(), key -> new ArrayList<>())
                    .add(period.getPeriodNo());
        }
        timetable.values().forEach(sections -> sections.values()
                .forEach(numbers -> numbers.sort(Integer::compareTo)));
        return new Context(teacherRefs, courseRefs, classRefs, timetable);
    }

    /** The model sees names, subjects and the real timetable — never internal ids. */
    private Map<String, Object> contextJson(Context context) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("teachers", context.teachers().stream()
                .map(teacher -> Map.of("name", teacher.name(),
                        "subject", teacher.subject() == null ? "" : teacher.subject()))
                .toList());
        json.put("courses", context.courses().stream()
                .map(course -> Map.of("name", course.name(),
                        "subject", course.subject() == null ? "" : course.subject(),
                        "requiredRoomType",
                        course.requiredRoomType() == null ? "" : course.requiredRoomType()))
                .toList());
        json.put("classes", context.classes().stream()
                .map(adminClass -> Map.of("name", adminClass.name()))
                .toList());
        Map<String, Object> timetable = new LinkedHashMap<>();
        context.timetable().forEach((day, sections) -> {
            Map<String, List<Integer>> bySection = new LinkedHashMap<>();
            sections.forEach((section, numbers) -> bySection.put(section.getLabel(), numbers));
            timetable.put(String.valueOf(day), bySection);
        });
        json.put("timetable", timetable);
        return json;
    }

    // ------------------------------------------------------------------ identity

    private boolean isAdmin() {
        return users.hasPermission(Permissions.SETTINGS_MANAGE);
    }

    private long elapsedMs(long startedNanos) {
        return Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("序列化生成记录失败", ex);
        }
    }
}
