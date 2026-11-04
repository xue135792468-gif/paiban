package com.paiban.scheduling.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paiban.common.policy.ExchangePolicyService;
import com.paiban.common.policy.ExchangePolicySnapshot;
import com.paiban.common.web.ApiException;
import com.paiban.scheduling.constraint.ConstraintChecker;
import com.paiban.scheduling.constraint.LessonView;
import com.paiban.scheduling.domain.Assignment;
import com.paiban.scheduling.domain.AssignmentRepository;
import com.paiban.scheduling.domain.DraftCyclePlanToken;
import com.paiban.scheduling.domain.DraftCyclePlanTokenRepository;
import com.paiban.scheduling.domain.TimetableDraft;
import com.paiban.scheduling.domain.TimetableDraftRepository;
import com.paiban.scheduling.plan.DraftCyclePlanner;
import com.paiban.scheduling.plan.DraftCyclePlanner.Candidate;
import com.paiban.scheduling.plan.DraftCyclePlanner.Options;
import com.paiban.scheduling.service.DraftValidator.ChangeAssessment;
import com.paiban.scheduling.service.DraftValidator.LessonChange;
import com.paiban.scheduling.service.DraftValidator.PlanLesson;
import com.paiban.scheduling.service.DraftValidator.PlanningSnapshot;
import com.paiban.scheduling.web.DraftChangeDtos.ChangeRequest;
import com.paiban.scheduling.web.DraftChangeDtos.ChangeResult;
import com.paiban.scheduling.web.DraftChangeDtos.LessonChangeItem;
import com.paiban.scheduling.web.DraftCyclePlanDtos.ApplyRequest;
import com.paiban.scheduling.web.DraftCyclePlanDtos.ApplyResponse;
import com.paiban.scheduling.web.DraftCyclePlanDtos.Plan;
import com.paiban.scheduling.web.DraftCyclePlanDtos.PlanMove;
import com.paiban.scheduling.web.DraftCyclePlanDtos.SearchRequest;
import com.paiban.scheduling.web.DraftCyclePlanDtos.SearchResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Search, preview and atomic application of same-class cycle plans.
 *
 * <p>Search is read-only with respect to the timetable: it never touches
 * {@code draft_lesson}, locks or the draft version; it only stores one-time tokens.
 * Apply locks the draft, re-reads the plan's fingerprint and independently re-verifies
 * the whole table before it hands the batch to {@link DraftChangeService}. Any change
 * to the draft, rules, settings, locks or assignments between search and apply makes
 * the fingerprint differ and the apply is refused with zero writes.
 */
@Service
public class DraftCyclePlanService {

    static final Duration TOKEN_TTL = Duration.ofMinutes(10);

    private final DraftWriteGuard guard;
    private final DraftValidator validator;
    private final DraftChangeService changeService;
    private final ConstraintService constraints;
    private final ExchangePolicyService policy;
    private final DraftCyclePlanTokenRepository tokens;
    private final TimetableDraftRepository drafts;
    private final AssignmentRepository assignments;
    private final ObjectMapper mapper;

    public DraftCyclePlanService(
            DraftWriteGuard guard,
            DraftValidator validator,
            DraftChangeService changeService,
            ConstraintService constraints,
            ExchangePolicyService policy,
            DraftCyclePlanTokenRepository tokens,
            TimetableDraftRepository drafts,
            AssignmentRepository assignments,
            ObjectMapper mapper) {
        this.guard = guard;
        this.validator = validator;
        this.changeService = changeService;
        this.constraints = constraints;
        this.policy = policy;
        this.tokens = tokens;
        this.drafts = drafts;
        this.assignments = assignments;
        this.mapper = mapper;
    }

