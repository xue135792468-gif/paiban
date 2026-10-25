package com.paiban.ai.imports;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paiban.ai.imports.ImportDtos.ApplyMissingPeriodsRequest;
import com.paiban.ai.imports.ImportDtos.ApplyMissingPeriodsResult;
import com.paiban.ai.imports.ImportDtos.MissingPeriodDto;
import com.paiban.ai.imports.ImportDtos.MissingPeriodItem;
import com.paiban.ai.imports.ImportDtos.MissingPeriodsDto;
import com.paiban.ai.imports.ImportDtos.RowDto;
import com.paiban.ai.imports.PeriodGapPlanner.Existing;
import com.paiban.ai.imports.PeriodGapPlanner.Plan;
import com.paiban.ai.imports.PeriodGapPlanner.Slot;
import com.paiban.ai.imports.PeriodGapPlanner.Status;
import com.paiban.common.web.ApiException;
import com.paiban.foundation.domain.Period;
import com.paiban.foundation.domain.PeriodRepository;
import com.paiban.foundation.service.SemesterService;
import com.paiban.foundation.web.SemesterDtos.PeriodDto;
import com.paiban.foundation.web.SemesterDtos.PeriodSlot;
import com.paiban.foundation.web.SemesterDtos.SemesterDto;
import com.paiban.identity.domain.AppUser;
import com.paiban.identity.domain.Permissions;
import com.paiban.identity.service.UserService;
import com.paiban.timetable.domain.TimetableEntryRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ticket 08: the periods a saved import preview references but the current semester does not
 * have. The list comes from {@code preview_json}, never from a fresh parse, so reading it or
 * filling it costs no model call. Filling is append-only: the planner's times are validated
 * again inside the write transaction, and no existing period row is touched.
 */
@Service
public class MissingPeriodsService {

    private static final String STALE_PLAN = "方案已过期，请刷新预览";
    private static final String PUBLISHED_REASON = "该学期已发布课表，作息不可补充";
    private static final String NO_SEMESTER = "请先设置当前学期";

    private final AiImportLogRepository logs;
    private final ObjectMapper objectMapper;
    private final SemesterService semesters;
    private final PeriodRepository periods;
    private final TimetableEntryRepository timetableEntries;
    private final UserService users;
    private final ImportPermissions permissions;

    public MissingPeriodsService(
            AiImportLogRepository logs,
            ObjectMapper objectMapper,
            SemesterService semesters,
            PeriodRepository periods,
            TimetableEntryRepository timetableEntries,
            UserService users,
            ImportPermissions permissions) {
        this.logs = logs;
        this.objectMapper = objectMapper;
        this.semesters = semesters;
        this.periods = periods;
        this.timetableEntries = timetableEntries;
        this.users = users;
        this.permissions = permissions;
    }

    /** The read-only gap list: what the preview uses, what the semester has, what is missing. */
    @Transactional(readOnly = true)
    public MissingPeriodsDto plan(long sessionId, String username) {
        AiImportLog log = requireSession(sessionId, username);
        return buildPlan(currentSemester(), rows(log));
    }

    /**
     * Appends the confirmed slots. The whole confirmation is checked again inside one
     * transaction: same semester, still unpublished, and the anchors still yield the same
     * times. A slot that is already there is skipped, so a repeated confirmation is a no-op.
     */
    @Transactional
    public ApplyMissingPeriodsResult apply(
            long sessionId, String username, ApplyMissingPeriodsRequest request) {
        permissions.requireSemesterManage(username);
        AiImportLog log = requireSession(sessionId, username);
        SemesterDto semester = currentSemester();
        if (!semester.id().equals(request.semesterId())) {
            throw ApiException.conflict(STALE_PLAN);
        }
        // Locks the semester and refuses a published one; the rows below are the anchors
        // the submitted times are validated against.
        List<PeriodDto> locked = semesters.lockPeriodsForAppend(semester.id());
        List<RowDto> rows = rows(log);
        Map<String, PeriodDto> existing = byKey(locked);
        Map<String, Plan> proposed = proposedPlans(locked, missingSlots(rows, existing.keySet()));

        List<PeriodSlot> toCreate = new ArrayList<>();
        Map<String, MissingPeriodItem> confirmed = new LinkedHashMap<>();
        for (MissingPeriodItem item : request.items()) {
            String key = key(item.dayOfWeek(), item.periodNo());
            MissingPeriodItem duplicate = confirmed.putIfAbsent(key, item);
            if (duplicate != null) {
                if (!duplicate.startTime().equals(item.startTime())
                        || !duplicate.endTime().equals(item.endTime())) {
                    throw ApiException.conflict(STALE_PLAN);
                }
                continue;
            }
            if (existing.containsKey(key)) {
                // Already appended by an earlier confirmation: idempotent, nothing to do.
                continue;
            }
            Plan plan = proposed.get(key);
            if (plan == null
                    || !plan.startTime().equals(item.startTime())
                    || !plan.endTime().equals(item.endTime())) {
                throw ApiException.conflict(STALE_PLAN);
            }
            toCreate.add(new PeriodSlot(plan.periodNo(), plan.label(), plan.section(),
                    plan.startTime(), plan.endTime(), List.of(plan.dayOfWeek())));
        }
        if (!toCreate.isEmpty()) {
            semesters.appendPeriods(semester.id(), toCreate);
        }
        return new ApplyMissingPeriodsResult(toCreate.size(), buildPlan(semester, rows));
    }

