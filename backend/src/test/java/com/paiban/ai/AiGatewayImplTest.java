package com.paiban.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paiban.ai.crypto.ApiKeyCipher;
import com.paiban.ai.domain.AiCallLog;
import com.paiban.ai.domain.AiCallLogRepository;
import com.paiban.ai.domain.AiModel;
import com.paiban.ai.domain.AiModelRepository;
import com.paiban.ai.domain.AiProvider;
import com.paiban.ai.domain.AiProviderRepository;
import com.paiban.ai.domain.AiTaskConfig;
import com.paiban.ai.domain.AiTaskConfigRepository;
import com.paiban.ai.service.AiCallLogWriter;
import com.paiban.ai.service.AiConfigResolver;
import com.paiban.ai.service.AiGatewayImpl;
import com.paiban.ai.strategy.AiInvocation;
import com.paiban.ai.strategy.AiStrategy;
import com.paiban.ai.strategy.AiStrategyOutcome;
import com.paiban.identity.domain.AppUser;
import com.paiban.identity.service.UserService;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Gateway routing, retry and fallback behaviour with a fake strategy and mocked
 * repositories: no Spring context, no network.
 */
class AiGatewayImplTest {

    private static final String SECRET = "sk-unit-test-secret-1234";

    private final AiTaskConfigRepository taskConfigs = mock(AiTaskConfigRepository.class);
    private final AiProviderRepository providers = mock(AiProviderRepository.class);
    private final AiModelRepository models = mock(AiModelRepository.class);
    private final AiCallLogRepository callLogs = mock(AiCallLogRepository.class);
    private final UserService users = mock(UserService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ApiKeyCipher cipher = new ApiKeyCipher("unit-test-encryption-key");
    private final FakeStrategy strategy = new FakeStrategy();

    private final Map<AiTaskType, AiTaskConfig> configs = new EnumMap<>(AiTaskType.class);
    private final Map<Long, AiProvider> providerRows = new HashMap<>();
    private final Map<Long, AiModel> modelRows = new HashMap<>();

    private AiGatewayImpl gateway;
    private AiConfigResolver resolver;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        when(taskConfigs.findByTaskType(any()))
                .thenAnswer(invocation -> Optional.ofNullable(configs.get(invocation.getArgument(0))));
        when(providers.findById(any()))
                .thenAnswer(invocation -> Optional.ofNullable(providerRows.get(invocation.getArgument(0))));
        when(models.findById(any()))
                .thenAnswer(invocation -> Optional.ofNullable(modelRows.get(invocation.getArgument(0))));

        resolver = new AiConfigResolver(taskConfigs, providers, models, cipher);
        gateway = new AiGatewayImpl(
                resolver,
                List.of(strategy),
                new AiCallLogWriter(callLogs, users, objectMapper),
                0L);
    }

    @Test
    void routesToTheModelBoundToTheTaskType() {
        AiProvider first = provider(1L, "一号", "http://127.0.0.1:18081/v1");
        AiModel firstModel = model(10L, 1L, "deepseek-chat", false);
        AiProvider second = provider(2L, "二号", "http://127.0.0.1:18082/v1");
        AiModel secondModel = model(20L, 2L, "gemini-2.0-flash", false);
        assign(AiTaskType.DATA_IMPORT, first, firstModel);
        assign(AiTaskType.SUBSTITUTION_RECOMMEND, second, secondModel);

        AiResult result = gateway.chat(request(AiTaskType.SUBSTITUTION_RECOMMEND));

        assertThat(result.success()).isTrue();
        assertThat(result.content()).isEqualTo("ok");
        assertThat(result.providerId()).isEqualTo(2L);
        assertThat(result.providerName()).isEqualTo("二号");
        assertThat(result.modelId()).isEqualTo(20L);
        assertThat(result.modelName()).isEqualTo("gemini-2.0-flash");
        assertThat(result.inputTokens()).isEqualTo(11);
        assertThat(result.outputTokens()).isEqualTo(7);
        assertThat(strategy.invocations).isEqualTo(1);
        assertThat(strategy.lastInvocation.model().getModelName()).isEqualTo("gemini-2.0-flash");
        assertThat(strategy.lastInvocation.provider().getBaseUrl()).isEqualTo("http://127.0.0.1:18082/v1");
        assertThat(strategy.lastInvocation.apiKey()).isEqualTo(SECRET);
    }

