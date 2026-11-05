package com.paiban.foundation.web;

import com.paiban.common.deletion.DeleteMode;
import com.paiban.common.deletion.EntityType;
import com.paiban.foundation.service.FoundationService;
import com.paiban.foundation.service.LifecycleService;
import com.paiban.foundation.web.FoundationDtos.AuditLogDto;
import com.paiban.foundation.web.FoundationDtos.ClassDto;
import com.paiban.foundation.web.FoundationDtos.CourseDto;
import com.paiban.foundation.web.FoundationDtos.GradeDto;
import com.paiban.foundation.web.FoundationDtos.SaveClassRequest;
import com.paiban.foundation.web.FoundationDtos.SaveCourseRequest;
import com.paiban.foundation.web.FoundationDtos.SaveGradeRequest;
import com.paiban.foundation.web.FoundationDtos.SaveTeacherRequest;
import com.paiban.foundation.web.FoundationDtos.TeacherDto;
import com.paiban.foundation.web.LifecycleDtos.DeleteImpactDto;
import com.paiban.foundation.web.LifecycleDtos.DeleteResultDto;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class FoundationController {

    private final FoundationService service;
    private final LifecycleService lifecycle;

    public FoundationController(FoundationService service, LifecycleService lifecycle) {
        this.service = service;
        this.lifecycle = lifecycle;
    }

    @GetMapping("/teachers")
    @PreAuthorize("hasAuthority('" + Permissions.FOUNDATION_MANAGE + "')")
    public List<TeacherDto> teachers() {
        return service.listTeachers();
    }

    /** Id + name only, so teachers can pick substitutes without seeing phone/email. */
    @GetMapping("/teachers/options")
    public List<FoundationDtos.TeacherOptionDto> teacherOptions() {
        return service.listTeacherOptions().stream()
                .map(teacher -> new FoundationDtos.TeacherOptionDto(teacher.id(), teacher.name()))
                .toList();
    }

    @PostMapping("/teachers")
    @PreAuthorize("hasAuthority('" + Permissions.FOUNDATION_MANAGE + "')")
    public TeacherDto createTeacher(@Valid @RequestBody SaveTeacherRequest request) {
        return service.createTeacher(request);
    }

    @PutMapping("/teachers/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.FOUNDATION_MANAGE + "')")
    public TeacherDto updateTeacher(@PathVariable Long id, @Valid @RequestBody SaveTeacherRequest request) {
        return service.updateTeacher(id, request);
    }

    @GetMapping("/teachers/{id}/delete-impact")
    @PreAuthorize("hasAuthority('" + Permissions.FOUNDATION_MANAGE + "')")
    public DeleteImpactDto teacherDeleteImpact(@PathVariable Long id) {
        return lifecycle.impact(EntityType.TEACHER, id);
    }

    @DeleteMapping("/teachers/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.FOUNDATION_MANAGE + "')")
    public DeleteResultDto deleteTeacher(
            @PathVariable Long id, @RequestParam(required = false) String mode) {
        return lifecycle.delete(EntityType.TEACHER, id, DeleteMode.parse(mode));
    }

    @PostMapping("/teachers/{id}/disable")
    @PreAuthorize("hasAuthority('" + Permissions.FOUNDATION_MANAGE + "')")
    public void disableTeacher(@PathVariable Long id) {
        lifecycle.disable(EntityType.TEACHER, id);
    }

    @PostMapping("/teachers/{id}/enable")
    @PreAuthorize("hasAuthority('" + Permissions.FOUNDATION_MANAGE + "')")
    public void enableTeacher(@PathVariable Long id) {
        lifecycle.enable(EntityType.TEACHER, id);
    }

    @GetMapping("/courses")
    public List<CourseDto> courses() {
        return service.listCourses();
    }

    @PostMapping("/courses")
    @PreAuthorize("hasAuthority('" + Permissions.FOUNDATION_MANAGE + "')")
    public CourseDto createCourse(@Valid @RequestBody SaveCourseRequest request) {
        return service.createCourse(request);
    }

    @PutMapping("/courses/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.FOUNDATION_MANAGE + "')")
    public CourseDto updateCourse(@PathVariable Long id, @Valid @RequestBody SaveCourseRequest request) {
        return service.updateCourse(id, request);
    }

    @GetMapping("/courses/{id}/delete-impact")
    @PreAuthorize("hasAuthority('" + Permissions.FOUNDATION_MANAGE + "')")
    public DeleteImpactDto courseDeleteImpact(@PathVariable Long id) {
        return lifecycle.impact(EntityType.COURSE, id);
    }

    @DeleteMapping("/courses/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.FOUNDATION_MANAGE + "')")
    public DeleteResultDto deleteCourse(
            @PathVariable Long id, @RequestParam(required = false) String mode) {
        return lifecycle.delete(EntityType.COURSE, id, DeleteMode.parse(mode));
    }

    @PostMapping("/courses/{id}/disable")
    @PreAuthorize("hasAuthority('" + Permissions.FOUNDATION_MANAGE + "')")
    public void disableCourse(@PathVariable Long id) {
        lifecycle.disable(EntityType.COURSE, id);
    }

    @PostMapping("/courses/{id}/enable")
    @PreAuthorize("hasAuthority('" + Permissions.FOUNDATION_MANAGE + "')")
    public void enableCourse(@PathVariable Long id) {
        lifecycle.enable(EntityType.COURSE, id);
    }

    @GetMapping("/grades")
    public List<GradeDto> grades(@RequestParam(required = false) Long semesterId) {
        return service.listGrades(semesterId);
    }

    @PostMapping("/grades")
    @PreAuthorize("hasAuthority('" + Permissions.FOUNDATION_MANAGE + "')")
    public GradeDto createGrade(
            @RequestParam(required = false) Long semesterId, @Valid @RequestBody SaveGradeRequest request) {
        return service.createGrade(semesterId, request);
    }

    @PutMapping("/grades/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.FOUNDATION_MANAGE + "')")
    public GradeDto updateGrade(@PathVariable Long id, @Valid @RequestBody SaveGradeRequest request) {
        return service.updateGrade(id, request);
    }

    @GetMapping("/grades/{id}/delete-impact")
    @PreAuthorize("hasAuthority('" + Permissions.FOUNDATION_MANAGE + "')")
    public DeleteImpactDto gradeDeleteImpact(@PathVariable Long id) {
        return lifecycle.impact(EntityType.GRADE, id);
    }

    @DeleteMapping("/grades/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.FOUNDATION_MANAGE + "')")
    public DeleteResultDto deleteGrade(
            @PathVariable Long id, @RequestParam(required = false) String mode) {
        return lifecycle.delete(EntityType.GRADE, id, DeleteMode.parse(mode));
    }

    @PostMapping("/grades/{id}/disable")
    @PreAuthorize("hasAuthority('" + Permissions.FOUNDATION_MANAGE + "')")
    public void disableGrade(@PathVariable Long id) {
        lifecycle.disable(EntityType.GRADE, id);
    }

    @PostMapping("/grades/{id}/enable")
    @PreAuthorize("hasAuthority('" + Permissions.FOUNDATION_MANAGE + "')")
    public void enableGrade(@PathVariable Long id) {
        lifecycle.enable(EntityType.GRADE, id);
    }

    @GetMapping("/classes")
    public List<ClassDto> classes(@RequestParam(required = false) Long semesterId) {
        return service.listClasses(semesterId);
    }

    @PostMapping("/classes")
    @PreAuthorize("hasAuthority('" + Permissions.FOUNDATION_MANAGE + "')")
    public ClassDto createClass(
            @RequestParam(required = false) Long semesterId, @Valid @RequestBody SaveClassRequest request) {
        return service.createClass(semesterId, request);
    }

    @PutMapping("/classes/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.FOUNDATION_MANAGE + "')")
    public ClassDto updateClass(@PathVariable Long id, @Valid @RequestBody SaveClassRequest request) {
        return service.updateClass(id, request);
    }

    @GetMapping("/classes/{id}/delete-impact")
    @PreAuthorize("hasAuthority('" + Permissions.FOUNDATION_MANAGE + "')")
    public DeleteImpactDto classDeleteImpact(@PathVariable Long id) {
        return lifecycle.impact(EntityType.CLASS, id);
    }

    @DeleteMapping("/classes/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.FOUNDATION_MANAGE + "')")
    public DeleteResultDto deleteClass(
            @PathVariable Long id, @RequestParam(required = false) String mode) {
        return lifecycle.delete(EntityType.CLASS, id, DeleteMode.parse(mode));
    }

    @PostMapping("/classes/{id}/disable")
    @PreAuthorize("hasAuthority('" + Permissions.FOUNDATION_MANAGE + "')")
    public void disableClass(@PathVariable Long id) {
        lifecycle.disable(EntityType.CLASS, id);
    }

    @PostMapping("/classes/{id}/enable")
    @PreAuthorize("hasAuthority('" + Permissions.FOUNDATION_MANAGE + "')")
    public void enableClass(@PathVariable Long id) {
        lifecycle.enable(EntityType.CLASS, id);
    }

    @GetMapping("/foundation/audit-logs")
    @PreAuthorize("hasAuthority('" + Permissions.FOUNDATION_MANAGE + "')")
    public List<AuditLogDto> auditLogs() {
        return service.auditLogs();
    }
}
