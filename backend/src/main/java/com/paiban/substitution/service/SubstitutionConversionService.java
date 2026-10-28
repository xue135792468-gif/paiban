package com.paiban.substitution.service;

import com.paiban.common.web.ApiException;
import com.paiban.foundation.domain.AdminClass;
import com.paiban.foundation.domain.AdminClassRepository;
import com.paiban.foundation.domain.AuditLog;
import com.paiban.foundation.domain.AuditLogRepository;
import com.paiban.foundation.domain.Course;
import com.paiban.foundation.domain.CourseRepository;
import com.paiban.foundation.domain.Room;
import com.paiban.foundation.domain.RoomRepository;
import com.paiban.foundation.domain.Semester;
import com.paiban.foundation.domain.Teacher;
import com.paiban.foundation.domain.TeacherRepository;
import com.paiban.foundation.service.SemesterService;
import com.paiban.substitution.conversion.LegacyConversionPlanner;
import com.paiban.substitution.conversion.LegacyConversionPlanner.BlockerCode;
import com.paiban.substitution.conversion.LegacyConversionPlanner.Change;
import com.paiban.substitution.conversion.LegacyConversionPlanner.EntrySlice;
import com.paiban.substitution.conversion.LegacyConversionPlanner.Item;
import com.paiban.substitution.conversion.LegacyConversionPlanner.LessonOnDate;
import com.paiban.substitution.conversion.LegacyConversionPlanner.Report;
import com.paiban.substitution.conversion.LegacyConversionPlanner.RowSlice;
import com.paiban.substitution.domain.Substitution;
import com.paiban.substitution.domain.SubstitutionConversion;
import com.paiban.substitution.domain.SubstitutionConversionItem;
import com.paiban.substitution.domain.SubstitutionConversionItemRepository;
import com.paiban.substitution.domain.SubstitutionConversionRepository;
import com.paiban.substitution.domain.SubstitutionPlanMove;
import com.paiban.substitution.domain.SubstitutionPlanMoveRepository;
import com.paiban.substitution.domain.SubstitutionRepository;
import com.paiban.substitution.domain.SubstitutionRequest;
import com.paiban.substitution.domain.SubstitutionRequestRepository;
import com.paiban.substitution.web.SubstitutionConversionDtos.AffectedDateDto;
import com.paiban.substitution.web.SubstitutionConversionDtos.ConversionBatchDto;
import com.paiban.substitution.web.SubstitutionConversionDtos.ConversionConfirmResultDto;
import com.paiban.substitution.web.SubstitutionConversionDtos.ConversionItemRecordDto;
import com.paiban.substitution.web.SubstitutionConversionDtos.ConversionPrecheckDto;
import com.paiban.substitution.web.SubstitutionConversionDtos.ConversionStatusDto;
import com.paiban.substitution.web.SubstitutionConversionDtos.ItemDto;
import com.paiban.substitution.web.SubstitutionConversionDtos.LessonDto;
import com.paiban.timetable.domain.TimetableEntry;
import com.paiban.timetable.domain.TimetableEntryRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

/**
 * 历史调代日期转换（票 02，ADR-0030）：只读预检 → 数据确认门 → 确认后确定性转换。
 *
 * <p>预检不写任何业务数据；确认转换在一个原子事务里完成（学期行锁 + 推导复核 +
 * 写实例日期 + 批次留痕），中途出错整单回滚、原数据不动；重复执行对已转换行
 * 跳过并如实报告。未转换的旧记录继续按旧语义读取，本服务不改任何读路径。
 */
@Service
public class SubstitutionConversionService {

    private final SubstitutionRepository substitutions;
    private final SubstitutionRequestRepository requests;
    private final SubstitutionPlanMoveRepository planMoves;
    private final SubstitutionConversionRepository conversions;
    private final SubstitutionConversionItemRepository conversionItems;
    private final TimetableEntryRepository entries;
    private final AdminClassRepository adminClasses;
    private final CourseRepository courses;
    private final TeacherRepository teachers;
    private final RoomRepository rooms;
    private final SemesterService semesters;
    private final SubstitutionEffects effects;
    private final AuditLogRepository auditLogs;
    /** 确认转换的事务边界：编程式事务让「失败留痕」能用独立事务写出去。 */
    private final TransactionTemplate writeTx;
    private final TransactionTemplate failureTx;

