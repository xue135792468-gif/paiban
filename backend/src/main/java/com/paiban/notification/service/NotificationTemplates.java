package com.paiban.notification.service;

import java.util.LinkedHashMap;
import java.util.Map;

public final class NotificationTemplates {

    public record Template(String title, String body) {}

    public static final String SUBSTITUTION_EFFECTIVE_ORIGINAL = "SUBSTITUTION_EFFECTIVE_ORIGINAL";
    public static final String SUBSTITUTION_EFFECTIVE_SUBSTITUTE = "SUBSTITUTION_EFFECTIVE_SUBSTITUTE";
    public static final String SUBSTITUTION_EFFECTIVE_HEAD_TEACHER = "SUBSTITUTION_EFFECTIVE_HEAD_TEACHER";
    public static final String SUBSTITUTION_EFFECTIVE_ADMIN = "SUBSTITUTION_EFFECTIVE_ADMIN";
    public static final String SUBSTITUTION_REJECTED = "SUBSTITUTION_REJECTED";
    public static final String SUBSTITUTION_SWAP_EFFECTIVE = "SUBSTITUTION_SWAP_EFFECTIVE";
    public static final String SUBSTITUTION_SWAP_HEAD_TEACHER = "SUBSTITUTION_SWAP_HEAD_TEACHER";
    public static final String SUBSTITUTION_SWAP_ADMIN = "SUBSTITUTION_SWAP_ADMIN";
    public static final String SUBSTITUTION_VERIFY_ALERT = "SUBSTITUTION_VERIFY_ALERT";
    public static final String SUBSTITUTION_PLAN_FAILED = "SUBSTITUTION_PLAN_FAILED";
    public static final String TIMETABLE_PUBLISHED = "TIMETABLE_PUBLISHED";

    private static final Map<String, Template> TEMPLATES = new LinkedHashMap<>();

    static {
        TEMPLATES.put(SUBSTITUTION_EFFECTIVE_ORIGINAL, new Template(
                "调代申请已批准",
                "您 [[${date}]] [[${periodLabel}]] 在 [[${className}]] 的 [[${courseName}]] 课已由 "
                        + "[[${substituteTeacher}]] 代课。"));
        TEMPLATES.put(SUBSTITUTION_EFFECTIVE_SUBSTITUTE, new Template(
                "您有一节代课安排",
                "请于 [[${date}]] [[${periodLabel}]] 到 [[${roomName}]] 为 [[${className}]] 代上 "
                        + "[[${courseName}]]（原教师：[[${originalTeacher}]]）。"));
        TEMPLATES.put(SUBSTITUTION_EFFECTIVE_HEAD_TEACHER, new Template(
                "本班课表有调代",
                "[[${className}]] [[${date}]] [[${periodLabel}]] 的 [[${courseName}]] 由 "
                        + "[[${originalTeacher}]] 调整为 [[${substituteTeacher}]] 代课。"));
        TEMPLATES.put(SUBSTITUTION_EFFECTIVE_ADMIN, new Template(
                "调代已生效",
                "[[${className}]] [[${date}]] [[${periodLabel}]] 的 [[${courseName}]]："
                        + "[[${originalTeacher}]] → [[${substituteTeacher}]]。"));
        TEMPLATES.put(SUBSTITUTION_REJECTED, new Template(
                "调代申请被驳回",
                "您 [[${date}]] [[${periodLabel}]] 在 [[${className}]] 的调代申请被驳回。审批意见：[[${comment}]]"));
        TEMPLATES.put(TIMETABLE_PUBLISHED, new Template(
                "课表已发布",
                "[[${semesterName}]] 的课表已发布，可在「课表」中查看最新安排。"));
        TEMPLATES.put(SUBSTITUTION_SWAP_EFFECTIVE, new Template(
                "您的调课已生效",
                "您参与的调课已整单批准：[[${details}]]。"));
        TEMPLATES.put(SUBSTITUTION_SWAP_HEAD_TEACHER, new Template(
                "本班课表有调课",
                "[[${className}]] 的调课已生效：[[${details}]]。"));
        TEMPLATES.put(SUBSTITUTION_SWAP_ADMIN, new Template(
                "调课方案已生效",
                "[[${className}]]：[[${details}]]。"));
        TEMPLATES.put(SUBSTITUTION_PLAN_FAILED, new Template(
                "调课方案未通过校验",
                "您的调课申请未能生成可生效的方案：[[${details}]]。请重新选择时间后再提交。"));
        TEMPLATES.put(SUBSTITUTION_VERIFY_ALERT, new Template(
                "调课方案校验失败",
                "一笔调课方案在批准前校验失败，已整单拒绝：[[${details}]]。请检查课表数据。"));
    }

    private NotificationTemplates() {}

    public static Template get(String code) {
        return TEMPLATES.get(code);
    }
}
