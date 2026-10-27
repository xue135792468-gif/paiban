package com.paiban.scheduling.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paiban.ai.AiGateway;
import com.paiban.ai.AiMessage;
import com.paiban.ai.AiOptions;
import com.paiban.ai.AiRequest;
import com.paiban.ai.AiResult;
import com.paiban.ai.AiTaskType;
import com.paiban.ai.domain.AiTaskConfig;
import com.paiban.ai.domain.AiTaskConfigRepository;
import com.paiban.ai.service.AiConfigResolver;
import com.paiban.scheduling.eval.ConstraintEvaluationCorpus.Sentence;
import com.paiban.scheduling.service.ConstraintProposalNormalizer.Context;
import com.paiban.scheduling.web.ConstraintProposalDtos.ConstraintProposalDto;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Runs C01–C22 against up to two explicitly named models and writes a report (ticket
 * 14/09). It is a manual tool: the CLI only starts when {@code paiban.eval.enabled} is
 * set, no HTTP endpoint reaches it, and CI never calls a real provider.
 *
 * <p>Before any call the round needs a reliable per-1K input/output price; without one
 * it writes a blocked report and returns. Each call reserves its worst-case cost, so
 * the shared 20-CNY budget cannot be exceeded by retries, failures or timeouts.
 */
@Service
public class ConstraintEvaluationRunner {

    public static final BigDecimal DEFAULT_BUDGET = BigDecimal.valueOf(20);
    public static final int DEFAULT_RUNS = 3;
    private static final int REQUIRED_SUPPORTED_PASSES = 52;
    private static final int REQUIRED_BOUNDARY_PASSES = 12;

    public record ModelRef(Long providerId, Long modelId, String label) {}

    public record Request(
            Path output,
            BigDecimal budgetCny,
            BigDecimal priceInPer1k,
            BigDecimal priceOutPer1k,
            int runsPerSentence,
            List<ModelRef> models,
            boolean stub,
            boolean bindWinner) {}

    public record RunRow(
            String code, int attempt, boolean supported, boolean passed, String reason,
            String content, List<ConstraintProposalDto> proposals,
            Integer inputTokens, Integer outputTokens, long latencyMs) {}

    public record ModelReport(
            ModelRef model, int supportedPass, int supportedTotal,
            int boundaryPass, int boundaryTotal, boolean passed, boolean complete,
            BigDecimal reservedCny, BigDecimal actualCny, String blockedReason,
            List<RunRow> runs) {}

    public record Report(
            String corpusVersion, String generatedAt, int runsPerSentence,
            BigDecimal budgetCny, BigDecimal priceInPer1k, BigDecimal priceOutPer1k,
            String verdict, String blockedReason, BigDecimal reservedCny,
            BigDecimal actualCny, BigDecimal remainingCny, List<ModelReport> models) {}

    private final AiGateway gateway;
    private final ConstraintEvaluationHarness harness;
    private final AiTaskConfigRepository taskConfigs;
    private final AiConfigResolver resolver;
    private final ObjectMapper mapper;

    public ConstraintEvaluationRunner(
            AiGateway gateway,
            ConstraintEvaluationHarness harness,
            AiTaskConfigRepository taskConfigs,
            AiConfigResolver resolver,
            ObjectMapper mapper) {
        this.gateway = gateway;
        this.harness = harness;
        this.taskConfigs = taskConfigs;
        this.resolver = resolver;
        this.mapper = mapper;
    }

