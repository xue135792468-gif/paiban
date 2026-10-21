package com.paiban.ai.service;

import com.paiban.ai.AiGateway;
import com.paiban.ai.AiRequest;
import com.paiban.ai.AiResult;
import com.paiban.ai.AiTaskType;
import com.paiban.ai.domain.AiProvider;
import com.paiban.ai.strategy.AiInvocation;
import com.paiban.ai.strategy.AiStrategy;
import com.paiban.ai.strategy.AiStrategyOutcome;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Default {@link AiGateway}: resolves the task's provider/model, picks the protocol
 * strategy, retries transient failures and always answers with a structured result.
 *
 * <p>Timeout is owned by the strategies (default 120s). A failed attempt is retried
 * twice, 1s apart, except when the provider answered with a 4xx that is not 429 —
 * retrying a rejected request only wastes time — and except on timeout, where one
 * retry is enough because each attempt already waited a full timeout window. Every
 * call, successful or not, is written to {@code ai_call_log} and no exception ever
 * escapes this class.
 */
@Service
public class AiGatewayImpl implements AiGateway {

    private static final int MAX_ATTEMPTS = 3;
    private static final int TIMEOUT_MAX_ATTEMPTS = 2;

    private final AiConfigResolver resolver;
    private final List<AiStrategy> strategies;
    private final AiCallLogWriter callLogWriter;
    private final long retryDelayMs;

    public AiGatewayImpl(
            AiConfigResolver resolver,
            List<AiStrategy> strategies,
            AiCallLogWriter callLogWriter,
            @Value("${paiban.ai.retry-delay-ms:1000}") long retryDelayMs) {
        this.resolver = resolver;
        this.strategies = strategies;
        this.callLogWriter = callLogWriter;
        this.retryDelayMs = retryDelayMs;
    }

    @Override
    public AiResult chat(AiRequest request) {
        return execute(request, false);
    }

    @Override
    public AiResult chatWithVision(AiRequest request) {
        return execute(request, true);
    }

    @Override
    public boolean configured() {
        return resolver.configured();
    }

    @Override
    public boolean configured(AiTaskType taskType) {
        return resolver.resolve(taskType).isPresent();
    }

    private AiResult execute(AiRequest request, boolean vision) {
        long started = System.nanoTime();
        if (request == null || request.taskType() == null) {
            callLogWriter.recordInvalid("AI 请求缺少任务类型");
            return new AiResult(
                    false, null, "INVALID_REQUEST", "AI 请求缺少任务类型",
                    null, null, null, null, null, null, elapsed(started), null);
        }
        if (request.messages() == null || request.messages().isEmpty()) {
            AiResult invalid = new AiResult(
                    false, null, "INVALID_REQUEST", "AI 请求缺少消息",
                    null, null, null, null, null, null, elapsed(started), null);
            callLogWriter.record(request, null, invalid);
            return invalid;
        }
        AiRequest effective = vision ? request : withoutImages(request);

        AiTarget target = resolver.resolve(request.taskType()).orElse(null);
        if (target == null) {
            return finish(
                    request,
                    null,
                    failure(null, "NOT_CONFIGURED", "该任务未配置可用的 AI 供应商与模型", elapsed(started)));
        }
        if (vision && !target.model().isVisionCapable()) {
            return finish(
                    request,
                    target,
                    failure(
                            target,
                            "VISION_NOT_SUPPORTED",
                            "模型 " + target.model().getModelName() + " 不支持图片输入",
                            elapsed(started)));
        }
        AiStrategy strategy = select(target.provider());
        if (strategy == null) {
            return finish(
                    request,
                    target,
                    failure(target, "PROVIDER_ERROR", "没有可用的 AI 协议实现", elapsed(started)));
        }

        AiStrategyOutcome outcome = null;
        int maxAttempts = MAX_ATTEMPTS;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            outcome = invoke(strategy, new AiInvocation(
                    target.provider(), target.apiKey(), target.model(), effective));
            if (outcome.success() || !retryable(outcome)) {
                break;
            }
            if (timedOut(outcome)) {
                maxAttempts = Math.min(maxAttempts, TIMEOUT_MAX_ATTEMPTS);
            }
            if (attempt < maxAttempts && !sleepBeforeRetry()) {
                break;
            }
        }

        long latencyMs = elapsed(started);
        AiResult result = outcome.success()
                ? new AiResult(
                        true,
                        outcome.content(),
                        null,
                        null,
                        target.provider().getId(),
                        target.provider().getName(),
                        target.model().getId(),
                        target.model().getModelName(),
                        outcome.inputTokens(),
                        outcome.outputTokens(),
                        latencyMs,
                        outcome.rawResponse())
                : failure(
                        target,
                        outcome.errorCode() == null ? "PROVIDER_ERROR" : outcome.errorCode(),
                        outcome.errorMessage(),
                        latencyMs);
        return finish(request, target, result);
    }

    /** The contract keeps {@code chat()} text-only even when the request carries images. */
    private AiRequest withoutImages(AiRequest request) {
        if (request.images() == null || request.images().isEmpty()) {
            return request;
        }
        return new AiRequest(
                request.taskType(), request.messages(), List.of(), request.options(),
                request.subjectType(), request.subjectId());
    }

    private AiStrategy select(AiProvider provider) {
        for (AiStrategy candidate : strategies) {
            if (!candidate.fallback() && candidate.supports(provider)) {
                return candidate;
            }
        }
        for (AiStrategy candidate : strategies) {
            if (candidate.supports(provider)) {
                return candidate;
            }
        }
        return null;
    }

    private AiStrategyOutcome invoke(AiStrategy strategy, AiInvocation invocation) {
        try {
            AiStrategyOutcome outcome = strategy.invoke(invocation);
            return outcome == null
                    ? AiStrategyOutcome.failure("PROVIDER_ERROR", "AI 策略未返回结果", null)
                    : outcome;
        } catch (RuntimeException ex) {
            return AiStrategyOutcome.failure(
                    "PROVIDER_ERROR", "AI 调用异常: " + ex.getClass().getSimpleName(), null);
        }
    }

    private boolean retryable(AiStrategyOutcome outcome) {
        Integer status = outcome.httpStatus();
        if (status == null) {
            return true;
        }
        if (status == 429) {
            return true;
        }
        return status >= 500;
    }

    private boolean timedOut(AiStrategyOutcome outcome) {
        return "TIMEOUT".equals(outcome.errorCode());
    }

    /** Returns false when the thread was interrupted and the retry loop must stop. */
    private boolean sleepBeforeRetry() {
        if (retryDelayMs <= 0) {
            return !Thread.currentThread().isInterrupted();
        }
        try {
            Thread.sleep(retryDelayMs);
            return true;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private AiResult failure(AiTarget target, String errorCode, String errorMessage, long latencyMs) {
        return new AiResult(
                false,
                null,
                errorCode,
                errorMessage,
                target == null ? null : target.provider().getId(),
                target == null ? null : target.provider().getName(),
                target == null ? null : target.model().getId(),
                target == null ? null : target.model().getModelName(),
                null,
                null,
                latencyMs,
                null);
    }

    private AiResult finish(AiRequest request, AiTarget target, AiResult result) {
        callLogWriter.record(request, target, result);
        return result;
    }

    private long elapsed(long started) {
        return (System.nanoTime() - started) / 1_000_000L;
    }
}
