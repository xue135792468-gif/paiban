package com.paiban.scheduling.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.paiban.foundation.domain.PeriodSection;
import com.paiban.scheduling.domain.ConstraintType;
import com.paiban.scheduling.domain.ConstraintType.Param;
import com.paiban.scheduling.domain.ConstraintType.ParamType;
import com.paiban.scheduling.web.ConstraintProposalDtos.ConstraintProposalDto;
import com.paiban.scheduling.web.ConstraintProposalDtos.ObjectOptionDto;
import com.paiban.scheduling.web.ConstraintProposalDtos.ProposalCandidateDto;
import com.paiban.scheduling.web.ConstraintProposalDtos.ProposalEntryDto;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Turns the model's names-and-words answer into catalogue-shaped proposals (ticket
 * 14/08).
 *
 * <p>The model never supplies database ids: it names a teacher/course/class and the
 * server resolves that name against this semester's data. A name that matches several
 * objects becomes a candidate choice, a missing condition stays visible as
 * {@code NEEDS_INPUT}, an illegal slot or number is {@code INVALID}, and only a fully
 * resolved requirement becomes {@code READY} with saveable entries. Nothing is guessed,
 * truncated or widened to a similar type.
 */
public final class ConstraintProposalNormalizer {

    public static final String READY = "READY";
    public static final String NEEDS_INPUT = "NEEDS_INPUT";
    public static final String UNSUPPORTED = "UNSUPPORTED";
    public static final String INVALID = "INVALID";

    public record TeacherRef(long id, String name, String subject) {}

    public record CourseRef(long id, String name, String subject, String requiredRoomType) {}

    public record ClassRef(long id, String name) {}

    /**
     * The semester's actual timetable, grouped per day and section. {@code periods} are
     * absolute day period numbers, so "afternoon second period" is resolved by the
     * section's own list rather than by a hard-coded 5–8.
     */
    public record Context(
            List<TeacherRef> teachers,
            List<CourseRef> courses,
            List<ClassRef> classes,
            Map<Integer, Map<PeriodSection, List<Integer>>> timetable) {

        public Set<Integer> days() {
            return timetable.keySet().stream().sorted().collect(Collectors.toCollection(LinkedHashSet::new));
        }

        public List<Integer> periodNumbers(Integer day, PeriodSection section) {
            Map<PeriodSection, List<Integer>> sections = timetable.get(day);
            if (sections == null) {
                return List.of();
            }
            return sections.getOrDefault(section, List.of());
        }

        /** True when any day of the semester uses this absolute period number. */
        public boolean hasPeriodNo(int periodNo) {
            return timetable.values().stream()
                    .flatMap(sections -> sections.values().stream())
                    .anyMatch(numbers -> numbers.contains(periodNo));
        }

        public boolean hasSlot(int day, int periodNo) {
            Map<PeriodSection, List<Integer>> sections = timetable.get(day);
            return sections != null && sections.values().stream()
                    .anyMatch(numbers -> numbers.contains(periodNo));
        }

        /**
         * The last morning period when every day has the same morning blocks; null when
         * the school's mornings differ, which is the case where "上午" cannot be turned
         * into one cut-off without guessing.
         */
        public Integer morningCutoff() {
            Integer cutoff = null;
            for (Integer day : timetable.keySet()) {
                List<Integer> morning = periodNumbers(day, PeriodSection.MORNING);
                if (morning.isEmpty()) {
                    continue;
                }
                int max = morning.stream().mapToInt(Integer::intValue).max().orElse(0);
                if (cutoff == null) {
                    cutoff = max;
                } else if (cutoff != max) {
                    return null;
                }
            }
            return cutoff;
        }
    }

    private ConstraintProposalNormalizer() {}

    public static List<ConstraintProposalDto> normalize(
            List<JsonNode> proposals, Context context, String groupPrefix) {
        List<ConstraintProposalDto> normalized = new ArrayList<>();
        for (int index = 0; index < proposals.size(); index++) {
            normalized.add(normalizeOne(
                    proposals.get(index), index + 1, context, groupPrefix));
        }
        return normalized;
    }