    public SubstitutionConversionService(
            SubstitutionRepository substitutions,
            SubstitutionRequestRepository requests,
            SubstitutionPlanMoveRepository planMoves,
            SubstitutionConversionRepository conversions,
            SubstitutionConversionItemRepository conversionItems,
            TimetableEntryRepository entries,
            AdminClassRepository adminClasses,
            CourseRepository courses,
            TeacherRepository teachers,
            RoomRepository rooms,
            SemesterService semesters,
            SubstitutionEffects effects,
            AuditLogRepository auditLogs,
            PlatformTransactionManager transactionManager) {
        this.substitutions = substitutions;
        this.requests = requests;
        this.planMoves = planMoves;
        this.conversions = conversions;
        this.conversionItems = conversionItems;
        this.entries = entries;
        this.adminClasses = adminClasses;
        this.courses = courses;
        this.teachers = teachers;
        this.rooms = rooms;
        this.semesters = semesters;
        this.effects = effects;
        this.auditLogs = auditLogs;
        this.writeTx = new TransactionTemplate(transactionManager);
        TransactionTemplate independent = new TransactionTemplate(transactionManager);
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.failureTx = independent;
    }

    // ------------------------------------------------------------------
    // 只读预检：报告 + 状态
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public ConversionPrecheckDto precheck(Long semesterId) {
        Long resolved = resolveSemester(semesterId);
        Loaded loaded = load(resolved);
        Report report = plan(loaded);
        return new ConversionPrecheckDto(resolved, Instant.now(),
                com.paiban.substitution.web.SubstitutionConversionDtos.toSummaryDto(report.summary()),
                report.items().stream().map(item -> toItemDto(item, loaded, report)).toList());
    }

    @Transactional(readOnly = true)
    public ConversionStatusDto status(Long semesterId) {
        Long resolved = resolveSemester(semesterId);
        Loaded loaded = load(resolved);
        Report report = plan(loaded);
        long legacy = loaded.rows().stream().filter(row -> row.getLessonDate() == null).count();
        List<ConversionBatchDto> batches = conversions.findBySemesterIdOrderByCreatedAtDesc(resolved)
                .stream().map(this::toBatchDto).toList();
        return new ConversionStatusDto(
                resolved,
                legacy,
                report.summary().convertible(),
                report.summary().blocked(),
                legacy == 0,
                legacy > 0 && report.summary().blocked() == 0,
                batches);
    }

    @Transactional(readOnly = true)
    public List<ConversionBatchDto> records(Long semesterId) {
        Long resolved = resolveSemester(semesterId);
        return conversions.findBySemesterIdOrderByCreatedAtDesc(resolved).stream()
                .map(this::toBatchDto)
                .toList();
    }

    // ------------------------------------------------------------------
    // 数据确认门 + 确定性转换
    // ------------------------------------------------------------------

    /**
     * 确认并转换。阻塞记录存在时拒绝（409），原数据保持不变；没有待转换
     * 行且已有成功批次时幂等返回。意外失败在独立事务里留下 FAILED 批次
     * 与失败原因，业务事务本身整单回滚。
     */
    public ConversionConfirmResultDto confirm(Long semesterId, String username, String comment) {
        try {
            return writeTx.execute(status -> confirmInsideTransaction(semesterId, username, comment));
        } catch (ApiException ex) {
            // 阻塞拒绝 / 无历史可转是正常业务结果：不留失败批次，一切照旧
            throw ex;
        } catch (RuntimeException ex) {
            recordFailure(semesterId, username, ex);
            throw ex;
        }
    }

