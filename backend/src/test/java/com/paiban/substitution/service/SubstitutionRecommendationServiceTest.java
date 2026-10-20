package com.paiban.substitution.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paiban.ai.AiGateway;
import com.paiban.ai.AiRequest;
import com.paiban.ai.AiResult;
import com.paiban.ai.AiTaskType;
import com.paiban.common.web.ApiException;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Pure rule/AI-output unit coverage: repositories and the gateway are mocks, so the
 * test runs without Spring or a database.
 */
class SubstitutionRecommendationServiceTest {

    private static final long SEMESTER_ID = 10L;
    private static final long COURSE_ID = 30L;
    private static final LocalDate DATE = LocalDate.of(2025, 9, 3);

    private SubstitutionRequestRepository requests;
    private SubstitutionRepository substitutions;
    private TeacherRepository teachers;
    private CourseRepository courses;
    private AdminClassRepository adminClasses;
    private TimetableEntryRepository entries;
    private FakeGateway gateway;
    private SubstitutionRecommendationService service;

    @BeforeEach
    void setUp() {
        requests = mock(SubstitutionRequestRepository.class);
        substitutions = mock(SubstitutionRepository.class);
        teachers = mock(TeacherRepository.class);
        courses = mock(CourseRepository.class);
        adminClasses = mock(AdminClassRepository.class);
        entries = mock(TimetableEntryRepository.class);
        gateway = new FakeGateway();
        service = new SubstitutionRecommendationService(
                requests, substitutions, teachers, courses, adminClasses, entries, gateway, new ObjectMapper());

        when(requests.findById(1L)).thenReturn(Optional.of(request()));
        when(courses.findById(COURSE_ID)).thenReturn(Optional.of(course("语文", "语文")));
        when(substitutions.findBySemesterIdAndEffectiveDateAndStatus(
                SEMESTER_ID, DATE, Substitution.EFFECTIVE)).thenReturn(List.of());
        when(substitutions.countBySemesterIdAndSubstituteTeacherIdAndStatus(
                eq(SEMESTER_ID), anyLong(), eq(Substitution.EFFECTIVE))).thenReturn(0L);
    }

    @Test
    void ranksPoolBySameSubjectAvailabilityAndWorkload() {
        Teacher originalTeacher = teacher(1L, "张老师", "语文", true);
        Teacher sameSubjectFree = teacher(2L, "李老师", "语文", true);
        Teacher sameSubjectBusy = teacher(3L, "王老师", "语文", true);
        Teacher otherSubjectFree = teacher(4L, "陈老师", "数学", true);
        Teacher otherSubjectBusy = teacher(5L, "赵老师", "英语", true);
        Teacher sameSubjectHeavy = teacher(6L, "周老师", "语文", true);
        Teacher inactive = teacher(7L, "退休老师", "语文", false);
        when(teachers.findAllByOrderByNameAsc()).thenReturn(List.of(
                originalTeacher, sameSubjectFree, sameSubjectBusy, otherSubjectFree,
                otherSubjectBusy, sameSubjectHeavy, inactive));
        when(entries.findBySemesterId(SEMESTER_ID)).thenReturn(List.of(
                entry(3L, 3, 3), entry(5L, 3, 3)));
        when(substitutions.countBySemesterIdAndSubstituteTeacherIdAndStatus(
                SEMESTER_ID, 6L, Substitution.EFFECTIVE)).thenReturn(5L);

        List<SubstitutionRecommendationService.Candidate> pool = service.candidatePool(request());

        assertThat(pool).extracting(SubstitutionRecommendationService.Candidate::teacherId)
                .containsExactly(2L, 6L, 3L, 4L, 5L);
        assertThat(pool).noneMatch(candidate -> candidate.teacherId() == 1L || candidate.teacherId() == 7L);
        assertThat(pool.get(0).ruleScore()).isEqualTo(80);
        assertThat(pool.get(1).ruleScore()).isEqualTo(70);
        assertThat(pool.get(2).ruleScore()).isEqualTo(50);
        assertThat(pool.get(3).ruleScore()).isEqualTo(30);
        assertThat(pool.get(4).ruleScore()).isZero();
        assertThat(pool.get(0).sameSubject()).isTrue();
        assertThat(pool.get(0).busyAtPeriod()).isFalse();
        assertThat(pool.get(2).busyAtPeriod()).isTrue();
    }

    @Test
    void fallsBackToCourseNameWhenCourseSubjectIsBlank() {
        when(courses.findById(COURSE_ID)).thenReturn(Optional.of(course("语文", "  ")));
        when(teachers.findAllByOrderByNameAsc()).thenReturn(List.of(
                teacher(1L, "张老师", "数学", true),
                teacher(2L, "李老师", "语文", true)));

        List<SubstitutionRecommendationService.Candidate> pool = service.candidatePool(request());

        assertThat(pool).hasSize(1);
        assertThat(pool.get(0).teacherId()).isEqualTo(2L);
        assertThat(pool.get(0).sameSubject()).isTrue();
    }

