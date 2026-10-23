package com.paiban.integration.org;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.paiban.common.web.ApiException;
import com.paiban.foundation.domain.Teacher;
import com.paiban.foundation.domain.TeacherRepository;
import com.paiban.integration.domain.DingDepartment;
import com.paiban.integration.domain.DingUser;
import com.paiban.integration.org.DingTalkSyncDecisions.Action;
import com.paiban.integration.org.DingTalkSyncPlan.Category;
import com.paiban.integration.org.DingTalkSyncPlan.Item;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Classification is pure logic: a mocked repository, no Spring, no network. */
class DingTalkSyncPlannerTest {

    private final TeacherRepository teachers = mock(TeacherRepository.class);
    private final DingTalkSyncPlanner planner =
            new DingTalkSyncPlanner(new DingTalkTeacherMatcher(teachers), teachers, new DingTalkMergeRules());

    @BeforeEach
    void noNameMatchesByDefault() {
        when(teachers.findByName(anyString())).thenReturn(List.of());
    }

    @Test
    void classifiesAnUnknownUserAsCreate() {
        DingTalkSyncPlan plan = planner.plan(directory(user("dd-1", "王老师", "13800000001", "EMP1")), none());

        assertThat(plan.items()).singleElement()
                .satisfies(item -> assertThat(item.category()).isEqualTo(Category.CREATE));
    }

    @Test
    void matchesByDingtalkUserIdBeforeEmployeeNumberAndPhone() {
        Teacher byId = teacher(1, "钉钉命中");
        Teacher byEmployeeNo = teacher(2, "工号命中");
        Teacher byPhone = teacher(3, "手机命中");
        when(teachers.findByDingtalkUserId("dd-1")).thenReturn(Optional.of(byId));
        when(teachers.findByEmployeeNo("EMP1")).thenReturn(Optional.of(byEmployeeNo));
        when(teachers.findByPhone("13800000001")).thenReturn(Optional.of(byPhone));

        Item item = planner.plan(directory(user("dd-1", "王老师", "13800000001", "EMP1")), none()).items().get(0);

        assertThat(item.matchedTeacherId()).isEqualTo(1L);
    }

    @Test
    void fallsBackToEmployeeNumberThenPhone() {
        Teacher byEmployeeNo = teacher(2, "工号命中");
        when(teachers.findByEmployeeNo("EMP1")).thenReturn(Optional.of(byEmployeeNo));

        Item byNo = planner.plan(directory(user("dd-1", "王老师", "13800000001", "EMP1")), none()).items().get(0);
        assertThat(byNo.matchedTeacherId()).isEqualTo(2L);

        when(teachers.findByEmployeeNo("EMP1")).thenReturn(Optional.empty());
        Teacher byPhone = teacher(3, "手机命中");
        when(teachers.findByPhone("13800000001")).thenReturn(Optional.of(byPhone));
        Item byMobile = planner.plan(directory(user("dd-1", "王老师", "13800000001", "EMP1")), none()).items().get(0);
        assertThat(byMobile.matchedTeacherId()).isEqualTo(3L);
    }

    @Test
    void flagsConflictsWithoutOverwritingWhenThereIsNothingToFill() {
        Teacher existing = teacher(1, "王老师");
        existing.setEmployeeNo("EMP1");
        existing.setPhone("13900000000");
        existing.setDingtalkUserId("dd-1");
        when(teachers.findByDingtalkUserId("dd-1")).thenReturn(Optional.of(existing));

        Item item = planner.plan(directory(user("dd-1", "王老师", "13800000001", "EMP1")), none()).items().get(0);

        assertThat(item.category()).isEqualTo(Category.SKIP);
        assertThat(item.reason()).contains("未覆盖");
        assertThat(item.fills()).isEmpty();
        assertThat(item.conflicts()).extracting(DingTalkSyncPlan.FieldChange::field)
                .containsExactly(DingTalkMergeRules.PHONE);
    }

    @Test
    void sameNameWithoutAMatchingKeyIsSuspectedNotMerged() {
        Teacher existing = teacher(7, "王老师");
        when(teachers.findByName("王老师")).thenReturn(List.of(existing));

        Item item = planner.plan(directory(user("dd-1", "王老师", "13800000001", "EMP1")), none()).items().get(0);

        assertThat(item.category()).isEqualTo(Category.SUSPECTED);
        assertThat(item.matchedTeacherId()).isNull();
        assertThat(item.candidates()).singleElement().satisfies(candidate -> {
            assertThat(candidate.id()).isEqualTo(7L);
            assertThat(candidate.name()).isEqualTo("王老师");
        });
    }

    @Test
    void aKeyMatchedTeacherIsNotProposedTwice() {
        Teacher existing = teacher(7, "王老师");
        existing.setEmployeeNo("EMP1");
        existing.setPhone("13800000001");
        existing.setDingtalkUserId("dd-1");
        when(teachers.findByDingtalkUserId("dd-1")).thenReturn(Optional.of(existing));
        when(teachers.findByName("王老师")).thenReturn(List.of(existing));

        DingTalkSyncPlan plan = planner.plan(
                directory(
                        user("dd-1", "王老师", "13800000001", "EMP1"),
                        user("dd-2", "王老师", "13800000002", "EMP2")),
                none());

        assertThat(plan.items()).extracting(Item::category)
                .containsExactly(Category.SKIP, Category.CREATE);
        assertThat(plan.items().get(1).candidates()).isEmpty();
    }

