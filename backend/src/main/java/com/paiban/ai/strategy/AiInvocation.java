package com.paiban.ai.strategy;

import com.paiban.ai.domain.AiModel;
import com.paiban.ai.domain.AiProvider;

/** Everything a strategy needs for one provider call; the API key is decrypted here, never logged. */
public record AiInvocation(AiProvider provider, String apiKey, AiModel model, com.paiban.ai.AiRequest request) {}
