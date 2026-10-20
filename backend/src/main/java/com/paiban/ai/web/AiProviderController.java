package com.paiban.ai.web;

import com.paiban.ai.service.AiProviderService;
import com.paiban.ai.web.AiDtos.ConnectionTestDto;
import com.paiban.ai.web.AiDtos.ModelDto;
import com.paiban.ai.web.AiDtos.PresetDto;
import com.paiban.ai.web.AiDtos.ProviderDto;
import com.paiban.ai.web.AiDtos.SaveModelRequest;
import com.paiban.ai.web.AiDtos.SaveProviderRequest;
import com.paiban.identity.domain.Permissions;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai-providers")
@PreAuthorize("hasAuthority('" + Permissions.SETTINGS_MANAGE + "')")
public class AiProviderController {

    private final AiProviderService service;

    public AiProviderController(AiProviderService service) {
        this.service = service;
    }

    @GetMapping
    public List<ProviderDto> list() {
        return service.list();
    }

    @GetMapping("/presets")
    public List<PresetDto> presets() {
        return service.presets();
    }

    @GetMapping("/{id}")
    public ProviderDto get(@PathVariable Long id) {
        return service.get(id);
    }

    @PostMapping
    public ProviderDto create(@Valid @RequestBody SaveProviderRequest request) {
        return service.create(request);
    }

    @PutMapping("/{id}")
    public ProviderDto update(@PathVariable Long id, @Valid @RequestBody SaveProviderRequest request) {
        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }

    @PostMapping("/{id}/test")
    public ConnectionTestDto test(@PathVariable Long id) {
        return service.testConnection(id);
    }

    @PostMapping("/{id}/fetch-models")
    public List<String> fetchModels(@PathVariable Long id) {
        return service.fetchModels(id);
    }

    @PostMapping("/{id}/models")
    public ModelDto createModel(@PathVariable Long id, @Valid @RequestBody SaveModelRequest request) {
        return service.createModel(id, request);
    }

    @PutMapping("/{id}/models/{modelId}")
    public ModelDto updateModel(
            @PathVariable Long id, @PathVariable Long modelId, @Valid @RequestBody SaveModelRequest request) {
        return service.updateModel(id, modelId, request);
    }

    @DeleteMapping("/{id}/models/{modelId}")
    public void deleteModel(@PathVariable Long id, @PathVariable Long modelId) {
        service.deleteModel(id, modelId);
    }
}