    @Test
    void marksARemoteInactiveUserForDeactivation() {
        Teacher existing = teacher(1, "王老师");
        existing.setEmployeeNo("EMP1");
        existing.setPhone("13800000001");
        existing.setDingtalkUserId("dd-1");
        when(teachers.findByDingtalkUserId("dd-1")).thenReturn(Optional.of(existing));

        DingUser inactive = new DingUser("dd-1", "王老师", "13800000001", null, "EMP1", false, List.of());
        Item item = planner.plan(directory(inactive), none()).items().get(0);

        assertThat(item.category()).isEqualTo(Category.MERGE);
        assertThat(item.fills()).extracting(DingTalkSyncPlan.FieldChange::field)
                .containsExactly(DingTalkMergeRules.ACTIVE);
    }

    @Test
    void honoursDepartmentAndUserExclusions() {
        DingUser inCanteen = new DingUser("dd-1", "厨师甲", null, null, null, true, List.of("食堂"));
        DingUser single = new DingUser("dd-2", "保安甲", null, null, null, true, List.of("保安"));

        DingTalkSyncPlan plan = planner.plan(
                directory(inCanteen, single),
                new DingTalkSyncDecisions(Set.of("食堂"), Set.of("dd-2"), Map.of()));

        assertThat(plan.items()).extracting(Item::category).containsExactly(Category.SKIP, Category.SKIP);
        assertThat(plan.items()).extracting(Item::reason).containsExactly("已排除", "已排除");
    }

    @Test
    void mergesASuspectedUserOnlyWhenTheAdminDecidesSo() {
        Teacher existing = teacher(7, "王老师");
        when(teachers.findByName("王老师")).thenReturn(List.of(existing));
        DingTalkSyncDecisions decisions = new DingTalkSyncDecisions(
                Set.of(), Set.of(), Map.of("dd-1", new DingTalkSyncDecisions.SuspectedDecision(Action.MERGE, 7L)));

        Item item = planner.plan(directory(user("dd-1", "王老师", "13800000001", "EMP1")), decisions).items().get(0);

        assertThat(item.category()).isEqualTo(Category.MERGE);
        assertThat(item.matchedTeacherId()).isEqualTo(7L);
        assertThat(item.fills()).extracting(DingTalkSyncPlan.FieldChange::field)
                .contains(DingTalkMergeRules.EMPLOYEE_NO, DingTalkMergeRules.PHONE);
    }

    @Test
    void createsASuspectedUserAsNewWhenTheAdminSaysTheyAreDifferentPeople() {
        Teacher existing = teacher(7, "王老师");
        when(teachers.findByName("王老师")).thenReturn(List.of(existing));
        DingTalkSyncDecisions decisions = new DingTalkSyncDecisions(
                Set.of(), Set.of(), Map.of("dd-1", new DingTalkSyncDecisions.SuspectedDecision(Action.CREATE, null)));

        Item item = planner.plan(directory(user("dd-1", "王老师", "13800000001", "EMP1")), decisions).items().get(0);

        assertThat(item.category()).isEqualTo(Category.CREATE);
    }

    @Test
    void skipsASuspectedUserWhenTheAdminSkips() {
        Teacher existing = teacher(7, "王老师");
        when(teachers.findByName("王老师")).thenReturn(List.of(existing));
        DingTalkSyncDecisions decisions = new DingTalkSyncDecisions(
                Set.of(), Set.of(), Map.of("dd-1", new DingTalkSyncDecisions.SuspectedDecision(Action.SKIP, null)));

        Item item = planner.plan(directory(user("dd-1", "王老师", "13800000001", "EMP1")), decisions).items().get(0);

        assertThat(item.category()).isEqualTo(Category.SKIP);
        assertThat(item.reason()).contains("疑似同名");
    }

    @Test
    void rejectsAMergeTargetThatIsNotAOneOfTheCandidates() {
        Teacher existing = teacher(7, "王老师");
        when(teachers.findByName("王老师")).thenReturn(List.of(existing));
        DingTalkSyncDecisions decisions = new DingTalkSyncDecisions(
                Set.of(), Set.of(), Map.of("dd-1", new DingTalkSyncDecisions.SuspectedDecision(Action.MERGE, 99L)));

        assertThatThrownBy(() -> planner.plan(directory(user("dd-1", "王老师", "13800000001", "EMP1")), decisions))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void skipsAUserWithoutAName() {
        DingUser nameless = new DingUser("dd-1", " ", null, null, null);

        Item item = planner.plan(directory(nameless), none()).items().get(0);

        assertThat(item.category()).isEqualTo(Category.SKIP);
        assertThat(item.reason()).isEqualTo("无有效字段");
    }

    private DingTalkDirectory directory(DingUser... users) {
        return new DingTalkDirectory(
                List.of(users),
                List.of(new DingDepartment(1, "根部门", null)),
                List.of(),
                0);
    }

    private DingUser user(String id, String name, String mobile, String jobNumber) {
        return new DingUser(id, name, mobile, null, jobNumber, true, List.of("全校教职工"));
    }

    private Teacher teacher(long id, String name) {
        Teacher teacher = new Teacher();
        teacher.setId(id);
        teacher.setName(name);
        teacher.setActive(true);
        return teacher;
    }

    private DingTalkSyncDecisions none() {
        return DingTalkSyncDecisions.none();
    }
}
