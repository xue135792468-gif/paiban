package com.paiban.scheduling.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.paiban.scheduling.service.RoomFillPlanner.ClassInfo;
import com.paiban.scheduling.service.RoomFillPlanner.ClassPlan;
import com.paiban.scheduling.service.RoomFillPlanner.ExistingRoom;
import com.paiban.scheduling.service.RoomFillPlanner.LessonGap;
import com.paiban.scheduling.service.RoomFillPlanner.ManualItem;
import com.paiban.scheduling.service.RoomFillPlanner.Plan;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The room decision of ticket 07, without IO: naming, capacity, reuse and refusals. */
class RoomFillPlannerTest {

    private static final String SEMESTER = "2025-2026学年第一学期";

    @Test
    void createsOneRoomPerClassNamedAfterTheSemesterAndTheClass() {
        Plan plan = RoomFillPlanner.plan(
                SEMESTER,
                List.of(new ClassInfo(1L, "八（1）班", "八年级", 45), new ClassInfo(2L, "八（2）班", "八年级", 38)),
                List.of(gap(11L, 1L, 45), gap(12L, 2L, 38)),
                List.of(),
                Map.of());

        assertThat(plan.safe()).isTrue();
        assertThat(plan.classes()).extracting(ClassPlan::className)
                .containsExactly("八（1）班", "八（2）班");
        assertThat(plan.classes()).allSatisfy(classPlan -> {
            assertThat(classPlan.action()).isEqualTo(RoomFillPlanner.ACTION_CREATE);
            assertThat(classPlan.roomId()).isNull();
            assertThat(classPlan.roomName())
                    .isEqualTo(SEMESTER + "-八年级" + classPlan.className() + "-固定教室");
            assertThat(classPlan.roomCapacity()).isEqualTo(classPlan.studentCount());
        });
    }

    @Test
    void capacityAlsoCoversALessonSnapshotLargerThanTheRoster() {
        Plan plan = RoomFillPlanner.plan(
                SEMESTER,
                List.of(new ClassInfo(1L, "八（1）班", "八年级", 45)),
                List.of(gap(11L, 1L, 45), gap(12L, 1L, 48)),
                List.of(),
                Map.of());

        assertThat(plan.classes()).singleElement().satisfies(classPlan -> {
            assertThat(classPlan.studentCount()).isEqualTo(45);
            assertThat(classPlan.requiredCapacity()).isEqualTo(48);
            assertThat(classPlan.roomCapacity()).isEqualTo(48);
            assertThat(classPlan.lessonCount()).isEqualTo(2);
        });
    }

    @Test
    void reusesTheRoomThisActionCreatedBefore() {
        Plan plan = RoomFillPlanner.plan(
                SEMESTER,
                List.of(new ClassInfo(1L, "八（1）班", "八年级", 45)),
                List.of(gap(11L, 1L, 45)),
                List.of(new ExistingRoom(7L, SEMESTER + "-八年级八（1）班-固定教室", "NORMAL", 45)),
                Map.of());

        assertThat(plan.classes()).singleElement().satisfies(classPlan -> {
            assertThat(classPlan.action()).isEqualTo(RoomFillPlanner.ACTION_REUSE);
            assertThat(classPlan.roomId()).isEqualTo(7L);
            assertThat(classPlan.roomCapacity()).isEqualTo(45);
        });
    }

    @Test
    void aSameNamedRoomTooSmallIsRefusedInsteadOfExpanded() {
        Plan plan = RoomFillPlanner.plan(
                SEMESTER,
                List.of(new ClassInfo(1L, "八（1）班", "八年级", 45)),
                List.of(gap(11L, 1L, 45)),
                List.of(new ExistingRoom(7L, SEMESTER + "-八年级八（1）班-固定教室", "NORMAL", 30)),
                Map.of());

        assertThat(plan.classes()).isEmpty();
        assertThat(plan.manualItems()).extracting(ManualItem::code)
                .containsExactly(RoomFillPlanner.ROOM_NAME_CONFLICT);
        assertThat(plan.manualItems().get(0).message()).contains("容量");
    }

    @Test
    void aSameNamedRoomOfAnotherTypeIsNotAdopted() {
        Plan plan = RoomFillPlanner.plan(
                SEMESTER,
                List.of(new ClassInfo(1L, "八（1）班", "八年级", 45)),
                List.of(gap(11L, 1L, 45)),
                List.of(new ExistingRoom(7L, SEMESTER + "-八年级八（1）班-固定教室", "LAB", 60)),
                Map.of());

        assertThat(plan.classes()).isEmpty();
        assertThat(plan.manualItems()).extracting(ManualItem::code)
                .containsExactly(RoomFillPlanner.ROOM_NAME_CONFLICT);
    }

    @Test
    void aClassWithoutAUsableSizeIsNeverPlannedAtZeroSeats() {
        Plan plan = RoomFillPlanner.plan(
                SEMESTER,
                List.of(new ClassInfo(1L, "八（1）班", "八年级", 0)),
                List.of(gap(11L, 1L, 0)),
                List.of(),
                Map.of());

        assertThat(plan.classes()).isEmpty();
        assertThat(plan.manualItems()).extracting(ManualItem::code)
                .containsExactly(RoomFillPlanner.CLASS_SIZE_UNKNOWN);
    }

    @Test
    void aCourseNeedingASpecialRoomIsManual() {
        Plan plan = RoomFillPlanner.plan(
                SEMESTER,
                List.of(new ClassInfo(1L, "八（1）班", "八年级", 45)),
                List.of(new LessonGap(11L, 1L, 45, "物理实验", true, false)),
                List.of(),
                Map.of());

        assertThat(plan.classes()).isEmpty();
        assertThat(plan.manualItems()).extracting(ManualItem::code)
                .containsExactly(RoomFillPlanner.SPECIAL_ROOM_REQUIRED);
        assertThat(plan.manualItems().get(0).message()).contains("物理实验");
    }