    public Report run(Request request) {
        int runs = DEFAULT_RUNS;
        BigDecimal budget = request.budgetCny() == null ? DEFAULT_BUDGET : request.budgetCny();
        if (request.runsPerSentence() != DEFAULT_RUNS) {
            return write(request, new Report(ConstraintEvaluationCorpus.VERSION,
                    Instant.now().toString(), DEFAULT_RUNS, budget, request.priceInPer1k(),
                    request.priceOutPer1k(), "INVALID_REQUEST",
                    "固定语料每句必须重复 3 次；不能改变重复次数来凑门槛",
                    BigDecimal.ZERO, BigDecimal.ZERO, budget, List.of()));
        }
        if (budget.compareTo(DEFAULT_BUDGET) > 0) {
            return write(request, new Report(ConstraintEvaluationCorpus.VERSION,
                    Instant.now().toString(), runs, DEFAULT_BUDGET, request.priceInPer1k(),
                    request.priceOutPer1k(), "INVALID_REQUEST",
                    "本轮评测总预算不得超过 20 元；不能提高预算来凑评测",
                    BigDecimal.ZERO, BigDecimal.ZERO, DEFAULT_BUDGET, List.of()));
        }
        if (!ConstraintEvaluationBudget.reliable(
                request.priceInPer1k(), request.priceOutPer1k())) {
            return write(request, new Report(ConstraintEvaluationCorpus.VERSION,
                    Instant.now().toString(), runs, budget, request.priceInPer1k(),
                    request.priceOutPer1k(), "PRICE_UNRELIABLE",
                    "单价或换算口径不可靠：未发出任何模型请求，请先确认每 1K 输入/输出的可靠价格",
                    BigDecimal.ZERO, BigDecimal.ZERO, budget, List.of()));
        }
        List<ModelRef> models = request.models();
        if (models.isEmpty()) {
            if (!request.stub()) {
                return write(request, new Report(ConstraintEvaluationCorpus.VERSION,
                        Instant.now().toString(), runs, budget, request.priceInPer1k(),
                        request.priceOutPer1k(), "NO_MODEL",
                        "没有指定候选模型；请用 --model providerId:modelId 明确选择",
                        BigDecimal.ZERO, BigDecimal.ZERO, budget, List.of()));
            }
            models = List.of(new ModelRef(0L, 0L, "stub"));
        }
        if (models.size() > 2) {
            return write(request, new Report(ConstraintEvaluationCorpus.VERSION,
                    Instant.now().toString(), runs, budget, request.priceInPer1k(),
                    request.priceOutPer1k(), "INVALID_REQUEST",
                    "最多比较两个候选模型", BigDecimal.ZERO, BigDecimal.ZERO, budget, List.of()));
        }
        ConstraintEvaluationBudget guard = new ConstraintEvaluationBudget(
                budget, request.priceInPer1k(), request.priceOutPer1k());
        List<ModelReport> reports = new ArrayList<>();
        String verdict = "FAILED";
        String blockedReason = null;
        boolean keepBound = false;
        OriginalConfig original = null;
        try {
            for (int index = 0; index < models.size(); index++) {
                ModelRef model = models.get(index);
                original = request.stub() ? null : bind(model);
                ModelReport outcome = evaluateModel(request, guard, model, runs);
                reports.add(outcome);
                if (outcome.passed()) {
                    verdict = "PASSED";
                    keepBound = request.bindWinner();
                    if (!keepBound && original != null) {
                        restore(original);
                        original = null;
                    }
                    break;
                }
                if (!outcome.complete()) {
                    verdict = "INCOMPLETE";
                    blockedReason = outcome.blockedReason();
                    break;
                }
                if (original != null) {
                    restore(original);
                    original = null;
                }
            }
        } finally {
            if (!keepBound && original != null) {
                restore(original);
            }
        }
        Report report = new Report(ConstraintEvaluationCorpus.VERSION,
                Instant.now().toString(), runs, budget, request.priceInPer1k(),
                request.priceOutPer1k(), verdict, blockedReason, guard.reserved(),
                guard.actual(), guard.remaining(), reports);
        return write(request, report);
    }

    private ModelReport evaluateModel(
            Request request, ConstraintEvaluationBudget guard, ModelRef model, int runs) {
        List<RunRow> rows = new ArrayList<>();
        int supportedPass = 0;
        int supportedTotal = 0;
        int boundaryPass = 0;
        int boundaryTotal = 0;
        boolean complete = true;
        String blockedReason = null;
        List<Sentence> sentences = ConstraintEvaluationCorpus.sentences();
        outer:
        for (Sentence sentence : sentences) {
            ConstraintEvaluationHarness.PromptText prompt = harness.prompt(sentence);
            for (int attempt = 1; attempt <= runs; attempt++) {
                BigDecimal worst = guard.worstCaseCny(prompt.system(), prompt.user());
                if (!guard.canAfford(worst)) {
                    complete = false;
                    blockedReason = "BUDGET_EXHAUSTED";
                    break outer;
                }
                guard.reserve(worst);
                AiResult result = request.stub()
                        ? stubResult(sentence, prompt)
                        : gateway.chat(new AiRequest(
                                AiTaskType.NATURAL_LANGUAGE_CONSTRAINTS,
                                List.of(AiMessage.system(prompt.system()),
                                        AiMessage.user(prompt.user())),
                                List.of(),
                                new AiOptions(null, 8192, true).withLimits(60, 1)));
                ConstraintEvaluationHarness.Attempt evaluated = harness.evaluate(sentence, result);
                guard.recordActual(result.inputTokens(), result.outputTokens(), worst);
                boolean passed = evaluated.verdict().passed();
                if (sentence.supported()) {
                    supportedTotal++;
                    if (passed) {
                        supportedPass++;
                    }
                } else {
                    boundaryTotal++;
                    if (passed) {
                        boundaryPass++;
                    }
                }
                rows.add(new RunRow(sentence.code(), attempt, sentence.supported(), passed,
                        evaluated.verdict().reason(), evaluated.content(),
                        evaluated.proposals(), result.inputTokens(), result.outputTokens(),
                        result.latencyMs()));
            }
        }
        boolean passed = complete && supportedPass >= REQUIRED_SUPPORTED_PASSES
                && boundaryPass >= REQUIRED_BOUNDARY_PASSES;
        return new ModelReport(model, supportedPass, supportedTotal,
                boundaryPass, boundaryTotal, passed, complete, guard.reserved(),
                guard.actual(), blockedReason, rows);
    }