    private static ConstraintProposalDto normalizeOne(
            JsonNode node, int index, Context context, String groupPrefix) {
        String proposalId = "p" + index;
        String sourceText = text(node, "sourceText");
        String typeCode = text(node, "type").toUpperCase(Locale.ROOT);
        String explanation = text(node, "explanation");
        String modelStatus = text(node, "status").toUpperCase(Locale.ROOT);
        String modelReason = text(node, "reason");

        ConstraintType type = null;
        try {
            type = typeCode.isBlank() ? null : ConstraintType.valueOf(typeCode);
        } catch (IllegalArgumentException ignored) {
            // handled below as UNSUPPORTED
        }
        if (type == null) {
            return proposal(proposalId, sourceText, typeCode, typeCode, "INHERIT", true, null,
                    UNSUPPORTED, explanation,
                    modelReason.isBlank() ? "现有约束目录不能精确表达这条要求" : modelReason,
                    Map.of(), List.of(), List.of());
        }

        if (UNSUPPORTED.equals(modelStatus)) {
            return proposal(proposalId, sourceText, type.name(), type.getDisplayName(),
                    "INHERIT", type.isDefaultHard(), type.isDefaultHard() ? null : type.getDefaultWeight(),
                    UNSUPPORTED, explanation,
                    modelReason.isBlank() ? "现有约束目录不能精确表达这条要求" : modelReason,
                    Map.of(), List.of(), List.of());
        }
        if (INVALID.equals(modelStatus) || NEEDS_INPUT.equals(modelStatus)) {
            String status = INVALID.equals(modelStatus) ? INVALID : NEEDS_INPUT;
            return proposal(proposalId, sourceText, type.name(), type.getDisplayName(),
                    strengthOf(node, type), effectiveHard(node, type),
                    effectiveWeight(node, type), status, explanation,
                    modelReason.isBlank() ? "这条要求还需要补充信息" : modelReason,
                    Map.of(), List.of(), List.of());
        }

        String strength = strengthOf(node, type);
        boolean effectiveHard = "HARD".equals(strength) || ("INHERIT".equals(strength) && type.isDefaultHard());
        Integer effectiveWeight = effectiveHard ? null : type.getDefaultWeight();
        Attempt attempt = new Attempt();
        String groupKey = type.isSlotGrouped() ? groupPrefix + "-" + proposalId : null;

        buildEntries(type, node, context, attempt, groupKey);

        if (READY.equals(attempt.status) && attempt.entries.isEmpty()) {
            attempt.needsInput("没有解析出可保存的条件");
        }
        Map<String, Object> displayParams = attempt.entries.isEmpty()
                ? Map.of() : attempt.entries.get(0).params();
        // Only a fully resolved requirement may carry saveable rows; a partially
        // resolved one stays visible with its reason and empty entries.
        List<ProposalEntryDto> entries = READY.equals(attempt.status)
                ? List.copyOf(attempt.entries) : List.of();
        return proposal(proposalId, sourceText, type.name(), type.getDisplayName(),
                strength, effectiveHard, effectiveWeight, attempt.status, explanation,
                attempt.reason, displayParams, entries, List.copyOf(attempt.candidates));
    }

    // ------------------------------------------------------------------ entries

