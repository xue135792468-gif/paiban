package com.paiban.substitution.conversion;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.util.StringUtils;

/**
 * 历史调代日期转换的推导器（ADR-0030 规格第 2 节「历史解释变更是显式迁移范围」）。
 *
 * <p>纯函数、不依赖 Spring 与数据库：输入生效行、来源申请日期与原方案移动，
 * 输出逐条转换结论（可转换 / 阻塞 / 已跳过）与「转换前后日期课表差异」。
 * 判定语义与读取路径保持一致——旧格式（lesson_date 为空）按「自 effective_date
 * 起持续覆盖」，新格式按实例日期精确命中；转换在字段依据不足时一律阻塞并保留
 * 原数据，绝不静默推断「确属长期安排」。
 */
public final class LegacyConversionPlanner {

    private LegacyConversionPlanner() {}

    /** 单条记录的转换结论。 */
    public enum Status {
        /** 依据充分，确认后可写入实例日期。 */
        CONVERTIBLE,
        /** 依据缺失、歧义或冲突，保持旧语义，等待人工决定。 */
        BLOCKED,
        /** 已带实例日期（此前已转换），重复执行时跳过。 */
        ALREADY_CONVERTED
    }

    /** 阻塞原因分类：报告与确认门都用它向管理者解释为什么不能转。 */
    public enum BlockerCode {
        /** 缺失来源申请（COVER 行找不到 substitution_request）。 */
        MISSING_REQUEST,
        /** 无对应方案移动（SWAP 行缺 plan_id 或方案里没有该条目的移动）。 */
        MISSING_MOVE,
        /** 同一方案下同一条目出现多条移动，无法唯一解释。 */
        AMBIGUOUS_MOVE,
        /** 来源依据与生效行日期互相矛盾（版本冲突）。 */
        VERSION_CONFLICT,
        /** 未知类型，无法唯一解释。 */
        UNKNOWN_TYPE
    }

    /** 基线课表条目中参与推导与差异计算的最小切片。 */
    public record EntrySlice(long entryId, long periodId, int dayOfWeek, int periodNo,
                             long teacherId, Long roomId) {}

    /** substitution 生效行切片（只传入 EFFECTIVE 行；CANCELLED 行不参与读取，也无需转换）。 */
    public record RowSlice(long id,
                           long entryId,
                           String type,
                           LocalDate effectiveDate,
                           LocalDate lessonDate,
                           LocalDate targetLessonDate,
                           java.time.Instant createdAt,
                           long requestId,
                           Long planId,
                           Long newPeriodId,
                           Integer newDayOfWeek,
                           Integer newPeriodNo,
                           Long newRoomId,
                           Long substituteTeacherId) {}

    /** 原方案的移动切片（substitution_plan_move）。 */
    public record MoveSlice(long planId, long entryId, LocalDate fromDate, LocalDate toDate) {}

    /** 逐条转换结论。 */
    public record Item(long substitutionId,
                       long entryId,
                       String type,
                       Status status,
                       LocalDate lessonDate,
                       LocalDate targetLessonDate,
                       String basis,
                       BlockerCode blockerCode,
                       String blockerReason) {}

    /** 某天某条目「当天实际出现的课」；null 表示该日无课。 */
    public record LessonOnDate(Long substitutionId, long periodId, long teacherId,
                               Long roomId, int periodNo) {}

    /** 转换前后某天某条目的差异；causedBy 指向引发差异的可转换行。 */
    public record Change(LocalDate date, long entryId, long causedBy,
                         LessonOnDate before, LessonOnDate after) {}

    /** 汇总数量：与逐条 items 恒等（同一次推导派生）；totalRows 是扫描的生效行总数。 */
    public record Summary(int totalRows, int convertible, int blocked,
                          int alreadyConverted, int changes) {}

    public record Report(List<Item> items, List<Change> changes, Summary summary) {}

