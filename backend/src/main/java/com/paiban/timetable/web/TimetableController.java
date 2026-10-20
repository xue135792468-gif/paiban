package com.paiban.timetable.web;

import com.paiban.common.web.ApiException;
import com.paiban.identity.domain.Permissions;
import com.paiban.timetable.service.TimetablePublishService;
import com.paiban.timetable.service.TimetableViewService;
import com.paiban.timetable.web.TimetableDtos.PublishInfoDto;
import com.paiban.timetable.web.TimetableDtos.PublishResultDto;
import com.paiban.timetable.web.TimetableDtos.SchoolTimetableDto;
import com.paiban.timetable.web.TimetableDtos.TimetableEntryDto;
import java.security.Principal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class TimetableController {

    private static final MediaType XLSX =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final TimetableViewService views;
    private final TimetablePublishService publishService;
    private final com.paiban.timetable.service.TimetableExportService exports;

    public TimetableController(
            TimetableViewService views,
            TimetablePublishService publishService,
            com.paiban.timetable.service.TimetableExportService exports) {
        this.views = views;
        this.publishService = publishService;
        this.exports = exports;
    }

    @GetMapping("/timetable/class/{classId}")
    public List<TimetableEntryDto> classView(
            @PathVariable Long classId,
            @RequestParam(defaultValue = "latest") String view,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            Authentication authentication) {
        boolean canAll = has(authentication, Permissions.TIMETABLE_VIEW);
        boolean canClass = has(authentication, Permissions.TIMETABLE_VIEW_CLASS);
        if (!views.canViewClass(authentication.getName(), canAll, canClass, classId)) {
            throw ApiException.forbidden("没有权限查看该班级课表");
        }
        views.requireClassExists(classId);
        return views.classView(classId, view, date);
    }

    @GetMapping("/timetable/teacher/{teacherId}")
    public List<TimetableEntryDto> teacherView(
            @PathVariable Long teacherId,
            @RequestParam(defaultValue = "latest") String view,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            Authentication authentication) {
        boolean canAll = has(authentication, Permissions.TIMETABLE_VIEW);
        boolean canOwn = has(authentication, Permissions.TIMETABLE_VIEW_OWN);
        if (!views.canViewTeacher(authentication.getName(), canAll, canOwn, teacherId)) {
            throw ApiException.forbidden("没有权限查看该教师课表");
        }
        return views.teacherView(teacherId, view, date);
    }

    @GetMapping("/timetable/room/{roomId}")
    @PreAuthorize("hasAuthority('" + Permissions.TIMETABLE_VIEW + "')")
    public List<TimetableEntryDto> roomView(
            @PathVariable Long roomId,
            @RequestParam(defaultValue = "latest") String view,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return views.roomView(roomId, view, date);
    }

    @GetMapping("/timetable/school")
    @PreAuthorize("hasAuthority('" + Permissions.TIMETABLE_VIEW + "')")
    public SchoolTimetableDto schoolView(
            @RequestParam(defaultValue = "latest") String view,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return views.schoolView(view, date);
    }

    @GetMapping("/timetable/class/{classId}/export/excel")
    @PreAuthorize("hasAuthority('" + Permissions.EXPORT_TIMETABLE + "')")
    public ResponseEntity<byte[]> exportClassExcel(
            @PathVariable Long classId,
            @RequestParam(defaultValue = "latest") String view,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return download(exports.excel("class", classId, view, date), "班级课表.xlsx", XLSX);
    }

    @GetMapping("/timetable/class/{classId}/export/pdf")
    @PreAuthorize("hasAuthority('" + Permissions.EXPORT_TIMETABLE + "')")
    public ResponseEntity<byte[]> exportClassPdf(
            @PathVariable Long classId,
            @RequestParam(defaultValue = "latest") String view,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return download(exports.pdf("class", classId, view, date), "班级课表.pdf", MediaType.APPLICATION_PDF);
    }

    @GetMapping("/timetable/teacher/{teacherId}/export/excel")
    @PreAuthorize("hasAuthority('" + Permissions.EXPORT_TIMETABLE + "')")
    public ResponseEntity<byte[]> exportTeacherExcel(
            @PathVariable Long teacherId,
            @RequestParam(defaultValue = "latest") String view,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return download(exports.excel("teacher", teacherId, view, date), "教师课表.xlsx", XLSX);
    }

    @GetMapping("/timetable/teacher/{teacherId}/export/pdf")
    @PreAuthorize("hasAuthority('" + Permissions.EXPORT_TIMETABLE + "')")
    public ResponseEntity<byte[]> exportTeacherPdf(
            @PathVariable Long teacherId,
            @RequestParam(defaultValue = "latest") String view,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return download(exports.pdf("teacher", teacherId, view, date), "教师课表.pdf", MediaType.APPLICATION_PDF);
    }

    @GetMapping("/timetable/room/{roomId}/export/excel")
    @PreAuthorize("hasAuthority('" + Permissions.EXPORT_TIMETABLE + "')")
    public ResponseEntity<byte[]> exportRoomExcel(
            @PathVariable Long roomId,
            @RequestParam(defaultValue = "latest") String view,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return download(exports.excel("room", roomId, view, date), "教室占用表.xlsx", XLSX);
    }

    @GetMapping("/timetable/room/{roomId}/export/pdf")
    @PreAuthorize("hasAuthority('" + Permissions.EXPORT_TIMETABLE + "')")
    public ResponseEntity<byte[]> exportRoomPdf(
            @PathVariable Long roomId,
            @RequestParam(defaultValue = "latest") String view,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return download(exports.pdf("room", roomId, view, date), "教室占用表.pdf", MediaType.APPLICATION_PDF);
    }

    @GetMapping("/timetable/school/export/excel")
    @PreAuthorize("hasAuthority('" + Permissions.EXPORT_TIMETABLE + "')")
    public ResponseEntity<byte[]> exportSchoolExcel(
            @RequestParam(defaultValue = "latest") String view,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return download(exports.excel("school", null, view, date), "全校总课表.xlsx", XLSX);
    }

    @GetMapping("/timetable/school/export/pdf")
    @PreAuthorize("hasAuthority('" + Permissions.EXPORT_TIMETABLE + "')")
    public ResponseEntity<byte[]> exportSchoolPdf(
            @RequestParam(defaultValue = "latest") String view,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return download(exports.pdf("school", null, view, date), "全校总课表.pdf", MediaType.APPLICATION_PDF);
    }

    private ResponseEntity<byte[]> download(byte[] content, String filename, MediaType type) {
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(filename, java.nio.charset.StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(type)
                .body(content);
    }

    @GetMapping("/timetable/publish-info")
    public PublishInfoDto publishInfo() {
        return publishService.publishInfo(views.semesterId());
    }

    @PostMapping("/timetable-drafts/{id}/publish")
    @PreAuthorize("hasAuthority('" + Permissions.TIMETABLE_PUBLISH + "')")
    public PublishResultDto publish(@PathVariable Long id, Principal principal) {
        return publishService.publish(id, principal == null ? "system" : principal.getName());
    }

    private boolean has(Authentication authentication, String authority) {
        return authentication.getAuthorities().stream()
                .anyMatch(granted -> granted.getAuthority().equals(authority));
    }
}
