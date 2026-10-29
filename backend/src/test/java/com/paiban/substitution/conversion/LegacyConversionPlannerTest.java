package com.paiban.substitution.conversion;

import static org.assertj.core.api.Assertions.assertThat;

import com.paiban.substitution.conversion.LegacyConversionPlanner.BlockerCode;
import com.paiban.substitution.conversion.LegacyConversionPlanner.Change;
import com.paiban.substitution.conversion.LegacyConversionPlanner.EntrySlice;
import com.paiban.substitution.conversion.LegacyConversionPlanner.Item;
import com.paiban.substitution.conversion.LegacyConversionPlanner.MoveSlice;
import com.paiban.substitution.conversion.LegacyConversionPlanner.Report;
import com.paiban.substitution.conversion.LegacyConversionPlanner.RowSlice;
import com.paiban.substitution.conversion.LegacyConversionPlanner.Status;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 历史转换推导器的纯单测（无 Spring）：日期还原依据、阻塞判定、
 * 转换前后差异与幂等跳过。所有日期锚定 2025-09-01 那一周，与集成测试一致。
 */
class LegacyConversionPlannerTest {

    private static final LocalDate SEMESTER_START = LocalDate.of(2025, 9, 1);
    private static final LocalDate SEMESTER_END = LocalDate.of(2026, 1, 20);
    private static final LocalDate MONDAY = LocalDate.of(2025, 9, 1);
    private static final LocalDate SECOND_MONDAY = LocalDate.of(2025, 9, 8);

    private static final Instant EARLIER = Instant.parse("2025-08-30T08:00:00Z");
    private static final Instant LATER = Instant.parse("2025-08-30T09:00:00Z");

    private record World(java.util.List<EntrySlice> entries,
                         java.util.List<RowSlice> rows,
                         Map<Long, LocalDate> requestDates,
                         java.util.List<MoveSlice> moves) {}

    private final java.util.List<EntrySlice> entries = new java.util.ArrayList<>();
    private final java.util.List<RowSlice> rows = new java.util.ArrayList<>();
    private final Map<Long, LocalDate> requestDates = new java.util.HashMap<>();
    private final java.util.List<MoveSlice> moves = new java.util.ArrayList<>();

    private EntrySlice entry(long entryId) {
        // 周一第 1 节、教师 11、教室 21 的基线课节
        EntrySlice slice = new EntrySlice(entryId, entryId * 100, 1, 1, 11L, 21L);
        entries.add(slice);
        return slice;
    }

    /** 旧格式 COVER 生效行：effective_date 起持续覆盖，lesson_date 为空。 */
    private RowSlice legacyCover(long id, long entryId, long requestId, LocalDate effectiveDate,
                                 long substituteTeacherId, Instant createdAt) {
        RowSlice row = new RowSlice(id, entryId, "COVER", effectiveDate, null, null,
                createdAt, requestId, null, null, null, null, null, substituteTeacherId);
        rows.add(row);
        return row;
    }

    /** 旧格式 SWAP 生效行：来源日持续覆盖到目标列，plan_id 指向原方案。 */
    private RowSlice legacySwap(long id, long entryId, long requestId, long planId,
                                LocalDate effectiveDate, int toDay, int toNo, Instant createdAt) {
        RowSlice row = new RowSlice(id, entryId, "SWAP", effectiveDate, null, null,
                createdAt, requestId, planId, 900L + toNo, toDay, toNo, null, 11L);
        rows.add(row);
        return row;
    }

    private void request(long requestId, LocalDate effectiveDate) {
        requestDates.put(requestId, effectiveDate);
    }

    private void move(long planId, long entryId, LocalDate from, LocalDate to) {
        moves.add(new MoveSlice(planId, entryId, from, to));
    }

    private Report plan() {
        return LegacyConversionPlanner.plan(
                SEMESTER_START, SEMESTER_END,
                List.copyOf(entries), List.copyOf(rows), Map.copyOf(requestDates), List.copyOf(moves));
    }

    private Item onlyItem(Report report) {
        assertThat(report.items()).hasSize(1);
        return report.items().get(0);
    }

