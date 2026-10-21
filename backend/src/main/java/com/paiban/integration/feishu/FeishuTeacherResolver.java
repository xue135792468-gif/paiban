package com.paiban.integration.feishu;

import com.paiban.foundation.domain.Teacher;
import com.paiban.foundation.domain.TeacherRepository;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Maps a Feishu identity onto a local teacher. Two matching orders exist on
 * purpose:
 *
 * <ul>
 *   <li>{@link #resolveBound} (userId -> openId) is used by login and binding, where
 *       an employee number is not proof of identity and must never grant access.</li>
 *   <li>{@link #resolveForSync} (userId -> openId -> employeeNo) is used by directory
 *       sync and contact events, where the employee number is the fallback that lets
 *       a freshly synced teacher be linked before any Feishu id is stored.</li>
 * </ul>
 */
@Component
public class FeishuTeacherResolver {

    private final TeacherRepository teachers;

    public FeishuTeacherResolver(TeacherRepository teachers) {
        this.teachers = teachers;
    }

    /** userId -> openId only; never falls back to the employee number. */
    public Optional<Teacher> resolveBound(FeishuUser user) {
        if (user == null) {
            return Optional.empty();
        }
        if (user.userId() != null) {
            Optional<Teacher> byUserId = teachers.findByFeishuUserId(user.userId());
            if (byUserId.isPresent()) {
                return byUserId;
            }
        }
        if (user.openId() != null) {
            return teachers.findByFeishuOpenId(user.openId());
        }
        return Optional.empty();
    }

    /** userId -> openId -> employeeNo; the order used by org sync and contact events. */
    public Optional<Teacher> resolveForSync(FeishuUser user) {
        if (user == null) {
            return Optional.empty();
        }
        Optional<Teacher> bound = resolveBound(user);
        if (bound.isPresent()) {
            return bound;
        }
        if (user.employeeNo() != null) {
            return teachers.findByEmployeeNo(user.employeeNo());
        }
        return Optional.empty();
    }
}
