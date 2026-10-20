package com.paiban.identity.web;

import com.paiban.foundation.web.ImportDtos.ImportReport;
import com.paiban.identity.domain.Permissions;
import com.paiban.identity.service.UserImportService;
import com.paiban.identity.service.UserService;
import com.paiban.identity.web.IdentityDtos.CreateUserRequest;
import com.paiban.identity.web.IdentityDtos.ResetPasswordRequest;
import com.paiban.identity.web.IdentityDtos.RoleDto;
import com.paiban.identity.web.IdentityDtos.UpdateUserRequest;
import com.paiban.identity.web.IdentityDtos.UserSummary;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class UserController {

    private final UserService userService;
    private final UserImportService userImportService;

    public UserController(UserService userService, UserImportService userImportService) {
        this.userService = userService;
        this.userImportService = userImportService;
    }

    @GetMapping("/users")
    @PreAuthorize("hasAuthority('" + Permissions.USER_MANAGE + "')")
    public List<UserSummary> list() {
        return userService.list();
    }

    @PostMapping("/users")
    @PreAuthorize("hasAuthority('" + Permissions.USER_MANAGE + "')")
    public UserSummary create(@Valid @RequestBody CreateUserRequest request) {
        return userService.create(request);
    }

    @PutMapping("/users/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.USER_MANAGE + "')")
    public UserSummary update(@PathVariable Long id, @RequestBody UpdateUserRequest request) {
        return userService.update(id, request);
    }

    @PostMapping("/users/{id}/reset-password")
    @PreAuthorize("hasAuthority('" + Permissions.USER_MANAGE + "')")
    public void resetPassword(@PathVariable Long id, @Valid @RequestBody ResetPasswordRequest request) {
        userService.resetPassword(id, request.newPassword());
    }

    @GetMapping("/roles")
    @PreAuthorize("hasAuthority('" + Permissions.USER_MANAGE + "')")
    public List<RoleDto> roles() {
        return userService.listRoles();
    }

    @PostMapping(value = "/users/import/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('" + Permissions.USER_MANAGE + "')")
    public ImportReport previewImport(@RequestParam("file") MultipartFile file) {
        return userImportService.preview(file);
    }

    @PostMapping(value = "/users/import/commit", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('" + Permissions.USER_MANAGE + "')")
    public ImportReport commitImport(@RequestParam("file") MultipartFile file) {
        return userImportService.commit(file);
    }

    @GetMapping("/users/import/template")
    @PreAuthorize("hasAuthority('" + Permissions.USER_MANAGE + "')")
    public ResponseEntity<byte[]> importTemplate() {
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename("用户账号导入模板.xlsx", StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(userImportService.template());
    }
}
