package com.paiban.integration.auth;

import com.paiban.common.settings.SettingsService;
import com.paiban.common.web.ApiException;
import com.paiban.foundation.domain.Teacher;
import com.paiban.foundation.domain.TeacherRepository;
import com.paiban.foundation.service.FoundationService;
import com.paiban.identity.domain.AppUser;
import com.paiban.identity.service.AuthService;
import com.paiban.identity.service.UserService;
import com.paiban.identity.web.IdentityDtos.LoginResponse;
import com.paiban.integration.feishu.FeishuUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Self-service binding for teachers arriving from Feishu (ticket 05).
 *
 * <p>The teacher table has no ID-card column, so identity is proven with the exact
 * name plus employee number and/or phone. One Feishu identity maps to exactly one
 * teacher and vice versa; conflicts are reported instead of silently rebinding.
 */
@Service
public class FeishuBindingService {

    static final String NOT_FOUND = "未找到您的信息，请联系教务管理员";
    static final String IDENTITY_TAKEN = "该飞书账号已绑定其他教师，请联系教务管理员";
    static final String TEACHER_TAKEN = "该教师已绑定其他飞书账号，请联系教务管理员";
    static final String NO_ACCOUNT = "该教师尚未开通系统账号，请联系教务管理员";

    private final TeacherRepository teachers;
    private final UserService users;
    private final FoundationService foundation;
    private final AuthService authService;
    private final SettingsService settings;

    public FeishuBindingService(
            TeacherRepository teachers,
            UserService users,
            FoundationService foundation,
            AuthService authService,
            SettingsService settings) {
        this.teachers = teachers;
        this.users = users;
        this.foundation = foundation;
        this.authService = authService;
        this.settings = settings;
    }

    @Transactional
    public LoginResponse bind(FeishuUser identity, String rawName, String rawEmployeeNo, String rawPhone) {
        if (!settings.feishuEnabled()) {
            throw ApiException.badRequest("飞书集成未启用");
        }
        String name = rawName == null ? "" : rawName.trim();
        String employeeNo = blankToNull(rawEmployeeNo);
        String phone = blankToNull(rawPhone);
        if (name.isEmpty() || (employeeNo == null && phone == null)) {
            throw ApiException.badRequest("请填写姓名，并提供工号或手机号");
        }

        Teacher teacher = resolveTeacher(name, employeeNo, phone);
        requireIdentityNotTaken(identity, teacher);
        requireTeacherNotBoundElsewhere(identity, teacher);

        if (identity.userId() != null) {
            teacher.setFeishuUserId(identity.userId());
        }
        if (identity.openId() != null) {
            teacher.setFeishuOpenId(identity.openId());
        }
        teachers.save(teacher);
        foundation.audit("system", "FEISHU_BIND", "teacher",
                "教师 " + teacher.getName() + " 绑定飞书账号 " + identityLabel(identity), 1, 0);

        AppUser account = users.usersByTeacherId(teacher.getId()).stream()
                .filter(AppUser::isEnabled)
                .findFirst()
                .orElseThrow(() -> ApiException.conflict(NO_ACCOUNT));
        return authService.issueFor(account.getUsername());
    }

    private Teacher resolveTeacher(String name, String employeeNo, String phone) {
        Teacher byEmployee = employeeNo == null
                ? null
                : teachers.findByEmployeeNo(employeeNo).orElse(null);
        Teacher byPhone = phone == null
                ? null
                : teachers.findByPhone(phone).orElse(null);
        Teacher teacher;
        if (employeeNo != null && phone != null) {
            if (byEmployee == null || byPhone == null || !byEmployee.getId().equals(byPhone.getId())) {
                throw ApiException.notFound(NOT_FOUND);
            }
            teacher = byEmployee;
        } else {
            teacher = byEmployee != null ? byEmployee : byPhone;
            if (teacher == null) {
                throw ApiException.notFound(NOT_FOUND);
            }
        }
        String storedName = teacher.getName() == null ? "" : teacher.getName().trim();
        if (!name.equals(storedName)) {
            throw ApiException.notFound(NOT_FOUND);
        }
        return teacher;
    }

    /** The same Feishu account must never resolve to two teachers. */
    private void requireIdentityNotTaken(FeishuUser identity, Teacher teacher) {
        Teacher owner = null;
        if (identity.userId() != null) {
            owner = teachers.findByFeishuUserId(identity.userId()).orElse(null);
        }
        if (owner == null && identity.openId() != null) {
            owner = teachers.findByFeishuOpenId(identity.openId()).orElse(null);
        }
        if (owner != null && !owner.getId().equals(teacher.getId())) {
            throw ApiException.conflict(IDENTITY_TAKEN);
        }
    }

    /** A teacher bound to another Feishu account needs an administrator to rebind. */
    private void requireTeacherNotBoundElsewhere(FeishuUser identity, Teacher teacher) {
        boolean bound = !isBlank(teacher.getFeishuUserId()) || !isBlank(teacher.getFeishuOpenId());
        if (!bound) {
            return;
        }
        boolean same = true;
        if (identity.userId() != null && !isBlank(teacher.getFeishuUserId())) {
            same = identity.userId().equals(teacher.getFeishuUserId());
        }
        if (identity.openId() != null && !isBlank(teacher.getFeishuOpenId())) {
            same = same && identity.openId().equals(teacher.getFeishuOpenId());
        }
        if (!same) {
            throw ApiException.conflict(TEACHER_TAKEN);
        }
    }

    private static String identityLabel(FeishuUser identity) {
        if (!isBlank(identity.userId())) {
            return identity.userId();
        }
        return identity.openId();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
