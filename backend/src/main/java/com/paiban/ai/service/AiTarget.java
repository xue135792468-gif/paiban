package com.paiban.ai.service;

import com.paiban.ai.domain.AiModel;
import com.paiban.ai.domain.AiProvider;

/**
 * A task slot resolved to a concrete provider and model. The API key is decrypted
 * here and only handed to the strategy; it is never logged or returned.
 */
public record AiTarget(AiProvider provider, AiModel model, String apiKey) {}
