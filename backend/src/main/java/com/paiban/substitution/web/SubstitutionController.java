package com.paiban.substitution.web;

import com.paiban.identity.domain.Permissions;
import com.paiban.substitution.service.SubstitutionRecommendationService;
import com.paiban.substitution.service.SubstitutionService;
import com.paiban.substitution.web.SubstitutionDtos.CreateSubstitutionRequest;
import com.paiban.substitution.web.SubstitutionDtos.DecisionRequest;
import com.paiban.substitution.web.SubstitutionDtos.HistoryDto;
import com.paiban.substitution.web.SubstitutionDtos.RecommendationResponse;
import com.paiban.substitution.web.SubstitutionDtos.SubstitutionRecordDto;
import com.paiban.substitution.web.SubstitutionDtos.SubstitutionRequestDto;
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

    private final SubstitutionService service;
    private final SubstitutionRecommendationService recommendations;

    public SubstitutionController(
            SubstitutionService service, SubstitutionRecommendationService recommendations) {
        this.service = service;
        this.recommendations = recommendations;
    }

    @PostMapping("/substitution-requests")
    @PreAuthorize("hasAuthority('" + Permissions.SUBSTITUTION_SUBMIT + "')")
    public SubstitutionRequestDto create(
            @Valid @RequestBody CreateSubstitutionRequest request, Principal principal) {
        return service.create(principal.getName(), request);
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
        return service.list(principal.getName(), semesterId, status, mine || !canApprove);
    }

    @GetMapping("/substitution-requests/pending")
    @PreAuthorize("hasAuthority('" + Permissions.SUBSTITUTION_APPROVE + "')")
    public List<SubstitutionRequestDto> pending(Principal principal) {
        return service.pending(principal.getName());
    }

    @GetMapping("/substitution-requests/{id}")
    public SubstitutionRequestDto detail(@PathVariable Long id, Principal principal) {
        return service.detail(principal.getName(), id);
    }

    @GetMapping("/substitution-requests/{id}/history")
    public List<HistoryDto> history(@PathVariable Long id, Principal principal) {
        return service.history(principal.getName(), id);
    }

    @PostMapping("/substitution-requests/{id}/approve")
    @PreAuthorize("hasAuthority('" + Permissions.SUBSTITUTION_APPROVE + "')")
    public SubstitutionRequestDto approve(
            @PathVariable Long id,
            @RequestBody(required = false) DecisionRequest decision,
            Principal principal) {
        return service.approve(id, principal.getName(), decision);
    }

    @PostMapping("/substitution-requests/{id}/reject")
    @PreAuthorize("hasAuthority('" + Permissions.SUBSTITUTION_APPROVE + "')")
    public SubstitutionRequestDto reject(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, String> body,
            Principal principal) {
        return service.reject(id, principal.getName(), body == null ? null : body.get("comment"));
    }

    @PostMapping("/substitution-requests/{id}/recommend")
    @PreAuthorize("hasAuthority('" + Permissions.SUBSTITUTION_APPROVE + "')")
    public RecommendationResponse recommend(@PathVariable Long id) {
        return recommendations.recommend(id);
    }

    @GetMapping("/substitutions")
    public List<SubstitutionRecordDto> records(
            @RequestParam(required = false) Long semesterId,
            @RequestParam(required = false) Long teacherId,
            @RequestParam(required = false) Long classId,
            @RequestParam(required = false) LocalDate dateFrom,
            @RequestParam(required = false) LocalDate dateTo,
            Authentication authentication,
            Principal principal) {
        boolean canViewAll = authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals(Permissions.SUBSTITUTION_VIEW));
        if (!canViewAll) {
            boolean canViewClass = authentication.getAuthorities().stream()
                    .anyMatch(authority -> authority.getAuthority().equals(Permissions.SUBSTITUTION_VIEW_CLASS));
            if (!canViewClass) {
                throw com.paiban.common.web.ApiException.forbidden("没有权限查看调代记录");
            }
            if (classId == null) {
                throw com.paiban.common.web.ApiException.badRequest("班主任查看调代记录时必须指定班级");
            }
            service.requireHeadTeacher(principal.getName(), classId);
        }
        return service.records(semesterId, teacherId, classId, dateFrom, dateTo);
    }

    @GetMapping("/substitutions/my")
    @PreAuthorize("hasAuthority('" + Permissions.SUBSTITUTION_VIEW_OWN + "')")
    public List<SubstitutionRecordDto> myRecords(Principal principal) {
        return service.myRecords(principal.getName());
    }

    @GetMapping("/substitutions/stats")
    @PreAuthorize("hasAuthority('" + Permissions.SUBSTITUTION_VIEW + "')")
    public TeacherStatsDto stats(@RequestParam Long teacherId) {
        return service.stats(teacherId);
    }
}
