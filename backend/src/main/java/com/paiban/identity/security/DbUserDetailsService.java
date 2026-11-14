package com.paiban.identity.security;

import com.paiban.identity.domain.AppUser;
import com.paiban.identity.domain.AppUserRepository;
import java.util.ArrayList;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class DbUserDetailsService implements UserDetailsService {

    /**
     * 票 03：DINGTALK 账号没有密码。Spring 的 {@code User} 不允许 null 密码，所以这里给一个
     * <b>只在内存里</b>的空密码哨兵来装载 JWT 的权限上下文——它从不写回数据库，也不被当成
     * 「可登录的密码」：密码入口在 {@code LocalAuthProvider} 里按登录类型先拒绝。
     */
    private static final String PASSWORDLESS_SENTINEL = "";

    private final AppUserRepository users;

    public DbUserDetailsService(AppUserRepository users) {
        this.users = users;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        AppUser user = users.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("用户不存在: " + username));
        List<GrantedAuthority> authorities = new ArrayList<>();
        user.getRoles().forEach(role -> {
            authorities.add(new SimpleGrantedAuthority("ROLE_" + role.getCode()));
            role.getPermissions().forEach(permission ->
                    authorities.add(new SimpleGrantedAuthority(permission.getCode())));
        });
        return User.withUsername(user.getUsername())
                .password(user.getPasswordHash() == null ? PASSWORDLESS_SENTINEL : user.getPasswordHash())
                .disabled(!user.isEnabled())
                .authorities(authorities)
                .build();
    }
}