    @Transactional
    public SearchResponse search(Long draftId, SearchRequest request) {
        TimetableDraft draft = drafts.findById(draftId)
                .orElseThrow(() -> ApiException.notFound("课表草稿不存在: " + draftId));
        guard.requireEditable(draft, request.expectedVersion());
        if (StringUtils.hasText(request.rulesFingerprint())) {
            String current = constraints.settings(draft.getSemesterId()).fingerprint();
            if (!current.equals(request.rulesFingerprint())) {
                throw ApiException.conflict("课表规则已变化，请刷新后重试");
            }
        }

        PlanningSnapshot snapshot = validator.planningSnapshot(draftId);
        long version = draft.getVersion();
        String fingerprint = fingerprint(draft, snapshot, request.sourceLessonId(),
                request.targetPeriodId());
        PlanLesson source = findSource(snapshot, request.sourceLessonId());
        if (source.periodId() == null) {
            throw ApiException.badRequest("来源课节还没有安排时间，不能换位");
        }
        if (source.locked()) {
            throw ApiException.conflict("来源课节已锁定，不能作为换位起点");
        }
        if (!snapshot.context().periods().containsKey(request.targetPeriodId())) {
            throw ApiException.badRequest("作息时段不存在或不属于该学期: " + request.targetPeriodId());
        }

        if (source.periodId().equals(request.targetPeriodId())) {
            return none(draft, version, fingerprint, "落点与来源相同，无需换位");
        }

        List<PlanLesson> occupants = snapshot.lessons().stream()
                .filter(lesson -> request.targetPeriodId().equals(lesson.periodId()))
                .toList();
        List<PlanLesson> sameClass = occupants.stream()
                .filter(lesson -> Objects.equals(lesson.classId(), source.classId()))
                .toList();
        boolean foreign = occupants.stream()
                .anyMatch(lesson -> !Objects.equals(lesson.classId(), source.classId()));
        // Other classes sharing the same time are normal context, not a cross-class
        // swap. Only a target with no same-class lesson at all is outside this search.
        if (sameClass.isEmpty() && foreign) {
            throw ApiException.conflict("只能在本班范围内换位");
        }
        if (sameClass.size() > 1) {
            throw ApiException.conflict("落点存在多节课，请先修复该格");
        }
        PlanLesson occupant = sameClass.isEmpty() ? null : sameClass.get(0);
        if (request.targetLessonId() != null
                && (occupant == null || !request.targetLessonId().equals(occupant.id()))) {
            throw ApiException.conflict("落点课节已变化，请重新选择");
        }
        if (occupant == null) {
            return none(draft, version, fingerprint, "落点为空，可直接移动");
        }
        if (occupant.locked()) {
            throw ApiException.conflict("落点课节已锁定，不能换位");
        }

        ExchangePolicySnapshot exchange = policy.current();
        Options options = new Options(
                exchange.maxMovedLessons(), exchange.rankingPreset(),
                request.nodeBudget() == null ? 0 : request.nodeBudget(),
                request.timeBudgetMs() == null ? 0 : request.timeBudgetMs());
        DraftCyclePlanner.Result result = DraftCyclePlanner.plan(
                snapshot, source.id(), request.targetPeriodId(), options, validator::assess);

        int softBefore = ConstraintChecker.softPenalty(snapshot.context(), viewsOf(snapshot));
        Instant now = Instant.now();
        tokens.deleteByExpiresAtBefore(now);
        Instant expiresAt = now.plus(TOKEN_TTL);
        List<Plan> plans = new ArrayList<>(result.candidates().size());
        for (Candidate candidate : result.candidates()) {
            String token = UUID.randomUUID().toString();
            String kind = candidate.moves().size() == 2 ? "DIRECT" : "CYCLE";
            List<PlanMove> moves = candidate.moves().stream()
                    .map(move -> toPlanMove(snapshot, move))
                    .toList();
            tokens.save(tokenRow(token, draftId, kind, source.id(), request.targetPeriodId(),
                    moves, fingerprint, now, expiresAt));
            plans.add(new Plan(token, kind, moves, candidate.cycleLength(),
                    candidate.impactTeachers(), candidate.impactDays(),
                    candidate.softPenaltyAfter(), candidate.softPenaltyAfter() - softBefore,
                    expiresAt));
        }

        String status;
        String message;
        if (!plans.isEmpty() && !result.budgetExhausted()) {
            status = "FOUND";
            message = "找到 " + plans.size() + " 个方案";
        } else if (result.budgetExhausted() && !plans.isEmpty()) {
            status = "BUDGET_EXHAUSTED";
            message = "搜索预算耗尽，以下是已独立验证的部分方案，尚未穷尽";
        } else if (result.budgetExhausted()) {
            status = "BUDGET_EXHAUSTED";
            message = "搜索预算耗尽，尚未找到方案；这不代表一定无解";
        } else {
            status = "NONE";
            message = "当前范围和上限内没有可用方案";
        }
        return new SearchResponse(status, message, version, fingerprint, plans);
    }

