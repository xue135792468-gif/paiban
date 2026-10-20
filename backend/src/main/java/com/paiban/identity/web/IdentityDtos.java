package com.paiban.identity.web;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

public final class IdentityDtos {

    private IdentityDtos() {}

    public record LoginRequest(@NotBlank(message = "用户名不能为空") String username,
                               @NotBlank(message = "密码不能为空") String password) {}

    public record UserSummary(Long id,
                              String username,
                              String displayName,
                              boolean enabled,
                              Long teacherId,
                              List<String> roles,
                              List<String> permissions) {}

    public record LoginResponse(String token, long expiresInMinutes, UserSummary user) {}

    public record CreateUserRequest(@NotBlank(message = "用户名不能为空") String username,
                                    @NotBlank(message = "密码不能为空") String password,
                                    @NotBlank(message = "姓名不能为空") String displayName,
                                    List<String> roleCodes,
                                    Long teacherId) {}

    public record UpdateUserRequest(String displayName,
                                    Boolean enabled,
                                    List<String> roleCodes,
                                    Long teacherId) {}

    public record ResetPasswordRequest(@NotBlank(message = "新密码不能为空") String newPassword) {}

    public record RoleDto(Long id, String code, String name, List<String> permissions) {}
}
