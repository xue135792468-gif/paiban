package com.paiban.ai.web;

import com.paiban.ai.service.AiTaskConfigService;
import com.paiban.ai.web.AiDtos.SaveTaskConfigRequest;
import com.paiban.ai.web.AiDtos.TaskConfigBundleDto;
import com.paiban.identity.domain.Permissions;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai-task-config")
@PreAuthorize("hasAuthority('" + Permissions.SETTINGS_MANAGE + "')")
public class AiTaskConfigController {

    private final AiTaskConfigService service;

    public AiTaskConfigController(AiTaskConfigService service) {
        this.service = service;
    }

    @GetMapping
    public TaskConfigBundleDto get() {
        return service.get();
    }

    @PutMapping
    public TaskConfigBundleDto save(@Valid @RequestBody SaveTaskConfigRequest request) {
        return service.save(request);
    }
}
