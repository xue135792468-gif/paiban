package com.paiban.scheduling.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paiban.foundation.domain.AdminClass;
import com.paiban.foundation.domain.AdminClassRepository;
import com.paiban.foundation.domain.Course;
import com.paiban.foundation.domain.CourseRepository;
import com.paiban.foundation.domain.Teacher;
import com.paiban.foundation.domain.TeacherRepository;
import com.paiban.foundation.service.SemesterService;
import com.paiban.scheduling.domain.ConstraintConfig;
import com.paiban.scheduling.domain.ConstraintConfigRepository;
import com.paiban.scheduling.domain.ConstraintEntry;
import com.paiban.scheduling.domain.ConstraintEntryRepository;
import com.paiban.scheduling.domain.ConstraintMigrationMap;
import com.paiban.scheduling.domain.ConstraintMigrationMapRepository;
import com.paiban.scheduling.domain.ConstraintMigrationRun;
import com.paiban.scheduling.domain.ConstraintMigrationRunRepository;
import com.paiban.scheduling.domain.ConstraintType;
import com.paiban.scheduling.domain.DraftLessonRepository;
import com.paiban.scheduling.domain.TimetableDraft;
import com.paiban.scheduling.domain.TimetableDraftRepository;
import com.paiban.scheduling.solver.ConstraintSettings;
import com.paiban.scheduling.web.ConstraintMigrationDtos.MigrationEvaluationDto;
import com.paiban.scheduling.web.ConstraintMigrationDtos.MigrationPreviewDto;
import com.paiban.scheduling.web.ConstraintMigrationDtos.MigrationReportDto;
import com.paiban.scheduling.web.ConstraintMigrationDtos.MigrationRowDto;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Ticket 11: migrates the stored legacy constraint configuration onto the current
 * catalogue without losing a single old row.
 *
 * <p>The classification is a static table, not scattered conditionals:
 * {@link #TYPE_ACTIONS} is the one source of truth and tests assert it agrees with
 * {@link ConstraintType#activeValues()}. Every run records one row per old config and
 * one per old entry in {@code constraint_migration_map}, keyed by
 * {@code (semester, oldType, oldEntryId)}, so a second run updates those rows instead
 * of appending duplicates. Deleted types are only paused ({@code enabled = false});
 * their parameters stay readable forever.
 *
 * <p>Each run also stores the shared {@code ConstraintChecker} verdict before and
 * after the migration: draft lessons, hard conflicts, soft penalty and the settings
 * fingerprint. The comparison is what proves the 19 retained types kept their meaning
 * and the 7 retired types stopped participating. A digest of every retained row is
 * recorded on both sides as well, because the fingerprint ignores paused rows by
 * design and would otherwise agree no matter what the migration did to them.
 *
 * <p>Because the run flips stored rows, it also advances the semester's configuration
 * version through {@link ConstraintConfigService#advanceVersion} in the same
 * transaction: the rule page, its cached previews and every version-aware save must
 * notice the change exactly as they would after a manual save.
 */
@Service
public class ConstraintMigrationService {

    /**
     * What happens to one old type. RETAIN keeps the type and its parameters,
     * CONVERT would re-express a type in another one (none today, because ticket 09
     * kept every active enum and parameter), DISABLE pauses a product-deleted type,
     * REVIEW leaves the row untouched and asks a human to resolve a dangling
     * reference.
     */
    public enum Action { RETAIN, CONVERT, DISABLE, REVIEW }

    /**
     * The 26-to-19 map. Built from the catalogue itself so it can never drift:
     * every active type is RETAIN and every retired one is DISABLE.
     */
    public static final Map<ConstraintType, Action> TYPE_ACTIONS = buildTypeActions();

    private static Map<ConstraintType, Action> buildTypeActions() {
        Map<ConstraintType, Action> actions = new EnumMap<>(ConstraintType.class);
        for (ConstraintType type : ConstraintType.values()) {
            actions.put(type, type.isRetired() ? Action.DISABLE : Action.RETAIN);
        }
        return Map.copyOf(actions);
    }

    private static final TypeReference<Map<String, Object>> PARAMS_TYPE = new TypeReference<>() {};
    private static final int NOTE_MAX = 500;

    private final ConstraintConfigRepository configs;
    private final ConstraintEntryRepository entries;
    private final ConstraintMigrationRunRepository runs;
    private final ConstraintMigrationMapRepository maps;
    private final SemesterService semesters;
    private final TeacherRepository teachers;
    private final CourseRepository courses;
    private final AdminClassRepository adminClasses;
    private final TimetableDraftRepository drafts;
    private final DraftLessonRepository draftLessons;
    private final ConstraintService constraintService;
    private final ConstraintConfigService configService;
    private final DraftValidator draftValidator;
    private final ObjectMapper objectMapper;

    public ConstraintMigrationService(
            ConstraintConfigRepository configs,
            ConstraintEntryRepository entries,
            ConstraintMigrationRunRepository runs,
            ConstraintMigrationMapRepository maps,
            SemesterService semesters,
            TeacherRepository teachers,
            CourseRepository courses,
            AdminClassRepository adminClasses,
            TimetableDraftRepository drafts,
            DraftLessonRepository draftLessons,
            ConstraintService constraintService,
            ConstraintConfigService configService,
            DraftValidator draftValidator,
            ObjectMapper objectMapper) {
        this.configs = configs;
        this.entries = entries;
        this.runs = runs;
        this.maps = maps;
        this.semesters = semesters;
        this.teachers = teachers;
        this.courses = courses;
        this.adminClasses = adminClasses;
        this.drafts = drafts;
        this.draftLessons = draftLessons;
        this.constraintService = constraintService;
        this.configService = configService;
        this.draftValidator = draftValidator;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------------ preview

    @Transactional(readOnly = true)
    public MigrationPreviewDto preview(Long semesterId) {
        Long resolved = resolveSemester(semesterId);
        Plan plan = plan(resolved);
        return new MigrationPreviewDto(
                resolved,
                plan.configs().size(),
                plan.entries().size(),
                constraintService.settings(resolved).fingerprint(),
                plan.summary().retained(),
                plan.summary().converted(),
                plan.summary().disabled(),
                plan.summary().review(),
                plan.rows().stream().map(this::toDto).toList());
    }

    // ---------------------------------------------------------------------- run

    /**
     * Applies the migration and stores its report. The whole write set (run row, map
     * rows, paused configs/entries, the advanced configuration version) is one
     * transaction; the before/after evaluation is computed with the shared settings
     * snapshot and the newest DRAFT, so there is no migration-specific interpretation
     * of the rules.
     */
    @Transactional
    public MigrationReportDto run(Long semesterId, String actor) {
        Long resolved = resolveSemester(semesterId);
        Plan plan = plan(resolved);
        Instant startedAt = Instant.now();
        Evaluation before = evaluate(resolved);

        ConstraintMigrationRun run = new ConstraintMigrationRun();
        run.setRunId(UUID.randomUUID().toString());
        run.setSemesterId(resolved);
        run.setStatus("RUNNING");
        run.setStartedAt(startedAt);
        run.setRunBy(StringUtils.hasText(actor) ? actor : "system");
        run.setBeforeJson(toJson(before));
        run.setSummaryJson(toJson(plan.summary()));
        runs.save(run);

        disableRetiredRows(plan);
        configs.flush();
        entries.flush();
        upsertMap(resolved, run.getRunId(), plan.rows(), startedAt);
        // Pausing rows is a configuration write: advance the same counter a save uses,
        // so the advanced page and every cached preview drop their stale 26-type view.
        configService.advanceVersion(resolved);

        Evaluation after = evaluate(resolved);
        run.setStatus("DONE");
        run.setFinishedAt(Instant.now());
        run.setAfterJson(toJson(after));
        runs.save(run);
        return reportOf(run, maps.findByRunIdOrderByOldTypeAscOldEntryIdAsc(run.getRunId()));
    }

    // ------------------------------------------------------------------- report

    @Transactional(readOnly = true)
    public MigrationReportDto report(Long semesterId) {
        Long resolved = resolveSemester(semesterId);
        return runs.findFirstBySemesterIdOrderByIdDesc(resolved)
                .map(run -> reportOf(run,
                        maps.findByRunIdOrderByOldTypeAscOldEntryIdAsc(run.getRunId())))
                .orElse(null);
    }

    /**
     * The semester the call is about: the named one, else the current semester. Both
     * endpoints let the parameter be omitted, and every other read/write of the rule
     * catalogue resolves it the same way. A semester that does not exist is a 404 and a
     * missing current semester a 400, never a {@code NullPointerException}.
     */
    private Long resolveSemester(Long semesterId) {
        Long resolved = semesterId != null ? semesterId : semesters.requireCurrent().getId();
        return semesters.require(resolved).getId();
    }

    // ------------------------------------------------------------------ planning

    private record Summary(int retained, int converted, int disabled, int review) {}

    /** One decision over one stored row; {@code oldEntryId = 0} means a config row. */
    private record DecisionRow(String oldType,
                               String oldTypeName,
                               Long oldEntryId,
                               Action action,
                               String newType,
                               String groupKey,
                               String paramsJson,
                               String note,
                               boolean requiresReview) {}

    private record Plan(Long semesterId,
                        List<ConstraintConfig> configs,
                        List<ConstraintEntry> entries,
                        List<DecisionRow> rows,
                        Summary summary) {}

    private Plan plan(Long semesterId) {
        List<ConstraintConfig> allConfigs = configs.findBySemesterId(semesterId);
        List<ConstraintEntry> allEntries = entries.findBySemesterId(semesterId);
        List<DecisionRow> rows = new ArrayList<>();
        allConfigs.stream()
                .sorted(Comparator.comparing(ConstraintConfig::getType))
                .map(config -> configDecision(config, semesterId))
                .forEach(rows::add);
        allEntries.stream()
                .sorted(Comparator.comparing(ConstraintEntry::getType)
                        .thenComparing(ConstraintEntry::getId))
                .map(entry -> entryDecision(entry, semesterId))
                .forEach(rows::add);
        return new Plan(semesterId, allConfigs, allEntries, List.copyOf(rows),
                summarize(rows));
    }

    private Summary summarize(List<DecisionRow> rows) {
        int retained = 0;
        int converted = 0;
        int disabled = 0;
        int review = 0;
        for (DecisionRow row : rows) {
            if (row.requiresReview()) {
                review++;
            }
            if (row.oldEntryId() != null && row.oldEntryId() == 0L) {
                switch (row.action()) {
                    case RETAIN -> retained++;
                    case CONVERT -> converted++;
                    case DISABLE -> disabled++;
                    case REVIEW -> {
                        // A config-level review still means the type is not auto-mapped;
                        // it is counted under review only.
                    }
                }
            }
        }
        return new Summary(retained, converted, disabled, review);
    }

    private DecisionRow configDecision(ConstraintConfig config, Long semesterId) {
        String raw = config.getType();
        ConstraintType type = typeOrNull(raw);
        if (type == null) {
            return review(raw, raw, 0L, null, config.getParamsJson(), null,
                    "未知约束类型，无法自动映射，请人工确认");
        }
        Action action = TYPE_ACTIONS.getOrDefault(type, Action.REVIEW);
        if (action == Action.DISABLE) {
            return new DecisionRow(raw, type.getDisplayName(), 0L, Action.DISABLE, null,
                    null, config.getParamsJson(),
                    "产品已删除该类型：停用旧配置，参数保留可查", false);
        }
        if (action == Action.CONVERT) {
            return new DecisionRow(raw, type.getDisplayName(), 0L, Action.CONVERT, raw,
                    null, config.getParamsJson(), "已转换到新类型", false);
        }
        // Entry-backed types read their parameters from entry rows only; the legacy
        // params column is a mirror of the first entry and is not evaluated.
        if (!type.isEntryBacked()) {
            Map<String, Object> params = parseOrNull(config.getParamsJson());
            if (params == null) {
                return review(raw, type.getDisplayName(), 0L, raw, config.getParamsJson(),
                        null, "params_json 无法解析，需人工确认");
            }
            Optional<ReferenceProblem> broken = firstBrokenReference(params, semesterId);
            if (broken.isPresent()) {
                return review(raw, type.getDisplayName(), 0L, raw, config.getParamsJson(),
                        null, broken.get().note());
            }
        }
        return new DecisionRow(raw, type.getDisplayName(), 0L, Action.RETAIN, raw, null,
                config.getParamsJson(),
                type.isEntryBacked() ? "保留；参数由条目逐条迁移" : "保留；类型与参数不变", false);
    }

    private DecisionRow entryDecision(ConstraintEntry entry, Long semesterId) {
        String raw = entry.getType();
        ConstraintType type = typeOrNull(raw);
        String entryId = entry.getId() == null ? "?" : String.valueOf(entry.getId());
        if (type == null) {
            return review(raw, raw, entry.getId(), null, entry.getParamsJson(), entry.getGroupKey(),
                    "未知约束类型，无法自动映射，请人工确认");
        }
        Action action = TYPE_ACTIONS.getOrDefault(type, Action.REVIEW);
        if (action == Action.DISABLE) {
            return new DecisionRow(raw, type.getDisplayName(), entry.getId(), Action.DISABLE, null,
                    entry.getGroupKey(), entry.getParamsJson(),
                    "产品已删除该类型：停用旧条目 #" + entryId + "，参数保留可查", false);
        }
        if (action == Action.CONVERT) {
            return new DecisionRow(raw, type.getDisplayName(), entry.getId(), Action.CONVERT, raw,
                    entry.getGroupKey(), entry.getParamsJson(), "已转换到新类型", false);
        }
        Map<String, Object> params = parseOrNull(entry.getParamsJson());
        if (params == null) {
            return review(raw, type.getDisplayName(), entry.getId(), raw, entry.getParamsJson(),
                    entry.getGroupKey(), "params_json 无法解析，需人工确认");
        }
        Optional<ReferenceProblem> broken = firstBrokenReference(params, semesterId);
        if (broken.isPresent()) {
            return review(raw, type.getDisplayName(), entry.getId(), raw, entry.getParamsJson(),
                    entry.getGroupKey(), broken.get().note());
        }
        Optional<ReferenceProblem> shape =
                entryShapeProblem(type, params, entry.getGroupKey());
        if (shape.isPresent()) {
            return review(raw, type.getDisplayName(), entry.getId(), raw, entry.getParamsJson(),
                    entry.getGroupKey(), shape.get().note());
        }
        return new DecisionRow(raw, type.getDisplayName(), entry.getId(), Action.RETAIN, raw,
                entry.getGroupKey(), entry.getParamsJson(), "保留；条目与参数不变", false);
    }

    private DecisionRow review(String oldType, String oldTypeName, Long oldEntryId,
                               String newType, String paramsJson, String groupKey, String note) {
        return new DecisionRow(oldType, oldTypeName, oldEntryId == null ? 0L : oldEntryId,
                Action.REVIEW, newType, groupKey, paramsJson, note, true);
    }

    private ConstraintType typeOrNull(String code) {
        if (!StringUtils.hasText(code)) {
            return null;
        }
        try {
            return ConstraintType.valueOf(code);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    // -------------------------------------------------------------- references

    private record ReferenceProblem(String note) {}

    /**
     * The retained row may only be copied verbatim when every object it names still
     * exists. Teachers and courses are global assets (a row with {@code deleted_at} is
     * gone), a class additionally has to belong to the semester being migrated.
     * Anything else is REVIEW, never a silent pause.
     */
    private Optional<ReferenceProblem> firstBrokenReference(
            Map<String, Object> params, Long semesterId) {
        Long teacherId = ConstraintSettings.longValue(params.get("teacherId"));
        if (teacherId != null) {
            Teacher teacher = teachers.findById(teacherId).orElse(null);
            if (teacher == null || teacher.getDeletedAt() != null) {
                return Optional.of(new ReferenceProblem(
                        "参数「teacherId」引用的教师#" + teacherId + " 不存在或已删除，需人工确认"));
            }
        }
        Long courseId = ConstraintSettings.longValue(params.get("courseId"));
        if (courseId != null) {
            Course course = courses.findById(courseId).orElse(null);
            if (course == null || course.getDeletedAt() != null) {
                return Optional.of(new ReferenceProblem(
                        "参数「courseId」引用的课程#" + courseId + " 不存在或已删除，需人工确认"));
            }
        }
        Long classId = ConstraintSettings.longValue(params.get("classId"));
        if (classId != null) {
            AdminClass adminClass = adminClasses.findById(classId).orElse(null);
            if (adminClass == null || adminClass.getDeletedAt() != null) {
                return Optional.of(new ReferenceProblem(
                        "参数「classId」引用的行政班#" + classId + " 不存在或已删除，需人工确认"));
            }
            if (!semesterId.equals(adminClass.getSemesterId())) {
                return Optional.of(new ReferenceProblem(
                        "参数「classId」引用的行政班#" + classId + " 属于其他学期，需人工确认"));
            }
        }
        return Optional.empty();
    }

    /**
     * The second half of "cannot be mapped automatically": the row's own shape.
     *
     * <p>A reference can be intact and the row still meaningless — a misspelled key
     * ({@code teacher_id}) is silently ignored by every evaluator, and a row that
     * names no slot, or a multi-slot row with no group key, cannot be reconstructed as
     * the rule the user wrote. Such rows are listed for a human instead of being
     * reported as RETAIN, which would claim the migration preserved a rule it did not
     * even understand (ticket 11 review).
     */
    private Optional<ReferenceProblem> entryShapeProblem(
            ConstraintType type, Map<String, Object> params, String groupKey) {
        Set<String> declared = new HashSet<>();
        for (ConstraintType.Param param : type.getParams()) {
            declared.add(param.name());
        }
        for (String key : params.keySet()) {
            if (!declared.contains(key)) {
                return Optional.of(new ReferenceProblem(
                        "参数「" + key + "」不在「" + type.getDisplayName()
                                + "」的参数表中，可能拼写有误，需人工确认"));
            }
        }
        for (ConstraintType.Param param : type.getParams()) {
            if (param.required() && isEmptyValue(params.get(param.name()))) {
                return Optional.of(new ReferenceProblem(
                        "缺少必填参数「" + param.name() + "」，无法自动映射，需人工确认"));
            }
        }
        if (type.isSlotGrouped() && !StringUtils.hasText(groupKey)) {
            return Optional.of(new ReferenceProblem(
                    "条目缺少分组标识（groupKey），无法确认它属于哪条多时段规则，需人工确认"));
        }
        return Optional.empty();
    }

    private boolean isEmptyValue(Object value) {
        return value == null || (value instanceof String text && !StringUtils.hasText(text));
    }

    // ------------------------------------------------------------------- writes

    /** Pauses retired rows only; no row is ever deleted, so the old data stays readable. */
    private void disableRetiredRows(Plan plan) {
        List<ConstraintConfig> changedConfigs = new ArrayList<>();
        for (ConstraintConfig config : plan.configs()) {
            ConstraintType type = typeOrNull(config.getType());
            if (type != null && type.isRetired() && config.isEnabled()) {
                config.setEnabled(false);
                changedConfigs.add(config);
            }
        }
        List<ConstraintEntry> changedEntries = new ArrayList<>();
        for (ConstraintEntry entry : plan.entries()) {
            ConstraintType type = typeOrNull(entry.getType());
            if (type != null && type.isRetired() && entry.isEnabled()) {
                entry.setEnabled(false);
                changedEntries.add(entry);
            }
        }
        if (!changedConfigs.isEmpty()) {
            configs.saveAll(changedConfigs);
        }
        if (!changedEntries.isEmpty()) {
            entries.saveAll(changedEntries);
        }
    }

    private void upsertMap(Long semesterId, String runId, List<DecisionRow> rows, Instant now) {
        for (DecisionRow row : rows) {
            ConstraintMigrationMap target = maps
                    .findBySemesterIdAndOldTypeAndOldEntryId(
                            semesterId, row.oldType(), row.oldEntryId())
                    .orElseGet(ConstraintMigrationMap::new);
            target.setRunId(runId);
            target.setSemesterId(semesterId);
            target.setOldType(row.oldType());
            target.setOldEntryId(row.oldEntryId());
            target.setNewType(row.newType());
            target.setAction(row.action().name());
            target.setGroupKey(row.groupKey());
            target.setParamsJson(row.paramsJson());
            target.setNote(truncate(row.note()));
            target.setRequiresReview(row.requiresReview());
            target.setMigratedAt(now);
            maps.save(target);
        }
    }

    private String truncate(String note) {
        if (note == null || note.length() <= NOTE_MAX) {
            return note;
        }
        return note.substring(0, NOTE_MAX);
    }

    // --------------------------------------------------------------- evaluation

    /**
     * One side of the comparison. {@code retainedDigest} hashes every row the migration
     * promises not to change (the active types' configs and entries, sorted by
     * type/group/params/enabled/hard/weight), which is the evidence the fingerprint
     * cannot give: {@link ConstraintSettings} ignores paused and retired rows, so it
     * would agree before and after even if the run had rewritten a retained rule.
     */
    private record Evaluation(boolean skipped,
                              int lessons,
                              int hardConflicts,
                              int softPenalty,
                              String settingsFingerprint,
                              int configCount,
                              int enabledConfigCount,
                              int entryCount,
                              int enabledEntryCount,
                              int retainedRows,
                              String retainedDigest) {}

    private record RetainedSummary(int rows, String digest) {}

    /**
     * The migration's comparison record: the newest DRAFT of the semester, judged by
     * the shared {@link DraftValidator}/{@link ConstraintService} snapshot. A semester
     * without a draft is not an error; it records {@code skipped = true} and leaves
     * the counts so the user can still see what was paused.
     */
    private Evaluation evaluate(Long semesterId) {
        List<ConstraintConfig> storedConfigs = configs.findBySemesterId(semesterId);
        List<ConstraintEntry> storedEntries = entries.findBySemesterId(semesterId);
        String fingerprint = constraintService.settings(semesterId).fingerprint();
        int enabledConfigs = (int) storedConfigs.stream()
                .filter(ConstraintConfig::isEnabled).count();
        int enabledEntries = (int) storedEntries.stream()
                .filter(ConstraintEntry::isEnabled).count();
        RetainedSummary retained = retainedSummary(semesterId);
        Optional<TimetableDraft> draft = drafts
                .findBySemesterIdOrderByCreatedAtDesc(semesterId).stream()
                .filter(row -> "DRAFT".equals(row.getStatus()))
                .findFirst();
        if (draft.isEmpty()) {
            return new Evaluation(true, 0, 0, 0, fingerprint,
                    storedConfigs.size(), enabledConfigs, storedEntries.size(), enabledEntries,
                    retained.rows(), retained.digest());
        }
        Long draftId = draft.get().getId();
        DraftValidator.ValidationResult verdict = draftValidator.validateDraft(draftId);
        int penalty = draftValidator.assessChanges(draftId, List.of()).softPenaltyBefore();
        int lessons = (int) draftLessons.countByDraftId(draftId);
        return new Evaluation(false, lessons, verdict.hardConflicts(), penalty, fingerprint,
                storedConfigs.size(), enabledConfigs, storedEntries.size(), enabledEntries,
                retained.rows(), retained.digest());
    }

    /**
     * Digest of the retained side: every active config and entry row, retired and
     * unknown codes excluded (pausing those is exactly what {@code run} does). Params
     * are rendered with sorted keys so two rows that differ only in JSON ordering
     * still hash the same.
     */
    private RetainedSummary retainedSummary(Long semesterId) {
        List<String> lines = new ArrayList<>();
        for (ConstraintConfig config : configs.findBySemesterId(semesterId)) {
            ConstraintType type = typeOrNull(config.getType());
            if (type == null || type.isRetired()) {
                continue;
            }
            lines.add(retainedLine("C", type, null, config.getParamsJson(), config.isEnabled(),
                    config.isHard(), config.getWeight()));
        }
        for (ConstraintEntry entry : entries.findBySemesterId(semesterId)) {
            ConstraintType type = typeOrNull(entry.getType());
            if (type == null || type.isRetired()) {
                continue;
            }
            lines.add(retainedLine("E", type, entry.getGroupKey(), entry.getParamsJson(),
                    entry.isEnabled(), entry.getHard(), entry.getWeight()));
        }
        lines.sort(Comparator.naturalOrder());
        return new RetainedSummary(lines.size(), sha256(String.join("\n", lines)));
    }

    private String retainedLine(String kind, ConstraintType type, String groupKey,
                                String paramsJson, boolean enabled, Boolean hard, Integer weight) {
        return kind + "|" + type.name() + "|" + (groupKey == null ? "" : groupKey.trim())
                + "|" + paramsText(paramsJson) + "|" + enabled + "|" + hard + "|" + weight;
    }

    /** Params in a stable form: keys sorted, values via {@code String.valueOf}. */
    private String paramsText(String paramsJson) {
        Map<String, Object> params = parseOrNull(paramsJson);
        if (params == null) {
            return "raw:" + (paramsJson == null ? "" : paramsJson.trim());
        }
        List<String> pairs = new ArrayList<>();
        params.forEach((key, value) -> pairs.add(key + "=" + String.valueOf(value)));
        pairs.sort(Comparator.naturalOrder());
        return String.join(",", pairs);
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte current : digest) {
                hex.append(Character.forDigit((current >> 4) & 0xF, 16))
                        .append(Character.forDigit(current & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    // ------------------------------------------------------------------ mapping

    private MigrationRowDto toDto(DecisionRow row) {
        return new MigrationRowDto(
                row.oldType(),
                row.oldTypeName(),
                row.oldEntryId(),
                row.action().name(),
                row.newType(),
                row.groupKey(),
                parseOrEmpty(row.paramsJson()),
                row.note(),
                row.requiresReview());
    }

    private MigrationRowDto toDto(ConstraintMigrationMap row) {
        return new MigrationRowDto(
                row.getOldType(),
                displayNameOf(row.getOldType()),
                row.getOldEntryId(),
                row.getAction(),
                row.getNewType(),
                row.getGroupKey(),
                parseOrEmpty(row.getParamsJson()),
                row.getNote(),
                row.isRequiresReview());
    }

    private MigrationReportDto reportOf(
            ConstraintMigrationRun run, List<ConstraintMigrationMap> mapRows) {
        Summary summary = read(run.getSummaryJson(), Summary.class, new Summary(0, 0, 0, 0));
        Evaluation before = read(run.getBeforeJson(), Evaluation.class, skipped());
        Evaluation after = read(run.getAfterJson(), Evaluation.class, skipped());
        return new MigrationReportDto(
                run.getRunId(),
                run.getSemesterId(),
                run.getStatus(),
                run.getStartedAt(),
                run.getFinishedAt(),
                run.getRunBy(),
                summary.retained(),
                summary.converted(),
                summary.disabled(),
                summary.review(),
                toDto(before),
                toDto(after),
                mapRows.stream().map(this::toDto).toList());
    }

    private MigrationEvaluationDto toDto(Evaluation evaluation) {
        return new MigrationEvaluationDto(
                evaluation.skipped(),
                evaluation.lessons(),
                evaluation.hardConflicts(),
                evaluation.softPenalty(),
                evaluation.settingsFingerprint(),
                evaluation.configCount(),
                evaluation.enabledConfigCount(),
                evaluation.entryCount(),
                evaluation.enabledEntryCount(),
                evaluation.retainedRows(),
                evaluation.retainedDigest());
    }

    private Evaluation skipped() {
        return new Evaluation(true, 0, 0, 0, null, 0, 0, 0, 0, 0, null);
    }

    private String displayNameOf(String code) {
        ConstraintType type = typeOrNull(code);
        return type == null ? code : type.getDisplayName();
    }

    private Map<String, Object> parseOrNull(String json) {
        if (json == null || !StringUtils.hasText(json)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, PARAMS_TYPE);
        } catch (JsonProcessingException ex) {
            return null;
        }
    }

    private Map<String, Object> parseOrEmpty(String json) {
        Map<String, Object> params = parseOrNull(json);
        return params == null ? Map.of() : params;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("序列化迁移记录失败", ex);
        }
    }

    private <T> T read(String json, Class<T> type, T fallback) {
        if (json == null || !StringUtils.hasText(json)) {
            return fallback;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException ex) {
            return fallback;
        }
    }

}