    @Test
    void aLessonPointingAtAMissingCourseIsManual() {
        Plan plan = RoomFillPlanner.plan(
                SEMESTER,
                List.of(new ClassInfo(1L, "八（1）班", "八年级", 45)),
                List.of(new LessonGap(11L, 1L, 45, null, false, true)),
                List.of(),
                Map.of());

        assertThat(plan.classes()).isEmpty();
        assertThat(plan.manualItems()).extracting(ManualItem::code)
                .containsExactly(RoomFillPlanner.COURSE_NOT_FOUND);
    }

    @Test
    void aLessonFromAnotherSemesterClassIsManual() {
        Plan plan = RoomFillPlanner.plan(
                SEMESTER, List.of(), List.of(gap(11L, 99L, 45)), List.of(), Map.of());

        assertThat(plan.classes()).isEmpty();
        assertThat(plan.manualItems()).extracting(ManualItem::code)
                .containsExactly(RoomFillPlanner.CLASS_NOT_FOUND);
    }

    @Test
    void aDerivedNameLongerThanTheRoomColumnIsManual() {
        String longSemester = "学期".repeat(50);
        Plan plan = RoomFillPlanner.plan(
                longSemester,
                List.of(new ClassInfo(1L, "八（1）班", "八年级", 45)),
                List.of(gap(11L, 1L, 45)),
                List.of(),
                Map.of());

        assertThat(plan.classes()).isEmpty();
        assertThat(plan.manualItems()).extracting(ManualItem::code)
                .containsExactly(RoomFillPlanner.ROOM_NAME_TOO_LONG);
    }

    @Test
    void classesComeOutInNameOrderRegardlessOfLessonOrder() {
        Plan plan = RoomFillPlanner.plan(
                SEMESTER,
                List.of(new ClassInfo(2L, "八（2）班", "八年级", 40), new ClassInfo(1L, "八（1）班", "八年级", 45)),
                List.of(gap(21L, 2L, 40), gap(11L, 1L, 45)),
                List.of(),
                Map.of());

        assertThat(plan.classes()).extracting(ClassPlan::className)
                .containsExactly("八（1）班", "八（2）班");
    }

    @Test
    void twoGradesWithAClassOfTheSameNameGetTwoDifferentRooms() {
        Plan plan = RoomFillPlanner.plan(
                SEMESTER,
                List.of(
                        new ClassInfo(1L, "（1）班", "八年级", 45),
                        new ClassInfo(2L, "（1）班", "九年级", 38)),
                List.of(gap(11L, 1L, 45), gap(12L, 2L, 38)),
                List.of(),
                Map.of());

        assertThat(plan.safe()).isTrue();
        assertThat(plan.classes()).extracting(ClassPlan::roomName)
                .containsExactly(
                        SEMESTER + "-八年级（1）班-固定教室", SEMESTER + "-九年级（1）班-固定教室");
        assertThat(plan.classes()).allSatisfy(classPlan ->
                assertThat(classPlan.action()).isEqualTo(RoomFillPlanner.ACTION_CREATE));
    }

    @Test
    void aSameNamedRoomUsedByAnotherClassThisSemesterIsNotReused() {
        Plan plan = RoomFillPlanner.plan(
                SEMESTER,
                List.of(new ClassInfo(1L, "八（1）班", "八年级", 45)),
                List.of(gap(11L, 1L, 45)),
                List.of(new ExistingRoom(7L, SEMESTER + "-八年级八（1）班-固定教室", "NORMAL", 45)),
                Map.of(7L, Set.of(99L)));

        assertThat(plan.classes()).isEmpty();
        assertThat(plan.manualItems()).extracting(ManualItem::code)
                .containsExactly(RoomFillPlanner.ROOM_IN_USE);
        assertThat(plan.manualItems().get(0).message()).contains("已被其他行政班使用");
    }

    @Test
    void theRoomsOwnClassMayKeepUsingIt() {
        Plan plan = RoomFillPlanner.plan(
                SEMESTER,
                List.of(new ClassInfo(1L, "八（1）班", "八年级", 45)),
                List.of(gap(11L, 1L, 45)),
                List.of(new ExistingRoom(7L, SEMESTER + "-八年级八（1）班-固定教室", "NORMAL", 45)),
                Map.of(7L, Set.of(1L)));

        assertThat(plan.classes()).singleElement().satisfies(classPlan -> {
            assertThat(classPlan.action()).isEqualTo(RoomFillPlanner.ACTION_REUSE);
            assertThat(classPlan.roomId()).isEqualTo(7L);
        });
    }

    @Test
    void whenRoomUseCannotBeVerifiedTheAssociationIsManual() {
        Plan plan = RoomFillPlanner.plan(
                SEMESTER,
                List.of(new ClassInfo(1L, "八（1）班", "八年级", 45)),
                List.of(gap(11L, 1L, 45)),
                List.of(new ExistingRoom(7L, SEMESTER + "-八年级八（1）班-固定教室", "NORMAL", 45)),
                null);

        assertThat(plan.classes()).isEmpty();
        assertThat(plan.manualItems()).extracting(ManualItem::code)
                .containsExactly(RoomFillPlanner.ROOM_OWNER_UNKNOWN);
    }

    private static LessonGap gap(long lessonId, long classId, int studentCount) {
        return new LessonGap(lessonId, classId, studentCount, "语文", false, false);
    }
}
