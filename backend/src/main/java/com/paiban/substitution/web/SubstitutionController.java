package com.paiban.substitution.web;

import com.paiban.identity.domain.Permissions;
import com.paiban.substitution.service.SubstitutionPlanService;
import com.paiban.substitution.service.SubstitutionQueryService;
import com.paiban.substitution.service.SubstitutionRecommendationService;
import com.paiban.substitution.service.SubstitutionRequestService;
import com.paiban.substitution.web.SubstitutionDtos.CreateSubstitutionRequest;
import com.paiban.substitution.web.SubstitutionDtos.DecisionRequest;
import com.paiban.substitution.web.SubstitutionDtos.GeneratePlansResponse;
import com.paiban.substitution.web.SubstitutionDtos.HistoryDto;
import com.paiban.substitution.web.SubstitutionDtos.RecommendationResponse;
import com.paiban.substitution.web.SubstitutionDtos.SubstitutionPlanDto;
import com.paiban.substitution.web.SubstitutionDtos.SubstitutionRecordDto;
import com.paiban.substitution.web.SubstitutionDtos.SubstitutionRequestDto;
import com.paiban.substitution.web.SubstitutionDtos.SwapTargetDto;
import com.paiban.substitution.web.SubstitutionDtos.TeacherStatsDto;
import jakarta.validation.Valid;
import java.security.Principal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class SubstitutionController {

    private final SubstitutionRequestService requests;
    private final SubstitutionPlanService plans;
    private final SubstitutionQueryService queries;
    private final SubstitutionRecommendationService recommendations;

    public SubstitutionController(
            SubstitutionRequestService requests,
            SubstitutionPlanService plans,
            SubstitutionQueryService queries,
            SubstitutionRecommendationService recommendations) {
        this.requests = requests;
        this.plans = plans;
        this.queries = queries;
        this.recommendations = recommendations;
    }

    @PostMapping("/substitution-requests")
    @PreAuthorize("hasAuthority('" + Permissions.SUBSTITUTION_SUBMIT + "')")
    public SubstitutionRequestDto create(
            @Valid @RequestBody CreateSubstitutionRequest request, Principal principal) {
        return requests.create(principal.getName(), request);
    }

    @GetMapping("/substitution-requests")
    public List<SubstitutionRequestDto> list(
            @RequestParam(required = false) Long semesterId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "false") boolean mine,
            Authentication authentication,
            Principal principal) {
        boolean canApprove = authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals(Permissions.SUBSTITUTION_APPROVE));
        return requests.list(principal.getName(), semesterId, status, mine || !canApprove);
    }

    @GetMapping("/substitution-requests/pending")
    @PreAuthorize("hasAuthority('" + Permissions.SUBSTITUTION_APPROVE + "')")
    public List<SubstitutionRequestDto> pending(Principal principal) {
        return requests.pending(principal.getName());
    }

    @GetMapping("/substitution-requests/{id}")
    public SubstitutionRequestDto detail(@PathVariable Long id, Principal principal) {
        return requests.detail(principal.getName(), id);
    }

    @GetMapping("/substitution-requests/{id}/history")
    public List<HistoryDto> history(@PathVariable Long id, Principal principal) {
        return requests.history(principal.getName(), id);
    }

    @PostMapping("/substitution-requests/{id}/approve")
    @PreAuthorize("hasAuthority('" + Permissions.SUBSTITUTION_APPROVE + "')")
    public SubstitutionRequestDto approve(
            @PathVariable Long id,
            @RequestBody(required = false) DecisionRequest decision,
            Principal principal) {
        return requests.approve(id, principal.getName(), decision);
    }

    @PostMapping("/substitution-requests/{id}/reject")
    @PreAuthorize("hasAuthority('" + Permissions.SUBSTITUTION_APPROVE + "')")
    public SubstitutionRequestDto reject(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, String> body,
            Principal principal) {
        return requests.reject(id, principal.getName(), body == null ? null : body.get("comment"));
    }

    @PostMapping("/substitution-requests/{id}/recommend")
    @PreAuthorize("hasAuthority('" + Permissions.SUBSTITUTION_APPROVE + "')")
    public RecommendationResponse recommend(@PathVariable Long id) {
        return recommendations.recommend(id);
    }

    /** Same-class slots the requester can swap into (no class-wide timetable data). */
    @GetMapping("/substitution-swap-targets")
    @PreAuthorize("hasAuthority('" + Permissions.SUBSTITUTION_SUBMIT + "')")
    public List<SwapTargetDto> swapTargets(
            @RequestParam Long entryId,
            @RequestParam LocalDate date,
            Principal principal) {
        return plans.swapTargets(principal.getName(), entryId, date);
    }

    /** Generate (or regenerate) up to three candidate swap cycles for a request. */
    @PostMapping("/substitution-requests/{id}/plans")
    @PreAuthorize("hasAuthority('" + Permissions.SUBSTITUTION_APPROVE + "')")
    public GeneratePlansResponse generatePlans(@PathVariable Long id, Principal principal) {
        return plans.generatePlans(id, principal.getName());
    }

    @GetMapping("/substitution-requests/{id}/plans")
    @PreAuthorize("hasAuthority('" + Permissions.SUBSTITUTION_SUBMIT + "')")
    public List<SubstitutionPlanDto> plans(@PathVariable Long id, Principal principal) {
        return plans.plansFor(id, principal.getName());
    }

    /** Approve one whole plan: every move becomes effective, or none does. */
    @PostMapping("/substitution-plans/{id}/approve")
    @PreAuthorize("hasAuthority('" + Permissions.SUBSTITUTION_APPROVE + "')")
    public SubstitutionRequestDto approvePlan(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, String> body,
            Principal principal) {
        return plans.approvePlan(id, principal.getName(), body == null ? null : body.get("comment"));
    }

    @GetMapping("/substitutions")
    public List<SubstitutionRecordDto> records(
            @RequestParam(required = false) Long semesterId,
            @RequestParam(required = false) Long teacherId,
            @RequestParam(required = false) Long classId,
            @RequestParam(required = false) LocalDate dateFrom,
            @RequestParam(required = false) LocalDate dateTo,
            @RequestParam(required = false) String type,
            Authentication authentication,
            Principal principal) {
        authorizeRecords(authentication, principal, classId);
        return queries.records(semesterId, teacherId, classId, dateFrom, dateTo, type);
    }

    /** Same filters as the records page; the type column distinguishes 调课 / 代课. */
    @GetMapping(value = "/substitutions/export", produces = "text/csv;charset=UTF-8")
    public org.springframework.http.ResponseEntity<byte[]> exportRecords(
            @RequestParam(required = false) Long semesterId,
            @RequestParam(required = false) Long teacherId,
            @RequestParam(required = false) Long classId,
            @RequestParam(required = false) LocalDate dateFrom,
            @RequestParam(required = false) LocalDate dateTo,
            @RequestParam(required = false) String type,
            Authentication authentication,
            Principal principal) {
        authorizeRecords(authentication, principal, classId);
        String csv = queries.recordsCsv(semesterId, teacherId, classId, dateFrom, dateTo, type);
        return org.springframework.http.ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=substitutions.csv")
                .contentType(org.springframework.http.MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(csv.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private void authorizeRecords(Authentication authentication, Principal principal, Long classId) {
        boolean canViewAll = authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals(Permissions.SUBSTITUTION_VIEW));
        if (canViewAll) {
            return;
        }
        boolean canViewClass = authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals(Permissions.SUBSTITUTION_VIEW_CLASS));
        if (!canViewClass) {
            throw com.paiban.common.web.ApiException.forbidden("没有权限查看调代记录");
        }
        if (classId == null) {
            throw com.paiban.common.web.ApiException.badRequest("班主任查看调代记录时必须指定班级");
        }
        queries.requireHeadTeacher(principal.getName(), classId);
    }

    @GetMapping("/substitutions/my")
    @PreAuthorize("hasAuthority('" + Permissions.SUBSTITUTION_VIEW_OWN + "')")
    public List<SubstitutionRecordDto> myRecords(Principal principal) {
        return queries.myRecords(principal.getName());
    }

    @GetMapping("/substitutions/stats")
    @PreAuthorize("hasAuthority('" + Permissions.SUBSTITUTION_VIEW + "')")
    public TeacherStatsDto stats(@RequestParam Long teacherId) {
        return queries.stats(teacherId);
    }
}