    @Test
    void coverDerivesItsLessonDateFromTheOriginalRequest() {
        entry(101);
        legacyCover(1, 101, 501, MONDAY, 22L, EARLIER);
        request(501, MONDAY);

        Item item = onlyItem(plan());

        assertThat(item.status()).isEqualTo(Status.CONVERTIBLE);
        assertThat(item.lessonDate()).isEqualTo(MONDAY);
        assertThat(item.targetLessonDate()).isNull();
        assertThat(item.blockerCode()).isNull();
        assertThat(item.basis()).contains("501").contains(MONDAY.toString());
    }

    @Test
    void swapDerivesSourceAndTargetDatesFromTheOriginalPlanMove() {
        entry(101);
        legacySwap(1, 101, 501, 601, MONDAY, 2, 3, EARLIER);
        request(501, MONDAY);
        move(601, 101, MONDAY, LocalDate.of(2025, 9, 2));

        Item item = onlyItem(plan());

        assertThat(item.status()).isEqualTo(Status.CONVERTIBLE);
        assertThat(item.lessonDate()).isEqualTo(MONDAY);
        assertThat(item.targetLessonDate()).isEqualTo(LocalDate.of(2025, 9, 2));
        assertThat(item.basis()).contains("601").contains("2025-09-02");
    }

    @Test
    void coverWithoutItsSourceRequestIsBlockedAndKeptLegacy() {
        entry(101);
        legacyCover(1, 101, 501, MONDAY, 22L, EARLIER);
        // 来源申请缺失：requestDates 里没有 501

        Item item = onlyItem(plan());

        assertThat(item.status()).isEqualTo(Status.BLOCKED);
        assertThat(item.blockerCode()).isEqualTo(BlockerCode.MISSING_REQUEST);
        assertThat(item.blockerReason()).isNotBlank();
        assertThat(item.lessonDate()).isNull();
    }

    @Test
    void swapWithoutAPlanIsBlocked() {
        entry(101);
        // 旧数据异常：SWAP 行没有 plan_id，无法还原目标日期
        rows.add(new RowSlice(1, 101, "SWAP", MONDAY, null, null,
                EARLIER, 501, null, 902L, 2, 2, null, 11L));

        Item item = onlyItem(plan());

        assertThat(item.status()).isEqualTo(Status.BLOCKED);
        assertThat(item.blockerCode()).isEqualTo(BlockerCode.MISSING_MOVE);
    }

    @Test
    void swapWhosePlanLostTheMoveIsBlocked() {
        entry(101);
        legacySwap(1, 101, 501, 601, MONDAY, 2, 3, EARLIER);
        request(501, MONDAY);
        // 方案 601 存在，但没有 101 这条移动

        Item item = onlyItem(plan());

        assertThat(item.status()).isEqualTo(Status.BLOCKED);
        assertThat(item.blockerCode()).isEqualTo(BlockerCode.MISSING_MOVE);
    }

    @Test
    void swapWithTwoMovesForTheSameEntryIsAmbiguous() {
        entry(101);
        legacySwap(1, 101, 501, 601, MONDAY, 2, 3, EARLIER);
        request(501, MONDAY);
        move(601, 101, MONDAY, LocalDate.of(2025, 9, 2));
        move(601, 101, MONDAY, LocalDate.of(2025, 9, 3));

        Item item = onlyItem(plan());

        assertThat(item.status()).isEqualTo(Status.BLOCKED);
        assertThat(item.blockerCode()).isEqualTo(BlockerCode.AMBIGUOUS_MOVE);
    }

    @Test
    void coverWhoseRequestDateContradictsTheRowIsAVersionConflict() {
        entry(101);
        legacyCover(1, 101, 501, MONDAY, 22L, EARLIER);
        request(501, SECOND_MONDAY);

        Item item = onlyItem(plan());

        assertThat(item.status()).isEqualTo(Status.BLOCKED);
        assertThat(item.blockerCode()).isEqualTo(BlockerCode.VERSION_CONFLICT);
    }