    private AiResult stubResult(
            Sentence sentence, ConstraintEvaluationHarness.PromptText prompt) {
        String answer = ConstraintEvaluationStub.answer(sentence);
        return new AiResult(true, answer, null, null, 0L, "stub", 0L, "stub",
                prompt.system().length() + prompt.user().length(), answer.length(),
                1L, answer);
    }

    // ------------------------------------------------------------------ binding

    private record OriginalConfig(boolean existed, String mode, Long providerId, Long modelId) {}

    private OriginalConfig bind(ModelRef model) {
        Optional<AiTaskConfig> existing = taskConfigs.findByTaskType(
                AiTaskType.NATURAL_LANGUAGE_CONSTRAINTS);
        OriginalConfig original = existing
                .map(config -> new OriginalConfig(true, config.getMode(),
                        config.getProviderId(), config.getModelId()))
                .orElseGet(() -> new OriginalConfig(false, null, null, null));
        AiTaskConfig config = existing.orElseGet(AiTaskConfig::new);
        config.setTaskType(AiTaskType.NATURAL_LANGUAGE_CONSTRAINTS);
        config.setMode("custom");
        config.setProviderId(model.providerId());
        config.setModelId(model.modelId());
        config.setUpdatedAt(Instant.now());
        taskConfigs.save(config);
        resolver.invalidate();
        return original;
    }

    private void restore(OriginalConfig original) {
        Optional<AiTaskConfig> current = taskConfigs.findByTaskType(
                AiTaskType.NATURAL_LANGUAGE_CONSTRAINTS);
        if (!original.existed()) {
            current.ifPresent(taskConfigs::delete);
            resolver.invalidate();
            return;
        }
        current.ifPresent(config -> {
            config.setMode(original.mode());
            config.setProviderId(original.providerId());
            config.setModelId(original.modelId());
            config.setUpdatedAt(Instant.now());
            taskConfigs.save(config);
        });
        resolver.invalidate();
    }

    // ------------------------------------------------------------------ report

    private Report write(Request request, Report report) {
        try {
            Path base = request.output();
            if (base == null) {
                base = Path.of("constraint-eval-report");
            }
            Path parent = base.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            String name = base.getFileName().toString();
            if (name.endsWith(".json") || name.endsWith(".md")) {
                name = name.substring(0, name.lastIndexOf('.'));
            }
            Path directory = parent == null ? Path.of("") : parent;
            Files.writeString(directory.resolve(name + ".json"),
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report));
            Files.writeString(directory.resolve(name + ".md"), markdown(report));
            return report;
        } catch (IOException ex) {
            throw new IllegalStateException("写入评测报告失败", ex);
        }
    }

    private String markdown(Report report) {
        StringBuilder out = new StringBuilder();
        out.append("# 自然语言约束模型评测\n\n");
        out.append("- 语料版本：").append(report.corpusVersion()).append("\n");
        out.append("- 生成时间：").append(report.generatedAt()).append("\n");
        out.append("- 每句次数：").append(report.runsPerSentence()).append("\n");
        out.append("- 结论：").append(report.verdict()).append("\n");
        if (report.blockedReason() != null) {
            out.append("- 阻塞原因：").append(report.blockedReason()).append("\n");
        }
        out.append("- 预算：").append(report.budgetCny()).append(" 元；已预留 ")
                .append(report.reservedCny()).append(" 元；按用量 ")
                .append(report.actualCny()).append(" 元；剩余 ")
                .append(report.remainingCny()).append(" 元\n");
        out.append("- 明确支持门槛：54 次中至少 52 次整句正确；边界 12 次零误转\n\n");
        for (ModelReport model : report.models()) {
            out.append("## ").append(model.model().label()).append("\n\n");
            out.append("- 明确支持：").append(model.supportedPass()).append("/")
                    .append(model.supportedTotal()).append(" 通过\n");
            out.append("- 边界：").append(model.boundaryPass()).append("/")
                    .append(model.boundaryTotal()).append(" 零误转\n");
            out.append("- 结论：").append(model.passed() ? "达标" : "未达标")
                    .append(model.complete() ? "" : "（未跑完）").append("\n\n");
            out.append("| 句子 | 次数 | 结果 | 原因 |\n|---|---|---|---|\n");
            for (RunRow row : model.runs()) {
                out.append("| ").append(row.code()).append(" | ").append(row.attempt())
                        .append(" | ").append(row.passed() ? "通过" : "失败")
                        .append(" | ").append(oneLine(row.reason())).append(" |\n");
            }
            out.append("\n### 失败明细\n\n");
            for (RunRow row : model.runs()) {
                if (row.passed()) {
                    continue;
                }
                out.append("#### ").append(row.code()).append(" 第").append(row.attempt())
                        .append("次\n\n");
                out.append("原因：").append(row.reason()).append("\n\n");
                out.append("原始输出（截断展示，完整内容见 JSON）：\n```\n")
                        .append(truncate(row.content(), 2000)).append("\n```\n\n");
            }
        }
        return out.toString();
    }

    private String oneLine(String value) {
        return value == null ? "" : value.replace("|", "\\|").replace("\n", " ");
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "\n…（已截断）";
    }
}