    @Test
    void chatDropsImagesEvenWhenTheRequestCarriesThem() {
        assign(AiTaskType.DATA_IMPORT, provider(1L, "一号", "http://x/v1"), model(10L, 1L, "vision-model", true));

        AiResult result = gateway.chat(new AiRequest(
                AiTaskType.DATA_IMPORT,
                List.of(new AiMessage("user", "看图")),
                List.of(new AiImage("image/png", new byte[] {1, 2, 3})),
                null));

        assertThat(result.success()).isTrue();
        assertThat(strategy.lastInvocation.request().images()).isEmpty();
    }

    @Test
    void visionIsRejectedWhenModelIsNotVisionCapable() {
        assign(AiTaskType.DATA_IMPORT, provider(1L, "一号", "http://x/v1"), model(10L, 1L, "text-only", false));

        AiResult result = gateway.chatWithVision(visionRequest());

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("VISION_NOT_SUPPORTED");
        assertThat(result.errorMessage()).isNotBlank();
        assertThat(strategy.invocations).isZero();

        AiCallLog logged = captureLog();
        assertThat(logged.getTaskType()).isEqualTo("data_import");
        assertThat(logged.isSuccess()).isFalse();
        assertThat(logged.getErrorCode()).isEqualTo("VISION_NOT_SUPPORTED");
        assertThat(logged.getModelId()).isEqualTo(10L);
    }

    @Test
    void visionIsForwardedWhenModelIsVisionCapable() {
        assign(AiTaskType.DATA_IMPORT, provider(1L, "一号", "http://x/v1"), model(10L, 1L, "vision-model", true));

        AiResult result = gateway.chatWithVision(visionRequest());

        assertThat(result.success()).isTrue();
        assertThat(strategy.lastInvocation.request().images()).hasSize(1);
        assertThat(strategy.lastInvocation.request().images().get(0).mimeType()).isEqualTo("image/png");
    }

    @Test
    void retriesTwiceThenReturnsStructuredFailure() {
        assign(AiTaskType.DATA_IMPORT, provider(1L, "一号", "http://x/v1"), model(10L, 1L, "m", false));
        strategy.script(
                AiStrategyOutcome.failure("PROVIDER_ERROR", "服务端错误", 500),
                AiStrategyOutcome.failure("PROVIDER_ERROR", "服务端错误", 500),
                AiStrategyOutcome.failure("PROVIDER_ERROR", "服务端错误", 500));

        AiResult result = gateway.chat(request(AiTaskType.DATA_IMPORT));

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("PROVIDER_ERROR");
        assertThat(result.latencyMs()).isGreaterThanOrEqualTo(0);
        assertThat(strategy.invocations).isEqualTo(3);
        assertThat(captureLog().getErrorCode()).isEqualTo("PROVIDER_ERROR");
    }

    @Test
    void doesNotRetryOnClientError() {
        assign(AiTaskType.DATA_IMPORT, provider(1L, "一号", "http://x/v1"), model(10L, 1L, "m", false));
        strategy.script(AiStrategyOutcome.failure("PROVIDER_ERROR", "请求无效", 400));

        AiResult result = gateway.chat(request(AiTaskType.DATA_IMPORT));

        assertThat(result.success()).isFalse();
        assertThat(strategy.invocations).isEqualTo(1);
    }

    @Test
    void retriesOnTooManyRequests() {
        assign(AiTaskType.DATA_IMPORT, provider(1L, "一号", "http://x/v1"), model(10L, 1L, "m", false));
        strategy.script(
                AiStrategyOutcome.failure("PROVIDER_ERROR", "限流", 429),
                AiStrategyOutcome.success("finally ok", 1, 2, "{}"));

        AiResult result = gateway.chat(request(AiTaskType.DATA_IMPORT));

        assertThat(result.success()).isTrue();
        assertThat(result.content()).isEqualTo("finally ok");
        assertThat(strategy.invocations).isEqualTo(2);
    }

    @Test
    void timeoutIsReportedAsTimeout() {
        assign(AiTaskType.DATA_IMPORT, provider(1L, "一号", "http://x/v1"), model(10L, 1L, "m", false));
        strategy.script(
                AiStrategyOutcome.failure("TIMEOUT", "AI 调用超时（120 秒）", null),
                AiStrategyOutcome.failure("TIMEOUT", "AI 调用超时（120 秒）", null),
                AiStrategyOutcome.failure("TIMEOUT", "AI 调用超时（120 秒）", null));

        AiResult result = gateway.chat(request(AiTaskType.DATA_IMPORT));

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("TIMEOUT");
        // A timed-out request already waited a full timeout window, so it is tried twice, not three times.
        assertThat(strategy.invocations).isEqualTo(2);
        assertThat(captureLog().getErrorCode()).isEqualTo("TIMEOUT");
    }