    private static void buildEntries(
            ConstraintType type, JsonNode node, Context context, Attempt attempt,
            String groupKey) {
        switch (type) {
            case TEACHER_UNAVAILABLE, TEACHER_PREFERRED_SLOT -> {
                Long teacher = requiredTeacher(node, context, attempt);
                addSlotEntries(type, node, context, attempt, groupKey,
                        params -> params.put("teacherId", teacher));
            }
            case TEACHER_MEETING_BLOCKED -> {
                String subject = text(node, "subject");
                addSlotEntries(type, node, context, attempt, groupKey,
                        params -> {
                            if (!subject.isBlank()) {
                                params.put("subject", subject);
                            }
                        });
            }
            case COURSE_UNAVAILABLE -> {
                Long course = requiredCourse(node, context, attempt);
                addSlotEntries(type, node, context, attempt, groupKey,
                        params -> params.put("courseId", course));
            }
            case TEACHER_DAY_OFF -> {
                Long teacher = requiredTeacher(node, context, attempt);
                List<Integer> days = days(node, context, attempt);
                if (days.isEmpty() && READY.equals(attempt.status)) {
                    attempt.needsInput("缺少星期");
                }
                for (Integer day : days) {
                    if (!context.timetable().containsKey(day)) {
                        attempt.invalid("本学期没有周" + day + "的课节");
                        continue;
                    }
                    Map<String, Object> params = new LinkedHashMap<>();
                    params.put("teacherId", teacher);
                    params.put("dayOfWeek", day);
                    attempt.entries.add(entry(params, groupKey));
                }
            }
            case TEACHER_NO_FIRST_PERIOD -> {
                Long teacher = requiredTeacher(node, context, attempt);
                Map<String, Object> params = new LinkedHashMap<>();
                params.put("teacherId", teacher);
                attempt.entries.add(entry(params, null));
            }
            case COURSE_REQUIRED_ROOM_TYPE -> {
                Long course = requiredCourse(node, context, attempt);
                String roomType = text(node, "roomType");
                if (roomType.isBlank()) {
                    attempt.needsInput("缺少教室类型");
                } else if (roomTypeParam(type).map(Param::options)
                        .filter(options -> !options.contains(roomType)).isPresent()) {
                    attempt.invalid("教室类型无效: " + roomType);
                }
                Map<String, Object> params = new LinkedHashMap<>();
                params.put("courseId", course);
                params.put("roomType", roomType);
                attempt.entries.add(entry(params, null));
            }
            case COURSE_NOT_IN_EVENING -> {
                Long course = requiredCourse(node, context, attempt);
                Map<String, Object> params = new LinkedHashMap<>();
                params.put("courseId", course);
                attempt.entries.add(entry(params, null));
            }
            case SAME_COURSE_ONCE_PER_DAY, SPORT_NOT_FIRST_AFTERNOON,
                    PE_LAST_PERIOD_AVOID, COURSE_SPREAD -> {
                Long course = optionalCourse(node, context, attempt);
                Map<String, Object> params = new LinkedHashMap<>();
                if (course != null) {
                    params.put("courseId", course);
                }
                attempt.entries.add(entry(params, null));
            }
            case CONSECUTIVE_LESSONS -> {
                Long course = requiredCourse(node, context, attempt);
                Integer min = number(node, "minConsecutive");
                if (min == null) {
                    attempt.needsInput("缺少连排节数");
                } else {
                    validateRange(type, "minConsecutive", min, attempt);
                }
                Map<String, Object> params = new LinkedHashMap<>();
                params.put("courseId", course);
                params.put("minConsecutive", min);
                attempt.entries.add(entry(params, null));
            }
            case MAIN_SUBJECT_MORNING -> {
                Long course = requiredCourse(node, context, attempt);
                Integer before = number(node, "beforePeriodNo");
                if (before == null) {
                    before = context.morningCutoff();
                    if (before == null) {
                        attempt.needsInput("本学期每天上午的截止节次不一致，请补充「上午截止节次」");
                    }
                } else {
                    validateRange(type, "beforePeriodNo", before, attempt);
                }
                if (before != null && !context.hasPeriodNo(before)) {
                    attempt.invalid("上午截止第" + before + "节不在本学期作息内");
                }
                Map<String, Object> params = new LinkedHashMap<>();
                params.put("courseId", course);
                params.put("beforePeriodNo", before);
                attempt.entries.add(entry(params, null));
            }
            default -> {
                if (type.isScopedThreshold()) {
                    buildThreshold(type, node, context, attempt);
                } else {
                    // The six single switches carry no parameters.
                    attempt.entries.add(entry(new LinkedHashMap<>(), null));
                }
            }
        }
    }