    @Transactional
    public ApplyResponse apply(Long draftId, ApplyRequest request, String actor) {
        TimetableDraft draft = guard.lockEditable(draftId, request.expectedVersion());
        DraftCyclePlanToken token = tokens.findWithLockByToken(request.token())
                .orElseThrow(() -> ApiException.notFound("方案不存在或已失效"));
        if (!draftId.equals(token.getDraftId())) {
            throw ApiException.badRequest("该方案不属于此课表");
        }
        Instant now = Instant.now();
        if (token.getUsedAt() != null) {
            throw ApiException.conflict("该方案已应用，请刷新查看最新课表");
        }
        if (token.getExpiresAt().isBefore(now)) {
            throw ApiException.conflict("方案已过期，请重新搜索");
        }

        StoredPlan plan = readPlan(token.getPlanJson());
        PlanningSnapshot snapshot = validator.planningSnapshot(draftId);
        String currentFingerprint = fingerprint(
                draft, snapshot, plan.sourceLessonId(), plan.targetPeriodId());
        if (!currentFingerprint.equals(token.getFingerprint())) {
            throw ApiException.conflict("课表已变化，请重新搜索");
        }

        Map<Long, PlanLesson> byId = new LinkedHashMap<>();
        snapshot.lessons().forEach(lesson -> byId.put(lesson.id(), lesson));
        List<LessonChange> changes = new ArrayList<>(plan.moves().size());
        List<LessonChangeItem> items = new ArrayList<>(plan.moves().size());
        Set<Long> seenLessons = new HashSet<>();
        Set<Long> seenTargets = new HashSet<>();
        for (StoredMove move : plan.moves()) {
            PlanLesson lesson = byId.get(move.lessonId());
            if (lesson == null || !Objects.equals(lesson.periodId(), move.fromPeriodId())
                    || !seenLessons.add(move.lessonId())
                    || !seenTargets.add(move.toPeriodId())) {
                throw ApiException.conflict("课表已变化，请重新搜索");
            }
            changes.add(new LessonChange(
                    move.lessonId(), move.toPeriodId(), lesson.roomId(), lesson.teacherId()));
            items.add(new LessonChangeItem(
                    move.lessonId(), move.toPeriodId(), null, null,
                    move.fromPeriodId(), lesson.teacherId(), lesson.roomId()));
        }
        // Independent whole-table re-check: every move is judged together, exactly as
        // the write service will see it, and the token's targets must be reproduced.
        ChangeAssessment assessment = validator.assess(snapshot, changes);
        if (!assessment.added().isEmpty()
                || !targetsReproduced(changes, snapshot, plan.moves())) {
            throw ApiException.conflict("该方案在当前课表下会产生新的冲突，请重新搜索");
        }

        ChangeResult result = changeService.apply(draftId, new ChangeRequest(
                items, draft.getVersion(), validator.rulesFingerprint(draftId), true), actor);
        boolean tokenUsed = result.saved();
        if (tokenUsed) {
            token.setUsedAt(now);
            token.setUsedVersion(result.version());
            tokens.save(token);
        }
        return new ApplyResponse(
                result.saved(), result.valid(), result.version(), result.hardConflicts(),
                result.softPenaltyBefore(), result.softPenaltyAfter(), result.softPenaltyDelta(),
                result.addedConflicts(), result.remainingConflicts(), result.conflicts(),
                tokenUsed);
    }

    // ---------------------------------------------------------------- fingerprint

