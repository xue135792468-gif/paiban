package com.paiban.integration.org;

import com.paiban.foundation.domain.Teacher;
import com.paiban.integration.domain.DingUser;
import com.paiban.integration.org.DingTalkSyncPlan.FieldChange;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The single source of truth for "what would change on this teacher".
 *
 * <p>Only empty local fields are filled; a non-empty local value is never
 * overwritten and shows up as a conflict instead. The DingTalk binding and the
 * active flag are sync-managed: the binding is filled when empty (a differing
 * non-empty binding is a conflict, so a wrong binding is not silently replaced)
 * and the active flag follows DingTalk.
 */
@Component
public class DingTalkMergeRules {

    public static final String NAME = "name";
    public static final String EMPLOYEE_NO = "employeeNo";
    public static final String PHONE = "phone";
    public static final String DINGTALK_USER_ID = "dingtalkUserId";
    public static final String ACTIVE = "active";

    public List<FieldChange> fills(Teacher teacher, DingUser user) {
        List<FieldChange> fills = new ArrayList<>();
        if (isBlank(teacher.getName()) && notBlank(user.name())) {
            fills.add(new FieldChange(NAME, "姓名", null, user.name()));
        }
        if (isBlank(teacher.getEmployeeNo()) && notBlank(user.jobNumber())) {
            fills.add(new FieldChange(EMPLOYEE_NO, "工号", null, user.jobNumber()));
        }
        if (isBlank(teacher.getPhone()) && notBlank(user.mobile())) {
            fills.add(new FieldChange(PHONE, "手机", null, user.mobile()));
        }
        if (isBlank(teacher.getDingtalkUserId()) && notBlank(user.userId())) {
            fills.add(new FieldChange(DINGTALK_USER_ID, "钉钉 UserID", null, user.userId()));
        }
        if (teacher.isActive() != user.active()) {
            fills.add(new FieldChange(ACTIVE, "状态", activeLabel(teacher.isActive()), activeLabel(user.active())));
        }
        return fills;
    }

    public List<FieldChange> conflicts(Teacher teacher, DingUser user) {
        List<FieldChange> conflicts = new ArrayList<>();
        if (notBlank(teacher.getName()) && notBlank(user.name()) && !teacher.getName().equals(user.name())) {
            conflicts.add(new FieldChange(NAME, "姓名", teacher.getName(), user.name()));
        }
        if (notBlank(teacher.getEmployeeNo())
                && notBlank(user.jobNumber())
                && !teacher.getEmployeeNo().equals(user.jobNumber())) {
            conflicts.add(new FieldChange(EMPLOYEE_NO, "工号", teacher.getEmployeeNo(), user.jobNumber()));
        }
        if (notBlank(teacher.getPhone())
                && notBlank(user.mobile())
                && !teacher.getPhone().equals(user.mobile())) {
            conflicts.add(new FieldChange(PHONE, "手机", teacher.getPhone(), user.mobile()));
        }
        if (notBlank(teacher.getDingtalkUserId())
                && notBlank(user.userId())
                && !teacher.getDingtalkUserId().equals(user.userId())) {
            conflicts.add(new FieldChange(
                    DINGTALK_USER_ID, "钉钉 UserID", teacher.getDingtalkUserId(), user.userId()));
        }
        return conflicts;
    }

    /** Applies exactly the field set returned by {@link #fills(Teacher, DingUser)}. */
    public void apply(Teacher teacher, DingUser user) {
        for (FieldChange change : fills(teacher, user)) {
            switch (change.field()) {
                case NAME -> teacher.setName(user.name());
                case EMPLOYEE_NO -> teacher.setEmployeeNo(user.jobNumber());
                case PHONE -> teacher.setPhone(user.mobile());
                case DINGTALK_USER_ID -> teacher.setDingtalkUserId(user.userId());
                case ACTIVE -> teacher.setActive(user.active());
                default -> throw new IllegalStateException("未知的同步字段: " + change.field());
            }
        }
    }

    private String activeLabel(boolean active) {
        return active ? "启用" : "停用";
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
