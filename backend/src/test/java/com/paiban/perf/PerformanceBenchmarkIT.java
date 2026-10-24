package com.paiban.perf;

import static org.assertj.core.api.Assertions.assertThat;

import ai.timefold.solver.core.api.solver.Solver;
import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.solver.SolverConfig;
import com.paiban.scheduling.service.TimetableProblemBuilder;
import com.paiban.scheduling.solver.ConstraintSettings;
import com.paiban.scheduling.solver.ConstraintSettingsHolder;
import com.paiban.scheduling.solver.Lesson;
import com.paiban.scheduling.solver.PaibanConstraintProvider;
import com.paiban.scheduling.solver.TimetableSolution;
import com.paiban.support.ApiIntegrationTest;
import com.paiban.support.TestData;
import com.paiban.substitution.plan.PlanFixtures;
import com.paiban.substitution.plan.PlanSnapshot;
import com.paiban.substitution.plan.PlanningResult;
import com.paiban.substitution.plan.SwapPlanner;
import java.time.LocalDate;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.test.context.ActiveProfiles;

/**
 * Manual benchmark for the targets in ADR-0017. It is skipped unless
 * {@code -Dperf=true} is passed, because a full-size solve takes minutes and the
 * numbers depend on the machine:
 *
 * <pre>
 * cd backend && ./mvnw -Dperf=true -Dtest=PerformanceBenchmarkIT test
 * </pre>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@EnabledIfSystemProperty(named = "perf", matches = "true")
class PerformanceBenchmarkIT extends ApiIntegrationTest {

    private static final int CLASSES = 100;
    private static final int TEACHERS = 500;
    private static final int ROOMS = 200;
    private static final int COURSES = 8;
    private static final int WEEKLY_LESSONS = 3;
    private static final long SOLVE_LIMIT_MS = Duration.ofMinutes(5).toMillis();
    private static final long MOVE_TARGET_MS = 500;
    private static final long VIEW_TARGET_MS = 200;
    private static final long SCHOOL_VIEW_TARGET_MS = 2_000;

    @Autowired
    TimetableProblemBuilder problemBuilder;

    @Autowired
    JdbcTemplate jdbc;

    /**
     * The swap search budget is 200ms for one class, a 7-day window and six
     * teachers. This guards the budget from regressing; it runs only with -Dperf.
     */
    @Test
    void swapPlannerStaysWithinItsBudget() {
        PlanFixtures.Builder builder = PlanFixtures.builder();
        builder.adminClass(1L, "高一1班", 45, null);
        for (int day = 1; day <= 5; day++) {
            for (int periodNo = 1; periodNo <= 8; periodNo++) {
                long id = day * 100L + periodNo;
                builder.teacher(id, "老师" + id, "科目" + id);
                builder.course(id, "课程" + id, null);
                builder.room(id, "教室" + id, "NORMAL", 50);
                builder.lesson(1L, id, id, id, day, periodNo);
            }
        }
        PlanSnapshot snapshot = builder.build();
        long entryId = snapshot.baseline().stream()
                .filter(lesson -> lesson.dayOfWeek() == 1 && lesson.periodNo() == 1)
                .findFirst().orElseThrow().entryId();
        long start = System.nanoTime();
        PlanningResult result = SwapPlanner.plan(
                snapshot,
                new SwapPlanner.Request(1L, entryId, LocalDate.of(2025, 9, 1), 102L),
                SwapPlanner.Options.defaults());
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(result.candidates()).isNotEmpty();
        assertThat(elapsedMs).isLessThan(500);
    }

    @Test
    void adr0017Targets() throws Exception {
        String token = adminToken();
        long semesterId = TestData.semester(token);
        as(token)
                .body(Map.of("morningCount", 4, "afternoonCount", 4, "eveningCount", 0))
                .when().post("/api/semesters/" + semesterId + "/periods/default")
                .then().statusCode(200);
        long gradeId = TestData.grade(token, semesterId, "高一");

        List<Long> courseIds = new ArrayList<>();
        for (int i = 0; i < COURSES; i++) {
            courseIds.add(TestData.course(token, "课程" + i));
        }
        List<Long> teacherIds = new ArrayList<>();
        for (int i = 0; i < TEACHERS; i++) {
            teacherIds.add(TestData.teacher(token, "教师" + i));
        }
        List<Long> roomIds = new ArrayList<>();
        for (int i = 0; i < ROOMS; i++) {
            roomIds.add(TestData.room(token, "教室" + i));
        }
        List<Long> classIds = new ArrayList<>();
        for (int i = 0; i < CLASSES; i++) {
            classIds.add(TestData.adminClass(token, semesterId, gradeId, "高一" + i + "班"));
        }
        for (int classIndex = 0; classIndex < classIds.size(); classIndex++) {
            for (int courseIndex = 0; courseIndex < courseIds.size(); courseIndex++) {
                int teacherIndex = (classIndex * courseIds.size() + courseIndex) % teacherIds.size();
                as(token)
                        .body(Map.of(
                                "classId", classIds.get(classIndex),
                                "courseId", courseIds.get(courseIndex),
                                "teacherId", teacherIds.get(teacherIndex),
                                "weeklyLessons", WEEKLY_LESSONS))
                        .when().post("/api/assignments")
                        .then().statusCode(200);
            }
        }

        ConstraintSettingsHolder.set(ConstraintSettings.defaults());
        TimetableSolution problem = problemBuilder.build(semesterId);
        assertThat(problem.getLessons()).hasSize(CLASSES * COURSES * WEEKLY_LESSONS);

        SolverConfig config = new SolverConfig()
                .withSolutionClass(TimetableSolution.class)
                .withEntityClasses(Lesson.class)
                .withConstraintProviderClass(PaibanConstraintProvider.class)
                .withTerminationSpentLimit(Duration.ofMillis(SOLVE_LIMIT_MS));
        Solver<TimetableSolution> solver = SolverFactory.<TimetableSolution>create(config).buildSolver();
        AtomicLong zeroHardAt = new AtomicLong(-1);
        long solveStart = System.nanoTime();
        solver.addEventListener(event -> {
            TimetableSolution best = event.getNewBestSolution();
            if (best.getScore() != null && best.getScore().hardScore() == 0 && zeroHardAt.get() < 0) {
                zeroHardAt.set(millisSince(solveStart));
                solver.terminateEarly();
            }
        });
        TimetableSolution solution = solver.solve(problem);
        long solveMs = millisSince(solveStart);

        System.out.println("== ADR-0017 性能基准（100 班 / 500 教师 / 200 教室 / "
                + problem.getLessons().size() + " 节课）==");
        System.out.println("排课到零硬冲突: " + solveMs + " ms（目标 < " + SOLVE_LIMIT_MS + "）score="
                + solution.getScore());
        assertThat(solution.getScore()).as("求解没有产出分数").isNotNull();
        assertThat(solution.getScore().hardScore()).as("排课结果存在硬冲突").isZero();
        assertThat(solveMs).as("排课耗时").isLessThan(SOLVE_LIMIT_MS);

        long draftId = insertDraft(semesterId, solution);
        as(token).when().post("/api/timetable-drafts/" + draftId + "/publish").then().statusCode(200);

        Long lessonId = jdbc.queryForObject(
                "select id from draft_lesson where draft_id = ? order by id limit 1", Long.class, draftId);
        Long periodId = jdbc.queryForObject(
                "select id from period where semester_id = ? order by day_of_week, period_no limit 1",
                Long.class, semesterId);
        Map<String, Object> move = Map.of("lessonId", lessonId, "periodId", periodId);
        long moveMs = medianMs(20, () -> as(token)
                .body(move)
                .when().post("/api/timetable-drafts/" + draftId + "/validate-move")
                .then().statusCode(200));
        report("拖拽冲突校验", moveMs, MOVE_TARGET_MS);
        assertThat(moveMs).as("拖拽校验耗时").isLessThan(MOVE_TARGET_MS);

        long classMs = medianMs(20, () -> as(token)
                .when().get("/api/timetable/class/" + classIds.get(0))
                .then().statusCode(200));
        report("班级课表", classMs, VIEW_TARGET_MS);
        assertThat(classMs).as("班级课表耗时").isLessThan(VIEW_TARGET_MS);

        long teacherMs = medianMs(20, () -> as(token)
                .when().get("/api/timetable/teacher/" + teacherIds.get(0))
                .then().statusCode(200));
        report("教师课表", teacherMs, VIEW_TARGET_MS);
        assertThat(teacherMs).as("教师课表耗时").isLessThan(VIEW_TARGET_MS);

        long roomMs = medianMs(20, () -> as(token)
                .when().get("/api/timetable/room/" + roomIds.get(0))
                .then().statusCode(200));
        report("教室课表", roomMs, VIEW_TARGET_MS);
        assertThat(roomMs).as("教室课表耗时").isLessThan(VIEW_TARGET_MS);

        long schoolMs = medianMs(5, () -> as(token)
                .when().get("/api/timetable/school")
                .then().statusCode(200));
        report("全校总表", schoolMs, SCHOOL_VIEW_TARGET_MS);
        assertThat(schoolMs).as("全校总表耗时").isLessThan(SCHOOL_VIEW_TARGET_MS);

        long concurrentMs = concurrentClassViews(token, classIds.get(0), 50, 5);
        System.out.println("50 并发 × 5 次班级课表: " + concurrentMs + " ms（全部 200）");
        assertThat(concurrentMs).as("50 并发班级课表").isLessThan(10_000);
    }

    private long insertDraft(long semesterId, TimetableSolution solution) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(
                    "insert into timetable_draft "
                            + "(semester_id, name, status, score_hard, score_soft, created_by, created_at) "
                            + "values (?,?,?,?,?,?,?)",
                    new String[] {"id"});
            statement.setLong(1, semesterId);
            statement.setString(2, "性能基准");
            statement.setString(3, "DRAFT");
            statement.setInt(4, solution.getScore().hardScore());
            statement.setInt(5, solution.getScore().softScore());
            statement.setString(6, "benchmark");
            statement.setTimestamp(7, Timestamp.from(Instant.now()));
            return statement;
        }, keys);
        long draftId = keys.getKey().longValue();

        List<Object[]> rows = new ArrayList<>();
        for (Lesson lesson : solution.getLessons()) {
            rows.add(new Object[] {
                draftId,
                lesson.getAssignmentId(),
                lesson.getLessonIndex(),
                lesson.getCourseId(),
                lesson.getTeacherId(),
                lesson.getAdminClassId(),
                lesson.getStudentCount(),
                lesson.getPeriod().getId(),
                lesson.getRoom().getId()
            });
        }
        jdbc.batchUpdate(
                "insert into draft_lesson "
                        + "(draft_id, assignment_id, lesson_index, course_id, teacher_id, admin_class_id, "
                        + "student_count, period_id, room_id) values (?,?,?,?,?,?,?,?,?)",
                rows);
        return draftId;
    }

    private long medianMs(int runs, Runnable action) {
        action.run();
        List<Long> samples = new ArrayList<>();
        for (int i = 0; i < runs; i++) {
            long start = System.nanoTime();
            action.run();
            samples.add(millisSince(start));
        }
        Collections.sort(samples);
        return samples.get(samples.size() / 2);
    }

    private long concurrentClassViews(String token, long classId, int users, int requestsEach) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        ExecutorService pool = Executors.newFixedThreadPool(users);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(users);
        AtomicInteger failures = new AtomicInteger();
        long began = System.nanoTime();
        for (int user = 0; user < users; user++) {
            pool.execute(() -> {
                try {
                    start.await();
                    for (int request = 0; request < requestsEach; request++) {
                        HttpRequest httpRequest = HttpRequest.newBuilder()
                                .uri(URI.create("http://127.0.0.1:" + port + "/api/timetable/class/" + classId))
                                .header("Authorization", "Bearer " + token)
                                .GET()
                                .build();
                        HttpResponse<String> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString());
                        if (response.statusCode() != 200) {
                            failures.incrementAndGet();
                        }
                    }
                } catch (Exception ex) {
                    failures.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).as("并发请求未在 30 秒内完成").isTrue();
        pool.shutdownNow();
        assertThat(failures.get()).as("并发请求失败数").isZero();
        return millisSince(began);
    }

    private long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private void report(String label, long actual, long target) {
        System.out.println(label + ": " + actual + " ms（目标 < " + target + "）"
                + (actual < target ? " PASS" : " FAIL"));
    }
}