    private MissingPeriodsDto buildPlan(SemesterDto semester, List<RowDto> rows) {
        boolean published = timetableEntries.countBySemesterId(semester.id()) > 0;
        List<Period> current = periods.findBySemesterIdOrderByDayOfWeekAscPeriodNoAsc(semester.id());
        Set<String> existing = new HashSet<>();
        List<Existing> anchors = new ArrayList<>();
        for (Period period : current) {
            existing.add(key(period.getDayOfWeek(), period.getPeriodNo()));
            anchors.add(new Existing(period.getDayOfWeek(), period.getPeriodNo(), period.getLabel(),
                    period.getSection(), period.getStartTime(), period.getEndTime()));
        }
        List<MissingPeriodDto> items = PeriodGapPlanner
                .plan(anchors, missingSlots(rows, existing)).stream()
                .map(plan -> published ? manual(plan, PUBLISHED_REASON) : dto(plan))
                .toList();
        return new MissingPeriodsDto(semester.id(), semester.name(), published, items);
    }

    private Map<String, Plan> proposedPlans(List<PeriodDto> existing, List<Slot> gaps) {
        List<Existing> anchors = existing.stream()
                .map(period -> new Existing(period.dayOfWeek(), period.periodNo(), period.label(),
                        period.section(), period.startTime(), period.endTime()))
                .toList();
        Map<String, Plan> proposed = new LinkedHashMap<>();
        for (Plan plan : PeriodGapPlanner.plan(anchors, gaps)) {
            if (plan.status() == Status.PROPOSED) {
                proposed.put(key(plan.dayOfWeek(), plan.periodNo()), plan);
            }
        }
        return proposed;
    }

    /**
     * The (day, periodNo) pairs the preview references and the semester lacks. A value
     * {@link SlotText} cannot read is left alone: the row keeps its own error and is never
     * guessed.
     */
    private static List<Slot> missingSlots(List<RowDto> rows, Set<String> existing) {
        Set<Slot> slots = new TreeSet<>(
                Comparator.comparingInt(Slot::dayOfWeek).thenComparingInt(Slot::periodNo));
        for (RowDto row : rows) {
            Integer day = SlotText.day(value(row, "dayOfWeek")).orElse(null);
            Integer periodNo = SlotText.periodNo(value(row, "periodNo")).orElse(null);
            if (day == null || periodNo == null || existing.contains(key(day, periodNo))) {
                continue;
            }
            slots.add(new Slot(day, periodNo));
        }
        return List.copyOf(slots);
    }

    private List<RowDto> rows(AiImportLog log) {
        String json = log.getPreviewJson();
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode rows = objectMapper.readTree(json).path("rows");
            if (!rows.isArray()) {
                return List.of();
            }
            return objectMapper.convertValue(rows, new TypeReference<List<RowDto>>() {});
        } catch (Exception ex) {
            return List.of();
        }
    }

    private static MissingPeriodDto dto(Plan plan) {
        boolean proposed = plan.status() == Status.PROPOSED;
        return new MissingPeriodDto(
                plan.dayOfWeek(), plan.periodNo(), plan.status().name(), plan.label(),
                proposed ? plan.section() : null,
                proposed ? plan.startTime() : null,
                proposed ? plan.endTime() : null,
                plan.reason());
    }

    private static MissingPeriodDto manual(Plan plan, String reason) {
        return new MissingPeriodDto(plan.dayOfWeek(), plan.periodNo(), Status.MANUAL.name(),
                plan.label(), null, null, null, reason);
    }

    private static Map<String, PeriodDto> byKey(List<PeriodDto> periods) {
        Map<String, PeriodDto> byKey = new LinkedHashMap<>();
        for (PeriodDto period : periods) {
            byKey.put(key(period.dayOfWeek(), period.periodNo()), period);
        }
        return byKey;
    }

    private static String key(int dayOfWeek, int periodNo) {
        return dayOfWeek + "-" + periodNo;
    }

    private static String value(RowDto row, String field) {
        if (row == null || row.values() == null) {
            return "";
        }
        String value = row.values().get(field);
        return value == null ? "" : value.trim();
    }

    private SemesterDto currentSemester() {
        return semesters.current().orElseThrow(() -> ApiException.badRequest(NO_SEMESTER));
    }

    /** The same ownership rule as every other session call: the owner, or an importer admin. */
    private AiImportLog requireSession(long id, String username) {
        AiImportLog log = logs.findById(id)
                .orElseThrow(() -> ApiException.notFound("导入会话不存在: " + id));
        AppUser user = users.getByUsername(username);
        boolean admin = user != null && users.hasPermission(user, Permissions.FOUNDATION_MANAGE);
        if (!Objects.equals(log.getUsername(), username) && !admin) {
            throw ApiException.forbidden("不能访问他人的导入记录");
        }
        return log;
    }
}