    @Test
    void swapWhoseMoveContradictsTheRowDateIsAVersionConflict() {
        entry(101);
        legacySwap(1, 101, 501, 601, MONDAY, 2, 3, EARLIER);
        request(501, MONDAY);
        // 移动的来源日与生效行日期不一致：无法唯一解释
        move(601, 101, SECOND_MONDAY, LocalDate.of(2025, 9, 9));

        Item item = onlyItem(plan());

        assertThat(item.status()).isEqualTo(Status.BLOCKED);
        assertThat(item.blockerCode()).isEqualTo(BlockerCode.VERSION_CONFLICT);
    }

    @Test
    void unknownTypeCannotBeExplainedUniquely() {
        entry(101);
        rows.add(new RowSlice(1, 101, "MYSTERY", MONDAY, null, null,
                EARLIER, 501, null, null, null, null, null, 22L));
        request(501, MONDAY);

        Item item = onlyItem(plan());

        assertThat(item.status()).isEqualTo(Status.BLOCKED);
        assertThat(item.blockerCode()).isEqualTo(BlockerCode.UNKNOWN_TYPE);
    }

    @Test
    void alreadyConvertedRowsAreReportedAsSkippedNotReconverted() {
        entry(101);
        // 已转换行：lesson_date 已写入
        rows.add(new RowSlice(1, 101, "COVER", MONDAY, MONDAY, null,
                EARLIER, 501, null, null, null, null, null, 22L));

        Item item = onlyItem(plan());

        assertThat(item.status()).isEqualTo(Status.ALREADY_CONVERTED);
        assertThat(item.lessonDate()).isEqualTo(MONDAY);
    }

    @Test
    void rerunningThePrecheckAfterConversionReportsNothingLeftToConvert() {
        entry(101);
        // 确认转换后的世界：两行都带上了实例日期
        rows.add(new RowSlice(1, 101, "COVER", MONDAY, MONDAY, null,
                EARLIER, 501, null, null, null, null, null, 22L));
        rows.add(new RowSlice(2, 101, "SWAP", SECOND_MONDAY, SECOND_MONDAY, LocalDate.of(2025, 9, 9),
                LATER, 502, 601L, 903L, 2, 3, null, 11L));
        request(501, MONDAY);
        request(502, SECOND_MONDAY);
        move(601, 101, SECOND_MONDAY, LocalDate.of(2025, 9, 9));

        Report report = plan();

        assertThat(report.items()).allSatisfy(item -> assertThat(item.status())
                .isEqualTo(Status.ALREADY_CONVERTED));
        assertThat(report.summary().convertible()).isZero();
        assertThat(report.summary().blocked()).isZero();
        assertThat(report.summary().alreadyConverted()).isEqualTo(2);
        assertThat(report.summary().totalRows()).isEqualTo(2);
    }

    @Test
    void coverChangeListShowsLaterWeeksReturningToTheBaseline() {
        entry(101);
        legacyCover(1, 101, 501, MONDAY, 22L, EARLIER);
        request(501, MONDAY);

        Report report = plan();

        assertThat(report.summary().convertible()).isEqualTo(1);
        // 旧语义：9-1 起每周一都被 22 号教师覆盖；转换后只有 9-1 当天被覆盖。
        List<Change> changes = report.changes();
        assertThat(changes).isNotEmpty();
        // 第一个差异周是 9-8（同一基线槽位的下一个实例）
        Change first = changes.get(0);
        assertThat(first.date()).isEqualTo(SECOND_MONDAY);
        assertThat(first.entryId()).isEqualTo(101);
        assertThat(first.causedBy()).isEqualTo(1L);
        assertThat(first.before().teacherId()).isEqualTo(22L);
        // 恢复常规安排：after 是基线课本身（原教师、无覆盖行），不是「无课」
        assertThat(first.after()).isNotNull();
        assertThat(first.after().substitutionId()).isNull();
        assertThat(first.after().teacherId()).isEqualTo(11L);
        // 9-1 当天两边一致（转换前后都覆盖），不出现在差异里
        assertThat(changes).extracting(Change::date).doesNotContain(MONDAY);
        // 学期结束后的日期不参与
        assertThat(changes).allSatisfy(change ->
                assertThat(change.date()).isAfter(SECOND_MONDAY.minusDays(1)));
        // 之后每个周一各一条
        assertThat(changes).hasSize((int) MONDAY.datesUntil(SEMESTER_END.plusDays(1))
                .filter(date -> date.getDayOfWeek().getValue() == 1 && date.isAfter(MONDAY))
                .count());
        assertThat(report.summary().changes()).isEqualTo(changes.size());
    }