    private static void buildThreshold(
            ConstraintType type, JsonNode node, Context context, Attempt attempt) {
        ConstraintType.ThresholdScope scope = type.thresholdScope();
        Long objectId = null;
        String objectField = scope.objectField();
        if ("teacherId".equals(objectField)) {
            objectId = optionalTeacher(node, context, attempt);
        } else if ("classId".equals(objectField)) {
            objectId = optionalClass(node, context, attempt);
        } else if ("courseId".equals(objectField)) {
            objectId = optionalCourse(node, context, attempt);
        }
        Integer value = number(node, scope.valueField());
        if (value == null) {
            attempt.needsInput("缺少「"
                    + type.getParams().stream()
                    .filter(param -> param.name().equals(scope.valueField()))
                    .map(Param::label).findFirst().orElse(scope.valueField()) + "」");
        } else {
            validateRange(type, scope.valueField(), value, attempt);
        }
        Map<String, Object> params = new LinkedHashMap<>();
        if (objectId != null) {
            params.put(objectField, objectId);
        }
        params.put(scope.valueField(), value);
        attempt.entries.add(entry(params, null));
    }

    private static void addSlotEntries(
            ConstraintType type, JsonNode node, Context context, Attempt attempt,
            String groupKey, java.util.function.Consumer<Map<String, Object>> head) {
        List<int[]> slots = slots(node, context, attempt);
        for (int[] slot : slots) {
            Map<String, Object> params = new LinkedHashMap<>();
            head.accept(params);
            params.put("dayOfWeek", slot[0]);
            params.put("periodNo", slot[1]);
            attempt.entries.add(entry(params, groupKey));
        }
    }

    // ------------------------------------------------------------------ slots

    private static List<int[]> slots(JsonNode node, Context context, Attempt attempt) {
        Set<Integer> days = allDays(node) ? context.days()
                : new LinkedHashSet<>(days(node, context, attempt));
        if (days.isEmpty() && READY.equals(attempt.status)) {
            attempt.needsInput("缺少星期");
        }
        List<Integer> absolutePeriods = integers(node, "periods");
        List<Integer> ordinals = integers(node, "sectionOrdinals");
        PeriodSection section = section(node, attempt);
        Integer singlePeriod = integer(node, "periodNo");
        if (absolutePeriods.isEmpty() && section == null && singlePeriod == null
                && READY.equals(attempt.status)) {
            attempt.needsInput("缺少课节");
        }

        Set<String> seen = new LinkedHashSet<>();
        List<int[]> slots = new ArrayList<>();
        for (Integer day : days) {
            if (!context.timetable().containsKey(day)) {
                attempt.invalid("本学期没有周" + day + "的课节");
                continue;
            }
            List<Integer> periodNos = new ArrayList<>();
            if (!absolutePeriods.isEmpty()) {
                periodNos.addAll(absolutePeriods);
            } else if (section != null) {
                List<Integer> available = context.periodNumbers(day, section);
                if (!ordinals.isEmpty()) {
                    for (Integer ordinal : ordinals) {
                        if (ordinal == null || ordinal < 1 || ordinal > available.size()) {
                            attempt.invalid("周" + day + "没有"
                                    + section.getLabel() + "第" + ordinal + "节");
                        } else {
                            periodNos.add(available.get(ordinal - 1));
                        }
                    }
                } else {
                    periodNos.addAll(available);
                }
            } else if (singlePeriod != null) {
                periodNos.add(singlePeriod);
            }
            for (Integer periodNo : new LinkedHashSet<>(periodNos)) {
                if (periodNo == null) {
                    continue;
                }
                if (!context.hasSlot(day, periodNo)) {
                    attempt.invalid("周" + day + "第" + periodNo + "节不在本学期作息内");
                    continue;
                }
                if (seen.add(day + "-" + periodNo)) {
                    slots.add(new int[]{day, periodNo});
                }
            }
        }
        return slots;
    }

