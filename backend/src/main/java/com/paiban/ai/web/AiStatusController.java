package com.paiban.ai.web;

import com.paiban.ai.AiGateway;
import com.paiban.ai.service.AiConfigResolver;
import com.paiban.ai.web.AiDtos.AiStatusDto;
import com.paiban.ai.web.AiDtos.AiTaskStatusDto;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI availability for any signed-in user, so the import and recommendation
 * screens can disable their buttons before a call is attempted.
 */
@RestController
@RequestMapping("/api/ai")
public class AiStatusController {

    private final AiGateway gateway;
    private final AiConfigResolver resolver;

    public AiStatusController(AiGateway gateway, AiConfigResolver resolver) {
        this.gateway = gateway;
        this.resolver = resolver;
    }

    @GetMapping("/status")
    public AiStatusDto status() {
        Map<String, AiTaskStatusDto> tasks = new LinkedHashMap<>();
        resolver.status().forEach((taskType, status) -> tasks.put(
                taskType,
                new AiTaskStatusDto(status.configured(), status.providerId(), status.modelId(), status.visionCapable())));
        return new AiStatusDto(gateway.configured(), tasks);
    }
}