    @Test
    void timeoutStillSucceedsWhenTheSecondAttemptWorks() {
        assign(AiTaskType.DATA_IMPORT, provider(1L, "一号", "http://x/v1"), model(10L, 1L, "m", false));
        strategy.script(
                AiStrategyOutcome.failure("TIMEOUT", "AI 调用超时（120 秒）", null),
                AiStrategyOutcome.success("finally ok", 1, 2, "{}"));

        AiResult result = gateway.chat(request(AiTaskType.DATA_IMPORT));

        assertThat(result.success()).isTrue();
        assertThat(strategy.invocations).isEqualTo(2);
    }

    @Test
    void strategyExceptionNeverEscapesAsThrowable() {
        assign(AiTaskType.DATA_IMPORT, provider(1L, "一号", "http://x/v1"), model(10L, 1L, "m", false));
        strategy.throwOnInvoke = true;

        AiResult result = gateway.chat(request(AiTaskType.DATA_IMPORT));

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("PROVIDER_ERROR");
        assertThat(strategy.invocations).isEqualTo(3);
        assertThat(captureLog().isSuccess()).isFalse();
    }

    @Test
    void invalidRequestIsStillAudited() {
        AiResult result = gateway.chat(null);

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("INVALID_REQUEST");

        AiCallLog logged = captureLog();
        assertThat(logged.getTaskType()).isEqualTo("invalid");
        assertThat(logged.isSuccess()).isFalse();
        assertThat(logged.getErrorCode()).isEqualTo("INVALID_REQUEST");
    }

    @Test
    void missingTaskConfigReturnsNotConfigured() {
        AiResult result = gateway.chat(request(AiTaskType.DATA_IMPORT));

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("NOT_CONFIGURED");
        assertThat(strategy.invocations).isZero();

        AiCallLog logged = captureLog();
        assertThat(logged.getTaskType()).isEqualTo("data_import");
        assertThat(logged.isSuccess()).isFalse();
        assertThat(logged.getErrorCode()).isEqualTo("NOT_CONFIGURED");
        assertThat(logged.getProviderId()).isNull();
    }

    @Test
    void disabledProviderOrModelReturnsNotConfigured() {
        AiProvider provider = provider(1L, "一号", "http://x/v1");
        AiModel model = model(10L, 1L, "m", false);
        assign(AiTaskType.DATA_IMPORT, provider, model);
        provider.setEnabled(false);
        resolver.invalidate();

        assertThat(gateway.chat(request(AiTaskType.DATA_IMPORT)).errorCode()).isEqualTo("NOT_CONFIGURED");

        provider.setEnabled(true);
        model.setEnabled(false);
        resolver.invalidate();
        assertThat(gateway.chat(request(AiTaskType.DATA_IMPORT)).errorCode()).isEqualTo("NOT_CONFIGURED");
    }

    @Test
    void configuredRequiresEveryTaskConfig() {
        assign(AiTaskType.DATA_IMPORT, provider(1L, "一号", "http://x/v1"), model(10L, 1L, "m", false));

        assertThat(gateway.configured()).isFalse();

        assign(AiTaskType.SUBSTITUTION_RECOMMEND,
                provider(2L, "二号", "http://y/v1"), model(20L, 2L, "m2", false));

        assertThat(gateway.configured()).isFalse();

        assign(AiTaskType.NATURAL_LANGUAGE_CONSTRAINTS,
                provider(3L, "三号", "http://z/v1"), model(30L, 3L, "m3", false));

        assertThat(gateway.configured()).isTrue();
    }

    @Test
    void writesCallLogWithoutSecretsAndTruncatesResponseJson() {
        assign(AiTaskType.DATA_IMPORT, provider(1L, "一号", "http://x/v1"), model(10L, 1L, "deepseek-chat", false));
        strategy.script(AiStrategyOutcome.success("{}", 12, 34, "y".repeat(30000)));
        authenticate("admin", 7L);

        AiResult result = gateway.chat(request(AiTaskType.DATA_IMPORT));

        assertThat(result.success()).isTrue();
        AiCallLog logged = captureLog();
        assertThat(logged.getTaskType()).isEqualTo("data_import");
        assertThat(logged.isSuccess()).isTrue();
        assertThat(logged.getProviderId()).isEqualTo(1L);
        assertThat(logged.getProviderName()).isEqualTo("一号");
        assertThat(logged.getModelId()).isEqualTo(10L);
        assertThat(logged.getModelName()).isEqualTo("deepseek-chat");
        assertThat(logged.getUserId()).isEqualTo(7L);
        assertThat(logged.getInputTokens()).isEqualTo(12);
        assertThat(logged.getOutputTokens()).isEqualTo(34);
        assertThat(logged.getLatencyMs()).isGreaterThanOrEqualTo(0);
        assertThat(logged.getCreatedAt()).isNotNull();
        assertThat(logged.getRequestJson()).contains("你好").doesNotContain(SECRET).doesNotContain("Authorization");
        assertThat(logged.getResponseJson()).hasSize(20000);
    }

