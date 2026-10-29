package com.paiban.substitution.web;

import com.paiban.identity.domain.Permissions;
import com.paiban.substitution.service.AdminSubstitutionService;
import com.paiban.substitution.service.AdminSwapService;
import com.paiban.substitution.web.SubstitutionWorkbenchDtos.CandidateListDto;
import com.paiban.substitution.web.SubstitutionWorkbenchDtos.ConsumeRequest;
import com.paiban.substitution.web.SubstitutionWorkbenchDtos.PreviewRequest;
import com.paiban.substitution.web.SubstitutionWorkbenchDtos.PreviewResponse;
import com.paiban.substitution.web.SubstitutionWorkbenchDtos.SwapCompareResponse;
import com.paiban.substitution.web.SubstitutionWorkbenchDtos.SwapPlanGenerateRequest;
import com.paiban.substitution.web.SubstitutionWorkbenchDtos.SwapPlansResponse;
import com.paiban.substitution.web.SubstitutionWorkbenchDtos.SwapPreviewCreateRequest;
import com.paiban.substitution.web.SubstitutionWorkbenchDtos.SwapPreviewResponse;
import com.paiban.substitution.web.SubstitutionWorkbenchDtos.SwapTargetsResponse;
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
 * 管理调代工作台端点。控制器层先做粗粒度的提交权限把关；
 * 细粒度的「全校课表查看 + 提交 + 审批」组合判定在服务层
 * （{@link AdminSubstitutionService#requireManager} 与
 * {@link AdminSwapService#requireManager}），仅查看权限者不可写。
 */
@RestController
@RequestMapping("/api")
public class SubstitutionWorkbenchController {

    private final AdminSubstitutionService service;
    private final AdminSwapService swapService;

    public SubstitutionWorkbenchController(AdminSubstitutionService service,
                                           AdminSwapService swapService) {
        this.service = service;
        this.swapService = swapService;
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

    // ---------------------- 票 05：管理员同班调课（明确目标日期的置换环） ----------------------

    /** 指定目标日期里来源班已有课的槽位（目标候选格）；空列表 = 当天空格。 */
    @GetMapping("/substitution-workbench/swap-targets")
    @PreAuthorize("hasAuthority('" + Permissions.SUBSTITUTION_SUBMIT + "')")
    public SwapTargetsResponse swapTargets(
            @RequestParam Long entryId,
            @RequestParam LocalDate lessonDate,
            @RequestParam LocalDate targetDate,
            Principal principal) {
        return swapService.swapTargets(principal.getName(), entryId, lessonDate, targetDate);
    }

    /** 生成置换环候选（≤3）：目标日期必须明确并原样回传；无解时返回原因。 */
    @PostMapping("/substitution-workbench/swap-plans")
    @PreAuthorize("hasAuthority('" + Permissions.SUBSTITUTION_SUBMIT + "')")
    public SwapPlansResponse generateSwapPlans(
            @Valid @RequestBody SwapPlanGenerateRequest request,
            Principal principal) {
        return swapService.generatePlans(principal.getName(), request);
    }

    /** 选定候选方案创建 SWAP 预览凭证：绑定快照哈希与整环方案，零申请零生效。 */
    @PostMapping("/substitution-workbench/swap-previews")
    @PreAuthorize("hasAuthority('" + Permissions.SUBSTITUTION_SUBMIT + "')")
    public SwapPreviewResponse swapPreview(
            @Valid @RequestBody SwapPreviewCreateRequest request,
            Principal principal) {
        return swapService.preview(principal.getName(), request);
    }

    /** 切换视角（class/teacher）与日期的单日左右对照；日期范围两侧联动。 */
    @GetMapping("/substitution-workbench/previews/{id}/swap-compare")
    @PreAuthorize("hasAuthority('" + Permissions.SUBSTITUTION_SUBMIT + "')")
    public SwapCompareResponse swapCompare(
            @PathVariable Long id,
            @RequestParam(defaultValue = "class") String view,
            @RequestParam(required = false) Long teacherId,
            @RequestParam LocalDate date,
            Principal principal) {
        return swapService.compare(id, principal.getName(), view, teacherId, date);
    }

    /** 消费 SWAP 凭证（代发）：整环方案随申请进待批队列，旧课表继续。 */
    @PostMapping("/substitution-workbench/previews/{id}/submit-approval-swap")
    @PreAuthorize("hasAuthority('" + Permissions.SUBSTITUTION_SUBMIT + "')")
    public WorkbenchActionResult submitSwapApproval(
            @PathVariable Long id,
            @RequestBody(required = false) ConsumeRequest body,
            Principal principal) {
        return swapService.submitApproval(id, principal.getName(), body == null ? null : body.comment());
    }

    /** 消费 SWAP 凭证（亲自确认）：整环走 approvePlan 同路径，失败整单零生效。 */
    @PostMapping("/substitution-workbench/previews/{id}/confirm-swap")
    @PreAuthorize("hasAuthority('" + Permissions.SUBSTITUTION_SUBMIT + "')")
    public WorkbenchActionResult confirmSwap(
            @PathVariable Long id,
            @RequestBody(required = false) ConsumeRequest body,
            Principal principal) {
        return swapService.confirm(id, principal.getName(), body == null ? null : body.comment());
    }
}
