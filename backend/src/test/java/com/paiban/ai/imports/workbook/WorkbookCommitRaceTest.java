package com.paiban.ai.imports.workbook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paiban.ai.imports.AiImportLog;
import com.paiban.ai.imports.AiImportLogRepository;
import com.paiban.ai.imports.ImportDtos.CommitResultDto;
import com.paiban.ai.imports.ImportDtos.RowDto;
import com.paiban.ai.imports.ImportMappingService.CourseDraft;
import com.paiban.ai.imports.ImportMappingService.TeacherDraft;
import com.paiban.common.web.ApiException;
import com.paiban.scheduling.domain.TimetableDraftRepository;
import com.paiban.support.ApiIntegrationTest;
import com.paiban.support.TestData;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

/**
 * Ticket 04: two "confirm import" requests that both read the preview before either writes
 * must not both create a draft. A real race depends on timing, so the test reproduces the
 * exact state both requests hold: two detached copies of the same PREVIEW row. Whichever
 * one saves second still carries the stale status, which is what the HTTP requests do.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class WorkbookCommitRaceTest extends ApiIntegrationTest {

    @Autowired
    private WorkbookImportFlow workbook;

    @Autowired
    private AiImportLogRepository importLogs;

    @Autowired
    private TimetableDraftRepository drafts;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void aStaleSecondCommitDoesNotWriteASecondDraft() throws Exception {
        seedFoundation();
        long warmDraft = commitOnce();
        assertThat(drafts.count()).isEqualTo(warmDraft);

        long sessionId = previewSession();
        AiImportLog first = importLogs.findById(sessionId).orElseThrow();
        AiImportLog stale = importLogs.findById(sessionId).orElseThrow();

        workbook.commit(first, "admin");
        long afterFirst = drafts.count();

        RuntimeException failure = catchThrowableOfType(
                () -> workbook.commit(stale, "admin"), RuntimeException.class);

        assertThat(drafts.count()).as("第二次提交不得再建一份草稿").isEqualTo(afterFirst);
        assertThat(isConflict(failure)).as("第二次提交必须明确冲突：%s", failure).isTrue();
    }

    @Test
    void twoConcurrentCommitsCreateExactlyOneDraft() throws Exception {
        seedFoundation();
        commitOnce();
        long before = drafts.count();

        long sessionId = previewSession();
        AiImportLog first = importLogs.findById(sessionId).orElseThrow();
        AiImportLog second = importLogs.findById(sessionId).orElseThrow();

        CyclicBarrier start = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Object> one = pool.submit(commitAt(start, first));
            Future<Object> two = pool.submit(commitAt(start, second));
            Object firstResult = one.get(30, TimeUnit.SECONDS);
            Object secondResult = two.get(30, TimeUnit.SECONDS);
            List<Object> results = List.of(firstResult, secondResult);

            assertThat(drafts.count())
                    .as("并发提交只能留下一份新草稿；两次结果：%s", results)
                    .isEqualTo(before + 1);
            assertThat(results)
                    .filteredOn(CommitResultDto.class::isInstance)
                    .hasSize(1);
            assertThat(results)
                    .filteredOn(ApiException.class::isInstance)
                    .singleElement()
                    .satisfies(result -> assertThat(((ApiException) result).getStatus())
                            .isEqualTo(HttpStatus.CONFLICT));
        } finally {
            pool.shutdownNow();
        }
        assertThat(drafts.count()).as("并发提交只能留下一份新草稿").isEqualTo(before + 1);
    }

    @Test
    void aFailedCommitReleasesTheClaimSoTheUserCanTryAgain() throws Exception {
        String admin = adminToken();
        long semesterId = seedFoundation(false);
        long sessionId = previewSession();

        ApiException failure = catchThrowableOfType(
                () -> workbook.commit(importLogs.findById(sessionId).orElseThrow(), "admin"),
                ApiException.class);

        assertThat(failure).isNotNull();
        assertThat(failure.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(importLogs.findById(sessionId).orElseThrow().getStatus())
                .as("失败后不能把会话永久卡在入库中")
                .isEqualTo(AiImportLog.PREVIEW);

        // Fix the missing prerequisite and confirm the same session again.
        generatePeriods(admin, semesterId);
        long before = drafts.count();
        CommitResultDto result =
                workbook.commit(importLogs.findById(sessionId).orElseThrow(), "admin");
        assertThat(result.draftId()).isPositive();
        assertThat(drafts.count()).isEqualTo(before + 1);
    }

    @Test
    void aClaimLeftByARestartGoesBackToPreview() throws Exception {
        seedFoundation();
        long sessionId = previewSession();
        AiImportLog log = importLogs.findById(sessionId).orElseThrow();
        log.setStatus(AiImportLog.COMMITTING);
        log.setAiExplanation("入库中");
        importLogs.save(log);

        workbook.recoverInterruptedSessions();

        AiImportLog recovered = importLogs.findById(sessionId).orElseThrow();
        assertThat(recovered.getStatus()).isEqualTo(AiImportLog.PREVIEW);
        assertThat(recovered.getAiExplanation()).contains("重新确认");
    }

    @Test
    void httpConcurrentCommitsAnswerOneSuccessAndOneConflict() throws Exception {
        String admin = adminToken();
        seedFoundation();
        long before = commitOnce();
        long sessionId = previewSession();

        CyclicBarrier start = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> call = () -> {
                start.await(10, TimeUnit.SECONDS);
                return as(admin).when()
                        .post("/api/ai/import/sessions/" + sessionId + "/commit")
                        .statusCode();
            };
            Future<Integer> one = pool.submit(call);
            Future<Integer> two = pool.submit(call);

            assertThat(List.of(one.get(30, TimeUnit.SECONDS), two.get(30, TimeUnit.SECONDS)))
                    .as("HTTP 并发提交必须是一个 200、一个 409")
                    .containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }
        assertThat(drafts.count()).as("HTTP 并发提交只能留下一份新草稿").isEqualTo(before + 1);
    }

    private Callable<Object> commitAt(CyclicBarrier start, AiImportLog log) {
        return () -> {
            start.await(10, TimeUnit.SECONDS);
            try {
                return workbook.commit(log, "admin");
            } catch (RuntimeException ex) {
                return ex;
            }
        };
    }

    /** The HTTP layer answers 409 for both the explicit conflict and a lost version race. */
    private boolean isConflict(RuntimeException failure) {
        return (failure instanceof ApiException api && api.getStatus() == HttpStatus.CONFLICT)
                || failure instanceof org.springframework.dao.OptimisticLockingFailureException;
    }

    /** A semester with periods (unless the test needs them missing), plus the named entities. */
    private long seedFoundation() {
        return seedFoundation(true);
    }

    private long seedFoundation(boolean withPeriods) {
        String admin = adminToken();
        long semesterId = TestData.semester(admin);
        if (withPeriods) {
            generatePeriods(admin, semesterId);
        }
        TestData.teacher(admin, "张三");
        TestData.course(admin, "语文");
        long gradeId = TestData.grade(admin, semesterId, "八年级");
        TestData.adminClass(admin, semesterId, gradeId, "八（1）");
        return semesterId;
    }

    private void generatePeriods(String admin, long semesterId) {
        as(admin)
                .body(Map.of(
                        "morningCount", 4, "afternoonCount", 4, "eveningCount", 0,
                        "days", List.of(1, 2, 3, 4, 5)))
                .when().post("/api/semesters/" + semesterId + "/periods/default")
                .then().statusCode(200);
    }

    /** The first commit creates the teaching class and assignment both racers reuse. */
    private long commitOnce() throws Exception {
        long sessionId = previewSession();
        workbook.commit(importLogs.findById(sessionId).orElseThrow(), "admin");
        return drafts.count();
    }

    private long previewSession() throws Exception {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("className", "八（1）");
        values.put("dayOfWeek", "星期一");
        values.put("periodNo", "1");
        values.put("courseName", "语文");
        values.put("teacherName", "张三");
        WorkbookPayload payload = new WorkbookPayload(
                List.of(new RowDto(values, Map.of(), List.of())),
                List.of(),
                true,
                "",
                List.of(),
                List.of(),
                List.of(),
                List.of(new TeacherDraft("张三", "", "", "", "语文")),
                List.of(new CourseDraft("语文", "语文")),
                List.of("八（1）"),
                Map.of());
        AiImportLog log = new AiImportLog();
        log.setUsername("admin");
        log.setImportType("excel");
        log.setTargetType("workbook");
        log.setStatus(AiImportLog.PREVIEW);
        log.setRowCount(1);
        log.setPreviewJson(mapper.writeValueAsString(payload));
        log.setCreatedAt(Instant.now());
        log.setUpdatedAt(Instant.now());
        return importLogs.save(log).getId();
    }
}