    /**
     * Everything an apply must be protected against: the draft version, the rule set,
     * the shared exchange policy, every lesson row, every assignment row and the
     * requested source/target. Locks live on the lesson rows, so they are included too.
     */
    public String fingerprint(TimetableDraft draft, PlanningSnapshot snapshot,
                              Long sourceLessonId, Long targetPeriodId) {
        List<String> lessons = snapshot.lessons().stream()
                .sorted(Comparator.comparing(PlanLesson::id))
                .map(lesson -> "L|" + lesson.id()
                        + "|" + lesson.assignmentId()
                        + "|" + lesson.lessonIndex()
                        + "|" + lesson.classId()
                        + "|" + lesson.courseId()
                        + "|" + lesson.teacherId()
                        + "|" + lesson.periodId()
                        + "|" + lesson.roomId()
                        + "|" + lesson.locked())
                .toList();
        List<String> assignmentLines = assignments.findBySemesterIdAndDeletedAtIsNull(draft.getSemesterId()).stream()
                .sorted(Comparator.comparing(Assignment::getId))
                .map(assignment -> "A|" + assignment.getId()
                        + "|" + assignment.getAdminClassId()
                        + "|" + assignment.getCourseId()
                        + "|" + assignment.getTeacherId()
                        + "|" + assignment.getWeeklyLessons())
                .toList();
        List<String> lines = new ArrayList<>();
        lines.add("V|" + draft.getVersion());
        lines.add("R|" + constraints.settings(draft.getSemesterId()).fingerprint());
        lines.add("P|" + policy.current().fingerprint());
        lines.addAll(lessons);
        lines.addAll(assignmentLines);
        lines.add("S|" + sourceLessonId + "|" + targetPeriodId);
        return sha256(String.join("\n", lines));
    }

    // ----------------------------------------------------------------- internals

    private PlanLesson findSource(PlanningSnapshot snapshot, Long sourceLessonId) {
        return snapshot.lessons().stream()
                .filter(lesson -> lesson.id().equals(sourceLessonId))
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("课节不存在: " + sourceLessonId));
    }

    private static SearchResponse none(
            TimetableDraft draft, long version, String fingerprint, String message) {
        return new SearchResponse("NONE", message, version, fingerprint, List.of());
    }

    private PlanMove toPlanMove(PlanningSnapshot snapshot, DraftCyclePlanner.Move move) {
        PlanLesson lesson = snapshot.lessons().stream()
                .filter(item -> item.id().equals(move.lessonId()))
                .findFirst()
                .orElseThrow();
        return new PlanMove(lesson.id(), move.fromPeriodId(), move.toPeriodId(),
                lesson.teacherId(), lesson.courseId(), lesson.classId());
    }

    private DraftCyclePlanToken tokenRow(
            String token, Long draftId, String kind, Long sourceLessonId, Long targetPeriodId,
            List<PlanMove> moves, String fingerprint, Instant now, Instant expiresAt) {
        DraftCyclePlanToken row = new DraftCyclePlanToken();
        row.setToken(token);
        row.setDraftId(draftId);
        row.setFingerprint(fingerprint);
        row.setCreatedAt(now);
        row.setExpiresAt(expiresAt);
        row.setPlanJson(writePlan(new StoredPlan(kind, sourceLessonId, targetPeriodId,
                moves.stream()
                        .map(move -> new StoredMove(move.lessonId(), move.fromPeriodId(),
                                move.toPeriodId()))
                        .toList())));
        return row;
    }

    /** The re-check must reproduce exactly the token's targets on the in-memory copy. */
    private static boolean targetsReproduced(
            List<LessonChange> changes, PlanningSnapshot snapshot, List<StoredMove> moves) {
        if (changes.size() != moves.size()) {
            return false;
        }
        Map<Long, Long> expected = new LinkedHashMap<>();
        moves.forEach(move -> expected.put(move.lessonId(), move.toPeriodId()));
        if (expected.size() != moves.size()) {
            return false;
        }
        for (LessonChange change : changes) {
            if (!Objects.equals(change.periodId(), expected.get(change.lessonId()))) {
                return false;
            }
        }
        return true;
    }

    private static List<LessonView> viewsOf(PlanningSnapshot snapshot) {
        return snapshot.lessons().stream()
                .map(lesson -> new LessonView(lesson.id(), lesson.classId(), lesson.courseId(),
                        lesson.teacherId(), lesson.roomId(), lesson.periodId(), lesson.studentCount()))
                .toList();
    }

    private String writePlan(StoredPlan plan) {
        try {
            return mapper.writeValueAsString(plan);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("无法序列化换位方案", ex);
        }
    }

    private StoredPlan readPlan(String json) {
        try {
            return mapper.readValue(json, StoredPlan.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("方案数据已损坏", ex);
        }
    }

    private static String sha256(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(Character.forDigit((value >> 4) & 0xF, 16));
                hex.append(Character.forDigit(value & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required on the JVM", ex);
        }
    }

    record StoredPlan(String kind, Long sourceLessonId, Long targetPeriodId,
                      List<StoredMove> moves) {}

    record StoredMove(Long lessonId, Long fromPeriodId, Long toPeriodId) {}
}
