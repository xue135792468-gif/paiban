package com.paiban.foundation.web;

import com.paiban.foundation.service.FoundationService;
import com.paiban.foundation.web.FoundationDtos.AuditLogDto;
import com.paiban.foundation.web.FoundationDtos.ClassDto;
import com.paiban.foundation.web.FoundationDtos.CourseDto;
import com.paiban.foundation.web.FoundationDtos.GradeDto;
import com.paiban.foundation.web.FoundationDtos.RoomDto;
import com.paiban.foundation.web.FoundationDtos.SaveClassRequest;
import com.paiban.foundation.web.FoundationDtos.SaveCourseRequest;
import com.paiban.foundation.web.FoundationDtos.SaveGradeRequest;
import com.paiban.foundation.web.FoundationDtos.SaveRoomRequest;
import com.paiban.foundation.web.FoundationDtos.SaveTeacherRequest;
import com.paiban.foundation.web.FoundationDtos.TeacherDto;
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

    public FoundationController(FoundationService service) {
        this.service = service;
    }

    @GetMapping("/teachers")
    public List<TeacherDto> teachers() {
        return service.listTeachers();
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

    @DeleteMapping("/teachers/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.FOUNDATION_MANAGE + "')")
    public void deleteTeacher(@PathVariable Long id) {
        service.deleteTeacher(id);
    }

    @GetMapping("/rooms")
    public List<RoomDto> rooms() {
        return service.listRooms();
    }

    @PostMapping("/rooms")
    @PreAuthorize("hasAuthority('" + Permissions.FOUNDATION_MANAGE + "')")
    public RoomDto createRoom(@Valid @RequestBody SaveRoomRequest request) {
        return service.createRoom(request);
    }

    @PutMapping("/rooms/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.FOUNDATION_MANAGE + "')")
    public RoomDto updateRoom(@PathVariable Long id, @Valid @RequestBody SaveRoomRequest request) {
        return service.updateRoom(id, request);
    }

    @DeleteMapping("/rooms/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.FOUNDATION_MANAGE + "')")
    public void deleteRoom(@PathVariable Long id) {
        service.deleteRoom(id);
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

    @DeleteMapping("/courses/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.FOUNDATION_MANAGE + "')")
    public void deleteCourse(@PathVariable Long id) {
        service.deleteCourse(id);
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

    @DeleteMapping("/grades/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.FOUNDATION_MANAGE + "')")
    public void deleteGrade(@PathVariable Long id) {
        service.deleteGrade(id);
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

    @DeleteMapping("/classes/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.FOUNDATION_MANAGE + "')")
    public void deleteClass(@PathVariable Long id) {
        service.deleteClass(id);
    }

    @GetMapping("/foundation/audit-logs")
    @PreAuthorize("hasAuthority('" + Permissions.FOUNDATION_MANAGE + "')")
    public List<AuditLogDto> auditLogs() {
        return service.auditLogs();
    }
}
