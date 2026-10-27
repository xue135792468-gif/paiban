package com.paiban.ai.imports.workbook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paiban.ai.AiGateway;
import com.paiban.ai.AiRequest;
import com.paiban.ai.AiResult;
import com.paiban.ai.AiTaskType;
import com.paiban.ai.imports.AiImportLog;
import com.paiban.ai.imports.AiImportLogRepository;
import com.paiban.ai.imports.ImportDtos.RowDto;
import com.paiban.ai.imports.workbook.WorkbookPlan.SheetCategory;
import com.paiban.ai.imports.workbook.WorkbookPlan.SheetPlan;
import com.paiban.ai.imports.workbook.WorkbookRunPlan.SheetMeta;
import com.paiban.support.ApiIntegrationTest;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

/**
 * Whole-workbook backgrounding (tickets 01-03): the upload answers PARSING, the plan and
 * every finished range are persisted, stop keeps results, retry only replays failed ranges,
 * and a late answer can never overwrite a settled session.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "paiban.ai.import.batch-size=2")
@ActiveProfiles("test")
class WorkbookBackgroundImportTest extends ApiIntegrationTest {

    private static final String XLSX =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String DATA_MARKER = "数据（JSON）：";

    @Autowired
    private FakeAiGateway fake;

    @Autowired
    private AiImportLogRepository importLogs;

    @Autowired
    private WorkbookImportFlow workbook;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void resetFake() {
        fake.reset();
    }

    @AfterEach
    void releaseAndClean() {
        fake.releaseAll();
        for (AiImportLog log : importLogs.findAll()) {
            if (log.getFilePath() == null) {
                continue;
            }
            try {
                Files.deleteIfExists(Paths.get(log.getFilePath()));
            } catch (IOException ignored) {
                // best-effort cleanup of a temp upload
            }
        }
    }

    @Test
    void planningHasNoFakeTotalAndProgressResumesAfterThePlan() throws Exception {
        String admin = adminToken();
        fake.planAnswer = planJson(false);
        CountDownLatch plannerGate = new CountDownLatch(1);
        fake.plannerGate = plannerGate;
        // Hold the first extraction call too, so the EXTRACTING poll sees a real 0/3.
        fake.gatedKeys = Set.of("数据源#1");
        fake.extractionGate = new CountDownLatch(1);

        Response accepted = upload(admin, workbook(Map.of("数据源", fiveRows())));
        long sessionId = accepted.jsonPath().getLong("sessionId");

        // The planner is still blocked: the page must say PLANNING without inventing 3 batches.
        accepted.then().statusCode(200).body("status", equalTo("PARSING"));
        JsonNode planning = awaitSession(admin, sessionId);
        assertThat(planning.path("status").asText()).isEqualTo("PARSING");
        assertThat(planning.path("progress").path("phase").asText()).isEqualTo("PLANNING");
        assertThat(planning.path("progress").path("total").asInt()).isZero();
        assertThat(planning.path("workbook").isNull() || planning.path("workbook").isMissingNode())
                .isTrue();

        plannerGate.countDown();
        JsonNode midway = awaitUntil(admin, sessionId,
                node -> "EXTRACTING".equals(node.path("progress").path("phase").asText())
                        && node.path("progress").path("currentBatch").asText().contains("数据源"));
        assertThat(midway.path("progress").path("total").asInt()).isEqualTo(3);
        assertThat(midway.path("progress").path("completed").asInt()).isZero();
        fake.extractionGate.countDown();

        JsonNode settled = awaitSettled(admin, sessionId);
        assertThat(settled.path("status").asText()).isEqualTo("PREVIEW");
        assertThat(settled.path("rows").size()).isEqualTo(5);
        assertThat(settled.path("progress").path("completed").asInt()).isEqualTo(3);
    }

    @Test
    void aFailedBatchDoesNotStopTheOthersAndBlocksTheCommit() throws Exception {
        String admin = adminToken();
        fake.planAnswer = planJson(false);
        fake.failKeys = Set.of("数据源#1");

        long sessionId = upload(admin, workbook(Map.of("数据源", fiveRows())))
                .jsonPath().getLong("sessionId");

        JsonNode partial = awaitSettled(admin, sessionId);
        assertThat(partial.path("status").asText()).isEqualTo("PARTIAL");
        assertThat(partial.path("progress").path("completed").asInt()).isEqualTo(2);
        assertThat(partial.path("progress").path("failed").asInt()).isEqualTo(1);
        assertThat(partial.path("failedBatches").size()).isEqualTo(1);
        assertThat(partial.path("failedBatches").get(0).path("label").asText())
                .contains("工作表「数据源」").contains("第 1-2 行");
        // Rows 3-5 came from the two batches that did run; the first range is missing.
        assertThat(partial.path("rows").size()).isEqualTo(3);

        as(admin).when().post("/api/ai/import/sessions/" + sessionId + "/commit")
                .then().statusCode(409);
    }

    @Test
    void stopKeepsFinishedRangesListsTheRestAndIgnoresTheLateAnswer() throws Exception {
        String admin = adminToken();
        fake.planAnswer = planJson(false);
        // The first batch finishes normally; the second is still in flight when the stop lands.
        fake.gatedKeys = Set.of("数据源#3");
        CountDownLatch gate = new CountDownLatch(1);
        fake.extractionGate = gate;

        long sessionId = upload(admin, workbook(Map.of("数据源", fiveRows())))
                .jsonPath().getLong("sessionId");
        awaitUntil(admin, sessionId, node ->
                node.path("progress").path("completed").asInt() == 1
                        && "PARSING".equals(node.path("status").asText()));

        as(admin).when().post("/api/ai/import/sessions/" + sessionId + "/stop")
                .then().statusCode(200);
        JsonNode stopped = awaitSession(admin, sessionId);
        assertThat(stopped.path("status").asText()).isEqualTo("PARTIAL");
        assertThat(stopped.path("progress").path("completed").asInt()).isEqualTo(1);
        assertThat(stopped.path("failedBatches").size()).isEqualTo(2);
        int rowsAtStop = stopped.path("rows").size();
        assertThat(rowsAtStop).isEqualTo(2);

        // The in-flight call returns after the stop: audit only, never a resurrected preview.
        gate.countDown();
        Thread.sleep(400);
        JsonNode afterLate = awaitSession(admin, sessionId);
        assertThat(afterLate.path("status").asText()).isEqualTo("PARTIAL");
        assertThat(afterLate.path("rows").size()).isEqualTo(rowsAtStop);
        assertThat(afterLate.path("progress").path("completed").asInt()).isEqualTo(1);
        assertThat(importLogs.findById(sessionId).orElseThrow().getAiRawResponse())
                .contains("values");

        // A second stop is a conflict, not a silent success; an incomplete preview cannot submit.
        as(admin).when().post("/api/ai/import/sessions/" + sessionId + "/stop")
                .then().statusCode(409);
        as(admin).when().post("/api/ai/import/sessions/" + sessionId + "/commit")
                .then().statusCode(409);
    }

    @Test
    void retryAfterStopReplaysOnlyTheUncoveredRanges() throws Exception {
        String admin = adminToken();
        fake.planAnswer = planJson(false);
        fake.gatedKeys = Set.of("数据源#3");
        CountDownLatch gate = new CountDownLatch(1);
        fake.extractionGate = gate;

        long sessionId = upload(admin, workbook(Map.of("数据源", fiveRows())))
                .jsonPath().getLong("sessionId");
        awaitUntil(admin, sessionId, node ->
                node.path("progress").path("completed").asInt() == 1
                        && "PARSING".equals(node.path("status").asText()));

        as(admin).when().post("/api/ai/import/sessions/" + sessionId + "/stop")
                .then().statusCode(200);
        gate.countDown();
        JsonNode stopped = awaitSettled(admin, sessionId);
        assertThat(stopped.path("status").asText()).isEqualTo("PARTIAL");

        // Only the recovered (not the successful) range is paid for again.
        int doneCalls = fake.calls("数据源#1");
        fake.gatedKeys = Set.of();
        as(admin).when().post("/api/ai/import/sessions/" + sessionId + "/retry")
                .then().statusCode(200);
        JsonNode settled = awaitSettled(admin, sessionId);
        assertThat(settled.path("status").asText()).isEqualTo("PREVIEW");
        assertThat(settled.path("rows").size()).isEqualTo(5);
        assertThat(fake.calls("数据源#1")).isEqualTo(doneCalls);
        assertThat(uniqueSlots(settled)).isEqualTo(5);
    }

    @Test
    void stopAfterPlanningBeforeAnyBatchStaysPartialAndCanRetry() throws Exception {
        String admin = adminToken();
        fake.planAnswer = planJson(false);
        fake.gatedKeys = Set.of("数据源#1");
        CountDownLatch gate = new CountDownLatch(1);
        fake.extractionGate = gate;

        long sessionId = upload(admin, workbook(Map.of("数据源", fiveRows())))
                .jsonPath().getLong("sessionId");
        awaitUntil(admin, sessionId, node ->
                "EXTRACTING".equals(node.path("progress").path("phase").asText())
                        && node.path("progress").path("completed").asInt() == 0);

        as(admin).when().post("/api/ai/import/sessions/" + sessionId + "/stop")
                .then().statusCode(200);
        JsonNode stopped = awaitSession(admin, sessionId);
        assertThat(stopped.path("status").asText()).isEqualTo("PARTIAL");
        assertThat(stopped.path("progress").path("completed").asInt()).isZero();
        assertThat(stopped.path("failedBatches").size()).isEqualTo(3);

        gate.countDown();
        Thread.sleep(300);
        fake.gatedKeys = Set.of();
        as(admin).when().post("/api/ai/import/sessions/" + sessionId + "/retry")
                .then().statusCode(200);
        JsonNode settled = awaitSettled(admin, sessionId);
        assertThat(settled.path("status").asText()).isEqualTo("PREVIEW");
        assertThat(settled.path("rows").size()).isEqualTo(5);
    }

    @Test
    void anotherUserCannotStopRetryOrAbandonTheSession() throws Exception {
        String admin = adminToken();
        fake.planAnswer = planJson(false);
        fake.gatedKeys = Set.of("数据源#1");
        CountDownLatch gate = new CountDownLatch(1);
        fake.extractionGate = gate;

        long sessionId = upload(admin, workbook(Map.of("数据源", fiveRows())))
                .jsonPath().getLong("sessionId");
        awaitUntil(admin, sessionId, node ->
                node.path("progress").path("currentBatch").asText().contains("数据源"));

        createUser(admin, "importviewer", "pass123", "teacher");
        String viewer = login("importviewer", "pass123");
        for (String action : List.of("stop", "retry", "abandon")) {
            as(viewer).when().post("/api/ai/import/sessions/" + sessionId + "/" + action)
                    .then().statusCode(403);
        }

        gate.countDown();
        awaitSettled(admin, sessionId);
    }

    @Test
    void abandonEndsTheSessionAndALateAnswerOnlyStaysInTheAudit() throws Exception {
        String admin = adminToken();
        fake.planAnswer = planJson(false);
        fake.gatedKeys = Set.of("数据源#1");
        CountDownLatch gate = new CountDownLatch(1);
        fake.extractionGate = gate;

        long sessionId = upload(admin, workbook(Map.of("数据源", fiveRows())))
                .jsonPath().getLong("sessionId");
        awaitUntil(admin, sessionId, node ->
                node.path("progress").path("currentBatch").asText().contains("数据源"));

        as(admin).when().post("/api/ai/import/sessions/" + sessionId + "/abandon")
                .then().statusCode(200)
                .body("status", equalTo("ABANDONED"));

        gate.countDown();
        awaitUntil(admin, sessionId, node -> "ABANDONED".equals(node.path("status").asText()));
        Thread.sleep(400);
        JsonNode abandoned = awaitSession(admin, sessionId);
        assertThat(abandoned.path("status").asText()).isEqualTo("ABANDONED");
        assertThat(abandoned.path("rows").size()).isZero();
        assertThat(importLogs.findById(sessionId).orElseThrow().getAiRawResponse())
                .contains("values");
        as(admin).when().post("/api/ai/import/sessions/" + sessionId + "/commit")
                .then().statusCode(409);
    }

    @Test
    void retryReplaysOnlyTheFailedRangeAndKeepsCrossSheetDedup() throws Exception {
        String admin = adminToken();
        fake.planAnswer = planJson(true);
        // Fail the whole 班级课表 sheet on the first pass; its range overlaps 数据源 on one slot.
        fake.failKeys = Set.of("班级课表#1");

        Map<String, List<List<String>>> sheets = new LinkedHashMap<>();
        sheets.put("数据源", List.of(
                List.of("八（1）|星期一|1", "语文", "张三"),
                List.of("八（1）|星期一|2", "数学", "李四"),
                List.of("八（1）|星期一|3", "英语", "王五")));
        sheets.put("班级课表", List.of(
                List.of("班级", "星期", "节次", "课程", "教师"),
                List.of("八（1）", "星期一", "1", "语文", "张三"),
                List.of("八（1）", "星期二", "1", "科学", "赵六")));
        long sessionId = upload(admin, workbook(sheets)).jsonPath().getLong("sessionId");

        JsonNode partial = awaitSettled(admin, sessionId);
        assertThat(partial.path("status").asText()).isEqualTo("PARTIAL");
        int dataSourceFirst = fake.calls("数据源#1");
        int dataSourceSecond = fake.calls("数据源#3");
        int classSheetFirst = fake.calls("班级课表#1");
        int plannerCalls = fake.plannerCalls.get();

        fake.failKeys = Set.of();
        as(admin).when().post("/api/ai/import/sessions/" + sessionId + "/retry")
                .then().statusCode(200)
                .body("status", equalTo("PARSING"));

        JsonNode settled = awaitSettled(admin, sessionId);
        assertThat(settled.path("status").asText()).isEqualTo("PREVIEW");
        assertThat(settled.path("rows").size()).isEqualTo(4);
        assertThat(uniqueSlots(settled)).isEqualTo(4);
        // Successful ranges and the plan were not paid for a second time.
        assertThat(fake.calls("数据源#1")).isEqualTo(dataSourceFirst);
        assertThat(fake.calls("数据源#3")).isEqualTo(dataSourceSecond);
        assertThat(fake.calls("班级课表#1")).isEqualTo(classSheetFirst + 1);
        assertThat(fake.plannerCalls.get()).isEqualTo(plannerCalls);
        assertThat(importLogs.findById(sessionId).orElseThrow().getStatus())
                .isEqualTo(AiImportLog.PREVIEW);
    }

    @Test
    void planningFailureOffersReplanAndTheReplanRunsAgain() throws Exception {
        String admin = adminToken();
        fake.planFails = true;
        long sessionId = upload(admin, workbook(Map.of("数据源", fiveRows())))
                .jsonPath().getLong("sessionId");

        JsonNode failed = awaitSettled(admin, sessionId);
        assertThat(failed.path("status").asText()).isEqualTo("FAILED");
        assertThat(failed.path("explanation").asText()).contains("stub 规划失败");
        assertThat(failed.path("workbook").isNull() || failed.path("workbook").isMissingNode())
                .isTrue();

        fake.planFails = false;
        fake.planAnswer = planJson(false);
        as(admin).when().post("/api/ai/import/sessions/" + sessionId + "/reparse")
                .then().statusCode(200)
                .body("status", equalTo("PARSING"));

        JsonNode replanned = awaitSettled(admin, sessionId);
        assertThat(replanned.path("status").asText()).isEqualTo("PREVIEW");
        assertThat(replanned.path("rows").size()).isEqualTo(5);
    }

    @Test
    void restartRecoveryTurnsUnfinishedRangesIntoRetryableFailures() {
        AiImportLog log = new AiImportLog();
        log.setImportType("excel");
        log.setTargetType("workbook");
        log.setUsername("admin");
        log.setStatus(AiImportLog.PARSING);
        log.setRunId("dead-run");
        log.setPlanVersion(1);
        log.setTotalBatches(2);
        log.setCompletedBatches(1);
        log.setRowCount(2);
        log.setCreatedAt(Instant.now());
        log.setUpdatedAt(Instant.now());
        WorkbookPlan plan = new WorkbookPlan(
                List.of(new SheetPlan("数据源", SheetCategory.TIMETABLE, Map.of(
                        "className", "列1·班级", "dayOfWeek", "列1·星期",
                        "periodNo", "列1·节次", "courseName", "列2",
                        "teacherName", "列3"), "", true)),
                true, "测试计划");
        log.setPlanJson(json(new WorkbookRunPlan(
                1, plan, List.of(new SheetMeta("数据源", null, 4)), List.of())));
        WorkbookBatchStore store = new WorkbookBatchStore(mapper);
        WorkbookBatchStore.Work doneWork = new WorkbookBatchStore.Work(
                "数据源#1-2@1", 1, "数据源", "timetable",
                plan.sheets().get(0).columns(), 1, 1, 4,
                List.of(List.of("八（1）", "星期一", "1", "语文", "张三"),
                        List.of("八（1）", "星期一", "2", "数学", "李四")));
        WorkbookBatchStore.State done = WorkbookBatchStore.State.pending(doneWork);
        store.replace(done, WorkbookBatchStore.Outcome.parsed(List.of(
                row("八（1）", "星期一", "1", "语文", "张三"),
                row("八（1）", "星期一", "2", "数学", "李四"))), "{\"rows\":[]}");
        WorkbookBatchStore.Work pendingWork = new WorkbookBatchStore.Work(
                "数据源#3-4@1", 2, "数据源", "timetable",
                plan.sheets().get(0).columns(), 3, 1, 4,
                List.of(List.of("八（1）", "星期一", "3", "英语", "王五"),
                        List.of("八（1）", "星期一", "4", "科学", "赵六")));
        WorkbookBatchStore.State pending = WorkbookBatchStore.State.pending(pendingWork);
        log.setBatchesJson(store.write(List.of(done, pending)));
        log.setPreviewJson(json(new WorkbookPayload(
                List.of(row("八（1）", "星期一", "1", "语文", "张三")),
                List.of(), true, "测试", List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), Map.of())));
        long sessionId = importLogs.save(log).getId();

        workbook.recoverInterruptedSessions();

        AiImportLog recovered = importLogs.findById(sessionId).orElseThrow();
        assertThat(recovered.getStatus()).isEqualTo(AiImportLog.PARTIAL);
        assertThat(recovered.getRunId()).isNull();
        assertThat(recovered.getCompletedBatches()).isEqualTo(1);
        assertThat(recovered.getAiExplanation()).contains("服务重启");
        assertThat(recovered.getBatchesJson()).contains("\"status\":\"FAILED\"");
    }

    // ---------------------------------------------------------------- fixtures

    private List<List<String>> fiveRows() {
        List<List<String>> rows = new ArrayList<>();
        for (int period = 1; period <= 5; period++) {
            rows.add(List.of("八（1）|星期一|" + period,
                    List.of("语文", "数学", "英语").get((period - 1) % 3), "张三"));
        }
        return rows;
    }

    private byte[] workbook(Map<String, List<List<String>>> sheets) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (Map.Entry<String, List<List<String>>> entry : sheets.entrySet()) {
                Sheet sheet = workbook.createSheet(entry.getKey());
                List<List<String>> rows = entry.getValue();
                for (int r = 0; r < rows.size(); r++) {
                    Row row = sheet.createRow(r);
                    List<String> values = rows.get(r);
                    for (int c = 0; c < values.size(); c++) {
                        row.createCell(c).setCellValue(values.get(c));
                    }
                }
            }
            workbook.write(out);
            return out.toByteArray();
        }
    }

    private String planJson(boolean withSecondSheet) {
        Map<String, Object> packed = new LinkedHashMap<>();
        packed.put("className", "列1·班级");
        packed.put("dayOfWeek", "列1·星期");
        packed.put("periodNo", "列1·节次");
        packed.put("courseName", "列2");
        packed.put("teacherName", "列3");
        List<Map<String, Object>> sheets = new ArrayList<>();
        sheets.add(sheetPlan("数据源", "timetable", packed));
        if (withSecondSheet) {
            Map<String, Object> wide = new LinkedHashMap<>();
            wide.put("className", "班级");
            wide.put("dayOfWeek", "星期");
            wide.put("periodNo", "节次");
            wide.put("courseName", "课程");
            wide.put("teacherName", "教师");
            sheets.add(sheetPlan("班级课表", "timetable", wide));
        }
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("sheets", sheets);
        args.put("explanation", "测试用计划");
        return json(Map.of("tool", "finish", "args", args));
    }

    private Map<String, Object> sheetPlan(
            String sheet, String category, Map<String, Object> columns) {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("sheet", sheet);
        plan.put("category", category);
        plan.put("columns", columns);
        plan.put("note", "");
        return plan;
    }

    private RowDto row(String className, String day, String period, String course, String teacher) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("className", className);
        values.put("dayOfWeek", day);
        values.put("periodNo", period);
        values.put("courseName", course);
        values.put("teacherName", teacher);
        values.put("roomName", "");
        Map<String, Double> confidence = new LinkedHashMap<>();
        values.keySet().forEach(field -> confidence.put(field, 1.0));
        return new RowDto(values, confidence, List.of());
    }

    private int uniqueSlots(JsonNode preview) {
        Set<String> slots = new java.util.HashSet<>();
        for (JsonNode row : preview.path("rows")) {
            JsonNode values = row.path("values");
            slots.add(values.path("className").asText() + "|"
                    + values.path("dayOfWeek").asText() + "|"
                    + values.path("periodNo").asText());
        }
        return slots.size();
    }

    // ------------------------------------------------------------------ helpers

    private Response upload(String token, byte[] bytes) {
        return RestAssured.given()
                .header("Authorization", "Bearer " + token)
                .multiPart("file", "后台工作簿.xlsx", bytes, XLSX)
                .queryParam("target", "workbook")
                .when().post("/api/ai/import/excel");
    }

    private JsonNode awaitSession(String token, long sessionId) {
        Response response = as(token).when().get("/api/ai/import/sessions/" + sessionId);
        response.then().statusCode(200);
        try {
            return mapper.readTree(response.body().asString());
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    private JsonNode awaitUntil(
            String token, long sessionId, java.util.function.Predicate<JsonNode> condition) {
        long deadline = System.currentTimeMillis() + 30_000;
        JsonNode last = null;
        while (System.currentTimeMillis() < deadline) {
            last = awaitSession(token, sessionId);
            if (condition.test(last)) {
                return last;
            }
            sleep(100);
        }
        throw new AssertionError("等待条件超时，最后一次响应: " + last);
    }

    private JsonNode awaitSettled(String token, long sessionId) {
        return awaitUntil(token, sessionId,
                node -> !"PARSING".equals(node.path("status").asText()));
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    @TestConfiguration
    static class FakeAiConfig {

        @Bean
        @Primary
        FakeAiGateway fakeAiGateway() {
            return new FakeAiGateway();
        }
    }

    static class FakeAiGateway implements AiGateway {

        private final ObjectMapper mapper = new ObjectMapper();
        volatile String planAnswer = "{\"tool\":\"finish\",\"args\":{\"sheets\":[]}}";
        volatile boolean planFails;
        volatile CountDownLatch plannerGate;
        volatile CountDownLatch extractionGate;
        volatile Set<String> gatedKeys = Set.of();
        volatile Set<String> failKeys = Set.of();
        final AtomicInteger plannerCalls = new AtomicInteger();
        private final Map<String, Integer> extractionCalls = new ConcurrentHashMap<>();

        void reset() {
            planAnswer = "{\"tool\":\"finish\",\"args\":{\"sheets\":[]}}";
            planFails = false;
            plannerGate = null;
            extractionGate = null;
            gatedKeys = Set.of();
            failKeys = Set.of();
            plannerCalls.set(0);
            extractionCalls.clear();
        }

        void releaseAll() {
            if (plannerGate != null) {
                plannerGate.countDown();
            }
            if (extractionGate != null) {
                extractionGate.countDown();
            }
        }

        int calls(String key) {
            return extractionCalls.getOrDefault(key, 0);
        }

        @Override
        public AiResult chat(AiRequest request) {
            String prompt = request.messages().get(request.messages().size() - 1).content();
            boolean planner = request.messages().stream()
                    .anyMatch(message -> message.content().contains("只读工具"));
            if (planner) {
                plannerCalls.incrementAndGet();
                await(plannerGate);
                if (planFails) {
                    return failure("stub 规划失败");
                }
                return ok(planAnswer);
            }
            try {
                int marker = prompt.indexOf(DATA_MARKER);
                JsonNode payload = mapper.readTree(prompt.substring(marker + DATA_MARKER.length()).trim());
                String key = payload.path("sheet").asText() + "#" + payload.path("firstRow").asInt();
                extractionCalls.merge(key, 1, Integer::sum);
                if (gatedKeys.contains(key)) {
                    await(extractionGate);
                }
                if (failKeys.contains(key)) {
                    return failure("stub 无法解析 " + key);
                }
                return ok(extractionAnswer(payload));
            } catch (Exception ex) {
                return failure("stub 无法读取请求：" + ex.getMessage());
            }
        }

        private String extractionAnswer(JsonNode payload) {
            List<String> fields = new ArrayList<>();
            payload.path("columns").fieldNames().forEachRemaining(fields::add);
            List<Map<String, Object>> rows = new ArrayList<>();
            for (JsonNode row : payload.path("rows")) {
                Map<String, String> values = new LinkedHashMap<>();
                Map<String, Double> confidence = new LinkedHashMap<>();
                for (int i = 0; i < fields.size(); i++) {
                    values.put(fields.get(i), row.path(i).asText(""));
                    confidence.put(fields.get(i), 1.0);
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("values", values);
                item.put("confidence", confidence);
                rows.add(item);
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("rows", rows);
            body.put("explanation", "");
            try {
                return mapper.writeValueAsString(body);
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        }

        private static void await(CountDownLatch latch) {
            if (latch == null) {
                return;
            }
            try {
                latch.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }

        private static AiResult ok(String payload) {
            return new AiResult(
                    true, payload, null, null, 1L, "fake", 2L, "fake-model",
                    10, 20, 5L, payload);
        }

        private static AiResult failure(String message) {
            return new AiResult(
                    false, null, "stub", message, 1L, "fake", 2L, "fake-model",
                    10, 0, 5L, null);
        }

        @Override
        public AiResult chatWithVision(AiRequest request) {
            return chat(request);
        }

        @Override
        public boolean configured() {
            return true;
        }

        @Override
        public boolean configured(AiTaskType taskType) {
            return true;
        }
    }
}