    /**
     * 推导整批转换方案与差异清单。
     *
     * @param semesterStart 学期开始日（旧格式覆盖的读取下界，与读路径一致）
     * @param semesterEnd   学期结束日（差异扫描的上界）
     */
    public static Report plan(LocalDate semesterStart,
                              LocalDate semesterEnd,
                              List<EntrySlice> entries,
                              List<RowSlice> rows,
                              Map<Long, LocalDate> requestDates,
                              List<MoveSlice> moves) {
        Map<Long, List<MoveSlice>> movesByPlan = new LinkedHashMap<>();
        for (MoveSlice move : moves) {
            movesByPlan.computeIfAbsent(move.planId(), ignored -> new ArrayList<>()).add(move);
        }
        Map<Long, EntrySlice> entryById = new LinkedHashMap<>();
        for (EntrySlice entry : entries) {
            entryById.put(entry.entryId(), entry);
        }

        List<Item> items = new ArrayList<>(rows.size());
        Map<Long, Item> convertibleByRow = new LinkedHashMap<>();
        for (RowSlice row : rows) {
            Item item = derive(row, requestDates, movesByPlan);
            items.add(item);
            if (item.status() == Status.CONVERTIBLE) {
                convertibleByRow.put(row.id(), item);
            }
        }

        List<Change> changes = changes(semesterStart, semesterEnd, entryById, rows, convertibleByRow);
        int convertible = (int) items.stream().filter(item -> item.status() == Status.CONVERTIBLE).count();
        int blocked = (int) items.stream().filter(item -> item.status() == Status.BLOCKED).count();
        int already = (int) items.stream().filter(item -> item.status() == Status.ALREADY_CONVERTED).count();
        return new Report(List.copyOf(items), List.copyOf(changes),
                new Summary(items.size(), convertible, blocked, already, changes.size()));    }

    // ---------------------------------------------------------------------
    // 单条推导：日期还原与阻塞判定
    // ---------------------------------------------------------------------

    private static Item derive(RowSlice row,
                               Map<Long, LocalDate> requestDates,
                               Map<Long, List<MoveSlice>> movesByPlan) {
        if (row.lessonDate() != null) {
            return new Item(row.id(), row.entryId(), typeOf(row), Status.ALREADY_CONVERTED,
                    row.lessonDate(), row.targetLessonDate(),
                    "已写入实例日期 " + row.lessonDate()
                            + (row.targetLessonDate() == null ? "" : "（落点 " + row.targetLessonDate() + "）"),
                    null, null);
        }
        String type = typeOf(row);
        return switch (type) {
            case "COVER" -> deriveCover(row, requestDates);
            case "SWAP" -> deriveSwap(row, movesByPlan);
            default -> blocked(row, type, BlockerCode.UNKNOWN_TYPE,
                    "未知类型 " + type + "，无法唯一解释日期依据，需人工决定");
        };
    }

    private static Item deriveCover(RowSlice row, Map<Long, LocalDate> requestDates) {
        LocalDate requestDate = requestDates.get(row.requestId());
        if (requestDate == null) {
            return blocked(row, "COVER", BlockerCode.MISSING_REQUEST,
                    "找不到来源申请 #" + row.requestId() + "，无法还原授课日期");
        }
        if (!requestDate.equals(row.effectiveDate())) {
            return blocked(row, "COVER", BlockerCode.VERSION_CONFLICT,
                    "来源申请 #" + row.requestId() + " 的生效日期 " + requestDate
                            + " 与生效行日期 " + row.effectiveDate() + " 矛盾，无法唯一解释");
        }
        return new Item(row.id(), row.entryId(), "COVER", Status.CONVERTIBLE,
                requestDate, null,
                "沿用来源申请 #" + row.requestId() + " 的生效日期 " + requestDate, null, null);
    }

