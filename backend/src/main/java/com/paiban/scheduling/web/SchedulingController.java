package com.paiban.scheduling.web;

import com.paiban.identity.domain.Permissions;
import com.paiban.scheduling.service.DraftValidator;
import com.paiban.scheduling.service.SchedulingService;
import com.paiban.scheduling.web.SchedulingDtos.DraftDetailDto;
import com.paiban.scheduling.web.SchedulingDtos.DraftDto;
import com.paiban.scheduling.web.SchedulingDtos.JobDto;
import com.paiban.scheduling.web.SchedulingDtos.MoveLessonRequest;
import com.paiban.scheduling.web.SchedulingDtos.MoveResult;
import com.paiban.scheduling.web.SchedulingDtos.RenameDraftRequest;
import com.paiban.scheduling.web.SchedulingDtos.StartJobRequest;
import com.paiban.scheduling.web.SchedulingDtos.ValidateMoveRequest;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class SchedulingController {

    private final SchedulingService service;

    public SchedulingController(SchedulingService service) {
        this.service = service;
    }

    @PostMapping("/scheduling-jobs")
    @PreAuthorize("hasAuthority('" + Permissions.SCHEDULING_RUN + "')")
    public JobDto start(
            @RequestParam(required = false) Long semesterId,
            @RequestBody(required = false) StartJobRequest request,
            Principal principal) {
        return service.start(semesterId, request, principal == null ? "system" : principal.getName());
    }

    @GetMapping("/scheduling-jobs/{id}")
    public JobDto get(@PathVariable Long id) {
        return service.get(id);
    }

    @GetMapping("/scheduling-jobs")
    public List<JobDto> list(@RequestParam(required = false) Long semesterId) {
        return service.listJobs(semesterId);
    }

    @DeleteMapping("/scheduling-jobs/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.SCHEDULING_RUN + "')")
    public JobDto cancel(@PathVariable Long id) {
        return service.cancel(id);
    }

    @GetMapping("/timetable-drafts")
    public List<DraftDto> drafts(@RequestParam(required = false) Long semesterId) {
        return service.listDrafts(semesterId);
    }

    @GetMapping("/timetable-drafts/{id}")
    public DraftDetailDto detail(@PathVariable Long id) {
        return service.draftDetail(id);
    }

    @PostMapping("/timetable-drafts/{id}/validate-move")
    public DraftValidator.ValidationResult validateMove(
            @PathVariable Long id, @Valid @RequestBody ValidateMoveRequest request) {
        return service.validateMove(id, request.lessonId(), request.periodId(), request.roomId());
    }

    @PatchMapping("/timetable-drafts/{id}/lessons/{lessonId}")
    @PreAuthorize("hasAuthority('" + Permissions.TIMETABLE_EDIT + "')")
    public MoveResult moveLesson(
            @PathVariable Long id,
            @PathVariable Long lessonId,
            @RequestBody MoveLessonRequest request) {
        return service.moveLesson(
                id,
                lessonId,
                request.periodId(),
                request.roomId(),
                Boolean.TRUE.equals(request.force()));
    }

    @PutMapping("/timetable-drafts/{id}/name")
    @PreAuthorize("hasAuthority('" + Permissions.TIMETABLE_EDIT + "')")
    public DraftDto rename(@PathVariable Long id, @Valid @RequestBody RenameDraftRequest request) {
        return service.renameDraft(id, request.name());
    }

    @DeleteMapping("/timetable-drafts/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.TIMETABLE_EDIT + "')")
    public void delete(@PathVariable Long id) {
        service.deleteDraft(id);
    }
}
