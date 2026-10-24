package com.paiban.integration.org;

import com.paiban.foundation.domain.Teacher;
import com.paiban.foundation.domain.TeacherRepository;
import com.paiban.integration.domain.DingUser;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Fixed matching order for DingTalk users: {@code dingtalk_user_id} -> employee
 * number -> phone. Names are deliberately not used here; an ambiguous name match is
 * surfaced for confirmation by the caller instead of being merged automatically.
 */
@Component
public class DingTalkTeacherMatcher {

    private final TeacherRepository teachers;

    public DingTalkTeacherMatcher(TeacherRepository teachers) {
        this.teachers = teachers;
    }

    public Optional<Teacher> match(DingUser user) {
        if (user == null) {
            return Optional.empty();
        }
        if (notBlank(user.userId())) {
            Optional<Teacher> byDingtalkId = teachers.findByDingtalkUserId(user.userId());
            if (byDingtalkId.isPresent()) {
                return byDingtalkId;
            }
        }
        if (notBlank(user.jobNumber())) {
            Optional<Teacher> byEmployeeNo = teachers.findByEmployeeNo(user.jobNumber());
            if (byEmployeeNo.isPresent()) {
                return byEmployeeNo;
            }
        }
        if (notBlank(user.mobile())) {
            return teachers.findByPhone(user.mobile());
        }
        return Optional.empty();
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