    private static Item deriveSwap(RowSlice row, Map<Long, List<MoveSlice>> movesByPlan) {
        if (row.planId() == null) {
            return blocked(row, "SWAP", BlockerCode.MISSING_MOVE,
                    "SWAP 行没有 plan_id，无对应方案移动，无法还原来源与目标日期");
        }
        List<MoveSlice> candidates = movesByPlan.getOrDefault(row.planId(), List.of()).stream()
                .filter(move -> move.entryId() == row.entryId())
                .toList();
        if (candidates.isEmpty()) {
            return blocked(row, "SWAP", BlockerCode.MISSING_MOVE,
                    "原方案 #" + row.planId() + " 中没有条目 " + row.entryId() + " 的移动记录");
        }
        if (candidates.size() > 1) {
            return blocked(row, "SWAP", BlockerCode.AMBIGUOUS_MOVE,
                    "原方案 #" + row.planId() + " 中条目 " + row.entryId() + " 有 "
                            + candidates.size() + " 条移动，无法唯一解释");
        }
        MoveSlice move = candidates.get(0);
        if (!move.fromDate().equals(row.effectiveDate())) {
            return blocked(row, "SWAP", BlockerCode.VERSION_CONFLICT,
                    "原方案 #" + row.planId() + " 的移动来源日 " + move.fromDate()
                            + " 与生效行日期 " + row.effectiveDate() + " 矛盾，无法唯一解释");
        }
        return new Item(row.id(), row.entryId(), "SWAP", Status.CONVERTIBLE,
                move.fromDate(), move.toDate(),
                "沿用原方案 #" + row.planId() + " 的移动 " + move.fromDate() + " → " + move.toDate(),
                null, null);
    }

    private static Item blocked(RowSlice row, String type, BlockerCode code, String reason) {
        return new Item(row.id(), row.entryId(), type, Status.BLOCKED, null, null, null, code, reason);
    }

    private static String typeOf(RowSlice row) {
        // org.springframework.util.StringUtils 仅作静态工具（hasText），不引入容器依赖
        return StringUtils.hasText(row.type()) ? row.type() : "COVER";
    }

    // ---------------------------------------------------------------------
    // 批次级差异：转换前后「日期课表」对比
    // ---------------------------------------------------------------------

    /**
     * 旧格式行是否作用于查询日（与 PlanSnapshot/TimetableViewService 的读取语义一致：
     * effective_date 落在学期内且不晚于查询日 = 持续覆盖）。
     */
    private static boolean legacyTouches(RowSlice row, LocalDate date, LocalDate semesterStart) {
        return !row.effectiveDate().isBefore(semesterStart) && !row.effectiveDate().isAfter(date);
    }

    /** 新格式行是否作用于查询日（来源实例日或调课落点日精确命中）。 */
    private static boolean instanceTouches(RowSlice row, LocalDate date) {
        return date.equals(row.lessonDate()) || date.equals(row.targetLessonDate());
    }

    private static boolean touches(RowSlice row, LocalDate date, LocalDate semesterStart) {
        return row.lessonDate() == null
                ? legacyTouches(row, date, semesterStart)
                : instanceTouches(row, date);
    }

    /**
     * 某天某条目的生效行：在「作用于该日」的行里取 createdAt（其次 id）最新的一笔，
     * 与 overlayMap / latestPerEntry 的「最后一笔胜出」一致。
     */
    private static RowSlice winnerFor(List<RowSlice> rowsOfEntry, LocalDate date, LocalDate semesterStart) {
        RowSlice winner = null;
        for (RowSlice row : rowsOfEntry) {
            if (!touches(row, date, semesterStart)) {
                continue;
            }
            if (winner == null || laterThan(row, winner)) {
                winner = row;
            }
        }
        return winner;
    }

    private static boolean laterThan(RowSlice left, RowSlice right) {
        int byTime = left.createdAt().compareTo(right.createdAt());
        return byTime != 0 ? byTime > 0 : left.id() > right.id();
    }

    /** 基线课节在某天的原始安排。 */
    private static LessonOnDate baselineLesson(EntrySlice entry) {
        return new LessonOnDate(null, entry.periodId(), entry.teacherId(), entry.roomId(), entry.periodNo());
    }

    /** 覆盖行在某天呈现的课（与读路径 apply/scheduleOn 的解析一致）。 */
    private static LessonOnDate appliedLesson(EntrySlice entry, RowSlice row) {
        long periodId = row.newPeriodId() != null ? row.newPeriodId() : entry.periodId();
        int periodNo = row.newPeriodNo() != null ? row.newPeriodNo() : entry.periodNo();
        return new LessonOnDate(row.id(), periodId, row.substituteTeacherId(),
                row.newRoomId() != null ? row.newRoomId() : entry.roomId(), periodNo);
    }

