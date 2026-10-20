package com.paiban.substitution.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paiban.ai.AiGateway;
import com.paiban.ai.AiMessage;
import com.paiban.ai.AiOptions;
import com.paiban.ai.AiRequest;
import com.paiban.ai.AiResult;
import com.paiban.ai.AiTaskType;
import com.paiban.common.web.ApiException;
import com.paiban.foundation.domain.AdminClass;
import com.paiban.foundation.domain.AdminClassRepository;
import com.paiban.foundation.domain.Course;
import com.paiban.foundation.domain.CourseRepository;
import com.paiban.foundation.domain.Teacher;
import com.paiban.foundation.domain.TeacherRepository;
import com.paiban.substitution.domain.Substitution;
import com.paiban.substitution.domain.SubstitutionRepository;
import com.paiban.substitution.domain.SubstitutionRequest;
import com.paiban.substitution.domain.SubstitutionRequestRepository;
import com.paiban.substitution.web.SubstitutionDtos.RecommendationDto;
import com.paiban.substitution.web.SubstitutionDtos.RecommendationResponse;
import com.paiban.timetable.domain.TimetableEntry;
import com.paiban.timetable.domain.TimetableEntryRepository;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * AI-assisted substitute recommendation (ADR-0021).
 *
 * <p>The candidate pool is always computed by backend rules, never by the model: the AI
 * only ranks and explains teachers the rules already consider valid. Recommendations are
 * advisory — nothing here creates or mutates a substitution request.
 */
@Service
public class SubstitutionRecommendationService {

    static final String SUBJECT_TYPE = "SUBSTITUTION_REQUEST";
    static final int MAX_POOL_SIZE = 20;
    static final int MAX_RECOMMENDATIONS = 3;
    static final int SAME_SUBJECT_SCORE = 50;
    static final int FREE_PERIOD_SCORE = 30;
    static final int PER_SUBSTITUTION_PENALTY = 2;

    private static final String[] DAY_NAMES = {"", "周一", "周二", "周三", "周四", "周五", "周六", "周日"};

    private static final String SYSTEM_PROMPT = """
            你是学校教务系统的调代推荐助手。请从用户给出的候选教师名单中，为请假教师的这节课推荐最多 3 名代课教师。
            推荐规则（按优先级从高到低）：
            1. 同学科优先；
            2. 当天该时段没有课（空闲）优先；
            3. 本学期已代课次数少优先。
            必须遵守：
            - 只能选择候选名单中的教师，teacherId 必须原样取自名单，禁止编造或使用名单外的 id；
            - 只输出 JSON 数组，不要输出 Markdown 代码块、解释或其他文字；
            - 数组元素格式：{"teacherId":7,"name":"李老师","reason":"推荐理由","score":92}；
            - reason 用一句中文说明推荐理由（如同学科、当天该时段无课、本学期代课次数少）；
            - score 为 0-100 的整数，分数越高越推荐。
            """;

    private final SubstitutionRequestRepository requests;
    private final SubstitutionRepository substitutions;
    private final TeacherRepository teachers;
    private final CourseRepository courses;
    private final AdminClassRepository adminClasses;
    private final TimetableEntryRepository entries;
    private final AiGateway gateway;
    private final ObjectMapper objectMapper;

    public SubstitutionRecommendationService(
            SubstitutionRequestRepository requests,
            SubstitutionRepository substitutions,
            TeacherRepository teachers,
            CourseRepository courses,
            AdminClassRepository adminClasses,
            TimetableEntryRepository entries,
            AiGateway gateway,
            ObjectMapper objectMapper) {
        this.requests = requests;
        this.substitutions = substitutions;
        this.teachers = teachers;
        this.courses = courses;
        this.adminClasses = adminClasses;
        this.entries = entries;
        this.gateway = gateway;
        this.objectMapper = objectMapper;
    }

