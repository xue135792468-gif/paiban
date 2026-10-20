package com.paiban.ai;

/**
 * An inline image for vision calls. The bytes are sent base64-encoded and are
 * never written to {@code ai_call_log}.
 */
public record AiImage(String mimeType, byte[] data) {}
