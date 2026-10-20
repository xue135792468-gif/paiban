package com.paiban.scheduling.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paiban.common.event.SemesterCreatedEvent;
import com.paiban.common.web.ApiException;
import com.paiban.foundation.service.SemesterService;
import com.paiban.scheduling.domain.ConstraintConfig;
import com.paiban.scheduling.domain.ConstraintConfigRepository;
import com.paiban.scheduling.domain.ConstraintTemplate;
import com.paiban.scheduling.domain.ConstraintTemplateRepository;
import com.paiban.scheduling.domain.ConstraintType;
import com.paiban.scheduling.domain.ConstraintType.Category;
import com.paiban.scheduling.domain.ConstraintType.Param;
import com.paiban.scheduling.domain.ConstraintType.ParamType;
import com.paiban.scheduling.web.ConstraintDtos.ConstraintConfigDto;
import com.paiban.scheduling.web.ConstraintDtos.ConstraintConfigRequest;
import com.paiban.scheduling.web.ConstraintDtos.ConstraintTypeDto;
import com.paiban.scheduling.web.ConstraintDtos.ParamDefDto;
import com.paiban.scheduling.web.ConstraintDtos.TemplateDto;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConstraintService {

    private final ConstraintConfigRepository configs;
    private final ConstraintTemplateRepository templates;
    private final SemesterService semesters;
    private final ObjectMapper objectMapper;

    public ConstraintService(
            ConstraintConfigRepository configs,
            ConstraintTemplateRepository templates,
            SemesterService semesters,
            ObjectMapper objectMapper) {
        this.configs = configs;
        this.templates = templates;
        this.semesters = semesters;
        this.objectMapper = objectMapper;
    }

    public List<ConstraintTypeDto> types() {
        return List.of(ConstraintType.values()).stream()
                .map(type -> new ConstraintTypeDto(
                        type.name(),
                        type.getDisplayName(),
                        type.getCategory().name(),
                        type.getCategory().getLabel(),
                        type.getDescription(),
                        type.isDefaultHard(),
                        type.getDefaultWeight(),
                        type.getParams().stream().map(this::toDto).toList()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ConstraintConfigDto> list(Long semesterId) {
        Long resolved = semesterId != null
                ? semesters.require(semesterId).getId()
                : semesters.requireCurrent().getId();
        return configs.findBySemesterId(resolved).stream().map(this::toDto).toList();
    }

    @Transactional
    public List<ConstraintConfigDto> save(Long semesterId, List<ConstraintConfigRequest> requests) {
        Long resolved = semesterId != null ? semesters.require(semesterId).getId()
                : semesters.requireCurrent().getId();
        List<String> errors = new ArrayList<>();
        List<ConstraintConfig> toSave = new ArrayList<>();
        for (ConstraintConfigRequest request : requests) {
            ConstraintType type = resolveType(request.type(), errors);
            if (type == null) {
                continue;
            }
            Map<String, Object> params = validateParams(type, request.params(), errors);
            int weight = request.weight() == null ? type.getDefaultWeight() : request.weight();
            if (weight < 0 || weight > 100) {
                errors.add(type.getDisplayName() + ": 权重必须在 0-100 之间");
            }
            ConstraintConfig config = new ConstraintConfig();
            config.setSemesterId(resolved);
            config.setType(type.name());
            config.setParamsJson(toJson(params));
            config.setWeight(weight);
            config.setHard(request.hard() == null ? type.isDefaultHard() : request.hard());
            config.setEnabled(request.enabled() == null || request.enabled());
            toSave.add(config);
        }
        if (!errors.isEmpty()) {
            throw ApiException.badRequest(String.join("; ", errors));
        }
        configs.deleteBySemesterId(resolved);
        configs.flush();
        return configs.saveAll(toSave).stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public List<ConstraintConfig> active(Long semesterId) {
        return configs.findBySemesterId(semesterId).stream()
                .filter(ConstraintConfig::isEnabled)
                .toList();
    }

    @Transactional(readOnly = true)
    public boolean hasConfigs(Long semesterId) {
        return !configs.findBySemesterId(semesterId).isEmpty();
    }

    @Transactional
    public TemplateDto saveTemplate(String name, Long semesterId) {
        Long resolved = semesterId != null ? semesters.require(semesterId).getId()
                : semesters.requireCurrent().getId();
        List<ConstraintConfigRequest> snapshot = configs.findBySemesterId(resolved).stream()
                .map(config -> new ConstraintConfigRequest(
                        config.getType(),
                        paramsFromJson(config.getParamsJson()),
                        config.getWeight(),
                        config.isHard(),
                        config.isEnabled()))
                .toList();
        ConstraintTemplate template = templates.findByName(name).orElseGet(ConstraintTemplate::new);
        template.setName(name);
        template.setConfigsJson(toJson(snapshot));
        if (template.getCreatedAt() == null) {
            template.setCreatedAt(Instant.now());
        }
        return toDto(templates.save(template), snapshot.size());
    }

    @Transactional(readOnly = true)
    public List<TemplateDto> listTemplates() {
        return templates.findAllByOrderByCreatedAtDesc().stream()
                .map(template -> toDto(template, readSnapshot(template).size()))
                .toList();
    }

    @Transactional
    public List<ConstraintConfigDto> applyTemplate(Long templateId, Long semesterId) {
        ConstraintTemplate template = templates.findById(templateId)
                .orElseThrow(() -> ApiException.notFound("约束模板不存在: " + templateId));
        return save(semesterId, readSnapshot(template));
    }

    @Transactional
    public void deleteTemplate(Long id) {
        if (!templates.existsById(id)) {
            throw ApiException.notFound("约束模板不存在: " + id);
        }
        templates.deleteById(id);
    }

    @EventListener
    @Transactional
    public void onSemesterCreated(SemesterCreatedEvent event) {
        if (!configs.findBySemesterId(event.semesterId()).isEmpty()) {
            return;
        }
        List<ConstraintConfig> defaults = new ArrayList<>();
        for (ConstraintType type : List.of(
                ConstraintType.TEACHER_CONFLICT,
                ConstraintType.CLASS_CONFLICT,
                ConstraintType.ROOM_CONFLICT)) {
            ConstraintConfig config = new ConstraintConfig();
            config.setSemesterId(event.semesterId());
            config.setType(type.name());
            config.setParamsJson("{}");
            config.setWeight(100);
            config.setHard(true);
            config.setEnabled(true);
            defaults.add(config);
        }
        configs.saveAll(defaults);
    }

    private ConstraintType resolveType(String code, List<String> errors) {
        if (code == null || code.isBlank()) {
            errors.add("约束类型不能为空");
            return null;
        }
        try {
            return ConstraintType.valueOf(code);
        } catch (IllegalArgumentException ex) {
            errors.add("未知约束类型: " + code);
            return null;
        }
    }

    private Map<String, Object> validateParams(
            ConstraintType type, Map<String, Object> input, List<String> errors) {
        Map<String, Object> source = input == null ? Map.of() : input;
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (Param param : type.getParams()) {
            Object value = source.get(param.name());
            if (value == null || (value instanceof String text && text.isBlank())) {
                if (param.required()) {
                    errors.add(type.getDisplayName() + ": 参数「" + param.label() + "」不能为空");
                }
                continue;
            }
            if (param.type() == ParamType.TEXT) {
                normalized.put(param.name(), value.toString());
                continue;
            }
            if (param.type() == ParamType.ROOM_TYPE) {
                String text = value.toString();
                if (!param.options().contains(text)) {
                    errors.add(type.getDisplayName() + ": 教室类型无效: " + text);
                } else {
                    normalized.put(param.name(), text);
                }
                continue;
            }
            Integer number = asInteger(value);
            if (number == null) {
                errors.add(type.getDisplayName() + ": 参数「" + param.label() + "」必须是整数");
                continue;
            }
            if (param.min() != null && number < param.min()) {
                errors.add(type.getDisplayName() + ": 参数「" + param.label() + "」不能小于 " + param.min());
            }
            if (param.max() != null && number > param.max()) {
                errors.add(type.getDisplayName() + ": 参数「" + param.label() + "」不能大于 " + param.max());
            }
            normalized.put(param.name(), number);
        }
        source.keySet().stream()
                .filter(key -> type.getParams().stream().noneMatch(param -> param.name().equals(key)))
                .forEach(key -> errors.add(type.getDisplayName() + ": 未知参数 " + key));
        return normalized;
    }

    private Integer asInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(value.toString().trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private ParamDefDto toDto(Param param) {
        return new ParamDefDto(
                param.name(),
                param.label(),
                param.type().name(),
                param.required(),
                param.min(),
                param.max(),
                param.defaultValue(),
                param.options());
    }

    private ConstraintConfigDto toDto(ConstraintConfig config) {
        ConstraintType type;
        try {
            type = ConstraintType.valueOf(config.getType());
        } catch (IllegalArgumentException ex) {
            type = null;
        }
        String name = type == null ? config.getType() : type.getDisplayName();
        Category category = type == null ? Category.PEDAGOGY : type.getCategory();
        String description = type == null ? "" : type.getDescription();
        return new ConstraintConfigDto(
                config.getId(),
                config.getType(),
                name,
                category.name(),
                category.getLabel(),
                description,
                paramsFromJson(config.getParamsJson()),
                config.getWeight(),
                config.isHard(),
                config.isEnabled());
    }

    private TemplateDto toDto(ConstraintTemplate template, int count) {
        return new TemplateDto(template.getId(), template.getName(), template.getCreatedAt(), count);
    }

    private Map<String, Object> paramsFromJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException ex) {
            return Map.of();
        }
    }

    private List<ConstraintConfigRequest> readSnapshot(ConstraintTemplate template) {
        try {
            return objectMapper.readValue(
                    template.getConfigsJson(), new TypeReference<List<ConstraintConfigRequest>>() {});
        } catch (JsonProcessingException ex) {
            throw ApiException.badRequest("约束模板内容损坏: " + template.getName());
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("序列化约束配置失败", ex);
        }
    }
}