    private ConversionConfirmResultDto confirmInsideTransaction(Long semesterId, String username, String comment) {
        Long resolved = resolveSemester(semesterId);
        // 与所有调代写入共用学期行锁：转换期间不允许并发生效写交错
        effects.lockSemester(resolved);
        Loaded loaded = load(resolved);
        Report report = plan(loaded);

        if (report.summary().blocked() > 0) {
            String codes = report.items().stream()
                    .filter(item -> item.status() == LegacyConversionPlanner.Status.BLOCKED)
                    .map(Item::blockerCode)
                    .map(BlockerCode::name)
                    .distinct()
                    .collect(Collectors.joining("、"));
            throw ApiException.conflict("存在 " + report.summary().blocked() + " 条阻塞记录（" + codes
                    + "），转换被拒绝，原数据保持不变；请在预检报告中人工处理阻塞记录后再确认");
        }
        // 没有仍待转换的行：若已有成功批次则幂等返回，不重复转换也不新建批次
        if (report.summary().convertible() == 0) {
            SubstitutionConversion last = conversions.findBySemesterIdOrderByCreatedAtDesc(resolved).stream()
                    .filter(batch -> SubstitutionConversion.CONVERTED.equals(batch.getStatus()))
                    .findFirst().orElse(null);
            if (last == null) {
                throw ApiException.badRequest("本学期没有旧格式历史记录需要转换");
            }
            return new ConversionConfirmResultDto(0, 0, true, toBatchDto(last));
        }

        Instant now = Instant.now();
        Map<Long, Substitution> rowById = loaded.rows().stream()
                .collect(Collectors.toMap(Substitution::getId, row -> row, (left, right) -> left));
        List<Substitution> modified = new ArrayList<>();
        List<SubstitutionConversionItem> items = new ArrayList<>();
        for (Item item : report.items()) {
            if (item.status() != LegacyConversionPlanner.Status.CONVERTIBLE) {
                continue;
            }
            Substitution row = rowById.get(item.substitutionId());
            if (row == null || row.getLessonDate() != null) {
                // 推导与现实脱节（理论上不可能）：宁可中断也不写脏
                throw new IllegalStateException(
                        "转换目标行状态已变化: substitution=" + item.substitutionId());
            }
            SubstitutionConversionItem record = new SubstitutionConversionItem();
            record.setSubstitutionId(row.getId());
            record.setEntryId(row.getEntryId());
            record.setType(typeOf(row));
            record.setLessonDateBefore(row.getLessonDate());
            record.setTargetLessonDateBefore(row.getTargetLessonDate());
            record.setLessonDateAfter(item.lessonDate());
            record.setTargetLessonDateAfter(item.targetLessonDate());
            record.setBasis(item.basis());
            row.setLessonDate(item.lessonDate());
            row.setTargetLessonDate(item.targetLessonDate());
            modified.add(row);
            items.add(record);
        }
        substitutions.saveAll(modified);

        SubstitutionConversion batch = new SubstitutionConversion();
        batch.setSemesterId(resolved);
        batch.setStatus(SubstitutionConversion.CONVERTED);
        // 本批确认时的未转换旧行 = 可转换 + 阻塞；成功批次里阻塞必为 0
        batch.setLegacyTotal(report.summary().convertible() + report.summary().blocked());
        batch.setConvertedCount(items.size());
        batch.setBlockedCount(report.summary().blocked());
        batch.setConfirmedBy(username);
        batch.setConfirmedAt(now);
        batch.setConfirmationComment(comment);
        batch.setCreatedAt(now);
        SubstitutionConversion savedBatch = conversions.save(batch);
        items.forEach(record -> record.setConversionId(savedBatch.getId()));
        conversionItems.saveAll(items);

        auditLogs.save(new AuditLog(username, "SUBSTITUTION_CONVERSION_CONFIRMED", "SUBSTITUTION_CONVERSION",
                "semester=" + resolved + ", converted=" + items.size()
                        + (StringUtils.hasText(comment) ? ", comment=" + comment : ""),
                items.size(), 0));
        return new ConversionConfirmResultDto(items.size(), report.summary().blocked(), false,
                toBatchDto(savedBatch, items));
    }

    /** 意外失败的留痕：独立事务，避免随业务回滚一起消失。 */
    private void recordFailure(Long semesterId, String username, RuntimeException cause) {
        try {
            failureTx.executeWithoutResult(status -> {
                try {
                    SubstitutionConversion batch = new SubstitutionConversion();
                    batch.setSemesterId(resolveSemester(semesterId));
                    batch.setStatus(SubstitutionConversion.FAILED);
                    batch.setLegacyTotal(0);
                    batch.setConvertedCount(0);
                    batch.setBlockedCount(0);
                    batch.setConfirmedBy(username);
                    batch.setConfirmedAt(Instant.now());
                    batch.setFailureReason(truncate(cause.getMessage() == null
                            ? cause.getClass().getSimpleName() : cause.getMessage()));
                    batch.setCreatedAt(Instant.now());
                    conversions.save(batch);
                    auditLogs.save(new AuditLog(username, "SUBSTITUTION_CONVERSION_FAILED",
                            "SUBSTITUTION_CONVERSION",
                            "semester=" + batch.getSemesterId() + ", reason=" + batch.getFailureReason(),
                            0, 1));
                } catch (RuntimeException ignored) {
                    // 失败留痕自身失败时不再叠加异常，原始异常照常抛出
                }
            });
        } catch (RuntimeException ignored) {
            // 同上
        }
    }

