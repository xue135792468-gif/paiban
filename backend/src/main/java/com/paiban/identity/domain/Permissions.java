package com.paiban.identity.domain;

/**
 * 权限点命名规范：&lt;resource&gt;:&lt;action&gt;。
 * 所有 Controller 的 @PreAuthorize 引用这里的常量，避免字符串拼写错误。
 */
public final class Permissions {

    public static final String USER_MANAGE = "user:manage";
    public static final String SCHOOL_MANAGE = "school:manage";
    public static final String SEMESTER_MANAGE = "semester:manage";
    public static final String FOUNDATION_MANAGE = "foundation:manage";
    public static final String FOUNDATION_VIEW = "foundation:view";
    public static final String ASSIGNMENT_MANAGE = "assignment:manage";
    public static final String ASSIGNMENT_VIEW_OWN = "assignment:view:own";
    public static final String CONSTRAINT_MANAGE = "constraint:manage";
    public static final String SCHEDULING_RUN = "scheduling:run";
    public static final String SCHEDULING_VIEW = "scheduling:view";
    public static final String TIMETABLE_EDIT = "timetable:edit";
    public static final String TIMETABLE_PUBLISH = "timetable:publish";
    public static final String TIMETABLE_VIEW = "timetable:view";
    public static final String TIMETABLE_VIEW_OWN = "timetable:view:own";
    public static final String TIMETABLE_VIEW_CLASS = "timetable:view:class";
    public static final String SUBSTITUTION_SUBMIT = "substitution:submit";
    public static final String SUBSTITUTION_APPROVE = "substitution:approve";
    public static final String SUBSTITUTION_VIEW = "substitution:view";
    public static final String SUBSTITUTION_VIEW_OWN = "substitution:view:own";
    public static final String SUBSTITUTION_VIEW_CLASS = "substitution:view:class";
    public static final String NOTIFICATION_VIEW = "notification:view";
    public static final String SETTINGS_MANAGE = "settings:manage";
    public static final String INTEGRATION_MANAGE = "integration:manage";
    public static final String ONBOARDING_MANAGE = "onboarding:manage";
    public static final String EXPORT_TIMETABLE = "export:timetable";

    private Permissions() {}
}