    @Test
    void swapChangeListKeepsTheTargetDateAndRestoresLaterWeeks() {
        EntrySlice slice = entry(101);
        legacySwap(1, 101, 501, 601, MONDAY, 2, 3, EARLIER);
        request(501, MONDAY);
        move(601, 101, MONDAY, LocalDate.of(2025, 9, 2));

        Report report = plan();

        // 旧语义：9-1 起这节课持续占据周二第 3 节，基线周一反而无课；
        // 转换后只有 9-2 当天在目标列，其余各周恢复基线。
        List<Change> changes = report.changes();
        // 目标日 9-2 本身转换前后一致，不出现差异
        assertThat(changes).extracting(Change::date).doesNotContain(LocalDate.of(2025, 9, 2));
        // 第一周的周一 9-8：旧语义没有课（被挪去周二列），新语义恢复基线周一第 1 节
        Change firstMonday = changes.stream()
                .filter(change -> change.date().equals(SECOND_MONDAY))
                .findFirst().orElseThrow();
        assertThat(firstMonday.before()).isNull();
        assertThat(firstMonday.after()).isNotNull();
        assertThat(firstMonday.after().substitutionId()).isNull();
        assertThat(firstMonday.after().teacherId()).isEqualTo(11L);
        assertThat(firstMonday.after().periodNo()).isEqualTo(1);
        // 后续每个周二：旧语义在目标列第 3 节，新语义无课（这节课基线在周一）
        List<Change> laterTuesdays = changes.stream()
                .filter(change -> change.date().getDayOfWeek().getValue() == 2)
                .toList();
        assertThat(laterTuesdays).isNotEmpty();
        assertThat(laterTuesdays).allSatisfy(change -> {
            assertThat(change.before()).isNotNull();
            assertThat(change.before().periodNo()).isEqualTo(3);
            assertThat(change.after()).isNull();
        });
        // 后续每个周一：旧语义无课，新语义恢复基线一节
        List<Change> laterMondays = changes.stream()
                .filter(change -> change.date().getDayOfWeek().getValue() == 1)
                .toList();
        assertThat(laterMondays).allSatisfy(change -> {
            assertThat(change.before()).isNull();
            assertThat(change.after()).isNotNull();
            assertThat(change.after().teacherId()).isEqualTo(slice.teacherId());
        });
    }

    @Test
    void blockedRowsDoNotProduceChangesBecauseTheyKeepOverriding() {
        entry(101);
        legacyCover(1, 101, 501, MONDAY, 22L, EARLIER);
        request(501, SECOND_MONDAY); // 版本冲突 → 阻塞

        Report report = plan();

        assertThat(onlyItem(report).status()).isEqualTo(Status.BLOCKED);
        // 阻塞行保持旧语义：转换前后没有差异
        assertThat(report.changes()).isEmpty();
        assertThat(report.summary().changes()).isZero();
    }

    @Test
    void changesAreAttributedToTheConvertibleRowEvenWhenChained() {
        entry(101);
        // 同一条目两笔可转换历史：9-1 A→B，9-8 B→C。
        // 转换后 9-1 由第一笔覆盖、9-8 由第二笔覆盖，9-15 起恢复基线；差异归因第二笔。
        legacyCover(1, 101, 501, MONDAY, 22L, EARLIER);
        legacyCover(2, 101, 502, SECOND_MONDAY, 33L, LATER);
        request(501, MONDAY);
        request(502, SECOND_MONDAY);

        Report report = plan();

        assertThat(report.items()).extracting(Item::status)
                .containsExactly(Status.CONVERTIBLE, Status.CONVERTIBLE);
        List<Change> changes = report.changes();
        assertThat(changes).allSatisfy(change -> assertThat(change.causedBy()).isEqualTo(2L));
        // 9-15 起每周一恢复基线（教师 11）
        Change thirdMonday = changes.stream()
                .filter(change -> change.date().equals(LocalDate.of(2025, 9, 15)))
                .findFirst().orElseThrow();
        assertThat(thirdMonday.before().teacherId()).isEqualTo(33L);
        assertThat(thirdMonday.after()).isNotNull();
        assertThat(thirdMonday.after().substitutionId()).isNull();
        assertThat(thirdMonday.after().teacherId()).isEqualTo(11L);
        // 9-1、9-8 当天各自仍被对应笔覆盖，两模型一致
        assertThat(changes).extracting(Change::date)
                .doesNotContain(MONDAY)
                .doesNotContain(SECOND_MONDAY);
    }