    // ------------------------------------------------------------------
    // 装载与推导
    // ------------------------------------------------------------------

    private Long resolveSemester(Long semesterId) {
        Semester semester = semesterId != null
                ? semesters.require(semesterId)
                : semesters.requireCurrent();
        return semester.getId();
    }

    /** 一次预检/确认共用的数据装载。rows 只含 EFFECTIVE 行（CANCELLED 行不参与读取）。 */
    private record Loaded(List<Substitution> rows,
                          Map<Long, TimetableEntry> entryById,
                          Map<Long, LocalDate> requestDates,
                          List<SubstitutionPlanMove> moves,
                          Semester semester) {}

    private Loaded load(Long semesterId) {
        List<Substitution> rows = substitutions.findBySemesterIdAndStatus(semesterId, Substitution.EFFECTIVE);
        Set<Long> entryIds = rows.stream().map(Substitution::getEntryId).collect(Collectors.toSet());
        Map<Long, TimetableEntry> entryById = entries.findAllById(entryIds).stream()
                .collect(Collectors.toMap(TimetableEntry::getId, entry -> entry, (left, right) -> left));
        Set<Long> requestIds = rows.stream().map(Substitution::getRequestId).collect(Collectors.toSet());
        Map<Long, LocalDate> requestDates = new HashMap<>();
        requests.findAllById(requestIds).forEach(request ->
                requestDates.put(request.getId(), request.getEffectiveDate()));
        Set<Long> planIds = rows.stream().map(Substitution::getPlanId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        List<SubstitutionPlanMove> moves = planIds.isEmpty()
                ? List.of() : planMoves.findByPlanIdIn(planIds);
        return new Loaded(rows, entryById, requestDates, moves, semesters.require(semesterId));
    }

    private Report plan(Loaded loaded) {
        List<EntrySlice> entrySlices = loaded.entryById().values().stream()
                .map(entry -> new EntrySlice(entry.getId(), entry.getPeriodId(), entry.getDayOfWeek(),
                        entry.getPeriodNo(), entry.getTeacherId(), entry.getRoomId()))
                .toList();
        List<RowSlice> rowSlices = loaded.rows().stream().map(this::toRowSlice).toList();
        List<LegacyConversionPlanner.MoveSlice> moveSlices = loaded.moves().stream()
                .map(move -> new LegacyConversionPlanner.MoveSlice(
                        move.getPlanId(), move.getEntryId(), move.getFromDate(), move.getToDate()))
                .toList();
        return LegacyConversionPlanner.plan(
                loaded.semester().getStartDate(), loaded.semester().getEndDate(),
                entrySlices, rowSlices, loaded.requestDates(), moveSlices);
    }

    private RowSlice toRowSlice(Substitution row) {
        return new RowSlice(row.getId(), row.getEntryId(), typeOf(row), row.getEffectiveDate(),
                row.getLessonDate(), row.getTargetLessonDate(), row.getCreatedAt(), row.getRequestId(),
                row.getPlanId(), row.getNewPeriodId(), row.getNewDayOfWeek(), row.getNewPeriodNo(),
                row.getNewRoomId(), row.getSubstituteTeacherId());
    }

    private static String typeOf(Substitution row) {
        return StringUtils.hasText(row.getType()) ? row.getType() : Substitution.TYPE_COVER;
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 500 ? value : value.substring(0, 497) + "...";
    }

    // ------------------------------------------------------------------
    // DTO 组装（名称解析只发生在读侧）
    // ------------------------------------------------------------------

    private ItemDto toItemDto(Item item, Loaded loaded, Report report) {
        Substitution row = loaded.rows().stream()
                .filter(candidate -> candidate.getId() == item.substitutionId())
                .findFirst().orElse(null);
        TimetableEntry entry = loaded.entryById().get(item.entryId());
        String className = entry == null ? null
                : adminClasses.findById(entry.getAdminClassId()).map(AdminClass::getName).orElse(null);
        String courseName = entry == null ? null
                : courses.findById(entry.getCourseId()).map(Course::getName).orElse(null);
        String originalTeacherName = entry == null ? null
                : teachers.findById(entry.getTeacherId()).map(Teacher::getName).orElse(null);
        String substituteTeacherName = row == null ? null
                : teachers.findById(row.getSubstituteTeacherId()).map(Teacher::getName).orElse(null);
        String fromPeriodLabel = entry == null ? null
                : SubstitutionMapper.label(entry.getDayOfWeek(), entry.getPeriodNo());
        List<AffectedDateDto> affected = affectedDates(item.substitutionId(), entry, className,
                courseName, report);
        return new ItemDto(
                item.substitutionId(),
                row == null ? null : row.getRequestId(),
                item.type().equals("SWAP") && row != null ? row.getPlanId() : null,
                item.entryId(),
                item.type(),
                item.status().name(),
                item.basis(),
                row == null ? null : row.getEffectiveDate().toString(),
                item.lessonDate() == null ? null : item.lessonDate().toString(),
                item.targetLessonDate() == null ? null : item.targetLessonDate().toString(),
                item.blockerCode() == null ? null : item.blockerCode().name(),
                item.blockerReason(),
                className,
                courseName,
                originalTeacherName,
                substituteTeacherName,
                fromPeriodLabel,
                affected);
    }

    private List<AffectedDateDto> affectedDates(long substitutionId, TimetableEntry entry,
                                                String className, String courseName, Report report) {
        if (entry == null) {
            return List.of();
        }
        return report.changes().stream()
                .filter(change -> change.causedBy() == substitutionId)
                .map(change -> new AffectedDateDto(
                        change.date().toString(),
                        change.after() != null && change.after().substitutionId() == null,
                        toLessonDto(change.before(), entry, className, courseName),
                        toLessonDto(change.after(), entry, className, courseName)))
                .toList();
    }

    private LessonDto toLessonDto(LessonOnDate lesson, TimetableEntry entry,
                                  String className, String courseName) {
        if (lesson == null) {
            return null;
        }
        return new LessonDto(
                lesson.substitutionId(),
                lesson.teacherId(),
                teachers.findById(lesson.teacherId()).map(Teacher::getName).orElse(null),
                lesson.roomId(),
                lesson.roomId() == null ? null : rooms.findById(lesson.roomId()).map(Room::getName).orElse(null),
                entry.getDayOfWeek(),
                lesson.periodNo(),
                SubstitutionMapper.label(entry.getDayOfWeek(), lesson.periodNo()),
                className,
                courseName);
    }

    private ConversionBatchDto toBatchDto(SubstitutionConversion batch) {
        return toBatchDto(batch, conversionItems.findByConversionIdOrderByIdAsc(batch.getId()));
    }

    private ConversionBatchDto toBatchDto(SubstitutionConversion batch,
                                          List<SubstitutionConversionItem> items) {
        return new ConversionBatchDto(
                batch.getId(),
                batch.getStatus(),
                batch.getLegacyTotal(),
                batch.getConvertedCount(),
                batch.getBlockedCount(),
                batch.getConfirmedBy(),
                batch.getConfirmedAt(),
                batch.getConfirmationComment(),
                batch.getFailureReason(),
                batch.getCreatedAt(),
                items.stream().map(item -> new ConversionItemRecordDto(
                        item.getSubstitutionId(),
                        item.getEntryId(),
                        item.getType(),
                        item.getLessonDateBefore() == null ? null : item.getLessonDateBefore().toString(),
                        item.getTargetLessonDateBefore() == null ? null
                                : item.getTargetLessonDateBefore().toString(),
                        item.getLessonDateAfter() == null ? null : item.getLessonDateAfter().toString(),
                        item.getTargetLessonDateAfter() == null ? null
                                : item.getTargetLessonDateAfter().toString(),
                        item.getBasis()))
                        .toList());
    }
}