    @Test
    void effectiveSubstitutionAtTheSameSlotMarksTeacherBusy() {
        when(teachers.findAllByOrderByNameAsc()).thenReturn(List.of(
                teacher(1L, "张老师", "语文", true),
                teacher(8L, "吴老师", "数学", true),
                teacher(9L, "郑老师", "数学", true)));
        Substitution sameSlot = substitution(8L, 90L, null, null);
        Substitution movedSlot = substitution(9L, 91L, 5, 5);
        when(substitutions.findBySemesterIdAndEffectiveDateAndStatus(
                SEMESTER_ID, DATE, Substitution.EFFECTIVE)).thenReturn(List.of(sameSlot, movedSlot));
        when(requests.findById(90L)).thenReturn(Optional.of(request(3, 3)));
        when(requests.findById(91L)).thenReturn(Optional.of(request(3, 9)));

        List<SubstitutionRecommendationService.Candidate> pool = service.candidatePool(request());

        assertThat(pool).filteredOn(candidate -> candidate.teacherId() == 8L)
                .singleElement()
                .matches(SubstitutionRecommendationService.Candidate::busyAtPeriod);
        assertThat(pool).filteredOn(candidate -> candidate.teacherId() == 9L)
                .singleElement()
                .matches(candidate -> !candidate.busyAtPeriod());
    }

    @Test
    void capsThePoolAtTwentyKeepingRuleOrder() {
        List<Teacher> all = new ArrayList<>();
        all.add(teacher(1L, "张老师", "语文", true));
        for (int index = 1; index <= 25; index++) {
            all.add(teacher(100L + index, String.format("T%02d", index), "数学", true));
        }
        when(teachers.findAllByOrderByNameAsc()).thenReturn(all);

        List<SubstitutionRecommendationService.Candidate> pool = service.candidatePool(request());

        assertThat(pool).hasSize(SubstitutionRecommendationService.MAX_POOL_SIZE);
        assertThat(pool).extracting(SubstitutionRecommendationService.Candidate::name)
                .containsExactly(
                        "T01", "T02", "T03", "T04", "T05", "T06", "T07", "T08", "T09", "T10",
                        "T11", "T12", "T13", "T14", "T15", "T16", "T17", "T18", "T19", "T20");
    }

    @Test
    void recommendSendsPoolContextAndKeepsOnlyKnownIds() {
        when(teachers.findAllByOrderByNameAsc()).thenReturn(List.of(
                teacher(1L, "张老师", "语文", true),
                teacher(2L, "李老师", "语文", true),
                teacher(3L, "王老师", "语文", true),
                teacher(4L, "陈老师", "数学", true),
                teacher(5L, "赵老师", "英语", true)));
        gateway.result = success("""
                [
                  {"teacherId":4,"name":"伪造的名字","reason":"不同学科但空闲","score":60},
                  {"teacherId":999,"name":"不存在","reason":"编造","score":99},
                  {"teacherId":4,"name":"陈老师","reason":"已代课 0 次","score":80},
                  {"teacherId":2,"name":"李老师","reason":"同学科、当天该时段无课","score":90},
                  {"teacherId":3,"name":"王老师","reason":"同学科","score":70},
                  {"teacherId":5,"name":"赵老师","reason":"空闲","score":50}
                ]
                """);

        RecommendationResponse response = service.recommend(1L);

        assertThat(response.requestId()).isEqualTo(1L);
        assertThat(response.providerName()).isEqualTo("测试供应商");
        assertThat(response.modelName()).isEqualTo("test-model");
        assertThat(response.recommendations()).extracting(RecommendationDto::teacherId)
                .containsExactly(2L, 4L, 3L);
        assertThat(response.recommendations()).extracting(RecommendationDto::score)
                .containsExactly(90, 80, 70);
        assertThat(response.recommendations().get(1).name()).isEqualTo("陈老师");
        assertThat(response.recommendations().get(1).reason()).isEqualTo("已代课 0 次");

        AiRequest sent = gateway.lastRequest;
        assertThat(sent.taskType()).isEqualTo(AiTaskType.SUBSTITUTION_RECOMMEND);
        assertThat(sent.options().jsonMode()).isTrue();
        assertThat(sent.subjectType()).isEqualTo(SubstitutionRecommendationService.SUBJECT_TYPE);
        assertThat(sent.subjectId()).isEqualTo(1L);
        assertThat(sent.messages()).hasSize(2);
        assertThat(sent.messages().get(0).role()).isEqualTo("system");
        assertThat(sent.messages().get(0).content()).contains("同学科优先", "本学期已代课次数少优先");
        assertThat(sent.messages().get(1).role()).isEqualTo("user");
        assertThat(sent.messages().get(1).content())
                .contains("日期：2025-09-03", "节次：第3节", "teacherId=2", "同学科=是", "本学期代课次数=0");
    }

    @Test
    void recommendParsesMarkdownFencedJson() {
        when(teachers.findAllByOrderByNameAsc()).thenReturn(List.of(
                teacher(1L, "张老师", "语文", true),
                teacher(2L, "李老师", "语文", true)));
        gateway.result = success("""
                ```json
                [{"teacherId":2,"name":"李老师","reason":"同学科","score":88}]
                ```
                """);

        RecommendationResponse response = service.recommend(1L);

        assertThat(response.recommendations()).singleElement()
                .satisfies(item -> assertThat(item.teacherId()).isEqualTo(2L));
    }