    @Test
    void aBlockedEarlierRowKeepsOverridingAfterLaterRowsAreConverted() {
        entry(101);
        // 第一笔阻塞（依据矛盾），第二笔可转换：转换后 9-15 起由阻塞的第一笔
        // 继续按旧语义覆盖——差异如实呈现「换回第一笔」，而不是谎报恢复基线。
        legacyCover(1, 101, 501, MONDAY, 22L, EARLIER);
        legacyCover(2, 101, 502, SECOND_MONDAY, 33L, LATER);
        request(501, SECOND_MONDAY); // 与第一行的日期矛盾 → 阻塞
        request(502, SECOND_MONDAY);

        Report report = plan();

        assertThat(report.items()).extracting(Item::status)
                .containsExactly(Status.BLOCKED, Status.CONVERTIBLE);
        Change thirdMonday = report.changes().stream()
                .filter(change -> change.date().equals(LocalDate.of(2025, 9, 15)))
                .findFirst().orElseThrow();
        assertThat(thirdMonday.causedBy()).isEqualTo(2L);
        assertThat(thirdMonday.before().teacherId()).isEqualTo(33L);
        assertThat(thirdMonday.after()).isNotNull();
        assertThat(thirdMonday.after().substitutionId()).isEqualTo(1L);
        assertThat(thirdMonday.after().teacherId()).isEqualTo(22L);
    }

    @Test
    void rowsStartingBeforeTheSemesterDoNotTouchAnyDate() {
        entry(101);
        // 与读取路径一致：effective_date 早于学期开始的旧行不参与任何日期
        legacyCover(1, 101, 501, SEMESTER_START.minusDays(1), 22L, EARLIER);
        request(501, SEMESTER_START.minusDays(1));

        Report report = plan();

        assertThat(onlyItem(report).status()).isEqualTo(Status.CONVERTIBLE);
        assertThat(report.changes()).isEmpty();
    }

    @Test
    void summaryCountsAlwaysMatchTheItemList() {
        entry(101);
        entry(102);
        legacyCover(1, 101, 501, MONDAY, 22L, EARLIER);       // 可转换
        legacyCover(2, 102, 404, MONDAY, 22L, EARLIER);       // 阻塞：缺申请
        rows.add(new RowSlice(3, 101, "COVER", SECOND_MONDAY, SECOND_MONDAY, null,
                LATER, 502, null, null, null, null, null, 22L)); // 已转换
        request(501, MONDAY);

        Report report = plan();

        assertThat(report.summary().totalRows()).isEqualTo(3);
        assertThat(report.summary().convertible()).isEqualTo(1);
        assertThat(report.summary().blocked()).isEqualTo(1);
        assertThat(report.summary().alreadyConverted()).isEqualTo(1);
        assertThat(report.summary().totalRows())
                .isEqualTo(report.summary().convertible()
                        + report.summary().blocked() + report.summary().alreadyConverted());
    }

    @Test
    void arrangementsReflectTheLegacyOverridingWinner() {
        entry(101);
        legacyCover(1, 101, 501, MONDAY, 22L, EARLIER);
        request(501, MONDAY);

        Report report = plan();

        // before 侧是「持续覆盖」的教师；after 侧恢复基线（原教师 11、原教室、无覆盖行）。
        Change first = report.changes().get(0);
        assertThat(first.before().substitutionId()).isEqualTo(1L);
        assertThat(first.before().roomId()).isEqualTo(21L);
        assertThat(first.after()).isNotNull();
        assertThat(first.after().substitutionId()).isNull();
        assertThat(first.after().teacherId()).isEqualTo(11L);
        assertThat(first.after().roomId()).isEqualTo(21L);
    }
}
