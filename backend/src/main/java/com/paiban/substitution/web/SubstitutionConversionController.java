package com.paiban.substitution.web;

import com.paiban.identity.domain.Permissions;
import com.paiban.substitution.service.SubstitutionConversionService;
import com.paiban.substitution.web.SubstitutionConversionDtos.ConversionBatchDto;
import com.paiban.substitution.web.SubstitutionConversionDtos.ConversionConfirmResultDto;
import com.paiban.substitution.web.SubstitutionConversionDtos.ConversionPrecheckDto;
import com.paiban.substitution.web.SubstitutionConversionDtos.ConversionStatusDto;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 历史调代日期转换（票 02）：预检报告与转换状态对管理者可查，
 * 数据确认门只对具备审批权限的教务开放。所有端点按权限常量授权。
 */
@RestController
@RequestMapping("/api")
public class SubstitutionConversionController {

    private final SubstitutionConversionService conversions;

    public SubstitutionConversionController(SubstitutionConversionService conversions) {
        this.conversions = conversions;
    }

    /** 只读预检报告：逐条依据、转换状态与转换前后差异，不改动任何业务数据。 */
    @GetMapping("/substitution-conversion/precheck")
    @PreAuthorize("hasAuthority('" + Permissions.SUBSTITUTION_VIEW + "')")
    public ConversionPrecheckDto precheck(@RequestParam(required = false) Long semesterId) {
        return conversions.precheck(semesterId);
    }

    /** 转换状态：未转换 / 已转换 / 含阻塞，供工作台提示与数据级判定使用。 */
    @GetMapping("/substitution-conversion/status")
    @PreAuthorize("hasAuthority('" + Permissions.SUBSTITUTION_VIEW + "')")
    public ConversionStatusDto status(@RequestParam(required = false) Long semesterId) {
        return conversions.status(semesterId);
    }

    /** 确认批次与失败原因留痕（CONVERTED / FAILED）。 */
    @GetMapping("/substitution-conversion/records")
    @PreAuthorize("hasAuthority('" + Permissions.SUBSTITUTION_VIEW + "')")
    public List<ConversionBatchDto> records(@RequestParam(required = false) Long semesterId) {
        return conversions.records(semesterId);
    }

    /** 数据确认门：经审批权限的教务确认后原子转换；阻塞存在时整单拒绝。 */
    @PostMapping("/substitution-conversion/confirm")
    @PreAuthorize("hasAuthority('" + Permissions.SUBSTITUTION_APPROVE + "')")
    public ConversionConfirmResultDto confirm(
            @RequestParam(required = false) Long semesterId,
            @RequestBody(required = false) Map<String, String> body,
            Principal principal) {
        String comment = body == null ? null : body.get("comment");
        return conversions.confirm(semesterId, principal.getName(), comment);
    }
}