    /** 某天该条目「实际出现的课」：无生效行或解析到的星期与当天不符即无课。 */
    private static LessonOnDate lessonOn(EntrySlice entry, RowSlice winner, LocalDate date) {
        if (winner == null) {
            return entry.dayOfWeek() == date.getDayOfWeek().getValue() ? baselineLesson(entry) : null;
        }
        int resolvedDay = winner.newDayOfWeek() != null ? winner.newDayOfWeek() : entry.dayOfWeek();
        if (resolvedDay != date.getDayOfWeek().getValue()) {
            return null;
        }
        return appliedLesson(entry, winner);
    }

    /**
     * 扫描整学期，产出转换前后的差异。差异只会由「可转换行收窄覆盖范围」引起：
     * before 赢家若是可转换行而 after 赢家不同，差异归因于该行；阻塞行与已转换行
     * 在两个模型里完全相同，不产生差异。
     */
    private static List<Change> changes(LocalDate semesterStart,
                                        LocalDate semesterEnd,
                                        Map<Long, EntrySlice> entryById,
                                        List<RowSlice> rows,
                                        Map<Long, Item> convertibleByRow) {
        Map<Long, List<RowSlice>> rowsByEntry = new LinkedHashMap<>();
        for (RowSlice row : rows) {
            rowsByEntry.computeIfAbsent(row.entryId(), ignored -> new ArrayList<>()).add(row);
        }
        List<Change> changes = new ArrayList<>();
        long days = Math.max(0, ChronoUnit.DAYS.between(semesterStart, semesterEnd));
        for (Map.Entry<Long, List<RowSlice>> byEntry : rowsByEntry.entrySet()) {
            EntrySlice entry = entryById.get(byEntry.getKey());
            if (entry == null) {
                // 基线条目已不存在（发布重建过 id）：该行不参与读取，也不产生课表差异
                continue;
            }
            List<RowSlice> rowsOfEntry = byEntry.getValue();
            List<RowSlice> afterRows = converted(rowsOfEntry, convertibleByRow);
            for (long offset = 0; offset <= days; offset++) {
                LocalDate date = semesterStart.plusDays(offset);
                RowSlice beforeWinner = winnerFor(rowsOfEntry, date, semesterStart);
                RowSlice afterWinner = winnerFor(afterRows, date, semesterStart);
                if (Objects.equals(beforeWinner == null ? null : beforeWinner.id(),
                        afterWinner == null ? null : afterWinner.id())) {
                    continue;
                }
                LessonOnDate before = lessonOn(entry, beforeWinner, date);
                LessonOnDate after = lessonOn(entry, afterWinner, date);
                if (Objects.equals(before, after)) {
                    continue;
                }
                // before 赢家必然是被收窄的可转换行；阻塞/已转换行在两模型中一致
                if (beforeWinner == null || !convertibleByRow.containsKey(beforeWinner.id())) {
                    continue;
                }
                changes.add(new Change(date, entry.entryId(), beforeWinner.id(), before, after));
            }
        }
        changes.sort(Comparator.comparing(Change::date).thenComparing(Change::entryId));
        return changes;
    }

    /** after 模型：可转换行替换为推导出的实例日期，其余行原样。 */
    private static List<RowSlice> converted(List<RowSlice> rowsOfEntry, Map<Long, Item> convertibleByRow) {
        List<RowSlice> result = new ArrayList<>(rowsOfEntry.size());
        for (RowSlice row : rowsOfEntry) {
            Item item = convertibleByRow.get(row.id());
            if (item == null) {
                result.add(row);
                continue;
            }
            result.add(new RowSlice(row.id(), row.entryId(), row.type(), row.effectiveDate(),
                    item.lessonDate(), item.targetLessonDate(), row.createdAt(), row.requestId(),
                    row.planId(), row.newPeriodId(), row.newDayOfWeek(), row.newPeriodNo(),
                    row.newRoomId(), row.substituteTeacherId()));
        }
        return result;
    }
}