    private static boolean allDays(JsonNode node) {
        return node.path("allDays").asBoolean(false);
    }

    private static List<Integer> days(JsonNode node, Context context, Attempt attempt) {
        List<Integer> days = integers(node, "days");
        Integer single = integer(node, "dayOfWeek");
        if (single != null && !days.contains(single)) {
            days.add(single);
        }
        for (Integer day : days) {
            if (day == null || day < 1 || day > 7) {
                attempt.invalid("星期必须是 1-7: " + day);
            }
        }
        return days.stream().filter(Objects::nonNull).distinct().toList();
    }

    // ------------------------------------------------------------------ names

    private static Long requiredTeacher(JsonNode node, Context context, Attempt attempt) {
        String name = text(node, "teacherName");
        if (name.isBlank()) {
            attempt.needsInput("缺少教师");
            return null;
        }
        return resolve("teacherId", "教师", name, context.teachers().stream()
                .map(teacher -> new Named(teacher.id(), teacher.name())).toList(), attempt,
                true);
    }

    private static Long optionalTeacher(JsonNode node, Context context, Attempt attempt) {
        String name = text(node, "teacherName");
        return name.isBlank() ? null : resolve("teacherId", "教师", name,
                context.teachers().stream()
                        .map(teacher -> new Named(teacher.id(), teacher.name())).toList(),
                attempt, true);
    }

    private static Long requiredCourse(JsonNode node, Context context, Attempt attempt) {
        String name = text(node, "courseName");
        if (name.isBlank()) {
            attempt.needsInput("缺少课程");
            return null;
        }
        return resolve("courseId", "课程", name, context.courses().stream()
                .map(course -> new Named(course.id(), course.name())).toList(), attempt, false);
    }

    private static Long optionalCourse(JsonNode node, Context context, Attempt attempt) {
        String name = text(node, "courseName");
        return name.isBlank() ? null : resolve("courseId", "课程", name,
                context.courses().stream()
                        .map(course -> new Named(course.id(), course.name())).toList(),
                attempt, false);
    }

    private static Long optionalClass(JsonNode node, Context context, Attempt attempt) {
        String name = text(node, "className");
        return name.isBlank() ? null : resolve("classId", "行政班", name,
                context.classes().stream()
                        .map(adminClass -> new Named(adminClass.id(), adminClass.name())).toList(),
                attempt, false);
    }

    private record Named(long id, String name) {}

    /**
     * Resolves a spoken name to exactly one object. "王老师" is read as the prefix
     * "王" only when that prefix is unique; two 张伟 both matching "张老师" become a
     * choice, never a guess. Other names must match exactly.
     */
    private static Long resolve(
            String field, String label, String spoken, List<Named> objects,
            Attempt attempt, boolean allowTeacherSuffix) {
        String name = spoken.trim();
        List<Named> exact = objects.stream()
                .filter(object -> object.name().equals(name))
                .toList();
        if (exact.size() == 1) {
            return exact.get(0).id();
        }
        if (exact.size() > 1) {
            attempt.candidates.add(new ProposalCandidateDto(field, label,
                    exact.stream().map(object -> new ObjectOptionDto(object.id(), object.name()))
                            .toList()));
            attempt.needsInput(label + "「" + name + "」有多个同名对象，请选择");
            return null;
        }
        if (allowTeacherSuffix && name.endsWith("老师") && name.length() > 2) {
            String prefix = name.substring(0, name.length() - 2);
            List<Named> matches = objects.stream()
                    .filter(object -> object.name().startsWith(prefix))
                    .toList();
            if (matches.size() == 1) {
                return matches.get(0).id();
            }
            if (matches.size() > 1) {
                attempt.candidates.add(new ProposalCandidateDto(field, label,
                        matches.stream().map(object -> new ObjectOptionDto(object.id(), object.name()))
                                .toList()));
                attempt.needsInput(label + "「" + name + "」对应多位教师，请选择");
                return null;
            }
        }
        attempt.needsInput("找不到" + label + "「" + name + "」");
        return null;
    }

