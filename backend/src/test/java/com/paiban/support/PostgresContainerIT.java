package com.paiban.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.paiban.common.web.ApiException;
import com.paiban.foundation.domain.AdminClass;
import com.paiban.foundation.domain.AdminClassRepository;
import com.paiban.foundation.domain.Course;
import com.paiban.foundation.domain.CourseRepository;
import com.paiban.foundation.domain.Grade;
import com.paiban.foundation.domain.GradeRepository;
import com.paiban.foundation.domain.Period;
import com.paiban.foundation.domain.PeriodRepository;
import com.paiban.foundation.domain.PeriodSection;
import com.paiban.foundation.domain.Room;
import com.paiban.foundation.domain.RoomRepository;
import com.paiban.foundation.domain.Semester;
import com.paiban.foundation.domain.SemesterRepository;
import com.paiban.foundation.domain.Teacher;
import com.paiban.foundation.domain.TeacherRepository;
import com.paiban.substitution.domain.Substitution;
import com.paiban.substitution.domain.SubstitutionConversionRepository;
import com.paiban.substitution.domain.SubstitutionPlanMove;
import com.paiban.substitution.domain.SubstitutionPlanMoveRepository;
import com.paiban.substitution.domain.SubstitutionPlanRepository;
import com.paiban.substitution.domain.SubstitutionRequest;
import com.paiban.substitution.domain.SubstitutionRequestRepository;
import com.paiban.substitution.domain.SubstitutionRepository;
import com.paiban.substitution.service.SubstitutionConversionService;
import com.paiban.substitution.web.SubstitutionConversionDtos.ConversionPrecheckDto;
import com.paiban.substitution.web.SubstitutionConversionDtos.ConversionStatusDto;
import com.paiban.timetable.domain.TimetableEntry;
import com.paiban.timetable.domain.TimetableEntryRepository;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real-PostgreSQL integration check required by the spec. It only runs when a
 * Docker daemon is available: {@code ./mvnw verify -Dtestcontainers=true}.
 * The default suite uses H2 in PostgreSQL mode because CI-less environments in
 * this workspace have no Docker.
 *
 * <p>票 02 在此验证：V39 迁移与约束（CHECK / FK）在真实 PostgreSQL 上生效，
 * 转换服务在真实 PG 上完成「预检 → 确认 → 实例日期写入」并可幂等重跑。
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("postgres")
@EnabledIfSystemProperty(named = "testcontainers", matches = "true")
class PostgresContainerIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // The postgres profile is treated as production, so the context refuses the
        // default development secret. This test only checks migrations, not auth.
        registry.add("paiban.security.jwt-secret", () -> "postgres-container-test-secret-0123456789abcdef");
    }

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    SemesterRepository semesters;
    @Autowired
    GradeRepository grades;
    @Autowired
    AdminClassRepository adminClasses;
    @Autowired
    TeacherRepository teachers;
    @Autowired
    CourseRepository courses;
    @Autowired
    RoomRepository rooms;
    @Autowired
    PeriodRepository periods;
    @Autowired
    TimetableEntryRepository entries;
    @Autowired
    com.paiban.scheduling.domain.TimetableDraftRepository drafts;
    @Autowired
    SubstitutionRepository substitutions;
    @Autowired
    SubstitutionRequestRepository requests;
    @Autowired
    SubstitutionPlanRepository plans;
    @Autowired
    SubstitutionPlanMoveRepository planMoves;
    @Autowired
    SubstitutionConversionRepository conversionBatches;
    @Autowired
    SubstitutionConversionService conversionService;

    @Test
    void flywayMigrationsApplyOnPostgres() {
        Integer tables = jdbcTemplate.queryForObject(
                "select count(*) from information_schema.tables where table_schema = 'public'", Integer.class);
        assertThat(tables).isNotNull().isGreaterThan(20);
        Integer semesters = jdbcTemplate.queryForObject("select count(*) from semester", Integer.class);
        assertThat(semesters).isNotNull();
    }

    @Test
    void conversionMigrationAddsConstrainedTables() {
        Integer conversionTables = jdbcTemplate.queryForObject(
                "select count(*) from information_schema.tables where table_schema = 'public' "
                        + "and table_name in ('substitution_conversion', 'substitution_conversion_item')",
                Integer.class);
        assertThat(conversionTables).isEqualTo(2);

        // CHECK 约束：批次状态只允许 CONVERTED / FAILED
        assertThatThrownBy(() -> jdbcTemplate.update(
                "insert into substitution_conversion (semester_id, status, legacy_total, converted_count, "
                        + "blocked_count, confirmed_by, confirmed_at, created_at) "
                        + "values (1, 'BOGUS', 0, 0, 0, 't', now(), now())"))
                .isInstanceOf(DataIntegrityViolationException.class);

        long semesterId = seedSemesterOnly();
        // FK 约束：明细必须挂在真实批次上
        assertThatThrownBy(() -> jdbcTemplate.update(
                "insert into substitution_conversion_item (conversion_id, substitution_id, entry_id, type, "
                        + "lesson_date_after) values (999999, 1, 1, 'COVER', ?)",
                LocalDate.of(2025, 9, 1)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from substitution_conversion where status = 'BOGUS'", Integer.class))
                .isZero();
        assertThat(semesters.findById(semesterId)).isPresent();
    }

    @Test
    void conversionServiceConvertsLegacyRowsOnRealPostgres() {
        Seed seed = seedLegacyCover();

        // 预检：报告可转换、业务数据未动
        ConversionPrecheckDto precheck = conversionService.precheck(seed.semesterId());
        assertThat(precheck.summary().convertible()).isEqualTo(1);
        assertThat(precheck.summary().blocked()).isZero();
        assertThat(precheck.items().get(0).lessonDate()).isEqualTo(seed.date().toString());
        assertThat(precheck.items().get(0).basis()).contains("申请 #" + seed.requestId());
        Substitution before = substitutions.findById(seed.substitutionId()).orElseThrow();
        assertThat(before.getLessonDate()).isNull();

        // 确认门：原子写入实例日期，批次与逐条留痕落库
        var result = conversionService.confirm(seed.semesterId(), "pg-admin", "真实 PG 验证");
        assertThat(result.convertedCount()).isEqualTo(1);
        assertThat(result.alreadyConverted()).isFalse();
        Substitution converted = substitutions.findById(seed.substitutionId()).orElseThrow();
        assertThat(converted.getLessonDate()).isEqualTo(seed.date());
        assertThat(converted.getEffectiveDate()).isEqualTo(seed.date());

        // 幂等重跑：不重复转换、不新建批次
        var repeat = conversionService.confirm(seed.semesterId(), "pg-admin", null);
        assertThat(repeat.alreadyConverted()).isTrue();
        assertThat(repeat.conversion().convertedCount()).isEqualTo(1);

        ConversionStatusDto status = conversionService.status(seed.semesterId());
        assertThat(status.legacyRowCount()).isZero();
        assertThat(status.converted()).isTrue();
        assertThat(status.conversions()).hasSize(1);
        assertThat(status.conversions().get(0).status()).isEqualTo("CONVERTED");
        assertThat(status.conversions().get(0).confirmedBy()).isEqualTo("pg-admin");
        assertThat(status.conversions().get(0).items()).hasSize(1);
        assertThat(status.conversions().get(0).items().get(0).basis())
                .contains("申请 #" + seed.requestId());
        assertThat(conversionBatches.findBySemesterIdOrderByCreatedAtDesc(seed.semesterId())).hasSize(1);
    }

    @Test
    void blockedRecordsRefuseConversionAndKeepLegacyRowsOnRealPostgres() {
        Seed seed = seedLegacySwapWithAmbiguousMove();

        ConversionPrecheckDto precheck = conversionService.precheck(seed.semesterId());
        assertThat(precheck.summary().blocked()).isEqualTo(1);
        assertThat(precheck.items().get(0).blockerCode()).isEqualTo("AMBIGUOUS_MOVE");

        assertThatThrownBy(() -> conversionService.confirm(seed.semesterId(), "pg-admin", null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("阻塞");
        // 原数据保持旧格式，没有留下任何转换批次
        assertThat(substitutions.findById(seed.substitutionId()).orElseThrow().getLessonDate()).isNull();
        assertThat(conversionBatches.findBySemesterIdOrderByCreatedAtDesc(seed.semesterId())).isEmpty();
    }

    // ------------------------------------------------------------------
    // 种子（直接经仓库写入，模拟票 01 之前的存量）
    // ------------------------------------------------------------------

    private record Seed(long semesterId, long requestId, long substitutionId, LocalDate date) {}

    /** 教师工号与教室名在真实 PostgreSQL 上有唯一约束；每个用例的种子必须互不相同。 */
    private static final java.util.concurrent.atomic.AtomicInteger SEQ =
            new java.util.concurrent.atomic.AtomicInteger();

    private long seedSemesterOnly() {
        Semester semester = new Semester();
        semester.setName("PG转换约束学期");
        semester.setStartDate(LocalDate.of(2025, 9, 1));
        semester.setEndDate(LocalDate.of(2026, 1, 20));
        semester.setCurrent(false);
        return semesters.save(semester).getId();
    }

    private Seed seedLegacyCover() {
        long semesterId = seedSemesterOnly();
        LocalDate monday = LocalDate.of(2025, 9, 1);
        String suffix = "-PG" + SEQ.incrementAndGet();

        Grade grade = new Grade();
        grade.setSemesterId(semesterId);
        grade.setName("高一");
        grade.setSortOrder(1);
        long gradeId = grades.save(grade).getId();

        Teacher original = new Teacher();
        original.setName("原任课");
        original.setEmployeeNo("PG-T1" + suffix);
        long originalId = teachers.save(original).getId();
        Teacher substitute = new Teacher();
        substitute.setName("代课人");
        substitute.setEmployeeNo("PG-T2" + suffix);
        long substituteId = teachers.save(substitute).getId();

        AdminClass adminClass = new AdminClass();
        adminClass.setSemesterId(semesterId);
        adminClass.setGradeId(gradeId);
        adminClass.setName("高一1班");
        adminClass.setStudentCount(45);
        long classId = adminClasses.save(adminClass).getId();

        Course course = new Course();
        course.setName("语文" + suffix);
        long courseId = courses.save(course).getId();
        Room room = new Room();
        room.setName("教室A" + suffix);
        long roomId = rooms.save(room).getId();

        Period period = new Period();
        period.setSemesterId(semesterId);
        period.setDayOfWeek(1);
        period.setPeriodNo(1);
        period.setLabel("周一第1节");
        period.setSection(PeriodSection.MORNING);
        period.setStartTime(LocalTime.of(8, 0));
        period.setEndTime(LocalTime.of(8, 45));
        long periodId = periods.save(period).getId();

        // timetable_entry.draft_id 非空：草稿发布是正式路径，直接播种时补一张已发布草稿。
        com.paiban.scheduling.domain.TimetableDraft draft =
                new com.paiban.scheduling.domain.TimetableDraft();
        draft.setSemesterId(semesterId);
        draft.setName("PG种子草稿" + suffix);
        draft.setStatus("PUBLISHED");
        long draftId = drafts.save(draft).getId();

        TimetableEntry entry = new TimetableEntry();
        entry.setDraftId(draftId);
        entry.setSemesterId(semesterId);
        entry.setAdminClassId(classId);
        entry.setCourseId(courseId);
        entry.setTeacherId(originalId);
        entry.setRoomId(roomId);
        entry.setPeriodId(periodId);
        entry.setDayOfWeek(1);
        entry.setPeriodNo(1);
        long entryId = entries.save(entry).getId();

        SubstitutionRequest request = new SubstitutionRequest();
        request.setSemesterId(semesterId);
        request.setEntryId(entryId);
        request.setAdminClassId(classId);
        request.setCourseId(courseId);
        request.setOriginalTeacherId(originalId);
        request.setEffectiveDate(monday);
        request.setPeriodId(periodId);
        request.setDayOfWeek(1);
        request.setPeriodNo(1);
        request.setReason("PG 历史代课");
        request.setType(SubstitutionRequest.TYPE_COVER);
        request.setStatus(SubstitutionRequest.EFFECTIVE);
        request.setApprovalMode("approval");
        long requestId = requests.save(request).getId();

        // 旧格式：lesson_date 留空 = 自 effective_date 起持续覆盖
        Substitution legacy = new Substitution();
        legacy.setSemesterId(semesterId);
        legacy.setRequestId(requestId);
        legacy.setEntryId(entryId);
        legacy.setEffectiveDate(monday);
        legacy.setOriginalTeacherId(originalId);
        legacy.setSubstituteTeacherId(substituteId);
        legacy.setType(Substitution.TYPE_COVER);
        legacy.setStatus(Substitution.EFFECTIVE);
        long substitutionId = substitutions.save(legacy).getId();
        return new Seed(semesterId, requestId, substitutionId, monday);
    }

    private Seed seedLegacySwapWithAmbiguousMove() {
        Seed cover = seedLegacyCover();
        long semesterId = cover.semesterId();
        LocalDate monday = cover.date();

        var plan = new com.paiban.substitution.domain.SubstitutionPlan();
        plan.setRequestId(cover.requestId());
        plan.setSemesterId(semesterId);
        plan.setSnapshotHash("pg-conversion-test");
        plan.setRankingPreset("min-impact");
        plan.setStatus(com.paiban.substitution.domain.SubstitutionPlan.APPROVED);
        plan.setImpactDays(1);
        plan.setImpactTeachers(2);
        plan.setSoftPenalty(0);
        plan.setCycleLength(2);
        plan.setSameWeek(true);
        long planId = plans.save(plan).getId();

        Substitution swap = substitutions.findById(cover.substitutionId()).orElseThrow();
        swap.setType(Substitution.TYPE_SWAP);
        swap.setPlanId(planId);
        substitutions.save(swap);

        long entryId = swap.getEntryId();
        saveMove(planId, entryId, monday, monday.plusDays(1), 1);
        saveMove(planId, entryId, monday, monday.plusDays(2), 2);
        return new Seed(semesterId, cover.requestId(), cover.substitutionId(), monday);
    }

    private void saveMove(long planId, long entryId, LocalDate from, LocalDate to, int order) {
        TimetableEntry entry = entries.findById(entryId).orElseThrow();
        Period fromPeriod = periods.findById(entry.getPeriodId()).orElseThrow();
        SubstitutionPlanMove move = new SubstitutionPlanMove();
        move.setPlanId(planId);
        move.setEntryId(entryId);
        move.setClassId(entry.getAdminClassId());
        move.setCourseId(entry.getCourseId());
        move.setTeacherId(entry.getTeacherId());
        move.setRoomId(entry.getRoomId());
        move.setFromPeriodId(entry.getPeriodId());
        move.setFromDayOfWeek(fromPeriod.getDayOfWeek());
        move.setFromPeriodNo(fromPeriod.getPeriodNo());
        move.setFromDate(from);
        move.setToPeriodId(entry.getPeriodId());
        move.setToDayOfWeek(fromPeriod.getDayOfWeek());
        move.setToPeriodNo(fromPeriod.getPeriodNo());
        move.setToDate(to);
        move.setMoveOrder(order);
        planMoves.save(move);
    }
}