    @Test
    void recommendRejectsRequestsThatAreNotPendingApproval() {
        SubstitutionRequest effective = request();
        effective.setStatus(SubstitutionRequest.EFFECTIVE);
        when(requests.findById(1L)).thenReturn(Optional.of(effective));

        assertThatThrownBy(() -> service.recommend(1L))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void recommendReturnsNotFoundForUnknownRequest() {
        when(requests.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.recommend(404L))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void recommendMapsGatewayFailureToBadGateway() {
        when(teachers.findAllByOrderByNameAsc()).thenReturn(List.of(
                teacher(1L, "张老师", "语文", true),
                teacher(2L, "李老师", "语文", true)));
        gateway.result = new AiResult(
                false, null, "NOT_CONFIGURED", "该任务未配置可用的 AI 供应商与模型",
                null, null, null, null, null, null, 3L, null);

        assertThatThrownBy(() -> service.recommend(1L))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> {
                    ApiException api = (ApiException) error;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
                    assertThat(api.getMessage()).contains("未配置", "AI 供应商");
                });
    }

    @Test
    void recommendMapsUnparseableContentToBadGateway() {
        when(teachers.findAllByOrderByNameAsc()).thenReturn(List.of(
                teacher(1L, "张老师", "语文", true),
                teacher(2L, "李老师", "语文", true)));
        gateway.result = success("模型今天不想输出 JSON");

        assertThatThrownBy(() -> service.recommend(1L))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.BAD_GATEWAY));
    }

    @Test
    void recommendRejectsAnAnswerThatOnlyContainsUnknownTeachers() {
        when(teachers.findAllByOrderByNameAsc()).thenReturn(List.of(
                teacher(1L, "张老师", "语文", true),
                teacher(2L, "李老师", "语文", true)));
        gateway.result = success("[{\"teacherId\":777,\"name\":\"幽灵\",\"reason\":\"编造\",\"score\":99}]");

        assertThatThrownBy(() -> service.recommend(1L))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> {
                    ApiException api = (ApiException) error;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
                    assertThat(api.getMessage()).contains("候选池");
                });
    }

    private static SubstitutionRequest request() {
        return request(3, 3);
    }

    private static SubstitutionRequest request(int dayOfWeek, int periodNo) {
        SubstitutionRequest request = new SubstitutionRequest();
        request.setId(1L);
        request.setSemesterId(SEMESTER_ID);
        request.setEntryId(100L);
        request.setAdminClassId(20L);
        request.setCourseId(COURSE_ID);
        request.setOriginalTeacherId(1L);
        request.setRequesterTeacherId(1L);
        request.setEffectiveDate(DATE);
        request.setPeriodId(40L);
        request.setDayOfWeek(dayOfWeek);
        request.setPeriodNo(periodNo);
        request.setReason("家中有事");
        request.setStatus(SubstitutionRequest.PENDING_APPROVAL);
        request.setApprovalMode("approval");
        return request;
    }

    private static Teacher teacher(long id, String name, String subject, boolean active) {
        Teacher teacher = new Teacher();
        teacher.setId(id);
        teacher.setName(name);
        teacher.setSubject(subject);
        teacher.setActive(active);
        return teacher;
    }

    private static Course course(String name, String subject) {
        Course course = new Course();
        course.setId(COURSE_ID);
        course.setName(name);
        course.setSubject(subject);
        return course;
    }

    private static TimetableEntry entry(long teacherId, int dayOfWeek, int periodNo) {
        TimetableEntry entry = new TimetableEntry();
        entry.setSemesterId(SEMESTER_ID);
        entry.setTeacherId(teacherId);
        entry.setDayOfWeek(dayOfWeek);
        entry.setPeriodNo(periodNo);
        return entry;
    }

    private static Substitution substitution(long substituteTeacherId, long requestId,
                                             Integer newDayOfWeek, Integer newPeriodNo) {
        Substitution substitution = new Substitution();
        substitution.setSemesterId(SEMESTER_ID);
        substitution.setSubstituteTeacherId(substituteTeacherId);
        substitution.setRequestId(requestId);
        substitution.setEffectiveDate(DATE);
        substitution.setStatus(Substitution.EFFECTIVE);
        substitution.setNewDayOfWeek(newDayOfWeek);
        substitution.setNewPeriodNo(newPeriodNo);
        return substitution;
    }

    private static AiResult success(String content) {
        return new AiResult(
                true, content, null, null, 1L, "测试供应商", 2L, "test-model", 120, 80, 15L, content);
    }

    private static final class FakeGateway implements AiGateway {

        private AiResult result;
        private AiRequest lastRequest;

        @Override
        public AiResult chat(AiRequest request) {
            this.lastRequest = request;
            return result;
        }

        @Override
        public AiResult chatWithVision(AiRequest request) {
            return chat(request);
        }

        @Override
        public boolean configured() {
            return true;
        }
    }
}