    /**
     * Recommends up to three substitutes for a pending request.
     *
     * <p>Deliberately not transactional: the provider round trip must not hold a database
     * connection, and every repository call runs in its own transaction.
     */
    public RecommendationResponse recommend(Long requestId) {
        SubstitutionRequest request = requests.findById(requestId)
                .orElseThrow(() -> ApiException.notFound("调代申请不存在: " + requestId));
        if (!SubstitutionRequest.PENDING_APPROVAL.equals(request.getStatus())) {
            throw ApiException.conflict("该申请当前状态为 " + request.getStatus() + "，不能生成代课推荐");
        }

        List<Candidate> pool = candidatePool(request);
        if (pool.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "没有可用的候选教师，请检查教师与课表数据");
        }
        Course course = courses.findById(request.getCourseId()).orElse(null);
        AdminClass adminClass = adminClasses.findById(request.getAdminClassId()).orElse(null);
        Teacher original = teachers.findById(request.getOriginalTeacherId()).orElse(null);

        AiRequest aiRequest = new AiRequest(
                        AiTaskType.SUBSTITUTION_RECOMMEND,
                        List.of(
                                AiMessage.system(SYSTEM_PROMPT),
                                AiMessage.user(userPrompt(request, course, adminClass, original, pool))),
                        List.of(),
                        AiOptions.asJson())
                .withSubject(SUBJECT_TYPE, requestId);

