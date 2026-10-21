package com.paiban.ai.strategy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paiban.ai.domain.AiProvider;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * DeepSeek speaks the OpenAI wire format, so it reuses the compatible strategy and
 * only claims the provider through {@link #supports}.
 */
@Component
public class DeepSeekStrategy extends OpenAiCompatibleStrategy {

    public DeepSeekStrategy(ObjectMapper objectMapper, @Value("${paiban.ai.timeout-seconds:120}") long timeoutSeconds) {
        super(objectMapper, timeoutSeconds);
    }

    @Override
    public boolean supports(AiProvider provider) {
        if (provider == null) {
            return false;
        }
        String protocol = provider.getProtocol();
        if (protocol != null && !protocol.isBlank() && !"openai".equalsIgnoreCase(protocol)) {
            return false;
        }
        String baseUrl = provider.getBaseUrl() == null ? "" : provider.getBaseUrl().toLowerCase(Locale.ROOT);
        return baseUrl.contains("deepseek");
    }

    @Override
    public boolean fallback() {
        return false;
    }
}
