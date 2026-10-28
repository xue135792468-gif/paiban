package com.paiban.substitution.web;

import com.paiban.identity.domain.Permissions;
import com.paiban.substitution.service.AdminSubstitutionService;
import com.paiban.substitution.web.SubstitutionWorkbenchDtos.CandidateListDto;
import com.paiban.substitution.web.SubstitutionWorkbenchDtos.ConsumeRequest;
import com.paiban.substitution.web.SubstitutionWorkbenchDtos.PreviewRequest;
import com.paiban.substitution.web.SubstitutionWorkbenchDtos.PreviewResponse;
import com.paiban.substitution.web.SubstitutionWorkbenchDtos.WorkbenchActionResult;
import jakarta.validation.Valid;
import java.security.Principal;
import java.time.LocalDate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理调代工作台（票 03）端点。控制器层先做粗粒度的提交权限把关；
 * 细粒度的「全校课表查看 + 提交 + 审批」组合判定在服务层
 * （{@link AdminSubstitutionService#requireManager}），仅查看权限者不可写。
 */
@RestController
@RequestMapping("/api")
public class SubstitutionWorkbenchController {

    private final AdminSubstitutionService service;

    public SubstitutionWorkbenchController(AdminSubstitutionService service) {
        this.service = service;
    }

    /** 候选代课教师：启用、排除当前教师、同科优先、冲突原因可见。 */
    @GetMapping("/substitution-workbench/candidates")
    @PreAuthorize("hasAuthority('" + Permissions.SUBSTITUTION_SUBMIT + "')")
    public CandidateListDto candidates(
            @RequestParam Long entryId,
            @RequestParam LocalDate lessonDate,
            Principal principal) {
        return service.candidates(principal.getName(), entryId, lessonDate);
    }

    /** 创建预览凭证：零申请、零生效、零通知；返回权威截止时间与左右对照。 */
    @PostMapping("/substitution-workbench/previews")
    @PreAuthorize("hasAuthority('" + Permissions.SUBSTITUTION_SUBMIT + "')")
    public PreviewResponse preview(@Valid @RequestBody PreviewRequest request, Principal principal) {
        return service.preview(principal.getName(), request);
    }

    /** 消费凭证（代发）：提交审批进待批队列，旧安排继续生效。 */
    @PostMapping("/substitution-workbench/previews/{id}/submit-approval")
    @PreAuthorize("hasAuthority('" + Permissions.SUBSTITUTION_SUBMIT + "')")
    public WorkbenchActionResult submitApproval(
            @PathVariable Long id,
            @RequestBody(required = false) ConsumeRequest body,
            Principal principal) {
        return service.submitApproval(id, principal.getName(), body == null ? null : body.comment());
    }

    /** 消费凭证（亲自确认）：与普通批准完全相同的校验、审计与写入事务。 */
    @PostMapping("/substitution-workbench/previews/{id}/confirm")
    @PreAuthorize("hasAuthority('" + Permissions.SUBSTITUTION_SUBMIT + "')")
    public WorkbenchActionResult confirm(
            @PathVariable Long id,
            @RequestBody(required = false) ConsumeRequest body,
            Principal principal) {
        return service.confirm(id, principal.getName(), body == null ? null : body.comment());
    }
}
