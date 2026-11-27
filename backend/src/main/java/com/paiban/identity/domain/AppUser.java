package com.paiban.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "app_user")
@Getter
@Setter
@NoArgsConstructor
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String username;

    /** DINGTALK 账号没有密码，因此这里允许 null；组合由 V62 的 CHECK 与实体默认值共同保证。 */
    @Column(name = "password_hash", length = 100)
    private String passwordHash;

    /** 票 03：登录类型。新账号默认 PASSWORD；钉钉免登账号显式设为 DINGTALK 且哈希为 null。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "login_type", nullable = false, length = 16)
    private LoginType loginType = LoginType.PASSWORD;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "teacher_id")
    private Long teacherId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
            name = "user_role",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id"))
    private Set<Role> roles = new HashSet<>();

    public boolean hasRole(String roleCode) {
        return roles.stream().anyMatch(role -> role.getCode().equals(roleCode));
    }
}