        AiResult result;
        try {
            result = gateway.chat(aiRequest);
        } catch (RuntimeException ex) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "AI 推荐调用失败，请稍后重试或手动选择代课教师");
        }
        if (result == null) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "AI 推荐调用失败，请稍后重试或手动选择代课教师");
        }
        if (!result.success()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, failureMessage(result));
        }

        List<RecommendationDto> recommendations = parseRecommendations(result.content(), pool);
        if (recommendations.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "AI 未返回候选池中的教师，请重试或手动选择代课教师");
        }
        return new RecommendationResponse(requestId, recommendations, result.providerName(), result.modelName());
    }

    /** Rule-built pool: active teachers, original teacher excluded, ranked before the AI sees it. */
    List<Candidate> candidatePool(SubstitutionRequest request) {
        Course course = courses.findById(request.getCourseId()).orElse(null);
        String courseSubject = courseSubject(course);
        Set<Long> busyTeacherIds = busyTeacherIds(request);
        Map<Long, Long> substitutionCounts = substitutions
                .findBySemesterIdAndStatus(request.getSemesterId(), Substitution.EFFECTIVE)
                .stream()
                .collect(Collectors.groupingBy(Substitution::getSubstituteTeacherId, Collectors.counting()));

        List<Candidate> candidates = new ArrayList<>();
        for (Teacher teacher : teachers.findAllByOrderByNameAsc()) {
            if (!teacher.isActive() || teacher.getId().equals(request.getOriginalTeacherId())) {
                continue;
            }
            String teacherSubject = teacher.getSubject() == null ? null : teacher.getSubject().trim();
            boolean sameSubject = courseSubject != null
                    && teacherSubject != null
                    && !teacherSubject.isEmpty()
                    && courseSubject.equalsIgnoreCase(teacherSubject);
            boolean freeAtPeriod = !busyTeacherIds.contains(teacher.getId());
            // ADR-0021: keep same-subject teachers (even if busy and adjustable) and free
            // teachers from other subjects; a different-subject teacher who is busy cannot
            // substitute without disturbing another class, so drop them.
            if (!sameSubject && !freeAtPeriod) {
                continue;
            }
            long substitutionCount = substitutionCounts.getOrDefault(teacher.getId(), 0L);
            int score = (sameSubject ? SAME_SUBJECT_SCORE : 0)
                    + (freeAtPeriod ? FREE_PERIOD_SCORE : 0)
                    - (int) (substitutionCount * PER_SUBSTITUTION_PENALTY);
            candidates.add(new Candidate(
                    teacher.getId(),
                    teacher.getName(),
                    teacherSubject,
                    sameSubject,
                    !freeAtPeriod,
                    substitutionCount,
                    score));
        }
        return candidates.stream()
                .sorted(Comparator.comparingInt(Candidate::ruleScore)
                        .reversed()
                        .thenComparing(Candidate::name, Comparator.nullsLast(String::compareTo))
                        .thenComparing(Candidate::teacherId))
                .limit(MAX_POOL_SIZE)
                .toList();
    }

    /** Teachers already teaching at the requested weekday + period, plus effective substitutions there. */
    private Set<Long> busyTeacherIds(SubstitutionRequest request) {
        Set<Long> busy = new HashSet<>();
        for (TimetableEntry entry : entries.findBySemesterId(request.getSemesterId())) {
            if (entry.getDayOfWeek() == request.getDayOfWeek() && entry.getPeriodNo() == request.getPeriodNo()) {
                busy.add(entry.getTeacherId());
            }
        }
        List<Substitution> effective = substitutions.findBySemesterIdAndEffectiveDateAndStatus(
                request.getSemesterId(), request.getEffectiveDate(), Substitution.EFFECTIVE);
        for (Substitution substitution : effective) {
            SubstitutionRequest other = null;
            if (substitution.getNewDayOfWeek() == null || substitution.getNewPeriodNo() == null) {
                other = requests.findById(substitution.getRequestId()).orElse(null);
            }
            int dayOfWeek = substitution.getNewDayOfWeek() != null
                    ? substitution.getNewDayOfWeek()
                    : other == null ? -1 : other.getDayOfWeek();
            int periodNo = substitution.getNewPeriodNo() != null
                    ? substitution.getNewPeriodNo()
                    : other == null ? -1 : other.getPeriodNo();
            if (dayOfWeek == request.getDayOfWeek() && periodNo == request.getPeriodNo()) {
                busy.add(substitution.getSubstituteTeacherId());
            }
        }
        return busy;
    }

    private String userPrompt(
            SubstitutionRequest request,
            Course course,
            AdminClass adminClass,
            Teacher original,
            List<Candidate> pool) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("请假教师：").append(original == null ? "未知" : original.getName()).append('\n');
        prompt.append("课程：").append(course == null ? "未知" : course.getName());
        if (courseSubject(course) != null) {
            prompt.append("（学科：").append(courseSubject(course)).append("）");
        }
        prompt.append('\n');
        prompt.append("班级：").append(adminClass == null ? "未知" : adminClass.getName()).append('\n');
        prompt.append("日期：").append(request.getEffectiveDate())
                .append("（").append(dayName(request.getDayOfWeek())).append("）\n");
        prompt.append("节次：第").append(request.getPeriodNo()).append("节\n");
        if (request.getReason() != null && !request.getReason().isBlank()) {
            prompt.append("请假原因：").append(request.getReason().trim()).append('\n');
        }
        prompt.append("\n候选教师（共 ").append(pool.size()).append(" 人）：\n");
        for (Candidate candidate : pool) {
            prompt.append("- teacherId=").append(candidate.teacherId())
                    .append("，姓名=").append(candidate.name())
                    .append("，学科=").append(candidate.subject() == null || candidate.subject().isBlank()
                            ? "未知" : candidate.subject())
                    .append("，同学科=").append(candidate.sameSubject() ? "是" : "否")
                    .append("，当天该时段有课=").append(candidate.busyAtPeriod() ? "是" : "否")
                    .append("，本学期代课次数=").append(candidate.substitutionCount())
                    .append('\n');
        }
        return prompt.toString();
    }

    /** Keeps only pool ids, drops duplicates, sorts by score and caps at three. */
    private List<RecommendationDto> parseRecommendations(String content, List<Candidate> pool) {
        JsonNode root = readJson(content);
        if (root == null) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "AI 返回内容无法解析为推荐列表，请重试或手动选择代课教师");
        }
        JsonNode array = root.isArray() ? root : root.path("recommendations");
        if (!array.isArray()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "AI 返回内容不是推荐数组，请重试或手动选择代课教师");
        }

        Map<Long, Candidate> poolById = pool.stream()
                .collect(Collectors.toMap(Candidate::teacherId, Function.identity()));
        Map<Long, RecommendationDto> bestByTeacher = new LinkedHashMap<>();
        for (JsonNode node : array) {
            if (!node.hasNonNull("teacherId")) {
                continue;
            }
            Candidate candidate = poolById.get(node.get("teacherId").asLong());
            if (candidate == null) {
                continue;
            }
            String reason = node.path("reason").asText("");
            int score = node.hasNonNull("score") ? node.get("score").asInt() : candidate.ruleScore();
            RecommendationDto recommendation = new RecommendationDto(
                    candidate.teacherId(),
                    candidate.name(),
                    reason.isBlank() ? fallbackReason(candidate) : reason.trim(),
                    score);
            RecommendationDto existing = bestByTeacher.get(candidate.teacherId());
            if (existing == null || recommendation.score() > existing.score()) {
                bestByTeacher.put(candidate.teacherId(), recommendation);
            }
        }
        return bestByTeacher.values().stream()
                .sorted(Comparator.comparingInt(RecommendationDto::score)
                        .reversed()
                        .thenComparing(RecommendationDto::name, Comparator.nullsLast(String::compareTo)))
                .limit(MAX_RECOMMENDATIONS)
                .toList();
    }

    private JsonNode readJson(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        String text = stripCodeFence(content.trim());
        try {
            return objectMapper.readTree(text);
        } catch (IOException directFailure) {
            int start = text.indexOf('[');
            int end = text.lastIndexOf(']');
            if (start >= 0 && end > start) {
                try {
                    return objectMapper.readTree(text.substring(start, end + 1));
                } catch (IOException ignored) {
                    return null;
                }
            }
            return null;
        }
    }

    private static String stripCodeFence(String text) {
        if (!text.startsWith("```")) {
            return text;
        }
        int firstLineEnd = text.indexOf('\n');
        if (firstLineEnd < 0) {
            return text;
        }
        String body = text.substring(firstLineEnd + 1);
        int closing = body.lastIndexOf("```");
        if (closing >= 0) {
            body = body.substring(0, closing);
        }
        return body.trim();
    }

    private static String courseSubject(Course course) {
        if (course == null) {
            return null;
        }
        if (course.getSubject() != null && !course.getSubject().isBlank()) {
            return course.getSubject().trim();
        }
        return course.getName() == null || course.getName().isBlank() ? null : course.getName().trim();
    }

    private static String dayName(int dayOfWeek) {
        return dayOfWeek >= 1 && dayOfWeek < DAY_NAMES.length ? DAY_NAMES[dayOfWeek] : "周" + dayOfWeek;
    }

    private static String fallbackReason(Candidate candidate) {
        List<String> parts = new ArrayList<>();
        if (candidate.sameSubject()) {
            parts.add("同学科");
        }
        parts.add(candidate.busyAtPeriod() ? "当天该时段已有课" : "当天该时段无课");
        parts.add("本学期已代课 " + candidate.substitutionCount() + " 次");
        return String.join("，", parts);
    }

    private static String failureMessage(AiResult result) {
        String code = result.errorCode() == null || result.errorCode().isBlank()
                ? "PROVIDER_ERROR" : result.errorCode();
        String detail = result.errorMessage() == null || result.errorMessage().isBlank()
                ? "AI 服务返回失败" : result.errorMessage();
        if ("NOT_CONFIGURED".equals(code)) {
            return "未配置可用的 AI 供应商与模型，无法生成推荐：" + detail;
        }
        return "AI 推荐失败（" + code + "）：" + detail;
    }

    /** One rule-ranked candidate, exactly what the prompt and the response need. */
    public record Candidate(
            Long teacherId,
            String name,
            String subject,
            boolean sameSubject,
            boolean busyAtPeriod,
            long substitutionCount,
            int ruleScore) {}
}
