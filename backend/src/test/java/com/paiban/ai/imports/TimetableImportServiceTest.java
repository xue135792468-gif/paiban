package com.paiban.ai.imports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import com.paiban.ai.imports.ImportDtos.RowDto;
import com.paiban.foundation.domain.AdminClassRepository;
import com.paiban.foundation.domain.CourseRepository;
import com.paiban.foundation.domain.PeriodRepository;
import com.paiban.foundation.domain.RoomRepository;
import com.paiban.foundation.domain.TeacherRepository;
import com.paiban.foundation.domain.TeachingClassRepository;
import com.paiban.scheduling.domain.Assignment;
import com.paiban.scheduling.domain.AssignmentRepository;
import com.paiban.scheduling.domain.DraftLesson;
import com.paiban.scheduling.domain.DraftLessonRepository;
import com.paiban.scheduling.domain.TimetableDraft;
import com.paiban.scheduling.domain.TimetableDraftRepository;
import com.paiban.support.ApiIntegrationTest;
import com.paiban.support.TestData;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class TimetableImportServiceTest extends ApiIntegrationTest {

    @Autowired
    private TimetableImportService service;

    @Autowired
    private TimetableDraftRepository drafts;

    @MockitoSpyBean
    private DraftLessonRepository draftLessons;

    @Autowired
    private AssignmentRepository assignments;

    @Autowired
    private TeachingClassRepository teachingClasses;

    @Autowired
    private AdminClassRepository adminClasses;

    @Autowired
    private PeriodRepository periods;

    @Autowired
    private CourseRepository courses;

    @Autowired
    private TeacherRepository teachers;

    @Autowired
    private RoomRepository rooms;

    private record Fixture(long semesterId, long classId, long courseId, long teacherId, long roomId) {}

    private Fixture fixture() {
        String token = adminToken();
        long semesterId = TestData.semester(token);
        as(token).body(Map.of("morningCount", 4, "afternoonCount", 4, "eveningCount", 0))
                .when().post("/api/semesters/" + semesterId + "/periods/default")
                .then().statusCode(200);
        long gradeId = TestData.grade(token, semesterId, "一年级");
        long classId = TestData.adminClass(token, semesterId, gradeId, "一年级1班");
        long courseId = TestData.course(token, "语文");
        long teacherId = TestData.teacher(token, "张三");
        long roomId = TestData.room(token, "101");
        return new Fixture(semesterId, classId, courseId, teacherId, roomId);
    }

    private static RowDto row(
            String className, String day, String period, String course, String teacher, String room) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("className", className);
        values.put("dayOfWeek", day);
        values.put("periodNo", period);
        values.put("courseName", course);
        values.put("teacherName", teacher);
        values.put("roomName", room);
        return new RowDto(values, Map.of(), List.of());
    }

    @Test
    void importsRowsIntoADraftWithAssignmentsAndLessons() {
        Fixture f = fixture();

        TimetableImportService.Result result = service.importRows(
                List.of(
                        row("一年级1班", "周一", "第1节", "语文", "张三", "101"),
                        row("一年级1班", "周一", "第2节", "语文", "张三", "101")),
                null,
                "admin");

        assertThat(result.errors()).isEmpty();
        assertThat(result.warnings()).isEmpty();
        assertThat(result.imported()).isEqualTo(2);
        assertThat(result.draftId()).isNotNull();

        TimetableDraft draft = drafts.findById(result.draftId()).orElseThrow();
        assertThat(draft.getSemesterId()).isEqualTo(f.semesterId());
        assertThat(draft.getStatus()).isEqualTo("DRAFT");
        assertThat(draft.getName()).startsWith("导入课表 ");
        assertThat(draft.getCreatedBy()).isEqualTo("admin");
        assertThat(draft.getScoreHard()).isZero();
        assertThat(draft.getScoreSoft()).isZero();

        List<DraftLesson> lessons = draftLessons.findByDraftIdOrderByIdAsc(result.draftId());
        assertThat(lessons).hasSize(2);
        assertThat(lessons).allSatisfy(lesson -> {
            assertThat(lesson.getAssignmentId()).isNotNull();
            assertThat(lesson.getAdminClassId()).isEqualTo(f.classId());
            assertThat(lesson.getCourseId()).isEqualTo(f.courseId());
            assertThat(lesson.getTeacherId()).isEqualTo(f.teacherId());
            assertThat(lesson.getRoomId()).isEqualTo(f.roomId());
            assertThat(lesson.getStudentCount()).isEqualTo(45);
            assertThat(lesson.getPeriodId()).isNotNull();
        });
        assertThat(lessons).extracting(DraftLesson::getLessonIndex).containsExactly(0, 1);

        List<Assignment> created = assignments.findBySemesterId(f.semesterId());
        assertThat(created).hasSize(1);
        assertThat(created.get(0).getWeeklyLessons()).isEqualTo(2);
        assertThat(created.get(0).getTeacherId()).isEqualTo(f.teacherId());
        assertThat(created.get(0).getAdminClassId()).isEqualTo(f.classId());
    }

    @Test
    void unmatchedNamesReturnRowErrorsWithCandidatesAndPersistNothing() {
        fixture();

        TimetableImportService.Result result = service.importRows(
                List.of(
                        row("一年级1班", "周一", "第1节", "语文", "张三", "101"),
                        row("不存在班", "周一", "第1节", "语文", "张三", "101"),
                        row("一年级1班", "周八", "第1节", "语文", "张三", "101"),
                        row("一年级1班", "周一", "第九节", "语文", "张三", "101"),
                        row("一年级1班", "周一", "第1节", "不存在课", "张三", "101"),
                        row("一年级1班", "周一", "第1节", "语文", "不存在师", "101"),
                        row("一年级1班", "周一", "第1节", "语文", "张三", "不存在室")),
                null,
                "admin");

        assertThat(result.draftId()).isNull();
        assertThat(result.imported()).isZero();
        assertThat(result.errors()).extracting(ImportDtos.RowErrorDto::row)
                .containsExactly(2, 3, 4, 5, 6, 7);
        assertThat(result.errors()).extracting(ImportDtos.RowErrorDto::field)
                .containsExactly("className", "dayOfWeek", "periodNo", "courseName", "teacherName", "roomName");
        assertThat(result.errors().get(0).candidates()).contains("一年级1班");
        assertThat(result.errors().get(1).candidates()).contains("周一", "周五");
        assertThat(result.errors().get(2).candidates()).contains("第1节", "第8节");
        assertThat(result.errors().get(3).candidates()).contains("语文");
        assertThat(result.errors().get(4).candidates()).contains("张三");
        assertThat(result.errors().get(5).candidates()).contains("101");

        assertThat(drafts.count()).isZero();
        assertThat(draftLessons.count()).isZero();
        assertThat(assignments.count()).isZero();
    }

    @Test
    void analyzeReportsUnmatchedNamesWithoutPersistingAnything() {
        fixture();

        List<ImportDtos.RowErrorDto> errors = service.analyze(
                List.of(
                        row("一年级1班", "周一", "第1节", "语文", "张三", "101"),
                        row("不存在班", "周一", "第1节", "语文", "张三", "101")),
                null);

        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).row()).isEqualTo(2);
        assertThat(errors.get(0).field()).isEqualTo("className");
        assertThat(errors.get(0).message()).contains("不存在班");
        assertThat(errors.get(0).candidates()).contains("一年级1班");
        assertThat(drafts.count()).isZero();
        assertThat(assignments.count()).isZero();
    }

    @Test
    void analyzeAcceptsFullyMatchedRowsAndWritesNothing() {
        fixture();

        List<ImportDtos.RowErrorDto> errors = service.analyze(
                List.of(row("一年级1班", "周一", "第1节", "语文", "张三", "101")), null);

        assertThat(errors).isEmpty();
        assertThat(drafts.count()).isZero();
        assertThat(draftLessons.count()).isZero();
        assertThat(assignments.count()).isZero();
    }

    @Test
    void inspectSummarizesLessonsAndCountsNewVersusUpdatedAssignments() {
        Fixture f = fixture();
        String token = adminToken();
        as(token).body(Map.of(
                        "classId", f.classId(),
                        "courseId", f.courseId(),
                        "teacherId", f.teacherId(),
                        "weeklyLessons", 3))
                .when().post("/api/assignments")
                .then().statusCode(200);
        TestData.course(token, "数学");
        TestData.teacher(token, "李四");

        TimetableImportService.Analysis analysis = service.inspect(
                List.of(
                        row("一年级1班", "周一", "第1节", "语文", "张三", "101"),
                        row("一年级1班", "周一", "第2节", "语文", "张三", "101"),
                        row("一年级1班", "周一", "第1节", "数学", "李四", "101")),
                null);

        assertThat(analysis.errors()).isEmpty();
        assertThat(analysis.summary().lessons()).isEqualTo(3);
        assertThat(analysis.summary().groups()).isEqualTo(2);
        assertThat(analysis.summary().newAssignments()).isEqualTo(1);
        assertThat(analysis.summary().updatedAssignments()).isEqualTo(1);
    }

    @Test
    void inspectOnlyCountsRowsThatCanBeImported() {
        fixture();

        TimetableImportService.Analysis analysis = service.inspect(
                List.of(
                        row("一年级1班", "周一", "第1节", "语文", "张三", "101"),
                        row("不存在班", "周一", "第1节", "语文", "张三", "101")),
                null);

        assertThat(analysis.errors()).hasSize(1);
        assertThat(analysis.summary().lessons()).isEqualTo(1);
        assertThat(analysis.summary().groups()).isEqualTo(1);
        assertThat(analysis.summary().newAssignments()).isEqualTo(1);
        assertThat(analysis.summary().updatedAssignments()).isZero();
    }

    @Test
    void normalizesWeekdayAndPeriodVariantsAndTrimsNames() {
        Fixture f = fixture();
        long firstPeriod = periodId(f.semesterId(), 1, 1);
        long secondPeriod = periodId(f.semesterId(), 1, 2);

        TimetableImportService.Result result = service.importRows(
                List.of(
                        row(" 一年级1班 ", "星期一", "1", "语文 ", " 张三", "101"),
                        row("一年级1班", "一", "一", "语文", "张三", "101"),
                        row("一年级1班", "1", "第一节", "语文", "张三", "101"),
                        row("一年级1班", "周一", "第2节", "语文", "张三", "101")),
                null,
                "admin");

        assertThat(result.errors()).isEmpty();
        assertThat(result.imported()).isEqualTo(4);
        assertThat(draftLessons.findByDraftIdOrderByIdAsc(result.draftId()))
                .extracting(DraftLesson::getPeriodId)
                .containsExactly(firstPeriod, firstPeriod, firstPeriod, secondPeriod);
    }

    @Test
    void explicitSemesterOverridesTheCurrentOne() {
        Fixture f = fixture();
        String token = adminToken();
        long otherSemester = TestData.semester(token);

        TimetableImportService.Result result = service.importRows(
                List.of(row("一年级1班", "周一", "第1节", "语文", "张三", "101")),
                f.semesterId(),
                "admin");

        assertThat(result.errors()).isEmpty();
        assertThat(drafts.findById(result.draftId()).orElseThrow().getSemesterId())
                .isEqualTo(f.semesterId())
                .isNotEqualTo(otherSemester);
    }

    @Test
    void missingCurrentSemesterGivesAReadableError() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.importRows(
                        List.of(row("一年级1班", "周一", "第1节", "语文", "张三", "101")), null, "admin"))
                .isInstanceOf(com.paiban.common.web.ApiException.class)
                .hasMessageContaining("尚未设置当前学期");
    }

    @Test
    void reusesAnExistingAssignmentAndUpdatesItFromTheImport() {
        Fixture f = fixture();
        String token = adminToken();
        as(token).body(Map.of(
                        "classId", f.classId(),
                        "courseId", f.courseId(),
                        "teacherId", f.teacherId(),
                        "weeklyLessons", 3))
                .when().post("/api/assignments")
                .then().statusCode(200);
        Assignment existing = assignments.findBySemesterId(f.semesterId()).get(0);
        long importedTeacher = TestData.teacher(token, "李四");
        long teachingClassId = teachingClasses.findByAdminClassId(f.classId()).orElseThrow().getId();

        TimetableImportService.Result result = service.importRows(
                List.of(
                        row("一年级1班", "周一", "第1节", "语文", "李四", "101"),
                        row("一年级1班", "周二", "第1节", "语文", "李四", "101")),
                null,
                "admin");

        assertThat(result.errors()).isEmpty();
        assertThat(assignments.findBySemesterId(f.semesterId())).hasSize(1);
        Assignment reused = assignments.findBySemesterId(f.semesterId()).get(0);
        assertThat(reused.getId()).isEqualTo(existing.getId());
        assertThat(reused.getWeeklyLessons()).isEqualTo(2);
        assertThat(reused.getTeacherId()).isEqualTo(importedTeacher);
        assertThat(draftLessons.findByDraftIdOrderByIdAsc(result.draftId()))
                .extracting(DraftLesson::getAssignmentId)
                .containsOnly(existing.getId());
        assertThat(teachingClasses.findByAdminClassId(f.classId()).orElseThrow().getId())
                .isEqualTo(teachingClassId);
        assertThat(teachingClasses.count()).isEqualTo(1);
    }

    @Test
    void createsTheTeachingClassWhenItIsMissing() {
        Fixture f = fixture();
        long teachingClassId = teachingClasses.findByAdminClassId(f.classId()).orElseThrow().getId();
        teachingClasses.deleteById(teachingClassId);

        TimetableImportService.Result result = service.importRows(
                List.of(row("一年级1班", "周一", "第1节", "语文", "张三", "101")), null, "admin");

        assertThat(result.errors()).isEmpty();
        assertThat(teachingClasses.findByAdminClassId(f.classId()).orElseThrow().getName())
                .isEqualTo("一年级1班");
        Assignment created = assignments.findBySemesterId(f.semesterId()).get(0);
        assertThat(created.getTeachingClassId())
                .isEqualTo(teachingClasses.findByAdminClassId(f.classId()).orElseThrow().getId());
    }

    @Test
    void multipleTeachersForOneCombinationKeepTheirOwnLessonsAndWarn() {
        Fixture f = fixture();
        long secondTeacher = TestData.teacher(adminToken(), "李四");

        TimetableImportService.Result result = service.importRows(
                List.of(
                        row("一年级1班", "周一", "第1节", "语文", "张三", "101"),
                        row("一年级1班", "周二", "第1节", "语文", "李四", "101")),
                null,
                "admin");

        assertThat(result.errors()).isEmpty();
        assertThat(result.imported()).isEqualTo(2);
        assertThat(result.warnings()).hasSize(1);
        assertThat(result.warnings().get(0))
                .contains("一年级1班")
                .contains("语文")
                .contains("张三")
                .contains("李四");

        Assignment assignment = assignments.findBySemesterId(f.semesterId()).get(0);
        assertThat(assignment.getTeacherId()).isEqualTo(f.teacherId());
        assertThat(draftLessons.findByDraftIdOrderByIdAsc(result.draftId()))
                .extracting(DraftLesson::getTeacherId)
                .containsExactly(f.teacherId(), secondTeacher);
    }

    @Test
    void duplicateSlotsStillProduceADraft() {
        Fixture f = fixture();
        String token = adminToken();
        TestData.course(token, "数学");
        TestData.teacher(token, "李四");

        TimetableImportService.Result result = service.importRows(
                List.of(
                        row("一年级1班", "周一", "第1节", "语文", "张三", "101"),
                        row("一年级1班", "周一", "第1节", "数学", "李四", "101")),
                null,
                "admin");

        assertThat(result.errors()).isEmpty();
        assertThat(result.imported()).isEqualTo(2);
        assertThat(draftLessons.findByDraftIdOrderByIdAsc(result.draftId()))
                .extracting(DraftLesson::getPeriodId)
                .containsOnly(periodId(f.semesterId(), 1, 1));
    }

    @Test
    void aPersistFailureRollsBackTheWholeImport() {
        Fixture f = fixture();
        doThrow(new DataIntegrityViolationException("模拟课节写入失败")).when(draftLessons).saveAll(any());

        assertThatThrownBy(() -> service.importRows(
                        List.of(row("一年级1班", "周一", "第1节", "语文", "张三", "101")), null, "admin"))
                .isInstanceOf(DataIntegrityViolationException.class);

        reset(draftLessons);
        assertThat(drafts.count()).isZero();
        assertThat(draftLessons.count()).isZero();
        assertThat(assignments.findBySemesterId(f.semesterId())).isEmpty();
    }

    private long periodId(long semesterId, int dayOfWeek, int periodNo) {
        return periods.findBySemesterIdOrderByDayOfWeekAscPeriodNoAsc(semesterId).stream()
                .filter(period -> period.getDayOfWeek() == dayOfWeek && period.getPeriodNo() == periodNo)
                .findFirst()
                .orElseThrow()
                .getId();
    }
}