    // ------------------------------------------------------------------ values

    private static PeriodSection section(JsonNode node, Attempt attempt) {
        String raw = text(node, "section").toUpperCase(Locale.ROOT);
        if (raw.isBlank()) {
            return null;
        }
        return switch (raw) {
            case "MORNING" -> PeriodSection.MORNING;
            case "AFTERNOON" -> PeriodSection.AFTERNOON;
            case "EVENING" -> PeriodSection.EVENING;
            default -> {
                attempt.invalid("未知时段: " + raw);
                yield null;
            }
        };
    }

    private static void validateRange(
            ConstraintType type, String field, Integer value, Attempt attempt) {
        Param param = type.getParams().stream()
                .filter(candidate -> candidate.name().equals(field))
                .findFirst().orElse(null);
        if (param == null) {
            return;
        }
        if (param.min() != null && value < param.min()) {
            attempt.invalid(param.label() + "不能小于 " + param.min());
        }
        if (param.max() != null && value > param.max()) {
            attempt.invalid(param.label() + "不能大于 " + param.max());
        }
    }

    private static java.util.Optional<Param> roomTypeParam(ConstraintType type) {
        return type.getParams().stream()
                .filter(param -> param.type() == ParamType.ROOM_TYPE)
                .findFirst();
    }

    // ------------------------------------------------------------------ json helpers

    private static ConstraintProposalDto proposal(
            String proposalId, String sourceText, String type, String typeName,
            String strength, boolean effectiveHard, Integer effectiveWeight,
            String status, String explanation, String reason, Map<String, Object> params,
            List<ProposalEntryDto> entries, List<ProposalCandidateDto> candidates) {
        return new ConstraintProposalDto(
                proposalId, sourceText, type, typeName, strength, effectiveHard,
                effectiveWeight, status, explanation, reason == null ? "" : reason,
                params, entries, candidates);
    }

    private static ProposalEntryDto entry(Map<String, Object> params, String groupKey) {
        return new ProposalEntryDto(params, groupKey, true, null, null);
    }

    private static String strengthOf(JsonNode node, ConstraintType type) {
        String raw = text(node, "strength").toUpperCase(Locale.ROOT);
        if ("HARD".equals(raw) || "SOFT".equals(raw)) {
            return raw;
        }
        return "INHERIT";
    }

    private static boolean effectiveHard(JsonNode node, ConstraintType type) {
        return switch (strengthOf(node, type)) {
            case "HARD" -> true;
            case "SOFT" -> false;
            default -> type.isDefaultHard();
        };
    }

    private static Integer effectiveWeight(JsonNode node, ConstraintType type) {
        return effectiveHard(node, type) ? null : type.getDefaultWeight();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asText("").trim();
    }

    private static Integer integer(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (value.isNumber()) {
            return value.asInt();
        }
        try {
            return Integer.parseInt(value.asText().trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Integer number(JsonNode node, String field) {
        Integer direct = integer(node, field);
        if (direct != null) {
            return direct;
        }
        return integer(node.path("values"), field);
    }

    private static List<Integer> integers(JsonNode node, String field) {
        List<Integer> values = new ArrayList<>();
        JsonNode array = node.path(field);
        if (array.isArray()) {
            array.forEach(item -> {
                if (item.isNumber()) {
                    values.add(item.asInt());
                } else {
                    try {
                        values.add(Integer.parseInt(item.asText().trim()));
                    } catch (NumberFormatException ignored) {
                        // the caller reports a missing/invalid condition
                    }
                }
            });
        }
        return values;
    }

    private static final class Attempt {
        String status = READY;
        String reason = "";
        final List<ProposalEntryDto> entries = new ArrayList<>();
        final List<ProposalCandidateDto> candidates = new ArrayList<>();

        void needsInput(String message) {
            if (!INVALID.equals(status)) {
                status = NEEDS_INPUT;
                reason = message;
            }
        }

        void invalid(String message) {
            status = INVALID;
            reason = message;
        }
    }
}
