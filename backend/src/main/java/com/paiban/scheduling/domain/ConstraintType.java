package com.paiban.scheduling.domain;

import java.util.List;

/**
 * The preset constraint catalogue. Each entry declares its parameter schema so
 * the UI can render a form and the API can validate input without per-type code.
 */
public enum ConstraintType {

    TEACHER_CONFLICT("教师不冲突", Category.CONFLICT, true, 100,
            "同一教师在同一课节只能出现在一个班级", List.of()),
    CLASS_CONFLICT("班级不冲突", Category.CONFLICT, true, 100,
            "同一班级在同一课节只能有一门课", List.of()),
    ROOM_CONFLICT("教室不冲突", Category.RESOURCE, true, 100,
            "同一教室在同一课节只能被一节课占用", List.of()),
    ROOM_CAPACITY_FIT("教室容量足够", Category.RESOURCE, true, 100,
            "教室容量不得小于班级人数", List.of()),

    TEACHER_UNAVAILABLE("教师禁排时段", Category.PREFERENCE, true, 100,
            "指定教师在某个课节不排课（如周三下午教研）",
            List.of(Param.id("teacherId", "教师"), Param.day("dayOfWeek", "星期"),
                    Param.period("periodNo", "第几节"))),
    TEACHER_MEETING_BLOCKED("教研活动禁排", Category.PREFERENCE, true, 100,
            "指定学科教师在某课节统一参加教研，不排课",
            List.of(Param.textOptional("subject", "学科（留空表示全部）"),
                    Param.day("dayOfWeek", "星期"), Param.period("periodNo", "第几节"))),
    COURSE_REQUIRED_ROOM_TYPE("课程指定教室类型", Category.RESOURCE, true, 100,
            "课程必须安排在指定类型的教室（如体育在操场）",
            List.of(Param.id("courseId", "课程"), Param.roomType("roomType", "教室类型"))),
    COURSE_NOT_IN_EVENING("课程不排晚自习", Category.PEDAGOGY, true, 100,
            "指定课程不安排在晚自习时段",
            List.of(Param.id("courseId", "课程"))),
    SAME_COURSE_ONCE_PER_DAY("同一课程一天最多一节", Category.PEDAGOGY, true, 100,
            "同一班级的同一课程每天最多安排一节",
            List.of(Param.idOptional("courseId", "课程（留空表示全部）"))),

    TEACHER_MAX_PER_DAY("教师一天最多 N 节", Category.PEDAGOGY, false, 60,
            "限制教师单日授课节数，避免过度集中",
            List.of(Param.integer("maxPerDay", "每天最多节数", 1, 12, 4))),
    CLASS_MAX_PER_DAY("班级一天最多 N 节", Category.PEDAGOGY, false, 50,
            "限制班级单日课程总数",
            List.of(Param.integer("maxPerDay", "每天最多节数", 1, 12, 8))),
    COURSE_MAX_PER_DAY("课程一天最多 N 节", Category.PEDAGOGY, false, 50,
            "限制同一班级某课程的单日节数",
            List.of(Param.integer("maxPerDay", "每天最多节数", 1, 8, 2))),
    EVENING_MAX("晚自习最多 N 节", Category.PEDAGOGY, false, 40,
            "限制每天晚自习的课程节数",
            List.of(Param.integer("maxPerDay", "每天最多节数", 0, 6, 2))),

    SPORT_NOT_FIRST_AFTERNOON("体育不在下午第一节", Category.PEDAGOGY, false, 70,
            "体育课避免安排在午休后的第一节",
            List.of(Param.idOptional("courseId", "课程（留空表示体育类）"))),
    CONSECUTIVE_LESSONS("连堂课连排", Category.PEDAGOGY, false, 60,
            "允许连堂的课程尽量安排在相邻课节",
            List.of(Param.id("courseId", "课程"), Param.integer("minConsecutive", "连排节数", 2, 4, 2))),
    COURSE_SPREAD("同一课程分散到不同天", Category.PEDAGOGY, false, 50,
            "同一班级的课程尽量分散到不同工作日",
            List.of(Param.idOptional("courseId", "课程（留空表示全部）"))),
    MAIN_SUBJECT_MORNING("主科尽量排上午", Category.PEDAGOGY, false, 55,
            "语文、数学、英语等主科优先安排在上午",
            List.of(Param.id("courseId", "课程"), Param.integer("beforePeriodNo", "上午截止节次", 2, 8, 4))),

