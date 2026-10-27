package com.paiban.ai.strategy;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Sends one HTTP exchange with a hard deadline that also covers a stalled response
 * body. {@link HttpClient#send} only honours the request timeout until the response
 * starts; a provider that keeps the body open (or streams forever) can otherwise hold
 * the caller past the task's 60-second boundary. The future's get() enforces the whole
 * exchange, and cancellation releases the caller.
 */
final class AiHttpCalls {

    private AiHttpCalls() {}

    static HttpResponse<String> send(
            HttpClient client, HttpRequest request, Duration timeout)
            throws InterruptedException, ExecutionException, TimeoutException {
        CompletableFuture<HttpResponse<String>> future = client.sendAsync(
                request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            future.cancel(true);
            throw ex;
        } catch (InterruptedException ex) {
            future.cancel(true);
            throw ex;
        }
    }
}