    @Test
    void logFailureDoesNotBreakTheCall() {
        assign(AiTaskType.DATA_IMPORT, provider(1L, "一号", "http://x/v1"), model(10L, 1L, "m", false));
        when(callLogs.save(any(AiCallLog.class))).thenThrow(new IllegalStateException("数据库不可用"));

        AiResult result = gateway.chat(request(AiTaskType.DATA_IMPORT));

        assertThat(result.success()).isTrue();
    }

    @Test
    void unresolvedUserIsLoggedAsNull() {
        assign(AiTaskType.DATA_IMPORT, provider(1L, "一号", "http://x/v1"), model(10L, 1L, "m", false));
        when(users.getByUsername(any())).thenThrow(new IllegalStateException("用户不存在"));
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken("ghost", null, List.of()));

        gateway.chat(request(AiTaskType.DATA_IMPORT));

        assertThat(captureLog().getUserId()).isNull();
    }

    private AiCallLog captureLog() {
        ArgumentCaptor<AiCallLog> captor = ArgumentCaptor.forClass(AiCallLog.class);
        verify(callLogs).save(captor.capture());
        return captor.getValue();
    }

    private void authenticate(String username, long userId) {
        AppUser user = new AppUser();
        user.setId(userId);
        user.setUsername(username);
        when(users.getByUsername(username)).thenReturn(user);
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(username, null, List.of()));
    }

    private AiRequest request(AiTaskType taskType) {
        return new AiRequest(taskType, List.of(new AiMessage("user", "你好")), null, null);
    }

    private AiRequest visionRequest() {
        return new AiRequest(
                AiTaskType.DATA_IMPORT,
                List.of(new AiMessage("user", "读出这张课表")),
                List.of(new AiImage("image/png", new byte[] {7, 7, 7})),
                null);
    }

    private AiProvider provider(long id, String name, String baseUrl) {
        AiProvider provider = new AiProvider();
        provider.setId(id);
        provider.setName(name);
        provider.setType("custom");
        provider.setBaseUrl(baseUrl);
        provider.setApiKeyCipher(cipher.encrypt(SECRET));
        provider.setEnabled(true);
        providerRows.put(id, provider);
        return provider;
    }

    private AiModel model(long id, long providerId, String modelName, boolean visionCapable) {
        AiModel model = new AiModel();
        model.setId(id);
        model.setProviderId(providerId);
        model.setModelName(modelName);
        model.setDisplayName(modelName);
        model.setVisionCapable(visionCapable);
        model.setEnabled(true);
        modelRows.put(id, model);
        return model;
    }

    private void assign(AiTaskType taskType, AiProvider provider, AiModel model) {
        AiTaskConfig config = new AiTaskConfig();
        config.setTaskType(taskType);
        config.setMode("unified");
        config.setProviderId(provider.getId());
        config.setModelId(model.getId());
        configs.put(taskType, config);
        // In production the write path publishes AiConfigChangedEvent, which drops the cache.
        resolver.invalidate();
    }

    /** Scripted strategy; every call is counted so routing and retries are observable. */
    private static final class FakeStrategy implements AiStrategy {

        private final Deque<AiStrategyOutcome> scripted = new ArrayDeque<>();
        private int invocations;
        private AiInvocation lastInvocation;
        private boolean throwOnInvoke;

        void script(AiStrategyOutcome... outcomes) {
            scripted.clear();
            scripted.addAll(List.of(outcomes));
        }

        @Override
        public boolean supports(AiProvider provider) {
            return true;
        }

        @Override
        public boolean fallback() {
            return false;
        }

        @Override
        public AiStrategyOutcome invoke(AiInvocation invocation) {
            invocations++;
            lastInvocation = invocation;
            if (throwOnInvoke) {
                throw new IllegalStateException("策略内部崩溃");
            }
            return scripted.isEmpty()
                    ? AiStrategyOutcome.success("ok", 11, 7, "{\"ok\":true}")
                    : scripted.poll();
        }
    }
}