    TEACHER_PREFERRED_SLOT("教师偏好时段", Category.PREFERENCE, false, 40,
            "尽量把指定教师的课排在其偏好课节",
            List.of(Param.id("teacherId", "教师"), Param.day("dayOfWeek", "星期"),
                    Param.period("periodNo", "第几节"))),
    TEACHER_MAX_CONSECUTIVE("教师连续上课上限", Category.PREFERENCE, false, 45,
            "同一教师连续授课不超过 N 节",
            List.of(Param.integer("maxConsecutive", "最多连续节数", 1, 8, 3))),
    TEACHER_NO_FIRST_PERIOD("教师不排第一节", Category.PREFERENCE, false, 35,
            "尽量不把指定教师的课排在每天第一节",
            List.of(Param.id("teacherId", "教师"))),
    TEACHER_DAY_OFF("教师固定无课日", Category.PREFERENCE, false, 30,
            "尽量为指定教师保留一天无课",
            List.of(Param.id("teacherId", "教师"), Param.day("dayOfWeek", "星期"))),
    CLASS_GAP_MINIMIZED("班级尽量不空堂", Category.PEDAGOGY, false, 30,
            "同一班级的课程尽量连续，减少空堂",
            List.of()),
    PE_LAST_PERIOD_AVOID("体育不在最后一节", Category.PEDAGOGY, false, 35,
            "体育课避免安排在当天最后一节",
            List.of(Param.idOptional("courseId", "课程（留空表示体育类）"))),
    HEAD_TEACHER_FIRST_PERIOD("班主任课优先第一节", Category.PREFERENCE, false, 20,
            "班主任所带课程尽量安排在本班第一节",
            List.of());

    public enum Category {
        CONFLICT("冲突类"),
        RESOURCE("资源类"),
        PEDAGOGY("教学规律"),
        PREFERENCE("教师偏好");

        private final String label;

        Category(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    public enum ParamType {
        ID, INT, DAY, PERIOD, TEXT, ROOM_TYPE
    }

    public record Param(String name,
                        String label,
                        ParamType type,
                        boolean required,
                        Integer min,
                        Integer max,
                        Object defaultValue,
                        List<String> options) {

        public static Param id(String name, String label) {
            return new Param(name, label, ParamType.ID, true, null, null, null, List.of());
        }

        public static Param idOptional(String name, String label) {
            return new Param(name, label, ParamType.ID, false, null, null, null, List.of());
        }

        public static Param integer(String name, String label, int min, int max, int defaultValue) {
            return new Param(name, label, ParamType.INT, true, min, max, defaultValue, List.of());
        }

        public static Param day(String name, String label) {
            return new Param(name, label, ParamType.DAY, true, 1, 7, 1, List.of());
        }

        public static Param period(String name, String label) {
            return new Param(name, label, ParamType.PERIOD, true, 1, 20, 1, List.of());
        }

        public static Param textOptional(String name, String label) {
            return new Param(name, label, ParamType.TEXT, false, null, null, null, List.of());
        }

        public static Param roomType(String name, String label) {
            return new Param(name, label, ParamType.ROOM_TYPE, true, null, null, "NORMAL",
                    List.of("NORMAL", "LAB", "PLAYGROUND", "MULTIMEDIA", "OTHER"));
        }
    }

    private final String displayName;
    private final Category category;
    private final boolean defaultHard;
    private final int defaultWeight;
    private final String description;
    private final List<Param> params;

    ConstraintType(
            String displayName,
            Category category,
            boolean defaultHard,
            int defaultWeight,
            String description,
            List<Param> params) {
        this.displayName = displayName;
        this.category = category;
        this.defaultHard = defaultHard;
        this.defaultWeight = defaultWeight;
        this.description = description;
        this.params = params;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Category getCategory() {
        return category;
    }

    public boolean isDefaultHard() {
        return defaultHard;
    }

    public int getDefaultWeight() {
        return defaultWeight;
    }

    public String getDescription() {
        return description;
    }

    public List<Param> getParams() {
        return params;
    }
}
