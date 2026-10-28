package com.paiban.ai.imports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paiban.ai.AiGateway;
import com.paiban.ai.AiRequest;
import com.paiban.ai.AiResult;
import com.paiban.ai.AiTaskType;
import com.paiban.foundation.domain.AdminClassRepository;
import com.paiban.foundation.domain.GradeRepository;
import com.paiban.foundation.domain.TeacherRepository;
import com.paiban.identity.domain.PermissionRepository;
import com.paiban.identity.domain.Permissions;
import com.paiban.identity.domain.Role;
import com.paiban.identity.domain.RoleRepository;
import com.paiban.scheduling.domain.DraftLessonRepository;
import com.paiban.scheduling.domain.TimetableDraftRepository;
import com.paiban.support.ApiIntegrationTest;
import com.paiban.support.TestData;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AiImportIntegrationTest extends ApiIntegrationTest {

    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Autowired
    private FakeAiGateway fake;

    @Autowired
    private AiImportLogRepository importLogs;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private PermissionRepository permissionRepository;

    @Autowired
    private AiImportService importService;

    @Autowired
    private TimetableDraftRepository drafts;

    @Autowired
    private DraftLessonRepository draftLessons;

    @Autowired
    private TeacherRepository teachers;

    @Autowired
    private AdminClassRepository adminClasses;

    @Autowired
    private GradeRepository grades;

    @BeforeEach
    void resetFake() {
        fake.reset();
    }

    @AfterEach
    void cleanUploadedSources() {
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
    void messyExcelBecomesPreviewThenImportsTwentyThreeTeachers() throws Exception {
        String admin = adminToken();
        fake.response = aiResponse(teachers(23, "语文", 0.5));

        Response preview = upload(admin, "/api/ai/import/excel?target=teacher", "教师名单.xlsx", excelBytes(23), XLSX);
        preview.then().statusCode(200)
                .body("targetType", equalTo("teacher"))
                .body("columns", hasSize(5))
                .body("rows", hasSize(23))
                .body("mappings[0].target", equalTo("name"))
                .body("rows[0].values.name", equalTo("教师1"))
                .body("rows[0].lowConfidence", org.hamcrest.Matchers.hasItem("subject"));

        long sessionId = preview.jsonPath().getLong("sessionId");

        List<Map<String, Object>> rows = preview.jsonPath().getList("rows");
        @SuppressWarnings("unchecked")
        Map<String, Object> firstValues = (Map<String, Object>) rows.get(0).get("values");
        firstValues.put("name", "教师一");
        as(admin).body(Map.of("rows", rows))
                .when().put("/api/ai/import/sessions/" + sessionId + "/rows")
                .then().statusCode(200)
                .body("status", equalTo("EDITED"))
                .body("rows[0].values.name", equalTo("教师一"));

        as(admin).when().post("/api/ai/import/sessions/" + sessionId + "/commit")
                .then().statusCode(200)
                .body("imported", equalTo(23))
                .body("draftId", org.hamcrest.Matchers.nullValue());

        List<Map<String, Object>> teacherRows = as(admin).when().get("/api/teachers")
                .then().statusCode(200).extract().jsonPath().getList("$");
        assertThat(teacherRows).hasSize(23);
        assertThat(teacherRows).anyMatch(row -> "教师一".equals(row.get("name")));

        AiImportLog stored = importLogs.findById(sessionId).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(AiImportLog.COMMITTED);
        // The original AI answer survives the edit; the confirmed copy carries the correction.
        assertThat(stored.getPreviewJson()).contains("教师1").doesNotContain("教师一");
        assertThat(stored.getConfirmedJson()).contains("教师一").contains("教师23");
        assertThat(stored.getResultJson()).contains("\"imported\":23").contains("教师一");
    }

    @Test
    void timetableTargetKeepsItsOwnFieldsAndSendsTheRawGrid() throws Exception {
        String admin = adminToken();
        fake.response = aiResponse(List.of(timetableRow()));

        Response preview = upload(
                admin, "/api/ai/import/excel?target=timetable", "一年级课表.xlsx", timetableExcelBytes(), XLSX);

        preview.then().statusCode(200)
                .body("targetType", equalTo("timetable"))
                .body("columns", hasSize(6))
                .body("columns[0].field", equalTo("className"))
                .body("rows", hasSize(1))
                .body("rows[0].values.className", equalTo("一年级1班"))
                .body("rows[0].values.dayOfWeek", equalTo("周一"))
                .body("rows[0].values.periodNo", equalTo("1"))
                .body("rows[0].values.courseName", equalTo("语文"))
                .body("rows[0].values.teacherName", equalTo("张三"))
                .body("rows[0].values.roomName", equalTo("101"));

        String prompt = fake.lastRequest.messages().get(1).content();
        assertThat(prompt).contains("2026 春季课表").contains("原始网格").contains("每一行代表一节课");
    }

    @Test
    void aMultiSheetWorkbookOnASingleTargetPathPointsAtTheWorkbookImport() throws Exception {
        String admin = adminToken();

        Response upload = upload(
                admin, "/api/ai/import/excel?target=timetable", "整本课表.xlsx",
                multiSheetWorkbookBytes(), XLSX);

        // The single-target path cannot represent several sheets, but the workbook path
        // can; the refusal has to name that way out instead of asking for a split file.
        upload.then().statusCode(400)
                .body("message", containsString("整本工作簿"));
        assertThat(fake.calls).isZero();
    }

    @Test
    void aLargeTimetableIsParsedOneClassPerCallAndMerged() throws Exception {
        String admin = adminToken();
        timetableFixture(admin);
        fake.responder = request -> aiResponse(List.of(
                timetableRow("语文", "周一", String.valueOf(fake.calls), "张三")));

        Response upload = upload(
                admin, "/api/ai/import/excel?target=timetable", "一年级课表.xlsx",
                timetableExcelBytes(4), XLSX);
        upload.then().statusCode(200).body("targetType", equalTo("timetable"));
        io.restassured.path.json.JsonPath done = awaitSession(admin, upload.jsonPath().getLong("sessionId"));

        // Four data rows: one AI call each, merged and de-duplicated for the preview.
        assertThat(done.getString("status")).isEqualTo("PREVIEW");
        assertThat(done.getList("rows")).hasSize(4);
        assertThat(fake.calls).isEqualTo(4);
        assertThat(fake.lastRequest.messages().get(1).content())
                .contains("rowsToParse")
                .contains("headerContext");
    }

    @Test
    void mergedPeriodHeaderIsContextNotTheFirstDataChunk() throws Exception {
        String admin = adminToken();
        timetableFixture(admin);
        fake.responder = request -> aiResponse(List.of(
                timetableRow("语文", "周一", String.valueOf(fake.calls), "张三")));

        Response upload = upload(
                admin, "/api/ai/import/excel?target=timetable", "年级课表.xlsx",
                timetableExcelWithPeriodRow(4), XLSX);
        io.restassured.path.json.JsonPath done = awaitSession(admin, upload.jsonPath().getLong("sessionId"));

        assertThat(done.getList("rows")).hasSize(4);
        assertThat(fake.calls).isEqualTo(4);
        assertThat(fake.userMessages.get(0))
                .contains("\"rowsToParse\":[[\"八（1）\"")
                .contains("\"headerContext\"");
    }

    // ------------------------------------------------ background parsing (ticket 01)

    @Test
    void aLargeTimetableReturnsParsingAtOnceAndBecomesPreviewWhenTheBatchesFinish() throws Exception {
        String admin = adminToken();
        timetableFixture(admin);
        fake.latencyMs = 150;
        fake.responder = request -> aiResponse(List.of(
                timetableRow("语文", "周一", String.valueOf(fake.calls), "张三")));

        Response upload = upload(
                admin, "/api/ai/import/excel?target=timetable", "一年级课表.xlsx",
                timetableExcelBytes(4), XLSX);

        // The upload returns before the AI is done, with the batch plan already visible.
        upload.then().statusCode(200)
                .body("sessionId", org.hamcrest.Matchers.notNullValue())
                .body("status", equalTo("PARSING"))
                .body("progress.total", equalTo(4))
                .body("progress.completed", equalTo(0))
                .body("progress.failed", equalTo(0))
                .body("rows", hasSize(0));
        long sessionId = upload.jsonPath().getLong("sessionId");
        assertThat(fake.calls).isLessThan(4);

        io.restassured.path.json.JsonPath done = awaitSession(admin, sessionId);

        assertThat(done.getString("status")).isEqualTo("PREVIEW");
        assertThat(done.getList("rows")).hasSize(4);
        assertThat(done.getMap("progress"))
                .containsEntry("total", 4)
                .containsEntry("completed", 4)
                .containsEntry("failed", 0);
        assertThat((Object) done.get("progress.currentBatch")).isNull();
        assertThat(done.getList("failedBatches")).isEmpty();
        assertThat(fake.calls).isEqualTo(4);
    }

    @Test
    void everyFinishedBatchIsPersistedBeforeTheSessionCompletes() throws Exception {
        String admin = adminToken();
        timetableFixture(admin);
        fake.latencyMs = 250;
        fake.responder = request -> aiResponse(List.of(
                timetableRow("语文", "周一", String.valueOf(fake.calls), "张三")));

        Response upload = upload(
                admin, "/api/ai/import/excel?target=timetable", "一年级课表.xlsx",
                timetableExcelBytes(4), XLSX);
        long sessionId = upload.jsonPath().getLong("sessionId");

        AiImportLog midway = null;
        for (int i = 0; i < 200; i++) {
            AiImportLog current = importLogs.findById(sessionId).orElseThrow();
            if (AiImportLog.PARSING.equals(current.getStatus()) && current.getCompletedBatches() >= 1) {
                midway = current;
                break;
            }
            if (!AiImportLog.PARSING.equals(current.getStatus())) {
                break;
            }
            Thread.sleep(50);
        }

        // A batch that already succeeded is on disk while the rest is still parsing.
        assertThat(midway).as("a finished batch visible while the session is still PARSING").isNotNull();
        assertThat(midway.getTotalBatches()).isEqualTo(4);
        assertThat(midway.getCompletedBatches()).isLessThan(midway.getTotalBatches());
        assertThat(midway.getBatchesJson()).contains("\"status\":\"DONE\"").contains("sourceRows");
        assertThat(midway.getPreviewJson()).contains("一年级1班");
        assertThat(midway.getRowCount()).isEqualTo(midway.getCompletedBatches());

        io.restassured.path.json.JsonPath done = awaitSession(admin, sessionId);
        assertThat(done.getString("status")).isEqualTo("PREVIEW");
        assertThat(done.getList("rows")).hasSize(4);
        AiImportLog stored = importLogs.findById(sessionId).orElseThrow();
        assertThat(stored.getCompletedBatches()).isEqualTo(4);
        assertThat(stored.getCurrentBatch()).isNull();
        assertThat(stored.getBatchesJson()).contains("\"status\":\"DONE\"").doesNotContain("\"PENDING\"");
    }

    @Test
    void aSmallTimetableStillReturnsItsPreviewInOneSynchronousCall() throws Exception {
        String admin = adminToken();
        timetableFixture(admin);
        fake.response = aiResponse(List.of(
                timetableRow("语文", "周一", "1", "张三"),
                timetableRow("语文", "周一", "2", "张三"),
                timetableRow("语文", "周一", "3", "张三")));

        Response preview = upload(
                admin, "/api/ai/import/excel?target=timetable", "一年级课表.xlsx",
                timetableExcelBytes(3), XLSX);

        preview.then().statusCode(200)
                .body("status", equalTo("PREVIEW"))
                .body("rows", hasSize(3))
                .body("progress", org.hamcrest.Matchers.nullValue());
        assertThat(fake.calls).isEqualTo(1);
        AiImportLog stored = importLogs.findById(preview.jsonPath().getLong("sessionId")).orElseThrow();
        assertThat(stored.getTotalBatches()).isZero();
    }

    @Test
    void aSessionThatIsStillParsingCannotBeEditedOrCommitted() throws Exception {
        String admin = adminToken();
        timetableFixture(admin);
        fake.latencyMs = 300;
        fake.responder = request -> aiResponse(List.of(
                timetableRow("语文", "周一", String.valueOf(fake.calls), "张三")));

        Response upload = upload(
                admin, "/api/ai/import/excel?target=timetable", "一年级课表.xlsx",
                timetableExcelBytes(4), XLSX);
        long sessionId = upload.jsonPath().getLong("sessionId");

        as(admin).when().post("/api/ai/import/sessions/" + sessionId + "/commit")
                .then().statusCode(409)
                .body("message", containsString("解析"));
        as(admin).body(Map.of("rows", List.of(timetableRow())))
                .when().put("/api/ai/import/sessions/" + sessionId + "/rows")
                .then().statusCode(409);

        // Let the background run settle so the next test starts from a quiet executor.
        assertThat(awaitSession(admin, sessionId).getString("status")).isEqualTo("PREVIEW");
    }

    // ------------------------------------------------- partial results (ticket 03)

    /** Parses every batch, except the ones whose 1-based call number is listed. */
    private java.util.function.Function<AiRequest, String> failOnBatch(int... failingCalls) {
        java.util.Set<Integer> failing = new java.util.HashSet<>();
        for (int call : failingCalls) {
            failing.add(call);
        }
        java.util.concurrent.atomic.AtomicInteger seen = new java.util.concurrent.atomic.AtomicInteger();
        return request -> {
            int call = seen.incrementAndGet();
            if (failing.contains(call)) {
                throw new IllegalStateException("模型返回为空");
            }
            return aiResponse(List.of(timetableRow("语文", "周一", String.valueOf(call), "张三")));
        };
    }

    @Test
    void aFailedBatchKeepsTheBatchesThatParsedAndLeavesTheSessionPartial() throws Exception {
        String admin = adminToken();
        timetableFixture(admin);
        fake.responder = failOnBatch(2);

        Response upload = upload(admin, "/api/ai/import/excel?target=timetable",
                "一年级课表.xlsx", timetableExcelBytes(4), XLSX);
        long sessionId = upload.jsonPath().getLong("sessionId");

        io.restassured.path.json.JsonPath partial = awaitSession(admin, sessionId);

        assertThat(partial.getString("status")).isEqualTo("PARTIAL");
        // Three of the four classes parsed: their lessons survive, the failed one has none.
        assertThat(partial.getList("rows")).hasSize(3);
        assertThat(partial.getMap("progress"))
                .containsEntry("total", 4)
                .containsEntry("completed", 3)
                .containsEntry("failed", 1);
        assertThat(partial.getList("failedBatches")).hasSize(1);
        assertThat(partial.getInt("failedBatches[0].index")).isEqualTo(2);
        assertThat(partial.getString("failedBatches[0].label")).isEqualTo("一年级2班");
        assertThat(partial.getString("failedBatches[0].reason"))
                .contains("第 2/4 批")
                .contains("一年级2班")
                .contains("解析失败")
                .contains("模型返回为空");
        // A failure in the middle must not stop the remaining batches.
        assertThat(fake.calls).isEqualTo(4);

        AiImportLog stored = importLogs.findById(sessionId).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(AiImportLog.PARTIAL);
        assertThat(stored.getCompletedBatches()).isEqualTo(3);
        assertThat(stored.getRowCount()).isEqualTo(3);
        assertThat(stored.getBatchesJson()).contains("\"status\":\"FAILED\"").contains("模型返回为空");
    }

    @Test
    void everyFailedBatchIsCollectedInThePartialResult() throws Exception {
        String admin = adminToken();
        timetableFixture(admin);
        fake.responder = failOnBatch(1, 3);

        Response upload = upload(admin, "/api/ai/import/excel?target=timetable",
                "一年级课表.xlsx", timetableExcelBytes(4), XLSX);
        long sessionId = upload.jsonPath().getLong("sessionId");

        io.restassured.path.json.JsonPath partial = awaitSession(admin, sessionId);

        assertThat(partial.getString("status")).isEqualTo("PARTIAL");
        assertThat(partial.getList("rows")).hasSize(2);
        assertThat(partial.getMap("progress"))
                .containsEntry("total", 4)
                .containsEntry("completed", 2)
                .containsEntry("failed", 2);
        assertThat(partial.getList("failedBatches")).hasSize(2);
        assertThat(partial.getInt("failedBatches[0].index")).isEqualTo(1);
        assertThat(partial.getString("failedBatches[0].label")).isEqualTo("一年级1班");
        assertThat(partial.getInt("failedBatches[1].index")).isEqualTo(3);
        assertThat(partial.getString("failedBatches[1].label")).isEqualTo("一年级3班");
        assertThat(partial.getString("failedBatches[1].reason")).contains("第 3/4 批");
        assertThat(fake.calls).isEqualTo(4);
    }

    @Test
    void aPartialSessionCannotBeCommittedOrEdited() throws Exception {
        String admin = adminToken();
        timetableFixture(admin);
        fake.responder = failOnBatch(2);

        Response upload = upload(admin, "/api/ai/import/excel?target=timetable",
                "一年级课表.xlsx", timetableExcelBytes(4), XLSX);
        long sessionId = upload.jsonPath().getLong("sessionId");
        assertThat(awaitSession(admin, sessionId).getString("status")).isEqualTo("PARTIAL");

        String sessions = "/api/ai/import/sessions/" + sessionId;
        as(admin).when().post(sessions + "/commit")
                .then().statusCode(409)
                .body("message", containsString("请先重试失败批次或放弃导入"));
        as(admin).body(Map.of("rows", List.of(timetableRow())))
                .when().put(sessions + "/rows")
                .then().statusCode(409)
                .body("message", containsString("请先重试失败批次或放弃导入"));
        as(admin).body(Map.of())
                .when().post(sessions + "/mapping")
                .then().statusCode(409);
        as(admin).when().post(sessions + "/continue")
                .then().statusCode(409);
        as(admin).when().post(sessions + "/reparse")
                .then().statusCode(409);

        // Reading a partial session stays open: the page polls and restores it.
        as(admin).when().get(sessions)
                .then().statusCode(200)
                .body("status", equalTo("PARTIAL"))
                .body("rows", hasSize(3));
    }

    // ------------------------------------------------------- stopping a parse (ticket 02)

    @Test
    void stoppingAParseKeepsTheFinishedBatchesAndLeavesAPreviewThatCanBeCommitted()
            throws Exception {
        String admin = adminToken();
        timetableFixture(admin);
        fake.latencyMs = 250;
        fake.responder = request -> aiResponse(List.of(
                timetableRow("语文", "周一", String.valueOf(fake.calls), "张三")));

        Response upload = upload(
                admin, "/api/ai/import/excel?target=timetable", "一年级课表.xlsx",
                timetableExcelBytes(4), XLSX);
        long sessionId = upload.jsonPath().getLong("sessionId");

        // Wait for the first batch to land: the stop must not be able to catch a cold start.
        for (int i = 0; i < 200; i++) {
            if (importLogs.findById(sessionId).orElseThrow().getCompletedBatches() >= 1) {
                break;
            }
            Thread.sleep(50);
        }
        assertThat(importLogs.findById(sessionId).orElseThrow().getCompletedBatches())
                .isGreaterThanOrEqualTo(1);

        Response stopped = as(admin).when()
                .post("/api/ai/import/sessions/" + sessionId + "/stop");

        stopped.then().statusCode(200)
                .body("status", equalTo("PREVIEW"))
                .body("progress.total", equalTo(4))
                .body("failedBatches", hasSize(0));
        int kept = stopped.jsonPath().getInt("progress.completed");
        assertThat(kept).isBetween(1, 3);
        assertThat(stopped.jsonPath().getList("rows")).hasSize(kept);
        assertThat((Object) stopped.jsonPath().get("progress.currentBatch")).isNull();

        // The in-flight batch may still land; no further AI call may go out after it.
        Thread.sleep(700);
        int settledCalls = fake.calls;
        Thread.sleep(700);
        assertThat(fake.calls).as("no AI call after the stop").isEqualTo(settledCalls);
        assertThat(settledCalls).isLessThan(4);

        AiImportLog stored = importLogs.findById(sessionId).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(AiImportLog.PREVIEW);
        // The total stays for the "已完成 N/M 批" display; only the kept batches remain.
        assertThat(stored.getTotalBatches()).isEqualTo(4);
        assertThat(stored.getCompletedBatches()).isEqualTo(kept);
        assertThat(stored.getCurrentBatch()).isNull();
        assertThat(stored.getBatchesJson()).doesNotContain("PENDING").doesNotContain("FAILED");
        assertThat(stored.getRowCount()).isEqualTo(kept);

        // A stopped session is a normal preview: it can be committed.
        as(admin).when().post("/api/ai/import/sessions/" + sessionId + "/commit")
                .then().statusCode(200)
                .body("imported", equalTo(kept));
        assertThat(importLogs.findById(sessionId).orElseThrow().getStatus())
                .isEqualTo(AiImportLog.COMMITTED);
    }

    @Test
    void stoppingTwiceIsAConflict() throws Exception {
        String admin = adminToken();
        timetableFixture(admin);
        fake.latencyMs = 250;
        fake.responder = request -> aiResponse(List.of(
                timetableRow("语文", "周一", String.valueOf(fake.calls), "张三")));

        Response upload = upload(
                admin, "/api/ai/import/excel?target=timetable", "一年级课表.xlsx",
                timetableExcelBytes(4), XLSX);
        long sessionId = upload.jsonPath().getLong("sessionId");

        // Let one batch finish, so the first stop leaves a preview rather than a failure.
        for (int i = 0; i < 200; i++) {
            if (importLogs.findById(sessionId).orElseThrow().getCompletedBatches() >= 1) {
                break;
            }
            Thread.sleep(50);
        }

        as(admin).when().post("/api/ai/import/sessions/" + sessionId + "/stop")
                .then().statusCode(200).body("status", equalTo("PREVIEW"));
        as(admin).when().post("/api/ai/import/sessions/" + sessionId + "/stop")
                .then().statusCode(409)
                .body("message", containsString("无法停止"));
    }

    @Test
    void stoppingASessionThatIsNotParsingIsAConflict() throws Exception {
        String admin = adminToken();
        timetableFixture(admin);
        fake.response = aiResponse(List.of(
                timetableRow("语文", "周一", "1", "张三"),
                timetableRow("语文", "周一", "2", "张三"),
                timetableRow("语文", "周一", "3", "张三")));

        // Three data rows (the chunk threshold) parse synchronously into a preview.
        Response preview = upload(
                admin, "/api/ai/import/excel?target=timetable", "一年级课表.xlsx",
                timetableExcelBytes(3), XLSX);
        preview.then().statusCode(200).body("status", equalTo("PREVIEW"));

        as(admin).when()
                .post("/api/ai/import/sessions/" + preview.jsonPath().getLong("sessionId") + "/stop")
                .then().statusCode(409)
                .body("message", containsString("不在解析中"));
    }

    @Test
    void stoppingBeforeTheFirstBatchFinishesNeverPretendsToHaveAPreview() throws Exception {
        String admin = adminToken();
        timetableFixture(admin);
        // Slow enough that the stop lands while the very first batch is still in flight.
        fake.latencyMs = 400;
        fake.responder = request -> aiResponse(List.of(
                timetableRow("语文", "周一", String.valueOf(fake.calls), "张三")));

        Response upload = upload(
                admin, "/api/ai/import/excel?target=timetable", "一年级课表.xlsx",
                timetableExcelBytes(4), XLSX);
        upload.then().statusCode(200).body("status", equalTo("PARSING"));
        long sessionId = upload.jsonPath().getLong("sessionId");

        Response stopped = as(admin).when()
                .post("/api/ai/import/sessions/" + sessionId + "/stop");

        // Nothing parsed means nothing to preview: an empty "preview (0 rows)" that could
        // still be committed would be a lie.
        stopped.then().statusCode(200)
                .body("status", equalTo("FAILED"))
                .body("explanation", equalTo("已停止解析，没有任何已完成的批次"))
                .body("rows", hasSize(0))
                .body("progress.total", equalTo(4))
                .body("progress.completed", equalTo(0))
                .body("failedBatches", hasSize(0));

        // The in-flight batch may still be answered; its result must not resurrect the run.
        Thread.sleep(700);
        int settledCalls = fake.calls;
        Thread.sleep(700);
        assertThat(fake.calls).as("no AI call after the stop").isEqualTo(settledCalls);

        AiImportLog stored = importLogs.findById(sessionId).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(AiImportLog.FAILED);
        assertThat(stored.getCompletedBatches()).isZero();
        assertThat(stored.getBatchesJson()).doesNotContain("PENDING");
        assertThat(stored.getPreviewJson()).isNull();

        as(admin).when().post("/api/ai/import/sessions/" + sessionId + "/commit")
                .then().statusCode(409);
    }

    @Test
    void aStoppedParseSaysHowManyBatchesWereLeftUnparsed() throws Exception {
        String admin = adminToken();
        timetableFixture(admin);
        fake.latencyMs = 250;
        fake.responder = request -> aiResponse(List.of(
                timetableRow("语文", "周一", String.valueOf(fake.calls), "张三")));

        Response upload = upload(
                admin, "/api/ai/import/excel?target=timetable", "一年级课表.xlsx",
                timetableExcelBytes(4), XLSX);
        long sessionId = upload.jsonPath().getLong("sessionId");

        for (int i = 0; i < 200; i++) {
            if (importLogs.findById(sessionId).orElseThrow().getCompletedBatches() >= 1) {
                break;
            }
            Thread.sleep(50);
        }

        Response stopped = as(admin).when()
                .post("/api/ai/import/sessions/" + sessionId + "/stop");
        stopped.then().statusCode(200).body("status", equalTo("PREVIEW"));

        io.restassured.path.json.JsonPath stoppedJson = stopped.jsonPath();
        int kept = stoppedJson.getInt("progress.completed");
        int dropped = 4 - kept;
        assertThat(kept).isBetween(1, 3);

        // The page must say what happened to the batches that were thrown away, and the
        // counters must only report batches that really exist in the record.
        assertThat(stoppedJson.getString("explanation"))
                .contains("已停止解析，剩余 " + dropped + " 批未解析");
        assertThat(stoppedJson.getMap("progress"))
                .containsEntry("total", 4)
                .containsEntry("completed", kept)
                .containsEntry("failed", 0);
        assertThat(stoppedJson.getList("failedBatches")).isEmpty();
        assertThat(stoppedJson.getList("rows")).hasSize(kept);

        AiImportLog stored = importLogs.findById(sessionId).orElseThrow();
        assertThat(stored.getCompletedBatches()).isEqualTo(kept);
        assertThat(stored.getBatchesJson()).doesNotContain("PENDING");
        assertThat(stored.getBatchesJson().split("\"index\"", -1)).hasSize(kept + 1);
    }

    @Test
    void anInterruptedParsingSessionIsRecoveredOnStartup() {
        String admin = adminToken();
        AiImportLog halfDone = new AiImportLog();
        halfDone.setImportType("excel");
        halfDone.setTargetType("timetable");
        halfDone.setUsername("admin");
        halfDone.setStatus(AiImportLog.PARSING);
        halfDone.setRowCount(1);
        halfDone.setImportedCount(0);
        halfDone.setTotalBatches(2);
        halfDone.setCompletedBatches(1);
        halfDone.setBatchesJson("[{\"index\":1,\"label\":\"一年级1班\",\"status\":\"DONE\","
                + "\"sourceRows\":[[\"一年级1班\",\"语文\"]],"
                + "\"rows\":[{\"values\":{\"className\":\"一年级1班\"},\"confidence\":{},\"lowConfidence\":[]}],"
                + "\"mappings\":[],\"explanation\":\"第一批\"},"
                + "{\"index\":2,\"label\":\"一年级2班\",\"status\":\"PENDING\","
                + "\"sourceRows\":[[\"一年级2班\",\"数学\"]]}]");
        halfDone.setCreatedAt(Instant.now());
        halfDone.setUpdatedAt(Instant.now());
        long resumedId = importLogs.save(halfDone).getId();

        AiImportLog nothingDone = new AiImportLog();
        nothingDone.setImportType("excel");
        nothingDone.setTargetType("timetable");
        nothingDone.setUsername("admin");
        nothingDone.setStatus(AiImportLog.PARSING);
        nothingDone.setRowCount(0);
        nothingDone.setImportedCount(0);
        nothingDone.setTotalBatches(2);
        nothingDone.setCompletedBatches(0);
        nothingDone.setCreatedAt(Instant.now());
        nothingDone.setUpdatedAt(Instant.now());
        long failedId = importLogs.save(nothingDone).getId();

        importService.recoverInterruptedSessions();

        AiImportLog resumed = importLogs.findById(resumedId).orElseThrow();
        // The class that never ran is a failed batch now: the session is partial, not a
        // complete preview that could be published with a class missing.
        assertThat(resumed.getStatus()).isEqualTo(AiImportLog.PARTIAL);
        assertThat(resumed.getCurrentBatch()).isNull();
        assertThat(resumed.getPreviewJson()).contains("一年级1班");

        AiImportLog failed = importLogs.findById(failedId).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(AiImportLog.FAILED);
        assertThat(failed.getAiExplanation()).contains("重启");

        // The recovered session is usable again: it shows up through the polling endpoint.
        as(admin).when().get("/api/ai/import/sessions/" + resumedId)
                .then().statusCode(200)
                .body("status", equalTo("PARTIAL"))
                .body("rows", hasSize(1))
                .body("failedBatches[0].label", equalTo("一年级2班"))
                .body("failedBatches[0].reason", containsString("重启"));
    }

    // --------------------------------------------------- abandoning a session (ticket 05)

    @Test
    void abandoningAParsingSessionStopsTheRunAndWritesNoBusinessData() throws Exception {
        String admin = adminToken();
        timetableFixture(admin);
        fake.latencyMs = 400;
        fake.responder = request -> aiResponse(List.of(
                timetableRow("语文", "周一", String.valueOf(fake.calls), "张三")));

        Response upload = upload(
                admin, "/api/ai/import/excel?target=timetable", "一年级课表.xlsx",
                timetableExcelBytes(4), XLSX);
        long sessionId = upload.jsonPath().getLong("sessionId");

        // Let one batch land: abandoning a cold start would prove nothing.
        for (int i = 0; i < 200; i++) {
            if (importLogs.findById(sessionId).orElseThrow().getCompletedBatches() >= 1) {
                break;
            }
            Thread.sleep(50);
        }
        assertThat(importLogs.findById(sessionId).orElseThrow().getCompletedBatches())
                .isGreaterThanOrEqualTo(1);

        Response abandoned = as(admin).when()
                .post("/api/ai/import/sessions/" + sessionId + "/abandon");

        abandoned.then().statusCode(200)
                .body("status", equalTo("ABANDONED"))
                .body("progress.total", equalTo(4));
        assertThat((Object) abandoned.jsonPath().get("progress.currentBatch")).isNull();

        // The stop flag ends the run: at most the batch already in flight may land.
        Thread.sleep(900);
        int settledCalls = fake.calls;
        Thread.sleep(700);
        assertThat(fake.calls).as("no AI call after abandoning").isEqualTo(settledCalls);
        assertThat(settledCalls).isLessThan(4);

        AiImportLog stored = importLogs.findById(sessionId).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(AiImportLog.ABANDONED);
        assertThat(stored.getCurrentBatch()).isNull();
        assertThat(stored.getAiExplanation()).contains("放弃");

        // The record stays readable for audit, it is just no longer resumable.
        as(admin).when().get("/api/ai/import/sessions/" + sessionId)
                .then().statusCode(200).body("status", equalTo("ABANDONED"));

        // Abandoning writes nothing: no draft, no lessons, no class/grade/teacher rows.
        assertThat(drafts.count()).isZero();
        assertThat(draftLessons.count()).isZero();
        assertThat(teachers.count()).isEqualTo(1);
        assertThat(adminClasses.count()).isEqualTo(1);
        assertThat(grades.count()).isEqualTo(1);
    }

    @Test
    void abandoningAPartialSessionKeepsItForAuditAndBlocksEveryOtherAction()
            throws Exception {
        String admin = adminToken();
        timetableFixture(admin);
        fake.responder = failOnBatch(2);

        Response upload = upload(admin, "/api/ai/import/excel?target=timetable",
                "一年级课表.xlsx", timetableExcelBytes(4), XLSX);
        long sessionId = upload.jsonPath().getLong("sessionId");
        assertThat(awaitSession(admin, sessionId).getString("status")).isEqualTo("PARTIAL");

        String sessions = "/api/ai/import/sessions/" + sessionId;
        // Abandoning takes no body: the reason on record is the fixed default.
        as(admin).when().post(sessions + "/abandon")
                .then().statusCode(200)
                .body("status", equalTo("ABANDONED"))
                .body("failedBatches", hasSize(1));

        AiImportLog stored = importLogs.findById(sessionId).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(AiImportLog.ABANDONED);
        assertThat(stored.getAiExplanation()).isEqualTo("用户放弃导入");
        // What did parse is still on record, for audit only.
        assertThat(stored.getPreviewJson()).contains("一年级1班");

        // Nothing can move an abandoned session forward any more.
        as(admin).when().post(sessions + "/commit")
                .then().statusCode(409)
                .body("message", containsString("已放弃"));
        as(admin).body(Map.of("rows", List.of(timetableRow())))
                .when().put(sessions + "/rows")
                .then().statusCode(409);
        as(admin).body(Map.of())
                .when().post(sessions + "/mapping")
                .then().statusCode(409);
        as(admin).when().post(sessions + "/continue").then().statusCode(409);
        as(admin).when().post(sessions + "/reparse").then().statusCode(409);
        as(admin).when().post(sessions + "/stop").then().statusCode(409);
        as(admin).when().post(sessions + "/abandon").then().statusCode(409);

        assertThat(drafts.count()).isZero();
        assertThat(draftLessons.count()).isZero();
        assertThat(teachers.count()).isEqualTo(1);
        assertThat(adminClasses.count()).isEqualTo(1);
        assertThat(grades.count()).isEqualTo(1);
    }

    @Test
    void abandoningASessionThatIsNotParsingOrPartialIsAConflict() throws Exception {
        String admin = adminToken();
        timetableFixture(admin);
        fake.response = aiResponse(List.of(
                timetableRow("语文", "周一", "1", "张三"),
                timetableRow("语文", "周一", "2", "张三"),
                timetableRow("语文", "周一", "3", "张三")));

        // Three data rows parse synchronously into a plain preview.
        Response preview = upload(admin, "/api/ai/import/excel?target=timetable",
                "一年级课表.xlsx", timetableExcelBytes(3), XLSX);
        preview.then().statusCode(200).body("status", equalTo("PREVIEW"));
        long sessionId = preview.jsonPath().getLong("sessionId");

        as(admin).when().post("/api/ai/import/sessions/" + sessionId + "/abandon")
                .then().statusCode(409)
                .body("message", containsString("解析中"));

        // A committed import is history too, not something to abandon.
        as(admin).when().post("/api/ai/import/sessions/" + sessionId + "/commit")
                .then().statusCode(200);
        as(admin).when().post("/api/ai/import/sessions/" + sessionId + "/abandon")
                .then().statusCode(409);
    }

    // ------------------------------------------------ retrying failed batches (ticket 04)

    /**
     * The retry answer is a lesson the timetable does not have yet, so a merge mistake
     * (a re-parsed successful batch) would show up as an extra or a missing row.
     */
    private Map<String, Object> retriedTimetableRow(String className, String period) {
        Map<String, Object> row = timetableRow("数学", "周二", period, "李四");
        @SuppressWarnings("unchecked")
        Map<String, Object> values = (Map<String, Object>) row.get("values");
        values.put("className", className);
        return row;
    }

    @Test
    void retryingOnlyTheFailedBatchMergesItBackIntoAFullPreview() throws Exception {
        String admin = adminToken();
        timetableFixture(admin);
        fake.responder = failOnBatch(2);

        Response upload = upload(admin, "/api/ai/import/excel?target=timetable",
                "一年级课表.xlsx", timetableExcelBytes(4), XLSX);
        long sessionId = upload.jsonPath().getLong("sessionId");
        assertThat(awaitSession(admin, sessionId).getString("status")).isEqualTo("PARTIAL");
        assertThat(fake.calls).isEqualTo(4);

        // The retry returns immediately, describing the round it just dispatched: the
        // original plan (4 batches), the 3 that already parsed, and the 1 being retried.
        fake.responder = request -> aiResponse(List.of(retriedTimetableRow("一年级2班", "9")));
        String sessions = "/api/ai/import/sessions/" + sessionId;
        as(admin).when().post(sessions + "/retry")
                .then().statusCode(200)
                .body("status", equalTo("PARSING"))
                .body("progress.total", equalTo(4))
                .body("progress.completed", equalTo(3))
                .body("progress.failed", equalTo(1))
                .body("progress.currentBatch", org.hamcrest.Matchers.nullValue())
                .body("rows", hasSize(3));

        io.restassured.path.json.JsonPath done = awaitSession(admin, sessionId);

        assertThat(done.getString("status")).isEqualTo("PREVIEW");
        assertThat(done.getList("rows")).hasSize(4);
        assertThat(done.getMap("progress"))
                .containsEntry("total", 4)
                .containsEntry("completed", 4)
                .containsEntry("failed", 0);
        assertThat(done.getList("failedBatches")).isEmpty();
        // One new AI call only: the three batches that already parsed are not re-asked.
        assertThat(fake.calls).isEqualTo(5);

        AiImportLog stored = importLogs.findById(sessionId).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(AiImportLog.PREVIEW);
        assertThat(stored.getCompletedBatches()).isEqualTo(4);
        assertThat(stored.getRowCount()).isEqualTo(4);
        assertThat(stored.getCurrentBatch()).isNull();
        assertThat(stored.getBatchesJson()).doesNotContain("FAILED");
        // The retried batch's own result is in the merged preview, not a duplicate.
        assertThat(stored.getPreviewJson()).contains("一年级2班").doesNotContain("解析失败");
    }

    @Test
    void aRetryThatFailsAgainStaysPartialAndReplacesTheFailureReason() throws Exception {
        String admin = adminToken();
        timetableFixture(admin);
        fake.responder = failOnBatch(2);

        Response upload = upload(admin, "/api/ai/import/excel?target=timetable",
                "一年级课表.xlsx", timetableExcelBytes(4), XLSX);
        long sessionId = upload.jsonPath().getLong("sessionId");
        io.restassured.path.json.JsonPath first =
                awaitSession(admin, sessionId);
        assertThat(first.getString("failedBatches[0].reason")).contains("模型返回为空");
        int afterFirstRound = fake.calls;

        fake.responder = request -> {
            throw new IllegalStateException("重试时服务超时");
        };
        String sessions = "/api/ai/import/sessions/" + sessionId;
        as(admin).when().post(sessions + "/retry").then().statusCode(200)
                .body("status", equalTo("PARSING"));

        io.restassured.path.json.JsonPath again = awaitSession(admin, sessionId);

        assertThat(again.getString("status")).isEqualTo("PARTIAL");
        assertThat(again.getList("rows")).hasSize(3);
        assertThat(again.getMap("progress"))
                .containsEntry("total", 4)
                .containsEntry("completed", 3)
                .containsEntry("failed", 1);
        assertThat(again.getList("failedBatches")).hasSize(1);
        assertThat(again.getInt("failedBatches[0].index")).isEqualTo(2);
        assertThat(again.getString("failedBatches[0].label")).isEqualTo("一年级2班");
        // The old reason is gone: this round's answer is what the page must show.
        assertThat(again.getString("failedBatches[0].reason"))
                .contains("重试时服务超时")
                .doesNotContain("模型返回为空");
        assertThat(again.getString("explanation")).doesNotContain("模型返回为空");
        assertThat(fake.calls).isEqualTo(afterFirstRound + 1);

        AiImportLog stored = importLogs.findById(sessionId).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(AiImportLog.PARTIAL);
        assertThat(stored.getCompletedBatches()).isEqualTo(3);
        assertThat(stored.getBatchesJson())
                .contains("重试时服务超时")
                .doesNotContain("模型返回为空");

        // Still retryable: a partial session keeps offering the failed classes.
        fake.responder = request -> aiResponse(List.of(retriedTimetableRow("一年级2班", "9")));
        as(admin).when().post(sessions + "/retry").then().statusCode(200);
        assertThat(awaitSession(admin, sessionId).getString("status")).isEqualTo("PREVIEW");
    }

    @Test
    void aRetryCallsTheAiOncePerFailedBatchAndNoMore() throws Exception {
        String admin = adminToken();
        timetableFixture(admin);
        fake.responder = failOnBatch(1, 3);

        Response upload = upload(admin, "/api/ai/import/excel?target=timetable",
                "一年级课表.xlsx", timetableExcelBytes(4), XLSX);
        long sessionId = upload.jsonPath().getLong("sessionId");
        io.restassured.path.json.JsonPath partial = awaitSession(admin, sessionId);
        assertThat(partial.getString("status")).isEqualTo("PARTIAL");
        assertThat(partial.getList("rows")).hasSize(2);
        assertThat(fake.calls).isEqualTo(4);

        // Two failed batches, two retry calls: the two successes are never re-asked.
        java.util.concurrent.atomic.AtomicInteger retried =
                new java.util.concurrent.atomic.AtomicInteger();
        fake.responder = request -> aiResponse(List.of(
                timetableRow("英语", "周三", String.valueOf(7 + retried.incrementAndGet()), "王五")));

        as(admin).when().post("/api/ai/import/sessions/" + sessionId + "/retry").then().statusCode(200);
        io.restassured.path.json.JsonPath done = awaitSession(admin, sessionId);

        assertThat(done.getString("status")).isEqualTo("PREVIEW");
        assertThat(done.getList("rows")).hasSize(4);
        assertThat(fake.calls).isEqualTo(6);
        assertThat(retried.get()).isEqualTo(2);
    }

    @Test
    void aRetryKeepsTheRawAnswerOfTheBatchesThatAlreadyParsed() throws Exception {
        String admin = adminToken();
        timetableFixture(admin);
        // Batch 2 fails, so batches 1, 3 and 4 leave an answer in the audit column.
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        fake.responder = request -> {
            int call = calls.incrementAndGet();
            if (call == 2) {
                throw new IllegalStateException("模型返回为空");
            }
            return markedAiResponse("FIRST-" + call);
        };

        Response upload = upload(admin, "/api/ai/import/excel?target=timetable",
                "一年级课表.xlsx", timetableExcelBytes(4), XLSX);
        long sessionId = upload.jsonPath().getLong("sessionId");
        assertThat(awaitSession(admin, sessionId).getString("status")).isEqualTo("PARTIAL");

        List<String> firstRound =
                rawAnswers(importLogs.findById(sessionId).orElseThrow().getAiRawResponse());
        assertThat(firstRound).hasSize(3);
        assertThat(String.join("\n", firstRound))
                .contains("FIRST-1").contains("FIRST-3").contains("FIRST-4");

        fake.responder = request -> markedAiResponse("SECOND-1");
        as(admin).when().post("/api/ai/import/sessions/" + sessionId + "/retry")
                .then().statusCode(200);
        assertThat(awaitSession(admin, sessionId).getString("status")).isEqualTo("PREVIEW");

        // The audit column is what the AI really answered: a retry adds its own answer and
        // must not erase the answers of the batches that parsed on the first pass.
        List<String> afterRetry =
                rawAnswers(importLogs.findById(sessionId).orElseThrow().getAiRawResponse());
        assertThat(afterRetry).hasSize(4);
        assertThat(afterRetry.stream().filter(raw -> raw.contains("FIRST-"))).hasSize(3);
        assertThat(afterRetry.stream().filter(raw -> raw.contains("SECOND-1"))).hasSize(1);
    }

    @Test
    void retryingASessionThatIsNotPartialIsAConflict() throws Exception {
        String admin = adminToken();
        timetableFixture(admin);
        fake.response = aiResponse(List.of(
                timetableRow("语文", "周一", "1", "张三"),
                timetableRow("语文", "周一", "2", "张三"),
                timetableRow("语文", "周一", "3", "张三")));

        Response preview = upload(admin, "/api/ai/import/excel?target=timetable",
                "一年级课表.xlsx", timetableExcelBytes(3), XLSX);
        preview.then().statusCode(200).body("status", equalTo("PREVIEW"));
        long sessionId = preview.jsonPath().getLong("sessionId");

        as(admin).when().post("/api/ai/import/sessions/" + sessionId + "/retry")
                .then().statusCode(409)
                .body("message", containsString("重试失败批次"));
        assertThat(fake.calls).isEqualTo(1);
        assertThat(importLogs.findById(sessionId).orElseThrow().getStatus())
                .isEqualTo(AiImportLog.PREVIEW);
    }

    @Test
    void aRetryCanBeStoppedAndLeavesTheFailedBatchRetryable() throws Exception {
        String admin = adminToken();
        timetableFixture(admin);
        fake.responder = failOnBatch(2);

        Response upload = upload(admin, "/api/ai/import/excel?target=timetable",
                "一年级课表.xlsx", timetableExcelBytes(4), XLSX);
        long sessionId = upload.jsonPath().getLong("sessionId");
        assertThat(awaitSession(admin, sessionId).getString("status")).isEqualTo("PARTIAL");
        int callsBefore = fake.calls;

        fake.latencyMs = 500;
        fake.responder = request -> aiResponse(List.of(retriedTimetableRow("一年级2班", "9")));
        String sessions = "/api/ai/import/sessions/" + sessionId;
        as(admin).when().post(sessions + "/retry").then().statusCode(200)
                .body("status", equalTo("PARSING"));

        // Stopping mid-retry goes back to the pre-retry state: the class is still failed,
        // so the page can offer the retry again.
        as(admin).when().post(sessions + "/stop")
                .then().statusCode(200)
                .body("status", equalTo("PARTIAL"))
                .body("progress.completed", equalTo(3))
                .body("failedBatches", hasSize(1))
                .body("failedBatches[0].label", equalTo("一年级2班"));

        Thread.sleep(900);
        assertThat(fake.calls).as("no further AI call after the stop").isEqualTo(callsBefore + 1);
        assertThat(importLogs.findById(sessionId).orElseThrow().getStatus())
                .isEqualTo(AiImportLog.PARTIAL);

        fake.latencyMs = 0;
        as(admin).when().post(sessions + "/retry").then().statusCode(200);
        assertThat(awaitSession(admin, sessionId).getString("status")).isEqualTo("PREVIEW");
    }

    @Test
    void aRecoveredFailedBatchCanBeRetriedWithoutTheOriginalUpload() throws Exception {
        String admin = adminToken();
        // A session that was left PARTIAL by the startup recovery (ticket 02): the upload
        // may be long gone, but the stored source rows are enough to ask the AI again.
        AiImportLog log = new AiImportLog();
        log.setImportType("excel");
        log.setTargetType("timetable");
        log.setUsername("admin");
        log.setStatus(AiImportLog.PARTIAL);
        log.setRowCount(1);
        log.setImportedCount(0);
        log.setTotalBatches(2);
        log.setCompletedBatches(1);
        log.setPreviewJson("{\"mappings\":[],\"rows\":[{\"values\":{\"className\":\"一年级1班\","
                + "\"dayOfWeek\":\"周一\",\"periodNo\":\"1\",\"courseName\":\"语文\","
                + "\"teacherName\":\"张三\",\"roomName\":\"101\"},\"confidence\":{},\"lowConfidence\":[]}],"
                + "\"explanation\":\"第一批\"}");
        log.setBatchesJson("[{\"index\":1,\"label\":\"一年级1班\",\"status\":\"DONE\","
                + "\"sourceRows\":[[\"一年级1班\",\"语文\"]],"
                + "\"rows\":[{\"values\":{\"className\":\"一年级1班\",\"dayOfWeek\":\"周一\","
                + "\"periodNo\":\"1\",\"courseName\":\"语文\",\"teacherName\":\"张三\","
                + "\"roomName\":\"101\"},\"confidence\":{},\"lowConfidence\":[]}],"
                + "\"mappings\":[],\"explanation\":\"第一批\"},"
                + "{\"index\":2,\"label\":\"一年级2班\",\"status\":\"FAILED\","
                + "\"reason\":\"服务重启中断了这一批，请重试\","
                + "\"sourceRows\":[[\"一年级2班\",\"语文\"]]}]");
        log.setCreatedAt(Instant.now());
        log.setUpdatedAt(Instant.now());
        long sessionId = importLogs.save(log).getId();

        fake.responder = request -> aiResponse(List.of(retriedTimetableRow("一年级2班", "9")));
        as(admin).when().post("/api/ai/import/sessions/" + sessionId + "/retry")
                .then().statusCode(200)
                .body("status", equalTo("PARSING"));

        io.restassured.path.json.JsonPath done = awaitSession(admin, sessionId);

        assertThat(done.getString("status")).isEqualTo("PREVIEW");
        assertThat(done.getList("rows")).hasSize(2);
        assertThat(done.getMap("progress"))
                .containsEntry("total", 2)
                .containsEntry("completed", 2)
                .containsEntry("failed", 0);
        assertThat(fake.calls).isEqualTo(1);
        // The prompt is rebuilt from the stored source rows, not from a re-read upload.
        assertThat(fake.lastRequest.messages().get(1).content())
                .contains("rowsToParse")
                .contains("一年级2班");
        assertThat(importLogs.findById(sessionId).orElseThrow().getBatchesJson())
                .doesNotContain("FAILED");
    }

    // ------------------------------------------------- phase 8 review fixes

    @Test
    void aBatchInFlightWhenTheParseIsStoppedStillLandsInTheAudit() throws Exception {
        String admin = adminToken();
        timetableFixture(admin);
        fake.latencyMs = 400;
        java.util.concurrent.atomic.AtomicInteger seen = new java.util.concurrent.atomic.AtomicInteger();
        fake.responder = request -> markedAiResponse("CALL-" + seen.incrementAndGet());

        Response upload = upload(admin, "/api/ai/import/excel?target=timetable",
                "一年级课表.xlsx", timetableExcelBytes(4), XLSX);
        long sessionId = upload.jsonPath().getLong("sessionId");
        // The second call has to be on the wire, or the stop could catch the run between
        // batches and there would be no late answer to record.
        waitFor(() -> fake.calls >= 2, "第二批已经发出");

        as(admin).when().post("/api/ai/import/sessions/" + sessionId + "/stop")
                .then().statusCode(200).body("status", equalTo("PREVIEW"));

        List<String> raw = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            String stored = importLogs.findById(sessionId).orElseThrow().getAiRawResponse();
            raw = stored == null ? new ArrayList<>() : rawAnswers(stored);
            if (raw.stream().anyMatch(answer -> answer.contains("CALL-2"))) {
                break;
            }
            Thread.sleep(100);
        }
        // A real AI call happened; the stop must not erase what it answered.
        assertThat(String.join("\n", raw)).contains("CALL-1").contains("CALL-2");

        // ...without turning the settled session back into a running one.
        AiImportLog stored = importLogs.findById(sessionId).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(AiImportLog.PREVIEW);
        assertThat(stored.getCurrentBatch()).isNull();
    }

    @Test
    void stoppingAParseThatAlreadyHasAFailedBatchLeavesItPartial() throws Exception {
        String admin = adminToken();
        timetableFixture(admin);
        fake.latencyMs = 250;
        fake.responder = failOnBatch(2);

        Response upload = upload(admin, "/api/ai/import/excel?target=timetable",
                "一年级课表.xlsx", timetableExcelBytes(4), XLSX);
        long sessionId = upload.jsonPath().getLong("sessionId");
        waitFor(() -> {
            String batches = importLogs.findById(sessionId).orElseThrow().getBatchesJson();
            return batches != null && batches.contains("\"FAILED\"");
        }, "第二批的失败已经落库");

        Response stopped = as(admin).when()
                .post("/api/ai/import/sessions/" + sessionId + "/stop");

        // One class is already on record as failed: the stop must not turn the session
        // into a preview that looks complete.
        stopped.then().statusCode(200)
                .body("status", equalTo("PARTIAL"))
                .body("progress.failed", equalTo(1))
                .body("failedBatches", hasSize(1))
                .body("failedBatches[0].label", equalTo("一年级2班"));
        int kept = stopped.jsonPath().getInt("progress.completed");
        assertThat(kept).isBetween(1, 2);
        assertThat(stopped.jsonPath().getList("rows")).hasSize(kept);

        // The kept classes stay kept and the failed one is still the class to retry.
        fake.latencyMs = 0;
        fake.responder = request -> aiResponse(List.of(retriedTimetableRow("一年级2班", "9")));
        as(admin).when().post("/api/ai/import/sessions/" + sessionId + "/retry")
                .then().statusCode(200);

        io.restassured.path.json.JsonPath done = awaitSession(admin, sessionId);
        assertThat(done.getString("status")).isEqualTo("PREVIEW");
        assertThat(done.getList("failedBatches")).isEmpty();
        assertThat(done.getMap("progress")).containsEntry("completed", kept + 1);
    }

    @Test
    void reparsingALargeTableRunsTheBatchesAgainInsteadOfOneWholeTableCall() throws Exception {
        String admin = adminToken();
        timetableFixture(admin);
        fake.responder = request -> aiResponse(List.of(
                timetableRow("语文", "周一", String.valueOf(fake.calls), "张三")));

        Response upload = upload(admin, "/api/ai/import/excel?target=timetable",
                "一年级课表.xlsx", timetableExcelBytes(4), XLSX);
        long sessionId = upload.jsonPath().getLong("sessionId");
        assertThat(awaitSession(admin, sessionId).getString("status")).isEqualTo("PREVIEW");
        assertThat(fake.calls).isEqualTo(4);

        // A large table is a batch plan, so "打回重析" has to rebuild that plan instead of
        // falling back to one whole-table call (the symptom this phase removed).
        Response again = as(admin).when()
                .post("/api/ai/import/sessions/" + sessionId + "/reparse");
        again.then().statusCode(200)
                .body("status", equalTo("PARSING"))
                .body("progress.total", equalTo(4))
                .body("progress.completed", equalTo(0))
                .body("rows", hasSize(0));

        io.restassured.path.json.JsonPath done = awaitSession(admin, sessionId);
        assertThat(done.getString("status")).isEqualTo("PREVIEW");
        assertThat(done.getList("rows")).hasSize(4);
        assertThat(done.getMap("progress")).containsEntry("completed", 4);
        // A second full pass, one call per batch: four more calls, no whole-table call.
        assertThat(fake.calls).isEqualTo(8);

        AiImportLog stored = importLogs.findById(sessionId).orElseThrow();
        assertThat(stored.getBatchesJson()).doesNotContain("PENDING");
        // The audit of the new round is rebuilt, not appended to the old one.
        assertThat(rawAnswers(stored.getAiRawResponse())).hasSize(4);
    }

    @Test
    void reparsingAfterAStopStartsTheProgressOver() throws Exception {
        String admin = adminToken();
        timetableFixture(admin);
        fake.latencyMs = 300;
        fake.responder = request -> aiResponse(List.of(
                timetableRow("语文", "周一", String.valueOf(fake.calls), "张三")));

        Response upload = upload(admin, "/api/ai/import/excel?target=timetable",
                "一年级课表.xlsx", timetableExcelBytes(4), XLSX);
        long sessionId = upload.jsonPath().getLong("sessionId");
        waitFor(() -> importLogs.findById(sessionId).orElseThrow().getCompletedBatches() >= 1,
                "第一批已经完成");
        Response stopped = as(admin).when()
                .post("/api/ai/import/sessions/" + sessionId + "/stop");
        stopped.then().statusCode(200).body("status", equalTo("PREVIEW"));
        assertThat(stopped.jsonPath().getInt("progress.completed")).isLessThan(4);

        fake.latencyMs = 0;
        Response again = as(admin).when()
                .post("/api/ai/import/sessions/" + sessionId + "/reparse");

        // The counters are the new plan's, not the stopped run's leftovers.
        again.then().statusCode(200)
                .body("status", equalTo("PARSING"))
                .body("progress.total", equalTo(4))
                .body("progress.completed", equalTo(0));

        io.restassured.path.json.JsonPath done = awaitSession(admin, sessionId);
        assertThat(done.getString("status")).isEqualTo("PREVIEW");
        assertThat(done.getList("rows")).hasSize(4);
        assertThat(done.getMap("progress"))
                .containsEntry("total", 4)
                .containsEntry("completed", 4)
                .containsEntry("failed", 0);
    }

    @Test
    void aStaleSessionCopyCannotOverwriteTheRowItRead() {
        AiImportLog log = new AiImportLog();
        log.setImportType("excel");
        log.setTargetType("timetable");
        log.setUsername("admin");
        log.setStatus(AiImportLog.PREVIEW);
        log.setRowCount(0);
        log.setImportedCount(0);
        log.setCreatedAt(Instant.now());
        log.setUpdatedAt(Instant.now());
        long sessionId = importLogs.save(log).getId();

        // A background thread and a stop request both hold the row: the second writer
        // must lose instead of writing its stale copy over the newer state.
        AiImportLog stale = importLogs.findById(sessionId).orElseThrow();
        AiImportLog newer = importLogs.findById(sessionId).orElseThrow();
        newer.setStatus(AiImportLog.PARSING);
        importLogs.save(newer);

        stale.setStatus(AiImportLog.ABANDONED);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> importLogs.save(stale))
                .isInstanceOf(org.springframework.orm.ObjectOptimisticLockingFailureException.class);
        assertThat(importLogs.findById(sessionId).orElseThrow().getStatus())
                .isEqualTo(AiImportLog.PARSING);
    }

    @Test
    void timetablePreviewListsUnmatchedNamesAsIssues() {
        String admin = adminToken();
        timetableFixture(admin);
        Map<String, Object> row = timetableRow();
        @SuppressWarnings("unchecked")
        Map<String, Object> values = (Map<String, Object>) row.get("values");
        values.put("className", "一年级(1)班");
        fake.response = aiResponse(List.of(row));

        Response preview = as(admin)
                .body(Map.of("text", "一年级(1)班 周一 第1节 语文 张三 101"))
                .when().post("/api/ai/import/text?target=timetable");

        preview.then().statusCode(200)
                .body("issues", hasSize(1))
                .body("issues[0].row", equalTo(1))
                .body("issues[0].field", equalTo("className"))
                .body("issues[0].value", equalTo("一年级(1)班"))
                .body("issues[0].required", equalTo(true))
                .body("issues[0].candidates", org.hamcrest.Matchers.hasItem("一年级1班"))
                .body("issues[0].inferredGrade", equalTo("一年级"))
                .body("issues[0].gradeCandidates", org.hamcrest.Matchers.hasItem("一年级"));
    }

    @Test
    void anUnmatchedClassKeepsTheInferredGradeWhenTheSemesterHasNoSuchGrade() {
        String admin = adminToken();
        timetableFixture(admin);
        Map<String, Object> row = timetableRow();
        @SuppressWarnings("unchecked")
        Map<String, Object> values = (Map<String, Object>) row.get("values");
        values.put("className", "八（1）");
        fake.response = aiResponse(List.of(row));

        Response preview = as(admin)
                .body(Map.of("text", "八（1） 周一 第1节 语文 张三 101"))
                .when().post("/api/ai/import/text?target=timetable");

        preview.then().statusCode(200)
                .body("issues", hasSize(1))
                .body("issues[0].field", equalTo("className"))
                .body("issues[0].inferredGrade", equalTo("八年级"))
                .body("issues[0].gradeCandidates", org.hamcrest.Matchers.hasItem("一年级"))
                .body("issues[0].gradeCandidates", org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.hasItem("八年级")));
    }

    @Test
    void aFailedSessionsPreviewCarriesTheReasonItFailed() {
        String admin = adminToken();
        fake.response = "这不是 JSON";

        as(admin).body(Map.of("text", "一年级1班 周一 第1节 语文 张三 101"))
                .when().post("/api/ai/import/text?target=timetable")
                .then().statusCode(400);

        AiImportLog failed = importLogs.findAll().stream()
                .filter(log -> AiImportLog.FAILED.equals(log.getStatus()))
                .max(java.util.Comparator.comparing(AiImportLog::getId))
                .orElseThrow();

        as(admin).when().get("/api/ai/import/sessions/" + failed.getId())
                .then().statusCode(200)
                .body("status", equalTo("FAILED"))
                .body("explanation", containsString("JSON"));
    }

    @Test
    void aRestoredSessionShowsTheEditedStatusAndTheEditedRows() {
        String admin = adminToken();
        timetableFixture(admin);
        fake.response = aiResponse(List.of(timetableRow()));

        Response preview = as(admin)
                .body(Map.of("text", "一年级1班 周一 第1节 语文 张三 101"))
                .when().post("/api/ai/import/text?target=timetable");
        preview.then().statusCode(200).body("status", equalTo("PREVIEW"));
        long sessionId = preview.jsonPath().getLong("sessionId");

        List<Map<String, Object>> rows = preview.jsonPath().getList("rows");
        @SuppressWarnings("unchecked")
        Map<String, Object> values = (Map<String, Object>) rows.get(0).get("values");
        values.put("roomName", "202");
        as(admin).body(Map.of("rows", rows))
                .when().put("/api/ai/import/sessions/" + sessionId + "/rows")
                .then().statusCode(200)
                .body("status", equalTo("EDITED"));

        // The restore entry point must report the edit, so the page can resume it.
        as(admin).when().get("/api/ai/import/sessions/" + sessionId)
                .then().statusCode(200)
                .body("status", equalTo("EDITED"))
                .body("rows[0].values.roomName", equalTo("202"));
    }

    @Test
    void teacherPreviewHasNoMappingIssues() {
        String admin = adminToken();
        fake.response = aiResponse(List.of(teacherRow("张三", "语文", "", 0.99)));

        as(admin).body(Map.of("text", "张三老师教语文"))
                .when().post("/api/ai/import/text?target=teacher")
                .then().statusCode(200)
                .body("issues", hasSize(0))
                .body("suggestedTarget", org.hamcrest.Matchers.nullValue())
                .body("summary", org.hamcrest.Matchers.nullValue());
    }

    @Test
    void timetablePreviewSummarizesNewAndUpdatedAssignments() {
        String admin = adminToken();
        TimetableFixture f = timetableFixture(admin);
        TestData.teacher(admin, "李四");
        TestData.course(admin, "数学");
        as(admin).body(Map.of(
                        "classId", f.classId(),
                        "courseId", f.courseId(),
                        "teacherId", f.teacherId(),
                        "weeklyLessons", 2))
                .when().post("/api/assignments")
                .then().statusCode(200);
        fake.response = aiResponse(List.of(
                timetableRow(),
                timetableRow("数学", "周一", "第2节", "李四")));

        as(admin).body(Map.of("text", "一年级1班 周一 第1节 语文 张三 101"))
                .when().post("/api/ai/import/text?target=timetable")
                .then().statusCode(200)
                .body("issues", hasSize(0))
                .body("summary.lessons", equalTo(2))
                .body("summary.unresolved", equalTo(0))
                .body("summary.groups", equalTo(2))
                .body("summary.newAssignments", equalTo(1))
                .body("summary.updatedAssignments", equalTo(1));
    }

    @Test
    void lookupFindsClassesInTheCurrentSemesterAndOtherEntitiesGlobally() {
        String admin = adminToken();
        TimetableFixture f = timetableFixture(admin);

        as(admin).when().get("/api/ai/import/lookup?type=class&q=1班")
                .then().statusCode(200)
                .body("$", hasSize(1))
                .body("[0].id", equalTo((int) f.classId()))
                .body("[0].name", equalTo("一年级1班"))
                .body("[0].extra", equalTo("一年级"));

        as(admin).when().get("/api/ai/import/lookup?type=course&q=语")
                .then().statusCode(200)
                .body("$", hasSize(1))
                .body("[0].id", equalTo((int) f.courseId()))
                .body("[0].name", equalTo("语文"))
                .body("[0].extra", equalTo("语文"));

        as(admin).when().get("/api/ai/import/lookup?type=teacher&q=张")
                .then().statusCode(200)
                .body("$", hasSize(1))
                .body("[0].id", equalTo((int) f.teacherId()))
                .body("[0].name", equalTo("张三"));

        as(admin).when().get("/api/ai/import/lookup?type=room&q=10")
                .then().statusCode(200)
                .body("$", hasSize(1))
                .body("[0].id", equalTo((int) f.roomId()))
                .body("[0].name", equalTo("101"));
    }

    @Test
    void classLookupUsesOnlyTheCurrentSemester() {
        String admin = adminToken();
        long first = TestData.semester(admin);
        long firstGrade = TestData.grade(admin, first, "一年级");
        TestData.adminClass(admin, first, firstGrade, "旧学期班");
        long second = TestData.semester(admin);
        long secondGrade = TestData.grade(admin, second, "一年级");
        long currentClass = TestData.adminClass(admin, second, secondGrade, "新学期班");

        as(admin).when().get("/api/ai/import/lookup?type=class&q=学期班")
                .then().statusCode(200)
                .body("$", hasSize(1))
                .body("[0].id", equalTo((int) currentClass))
                .body("[0].name", equalTo("新学期班"));
    }

    @Test
    void lookupReturnsAtMostTwentyNamesSortedByName() {
        String admin = adminToken();
        for (int i = 1; i <= 22; i++) {
            TestData.course(admin, String.format("课程%02d", i));
        }

        as(admin).when().get("/api/ai/import/lookup?type=course&q=课程")
                .then().statusCode(200)
                .body("$", hasSize(20))
                .body("[0].name", equalTo("课程01"))
                .body("[19].name", equalTo("课程20"));
    }

    @Test
    void unknownLookupTypeIsRejected() {
        String admin = adminToken();

        as(admin).when().get("/api/ai/import/lookup?type=student&q=x")
                .then().statusCode(400)
                .body("message", containsString("不支持的查询类型"));
    }

    @Test
    void mappingResolvesUnmatchedNamesAndLetsTheImportCommit() {
        String admin = adminToken();
        TimetableFixture f = timetableFixture(admin);
        Map<String, Object> row = timetableRow();
        @SuppressWarnings("unchecked")
        Map<String, Object> values = (Map<String, Object>) row.get("values");
        values.put("className", "一年级(1)班");
        fake.response = aiResponse(List.of(row));

        Response preview = as(admin)
                .body(Map.of("text", "一年级(1)班 周一 第1节 语文 张三 101"))
                .when().post("/api/ai/import/text?target=timetable");
        preview.then().statusCode(200).body("issues", hasSize(1));
        long sessionId = preview.jsonPath().getLong("sessionId");

        as(admin).body(Map.of("mappings", List.of(Map.of(
                        "field", "className",
                        "source", "一年级(1)班",
                        "targetId", f.classId()))))
                .when().post("/api/ai/import/sessions/" + sessionId + "/mapping")
                .then().statusCode(200)
                .body("status", equalTo("EDITED"))
                .body("issues", hasSize(0))
                .body("rows[0].values.className", equalTo("一年级1班"))
                .body("mappings", org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.hasEntry("source", "一年级(1)班"),
                        org.hamcrest.Matchers.hasEntry("target", "一年级1班"),
                        org.hamcrest.Matchers.hasEntry("note", "用户映射（班级）"))));

        as(admin).when().post("/api/ai/import/sessions/" + sessionId + "/commit")
                .then().statusCode(200)
                .body("draftId", org.hamcrest.Matchers.notNullValue());

        AiImportLog stored = importLogs.findById(sessionId).orElseThrow();
        // The AI answer stays untouched; the user's mapping lives in the working copy.
        assertThat(stored.getPreviewJson()).contains("一年级(1)班").doesNotContain("一年级1班");
        assertThat(stored.getConfirmedJson()).contains("一年级1班").contains("一年级(1)班");
    }

    @Test
    void mappingRejectsAnUnknownTargetId() {
        String admin = adminToken();
        timetableFixture(admin);
        fake.response = aiResponse(List.of(timetableRow()));
        long sessionId = timetableSession(admin);

        as(admin).body(Map.of("mappings", List.of(Map.of(
                        "field", "className",
                        "source", "一年级1班",
                        "targetId", 999999))))
                .when().post("/api/ai/import/sessions/" + sessionId + "/mapping")
                .then().statusCode(400)
                .body("message", containsString("找不到班级"));
    }

    @Test
    void mappingRejectsATargetOfTheWrongType() {
        String admin = adminToken();
        timetableFixture(admin);
        long courseId = TestData.course(admin, "数学");
        fake.response = aiResponse(List.of(timetableRow()));
        long sessionId = timetableSession(admin);

        as(admin).body(Map.of("mappings", List.of(Map.of(
                        "field", "className",
                        "source", "一年级1班",
                        "targetId", courseId))))
                .when().post("/api/ai/import/sessions/" + sessionId + "/mapping")
                .then().statusCode(400)
                .body("message", containsString("找不到班级"));
    }

    @Test
    void teacherSessionsDoNotSupportNameMapping() {
        String admin = adminToken();
        fake.response = aiResponse(List.of(teacherRow("张三", "语文", "", 0.99)));
        Response preview = as(admin).body(Map.of("text", "张三老师教语文"))
                .when().post("/api/ai/import/text?target=teacher");
        long sessionId = preview.jsonPath().getLong("sessionId");

        as(admin).body(Map.of("mappings", List.of(Map.of(
                        "field", "className", "source", "x", "targetId", 1))))
                .when().post("/api/ai/import/sessions/" + sessionId + "/mapping")
                .then().statusCode(400)
                .body("message", containsString("不支持名称映射"));
    }

    @Test
    void createsAreOptInAndOnlyRunWhenExplicitlyRequested() {
        String admin = adminToken();
        TimetableFixture f = timetableFixture(admin);
        Map<String, Object> row = timetableRow();
        @SuppressWarnings("unchecked")
        Map<String, Object> values = (Map<String, Object>) row.get("values");
        values.put("courseName", "道法法治");
        fake.response = aiResponse(List.of(row));

        Response preview = as(admin)
                .body(Map.of("text", "一年级1班 周一 第1节 道法法治 张三 101"))
                .when().post("/api/ai/import/text?target=timetable");
        preview.then().statusCode(200)
                .body("issues", hasSize(1))
                .body("issues[0].field", equalTo("courseName"))
                .body("issues[0].value", equalTo("道法法治"));
        long sessionId = preview.jsonPath().getLong("sessionId");

        as(admin).body(Map.of("mappings", List.of(Map.of(
                        "field", "className", "source", "一年级1班", "targetId", f.classId()))))
                .when().post("/api/ai/import/sessions/" + sessionId + "/mapping")
                .then().statusCode(200)
                .body("issues", hasSize(1))
                .body("issues[0].field", equalTo("courseName"));

        assertThat(courseNames(admin)).doesNotContain("道法法治").doesNotContain("道德与法治");

        as(admin).body(Map.of("creates", List.of(Map.of(
                        "field", "courseName", "source", "道法法治", "name", "道德与法治"))))
                .when().post("/api/ai/import/sessions/" + sessionId + "/mapping")
                .then().statusCode(200)
                .body("issues", hasSize(0))
                .body("rows[0].values.courseName", equalTo("道德与法治"));

        assertThat(courseNames(admin)).contains("道德与法治");

        as(admin).when().post("/api/ai/import/sessions/" + sessionId + "/commit")
                .then().statusCode(200)
                .body("draftId", org.hamcrest.Matchers.notNullValue());
    }

    @Test
    void creatingAClassRequiresAGradeIdOrName() {
        String admin = adminToken();
        TimetableFixture f = timetableFixture(admin);
        Map<String, Object> row = timetableRow();
        @SuppressWarnings("unchecked")
        Map<String, Object> values = (Map<String, Object>) row.get("values");
        values.put("className", "八（1）");
        fake.response = aiResponse(List.of(row));
        long sessionId = timetableSession(admin);

        as(admin).body(Map.of("creates", List.of(Map.of(
                        "field", "className", "source", "八（1）", "name", "八年级1班"))))
                .when().post("/api/ai/import/sessions/" + sessionId + "/mapping")
                .then().statusCode(400)
                .body("message", containsString("年级"));

        as(admin).body(Map.of("creates", List.of(Map.of(
                        "field", "className", "source", "八（1）", "name", "八年级1班",
                        "gradeId", f.gradeId()))))
                .when().post("/api/ai/import/sessions/" + sessionId + "/mapping")
                .then().statusCode(200)
                .body("issues", hasSize(0))
                .body("rows[0].values.className", equalTo("八年级1班"));

        assertThat(as(admin).when().get("/api/classes?semesterId=" + f.semesterId())
                .then().statusCode(200).extract().jsonPath().getList("name"))
                .contains("八年级1班");
    }

    @Test
    void aGradeNameCreatesTheMissingGradeAndTheClassWithItsStudentCount() {
        String admin = adminToken();
        TimetableFixture f = timetableFixture(admin);
        Map<String, Object> row = timetableRow();
        @SuppressWarnings("unchecked")
        Map<String, Object> values = (Map<String, Object>) row.get("values");
        values.put("className", "八（1）");
        fake.response = aiResponse(List.of(row));
        Response preview = as(admin)
                .body(Map.of("text", "八（1） 周一 第1节 语文 张三 101"))
                .when().post("/api/ai/import/text?target=timetable");
        preview.then().statusCode(200);
        long sessionId = preview.jsonPath().getLong("sessionId");

        as(admin).body(Map.of("creates", List.of(Map.of(
                        "field", "className", "source", "八（1）", "name", "八年级1班",
                        "gradeName", "八年级", "studentCount", 48))))
                .when().post("/api/ai/import/sessions/" + sessionId + "/mapping")
                .then().statusCode(200)
                .body("issues", hasSize(0))
                .body("rows[0].values.className", equalTo("八年级1班"));

        List<Map<String, Object>> grades = as(admin)
                .when().get("/api/grades?semesterId=" + f.semesterId())
                .then().statusCode(200).extract().jsonPath().getList("$");
        assertThat(grades).hasSize(2);
        assertThat(grades).anySatisfy(grade -> {
            assertThat(grade.get("name")).isEqualTo("八年级");
            assertThat(grade.get("sortOrder")).isEqualTo(2);
        });

        List<Map<String, Object>> classes = as(admin)
                .when().get("/api/classes?semesterId=" + f.semesterId())
                .then().statusCode(200).extract().jsonPath().getList("$");
        assertThat(classes).anySatisfy(adminClass -> {
            assertThat(adminClass.get("name")).isEqualTo("八年级1班");
            assertThat(adminClass.get("gradeName")).isEqualTo("八年级");
            assertThat(adminClass.get("studentCount")).isEqualTo(48);
        });
    }

    @Test
    void anExistingGradeNameIsMatchedInsteadOfCreatingADuplicate() {
        String admin = adminToken();
        TimetableFixture f = timetableFixture(admin);
        fake.response = aiResponse(List.of(timetableRow()));
        long sessionId = timetableSession(admin);

        as(admin).body(Map.of("creates", List.of(Map.of(
                        "field", "className", "source", "一年级(1)班", "name", "一年级2班",
                        "gradeName", "一年级"))))
                .when().post("/api/ai/import/sessions/" + sessionId + "/mapping")
                .then().statusCode(200)
                .body("issues", hasSize(0));

        assertThat(as(admin).when().get("/api/grades?semesterId=" + f.semesterId())
                .then().statusCode(200).extract().jsonPath().getList("name"))
                .containsExactly("一年级");

        List<Map<String, Object>> classes = as(admin)
                .when().get("/api/classes?semesterId=" + f.semesterId())
                .then().statusCode(200).extract().jsonPath().getList("$");
        assertThat(classes).anySatisfy(adminClass -> {
            assertThat(adminClass.get("name")).isEqualTo("一年级2班");
            assertThat(adminClass.get("gradeId")).isEqualTo((int) f.gradeId());
            assertThat(adminClass.get("gradeName")).isEqualTo("一年级");
        });
    }

    @Test
    void oneBatchCanCreateClassesUnderSeveralNewGrades() {
        String admin = adminToken();
        TimetableFixture f = timetableFixture(admin);
        fake.response = aiResponse(List.of(timetableRow()));
        long sessionId = timetableSession(admin);

        as(admin).body(Map.of("creates", List.of(
                        Map.of("field", "className", "source", "八（1）", "name", "八年级1班",
                                "gradeName", "八年级"),
                        Map.of("field", "className", "source", "八（2）", "name", "八年级2班",
                                "gradeName", "八年级"),
                        Map.of("field", "className", "source", "九（1）", "name", "九年级1班",
                                "gradeName", "九年级"))))
                .when().post("/api/ai/import/sessions/" + sessionId + "/mapping")
                .then().statusCode(200)
                .body("issues", hasSize(0));

        // The two 八年级 creates share one new grade.
        assertThat(as(admin).when().get("/api/grades?semesterId=" + f.semesterId())
                .then().statusCode(200).extract().jsonPath().getList("name"))
                .containsExactlyInAnyOrder("一年级", "八年级", "九年级");

        List<Map<String, Object>> classes = as(admin)
                .when().get("/api/classes?semesterId=" + f.semesterId())
                .then().statusCode(200).extract().jsonPath().getList("$");
        assertThat(classes).hasSize(4);
        assertThat(classes).anySatisfy(adminClass -> {
            assertThat(adminClass.get("name")).isEqualTo("八年级2班");
            assertThat(adminClass.get("gradeName")).isEqualTo("八年级");
        });
        assertThat(classes).anySatisfy(adminClass -> {
            assertThat(adminClass.get("name")).isEqualTo("九年级1班");
            assertThat(adminClass.get("gradeName")).isEqualTo("九年级");
        });
    }

    @Test
    void aClassCreatedWithoutAStudentCountDefaultsToFortyFiveAndStaysEditable() {
        String admin = adminToken();
        TimetableFixture f = timetableFixture(admin);
        TestData.adminClass(admin, f.semesterId(), f.gradeId(), "一年级3班");
        fake.response = aiResponse(List.of(timetableRow()));
        long sessionId = timetableSession(admin);

        as(admin).body(Map.of("creates", List.of(
                        Map.of("field", "className", "source", "一年级(2)班", "name", "一年级2班",
                                "gradeName", "一年级"),
                        Map.of("field", "className", "source", "一年级(3)班", "name", "一年级3班",
                                "gradeName", "一年级", "studentCount", 30))))
                .when().post("/api/ai/import/sessions/" + sessionId + "/mapping")
                .then().statusCode(200);

        List<Map<String, Object>> classes = as(admin)
                .when().get("/api/classes?semesterId=" + f.semesterId())
                .then().statusCode(200).extract().jsonPath().getList("$");
        assertThat(classes).anySatisfy(adminClass -> {
            assertThat(adminClass.get("name")).isEqualTo("一年级2班");
            assertThat(adminClass.get("studentCount")).isEqualTo(45);
        });
        // A class the mapping only re-used keeps the count it already had.
        assertThat(classes).anySatisfy(adminClass -> {
            assertThat(adminClass.get("name")).isEqualTo("一年级3班");
            assertThat(adminClass.get("studentCount")).isEqualTo(45);
        });

        // The created class must stay editable: 0 used to fail the edit form's own validation.
        Map<String, Object> created = classes.stream()
                .filter(adminClass -> "一年级2班".equals(adminClass.get("name")))
                .findFirst().orElseThrow();
        as(admin).body(Map.of("gradeId", created.get("gradeId"), "name", "一年级2班改"))
                .when().put("/api/classes/" + created.get("id"))
                .then().statusCode(200)
                .body("name", equalTo("一年级2班改"))
                .body("studentCount", equalTo(45));
    }

    @Test
    void creatingAClassWithANonPositiveStudentCountIsRejectedLikeTheManualForm() {
        String admin = adminToken();
        TimetableFixture f = timetableFixture(admin);
        fake.response = aiResponse(List.of(timetableRow()));
        long sessionId = timetableSession(admin);

        // The manual form is the yardstick: the same value must fail the same way.
        String manualMessage = as(admin)
                .body(Map.of("gradeId", f.gradeId(), "name", "一年级1班", "studentCount", 0))
                .when().put("/api/classes/" + f.classId())
                .then().statusCode(400)
                .body("message", equalTo("参数校验失败"))
                .extract().jsonPath().getString("fields.studentCount");
        assertThat(manualMessage).isNotBlank();

        for (int invalid : new int[] {0, -1}) {
            Response rejection = as(admin).body(Map.of("creates", List.of(Map.of(
                            "field", "className", "source", "八（1）", "name", "八年级1班",
                            "gradeName", "八年级", "studentCount", invalid))))
                    .when().post("/api/ai/import/sessions/" + sessionId + "/mapping")
                    .then().statusCode(400)
                    .body("message", equalTo("参数校验失败"))
                    .extract().response();

            Map<String, String> fields = rejection.jsonPath().getMap("fields");
            assertThat(fields).hasSize(1);
            assertThat(fields.keySet()).anySatisfy(key -> assertThat(key).endsWith("studentCount"));
            assertThat(fields.values()).containsExactly(manualMessage);
        }

        // Rejection happens before any write: nothing was created, no grade either.
        assertThat(adminClasses.findBySemesterIdOrderByGradeIdAscNameAsc(f.semesterId()))
                .extracting(adminClass -> adminClass.getName())
                .containsExactly("一年级1班");
        assertThat(as(admin).when().get("/api/grades?semesterId=" + f.semesterId())
                .then().statusCode(200).extract().jsonPath().getList("name"))
                .containsExactly("一年级");
    }

    @Test
    void aGradeIdWinsOverAGradeNameWhenBothAreSent() {
        String admin = adminToken();
        TimetableFixture f = timetableFixture(admin);
        fake.response = aiResponse(List.of(timetableRow()));
        long sessionId = timetableSession(admin);

        as(admin).body(Map.of("creates", List.of(Map.of(
                        "field", "className", "source", "八（1）", "name", "八年级1班",
                        "gradeId", f.gradeId(), "gradeName", "八年级", "studentCount", 40))))
                .when().post("/api/ai/import/sessions/" + sessionId + "/mapping")
                .then().statusCode(200)
                .body("issues", hasSize(0));

        assertThat(as(admin).when().get("/api/grades?semesterId=" + f.semesterId())
                .then().statusCode(200).extract().jsonPath().getList("name"))
                .containsExactly("一年级");

        List<Map<String, Object>> classes = as(admin)
                .when().get("/api/classes?semesterId=" + f.semesterId())
                .then().statusCode(200).extract().jsonPath().getList("$");
        assertThat(classes).anySatisfy(adminClass -> {
            assertThat(adminClass.get("name")).isEqualTo("八年级1班");
            assertThat(adminClass.get("gradeName")).isEqualTo("一年级");
            assertThat(adminClass.get("studentCount")).isEqualTo(40);
        });
    }

    @Test
    void aClassNameThatExistsUnderAnotherGradeIsRejectedAndLeavesNoNewGrade() {
        String admin = adminToken();
        TimetableFixture f = timetableFixture(admin);
        fake.response = aiResponse(List.of(timetableRow()));
        long sessionId = timetableSession(admin);

        as(admin).body(Map.of("creates", List.of(Map.of(
                        "field", "className", "source", "八（1）", "name", "一年级1班",
                        "gradeName", "八年级"))))
                .when().post("/api/ai/import/sessions/" + sessionId + "/mapping")
                .then().statusCode(400)
                .body("message", containsString("其他年级"));

        // The rejected create also rolls back the grade it would have needed.
        assertThat(as(admin).when().get("/api/grades?semesterId=" + f.semesterId())
                .then().statusCode(200).extract().jsonPath().getList("name"))
                .containsExactly("一年级");
    }

    @Test
    void mappingRequiresTimetableEditBeyondFoundationManage() {
        String admin = adminToken();
        timetableFixture(admin);
        fake.response = aiResponse(List.of(timetableRow()));

        Role role = new Role("foundation_only_map_" + TestData.suffix(), "仅基础数据");
        role.setPermissions(Set.of(
                permissionRepository.findByCode(Permissions.FOUNDATION_MANAGE).orElseThrow()));
        roleRepository.save(role);
        String username = "imp_" + TestData.suffix();
        createUser(admin, username, "import123", role.getCode());
        String importer = login(username, "import123");

        Response preview = as(importer)
                .body(Map.of("text", "一年级1班 周一 第1节 语文 张三 101"))
                .when().post("/api/ai/import/text?target=timetable");
        preview.then().statusCode(200);
        long sessionId = preview.jsonPath().getLong("sessionId");

        as(importer).body(Map.of("mappings", List.of(Map.of(
                        "field", "className", "source", "一年级1班", "targetId", 1))))
                .when().post("/api/ai/import/sessions/" + sessionId + "/mapping")
                .then().statusCode(403)
                .body("message", containsString("没有权限"));
    }

    @Test
    void timetableCommitCreatesADraftInTheCurrentSemesterAndRecordsItsId() {
        String admin = adminToken();
        TimetableFixture fixture = timetableFixture(admin);
        fake.response = aiResponse(List.of(timetableRow()));

        Response preview = as(admin)
                .body(Map.of("text", "一年级1班 周一 第1节 语文 张三 101"))
                .when().post("/api/ai/import/text?target=timetable");
        preview.then().statusCode(200);
        long sessionId = preview.jsonPath().getLong("sessionId");

        Response commit = as(admin).when().post("/api/ai/import/sessions/" + sessionId + "/commit");
        commit.then().statusCode(200)
                .body("imported", equalTo(1))
                .body("draftId", org.hamcrest.Matchers.notNullValue())
                .body("warnings", hasSize(0));
        long draftId = commit.jsonPath().getLong("draftId");

        as(admin).when().get("/api/timetable-drafts/" + draftId)
                .then().statusCode(200)
                .body("draft.status", equalTo("DRAFT"))
                .body("draft.semesterId", equalTo((int) fixture.semesterId()))
                .body("lessons", hasSize(1))
                .body("lessons[0].className", equalTo("一年级1班"))
                .body("lessons[0].courseName", equalTo("语文"))
                .body("lessons[0].teacherName", equalTo("张三"))
                .body("lessons[0].roomName", equalTo("101"));

        AiImportLog stored = importLogs.findById(sessionId).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(AiImportLog.COMMITTED);
        assertThat(stored.getResultJson())
                .contains("\"draftId\":" + draftId)
                .contains("\"lessons\":1");
    }

    @Test
    void timetableCommitReturnsRowErrorsWithCandidatesAndWritesNoDraft() {
        String admin = adminToken();
        timetableFixture(admin);
        Map<String, Object> row = timetableRow();
        @SuppressWarnings("unchecked")
        Map<String, Object> values = (Map<String, Object>) row.get("values");
        values.put("className", "不存在的班级");
        fake.response = aiResponse(List.of(row));

        Response preview = as(admin)
                .body(Map.of("text", "一年级1班 周一 第1节 语文 张三 101"))
                .when().post("/api/ai/import/text?target=timetable");
        preview.then().statusCode(200);
        long sessionId = preview.jsonPath().getLong("sessionId");

        as(admin).when().post("/api/ai/import/sessions/" + sessionId + "/commit")
                .then().statusCode(400)
                .body("errors[0].row", equalTo(1))
                .body("errors[0].field", equalTo("className"))
                .body("errors[0].candidates", org.hamcrest.Matchers.hasItem("一年级1班"));

        assertThat(as(admin).when().get("/api/timetable-drafts")
                .then().statusCode(200).extract().jsonPath().getList("$")).isEmpty();
    }

    @Test
    void timetableCommitRequiresTimetableEditBeyondFoundationManage() {
        String admin = adminToken();
        timetableFixture(admin);
        fake.response = aiResponse(List.of(timetableRow()));

        Role role = new Role("foundation_only_" + TestData.suffix(), "仅基础数据");
        role.setPermissions(Set.of(
                permissionRepository.findByCode(Permissions.FOUNDATION_MANAGE).orElseThrow()));
        roleRepository.save(role);
        String username = "imp_" + TestData.suffix();
        createUser(admin, username, "import123", role.getCode());
        String importer = login(username, "import123");

        Response preview = as(importer)
                .body(Map.of("text", "一年级1班 周一 第1节 语文 张三 101"))
                .when().post("/api/ai/import/text?target=timetable");
        preview.then().statusCode(200);
        long sessionId = preview.jsonPath().getLong("sessionId");

        as(importer).when().post("/api/ai/import/sessions/" + sessionId + "/commit")
                .then().statusCode(403)
                .body("message", containsString("没有权限"));

        assertThat(as(admin).when().get("/api/timetable-drafts")
                .then().statusCode(200).extract().jsonPath().getList("$")).isEmpty();
    }

    @Test
    void unknownTargetIsRejectedWithABadRequest() {
        String admin = adminToken();

        as(admin).body(Map.of("text", "张老师教语文"))
                .when().post("/api/ai/import/text?target=students")
                .then().statusCode(400)
                .body("message", containsString("不支持的导入目标"));
    }

    @Test
    void previewReportsTheTargetTheAiSuggestsWithoutSwitching() {
        String admin = adminToken();
        fake.response = aiResponse(List.of(teacherRow("张三", "语文", "", 0.99)), "timetable");

        as(admin).body(Map.of("text", "一年级1班 周一 第1节 语文 张三"))
                .when().post("/api/ai/import/text?target=teacher")
                .then().statusCode(200)
                .body("targetType", equalTo("teacher"))
                .body("suggestedTarget", equalTo("timetable"));
    }

    @Test
    void textImportWithoutATargetIsTreatedAsWorkbookAndRefused() {
        String admin = adminToken();

        // Nobody named a shape. The workbook flow is spreadsheet-only, so the request stops
        // before any model call instead of reading timetable text into teacher master data.
        as(admin).body(Map.of("text", "一年级1班 周一 第1节 语文 张三 101"))
                .when().post("/api/ai/import/text")
                .then().statusCode(400)
                .body("message", containsString("target=teacher"));

        // Naming workbook explicitly is the same request, not a separate one.
        as(admin).body(Map.of("text", "一年级1班 周一 第1节 语文 张三 101"))
                .when().post("/api/ai/import/text?target=workbook")
                .then().statusCode(400)
                .body("message", containsString("target=timetable"));

        assertThat(fake.calls).isZero();
    }

    @Test
    void timetablePreviewCountsEveryRowAndTheUnresolvedOnes() {
        String admin = adminToken();
        timetableFixture(admin);
        Map<String, Object> unmatched = timetableRow();
        @SuppressWarnings("unchecked")
        Map<String, Object> values = (Map<String, Object>) unmatched.get("values");
        values.put("className", "不存在的班级");
        fake.response = aiResponse(List.of(
                timetableRow(), timetableRow(), timetableRow(), unmatched));

        as(admin).body(Map.of("text", "一年级1班 周一 第1节 语文 张三 101"))
                .when().post("/api/ai/import/text?target=timetable")
                .then().statusCode(200)
                .body("issues", hasSize(1))
                .body("issues[0].row", equalTo(4))
                .body("summary.lessons", equalTo(4))
                .body("summary.unresolved", equalTo(1))
                .body("summary.groups", equalTo(1))
                .body("summary.newAssignments", equalTo(1));
    }

    @Test
    void previewWithoutACurrentSemesterBlocksTheImportAndCommitExplainsWhy() {
        String admin = adminToken();
        fake.response = aiResponse(List.of(timetableRow()));

        Response preview = as(admin)
                .body(Map.of("text", "一年级1班 周一 第1节 语文 张三 101"))
                .when().post("/api/ai/import/text?target=timetable");
        preview.then().statusCode(200)
                .body("issues", hasSize(1))
                .body("issues[0].row", equalTo(0))
                .body("issues[0].field", equalTo("semester"))
                .body("issues[0].message", containsString("请先设置当前学期"))
                .body("summary.lessons", equalTo(1))
                .body("summary.unresolved", equalTo(1));
        long sessionId = preview.jsonPath().getLong("sessionId");

        as(admin).when().post("/api/ai/import/sessions/" + sessionId + "/commit")
                .then().statusCode(400)
                .body("message", containsString("尚未设置当前学期"));
    }

    @Test
    void aStoredTargetThatNoLongerExistsIsABadRequestNotAServerError() {
        String admin = adminToken();
        AiImportLog log = new AiImportLog();
        log.setImportType("text");
        log.setTargetType("students");
        log.setUsername("admin");
        log.setStatus(AiImportLog.PREVIEW);
        log.setRowCount(0);
        log.setImportedCount(0);
        log.setPreviewJson("{\"mappings\":[],\"rows\":[{\"values\":{\"name\":\"张三\"}}]}");
        log.setCreatedAt(Instant.now());
        log.setUpdatedAt(Instant.now());
        AiImportLog saved = importLogs.save(log);

        as(admin).when().get("/api/ai/import/sessions/" + saved.getId())
                .then().statusCode(400)
                .body("message", containsString("导入目标类型无效"));
    }

    @Test
    void imageImportRejectsNonTeacherTargets() throws Exception {
        String admin = adminToken();

        upload(admin, "/api/ai/import/image?target=timetable", "课表照片.png", pngBytes(), "image/png")
                .then().statusCode(400)
                .body("message", containsString("图片导入暂只支持教师"));
        assertThat(fake.calls).isZero();
    }

    @Test
    void imageImportWithoutATargetAsksForOne() throws Exception {
        String admin = adminToken();

        upload(admin, "/api/ai/import/image", "课表照片.png", pngBytes(), "image/png")
                .then().statusCode(400)
                .body("message", containsString("target=teacher"));
        assertThat(fake.calls).isZero();
    }

    @Test
    void anEmptyMappingRequestOnlyRevalidatesThePreview() {
        String admin = adminToken();
        timetableFixture(admin);
        fake.response = aiResponse(List.of(timetableRow()));
        long sessionId = timetableSession(admin);

        as(admin).body(Map.of())
                .when().post("/api/ai/import/sessions/" + sessionId + "/mapping")
                .then().statusCode(200)
                .body("issues", hasSize(0))
                .body("status", equalTo("EDITED"));
    }

    @Test
    void aRejectedCreateDoesNotLeaveEarlierCreatesBehind() {
        String admin = adminToken();
        timetableFixture(admin);
        fake.response = aiResponse(List.of(timetableRow()));
        long sessionId = timetableSession(admin);

        as(admin).body(Map.of("creates", List.of(
                        Map.of("field", "courseName", "source", "道法法治", "name", "道德与法治"),
                        Map.of("field", "className", "source", "八（1）", "name", "八年级1班"))))
                .when().post("/api/ai/import/sessions/" + sessionId + "/mapping")
                .then().statusCode(400)
                .body("message", containsString("年级"));

        // The class creation was invalid, so the valid course creation before it must
        // not have been written either.
        assertThat(courseNames(admin)).doesNotContain("道德与法治");
    }

    @Test
    void csvTitleRowBeforeTheHeaderReachesTheAi() {
        String admin = adminToken();
        fake.response = aiResponse(List.of(teacherRow("赵六", "化学", "13800000004", 0.95)));
        String csv = "2026 教师名单\n姓名,学科,电话\n赵六,化学,13800000004\n";

        Response preview = upload(
                admin, "/api/ai/import/excel?target=teacher", "教师.csv", csv.getBytes(StandardCharsets.UTF_8), "text/csv");
        preview.then().statusCode(200).body("rows", hasSize(1));
        assertThat(fake.lastRequest.messages().get(1).content())
                .contains("[\"2026 教师名单\"]")
                .contains("[\"姓名\",\"学科\",\"电话\"]");
    }

    @Test
    void pastedTextIsParsedAndCommitted() {
        String admin = adminToken();
        fake.response = aiResponse(List.of(
                teacherRow("张三", "语文", "13800000001", 0.99),
                teacherRow("李四", "数学", "13800000002", 0.98)));

        Response preview = as(admin).body(Map.of("text", "张老师教语文，电话 13800000001；李老师教数学"))
                .when().post("/api/ai/import/text?target=teacher");
        preview.then().statusCode(200)
                .body("rows", hasSize(2))
                .body("explanation", containsString("映射"))
                .body("threshold", org.hamcrest.Matchers.notNullValue());

        long sessionId = preview.jsonPath().getLong("sessionId");
        as(admin).when().post("/api/ai/import/sessions/" + sessionId + "/commit")
                .then().statusCode(200)
                .body("imported", equalTo(2));

        assertThat(as(admin).when().get("/api/teachers").then().statusCode(200)
                .extract().jsonPath().getList("name")).containsExactlyInAnyOrder("张三", "李四");
    }

    @Test
    void imageInputUsesTheVisionGatewayAndCanBeDownloaded() throws Exception {
        String admin = adminToken();
        byte[] image = pngBytes();
        fake.response = aiResponse(List.of(teacherRow("王五", "英语", "13800000003", 0.9)));

        Response preview = upload(admin, "/api/ai/import/image?target=teacher", "课表照片.png", image, "image/png");
        preview.then().statusCode(200).body("rows", hasSize(1));

        assertThat(fake.lastRequest).isNotNull();
        assertThat(fake.lastRequest.images()).hasSize(1);
        assertThat(fake.lastRequest.images().get(0).mimeType()).isEqualTo("image/png");

        long sessionId = preview.jsonPath().getLong("sessionId");
        byte[] downloaded = RestAssured.given()
                .header("Authorization", "Bearer " + admin)
                .when().get("/api/ai/import/sessions/" + sessionId + "/file")
                .then().statusCode(200)
                .contentType("image/png")
                .extract().asByteArray();
        assertThat(downloaded).isEqualTo(image);
    }

    @Test
    void csvWithGbkEncodingIsAccepted() {
        String admin = adminToken();
        fake.response = aiResponse(List.of(teacherRow("赵六", "化学", "13800000004", 0.95)));
        String csv = "姓名,学科,电话\n赵六,化学,13800000004\n";
        byte[] bytes = csv.getBytes(Charset.forName("GBK"));

        Response preview = upload(admin, "/api/ai/import/excel?target=teacher", "教师.csv", bytes, "text/csv");
        preview.then().statusCode(200).body("rows", hasSize(1));
        assertThat(fake.lastRequest.messages().get(1).content()).contains("赵六");
    }

    @Test
    void validationFailureBlocksTheWholeCommit() {
        String admin = adminToken();
        Map<String, Object> missingName = teacherRow("", "语文", "13800000005", 0.9);
        fake.response = aiResponse(List.of(missingName, teacherRow("孙七", "物理", "", 0.9)));

        Response preview = as(admin).body(Map.of("text", "某老师没写名字"))
                .when().post("/api/ai/import/text?target=teacher");
        preview.then().statusCode(200);
        long sessionId = preview.jsonPath().getLong("sessionId");

        as(admin).when().post("/api/ai/import/sessions/" + sessionId + "/commit")
                .then().statusCode(400)
                .body("errors[0].field", equalTo("name"))
                .body("message", containsString("校验失败"));

        assertThat(as(admin).when().get("/api/teachers").then().statusCode(200)
                .extract().jsonPath().getList("$")).isEmpty();
    }

    @Test
    void userEditsWinOverAiDuringContinueAndReparseStillWorks() {
        String admin = adminToken();
        fake.response = aiResponse(List.of(teacherRow("张三", "", "", 0.4)));

        Response preview = as(admin).body(Map.of("text", "张三老师"))
                .when().post("/api/ai/import/text?target=teacher");
        long sessionId = preview.jsonPath().getLong("sessionId");

        List<Map<String, Object>> rows = preview.jsonPath().getList("rows");
        @SuppressWarnings("unchecked")
        Map<String, Object> values = (Map<String, Object>) rows.get(0).get("values");
        values.put("subject", "语文");
        as(admin).body(Map.of("rows", rows))
                .when().put("/api/ai/import/sessions/" + sessionId + "/rows")
                .then().statusCode(200);

        fake.response = aiResponse(List.of(teacherRow("AI 覆盖", "数学", "", 0.9)));
        as(admin).when().post("/api/ai/import/sessions/" + sessionId + "/continue")
                .then().statusCode(200)
                .body("status", equalTo("EDITED"))
                .body("rows[0].values.name", equalTo("张三"))
                .body("rows[0].values.subject", equalTo("语文"));

        fake.response = aiResponse(List.of(
                teacherRow("张三", "语文", "", 0.9),
                teacherRow("李四", "数学", "", 0.9)));
        as(admin).when().post("/api/ai/import/sessions/" + sessionId + "/reparse")
                .then().statusCode(200)
                .body("status", equalTo("PREVIEW"))
                .body("rows", hasSize(2));
    }

    @Test
    void teacherCannotUseTheImportPipeline() {
        String admin = adminToken();
        String username = "imp_" + TestData.suffix();
        createUser(admin, username, "teacher123", "teacher");
        String teacher = login(username, "teacher123");

        as(teacher).body(Map.of("text", "张老师教语文"))
                .when().post("/api/ai/import/text")
                .then().statusCode(403);
    }

    @Test
    void unconfiguredAiGivesAClearMessage() {
        String admin = adminToken();
        fake.available = false;

        as(admin).body(Map.of("text", "张老师教语文"))
                .when().post("/api/ai/import/text?target=teacher")
                .then().statusCode(400)
                .body("message", containsString("配置 AI 供应商"));
    }

    // ------------------------------------------------------------------ helpers

    private record TimetableFixture(long semesterId, long gradeId, long classId, long courseId, long teacherId, long roomId) {}

    private TimetableFixture timetableFixture(String token) {
        long semesterId = TestData.semester(token);
        as(token).body(Map.of("morningCount", 4, "afternoonCount", 4, "eveningCount", 0))
                .when().post("/api/semesters/" + semesterId + "/periods/default")
                .then().statusCode(200);
        long gradeId = TestData.grade(token, semesterId, "一年级");
        long classId = TestData.adminClass(token, semesterId, gradeId, "一年级1班");
        long courseId = TestData.course(token, "语文");
        long teacherId = TestData.teacher(token, "张三");
        long roomId = TestData.room(token, "101");
        return new TimetableFixture(semesterId, gradeId, classId, courseId, teacherId, roomId);
    }

    private List<String> courseNames(String token) {
        return as(token).when().get("/api/courses")
                .then().statusCode(200).extract().jsonPath().getList("name");
    }

    private long timetableSession(String token) {
        Response preview = as(token)
                .body(Map.of("text", "一年级1班 周一 第1节 语文 张三 101"))
                .when().post("/api/ai/import/text?target=timetable");
        preview.then().statusCode(200);
        return preview.jsonPath().getLong("sessionId");
    }

    private Response upload(String token, String path, String name, byte[] bytes, String contentType) {
        return RestAssured.given()
                .header("Authorization", "Bearer " + token)
                .multiPart("file", name, bytes, contentType)
                .when().post(path);
    }

    /** Waits for a condition the background parser reaches on its own (no Awaitility here). */
    private void waitFor(java.util.function.BooleanSupplier condition, String what)
            throws Exception {
        for (int i = 0; i < 200; i++) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("等待超时：" + what);
    }

    /** No Awaitility in this project: poll the session endpoint until it leaves PARSING. */
    private io.restassured.path.json.JsonPath awaitSession(String token, long sessionId) throws Exception {
        for (int i = 0; i < 300; i++) {
            Response response = as(token).when().get("/api/ai/import/sessions/" + sessionId);
            response.then().statusCode(200);
            io.restassured.path.json.JsonPath json = response.jsonPath();
            if (!AiImportLog.PARSING.equals(json.getString("status"))) {
                return json;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("导入会话 " + sessionId + " 一直在解析中");
    }

    private byte[] excelBytes(int count) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("教师");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("老师姓名");
            header.createCell(1).setCellValue("任教学科");
            header.createCell(2).setCellValue("联系电话");
            for (int i = 1; i <= count; i++) {
                Row row = sheet.createRow(i);
                row.createCell(0).setCellValue("教师" + i);
                row.createCell(1).setCellValue("语文");
                row.createCell(2).setCellValue("1380000" + String.format("%04d", i));
            }
            workbook.write(out);
            return out.toByteArray();
        }
    }

    private byte[] multiSheetWorkbookBytes() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet data = workbook.createSheet("数据源");
            data.createRow(0).createCell(0).setCellValue("班级");
            Sheet teachers = workbook.createSheet("教师课表");
            teachers.createRow(0).createCell(0).setCellValue("教师");
            workbook.write(out);
            return out.toByteArray();
        }
    }

    private byte[] timetableExcelBytes() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("一年级课表");
            Row title = sheet.createRow(0);
            title.createCell(0).setCellValue("2026 春季课表");
            sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(0, 0, 0, 2));
            Row header = sheet.createRow(1);
            header.createCell(0).setCellValue("班级");
            header.createCell(1).setCellValue("星期一");
            header.createCell(2).setCellValue("星期二");
            Row row = sheet.createRow(2);
            row.createCell(0).setCellValue("一年级1班");
            row.createCell(1).setCellValue("语文");
            row.createCell(2).setCellValue("数学");
            workbook.write(out);
            return out.toByteArray();
        }
    }

    private byte[] timetableExcelBytes(int dataRows) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("一年级课表");
            Row title = sheet.createRow(0);
            title.createCell(0).setCellValue("2026 春季课表");
            Row header = sheet.createRow(1);
            header.createCell(0).setCellValue("班级");
            header.createCell(1).setCellValue("星期一");
            header.createCell(2).setCellValue("星期二");
            for (int i = 0; i < dataRows; i++) {
                Row row = sheet.createRow(2 + i);
                row.createCell(0).setCellValue("一年级" + (i + 1) + "班");
                row.createCell(1).setCellValue("语文");
                row.createCell(2).setCellValue("数学");
            }
            workbook.write(out);
            return out.toByteArray();
        }
    }

    private byte[] timetableExcelWithPeriodRow(int dataRows) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("年级课表");
            Row title = sheet.createRow(0);
            title.createCell(0).setCellValue("2026 春季课表");
            sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(0, 0, 0, 2));
            Row header = sheet.createRow(1);
            header.createCell(0).setCellValue("班级");
            header.createCell(1).setCellValue("星期一");
            header.createCell(2).setCellValue("星期二");
            // The real workbook merges A2:A3, so the period row's first cell reads "班级".
            sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(1, 2, 0, 0));
            Row periods = sheet.createRow(2);
            periods.createCell(0).setCellValue("");
            periods.createCell(1).setCellValue("一");
            periods.createCell(2).setCellValue("二");
            for (int i = 0; i < dataRows; i++) {
                Row row = sheet.createRow(3 + i);
                row.createCell(0).setCellValue("八（" + (i + 1) + "）");
                row.createCell(1).setCellValue("语文");
                row.createCell(2).setCellValue("数学");
            }
            workbook.write(out);
            return out.toByteArray();
        }
    }

    private Map<String, Object> timetableRow() {
        return timetableRow("语文", "周一", "1", "张三");
    }

    private Map<String, Object> timetableRow(String course, String day, String period, String teacher) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("className", "一年级1班");
        values.put("dayOfWeek", day);
        values.put("periodNo", period);
        values.put("courseName", course);
        values.put("teacherName", teacher);
        values.put("roomName", "101");
        Map<String, Object> confidence = new LinkedHashMap<>();
        values.keySet().forEach(field -> confidence.put(field, 0.99));
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("values", values);
        row.put("confidence", confidence);
        return row;
    }

    private byte[] pngBytes() throws Exception {
        BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private List<Map<String, Object>> teachers(int count, String subject, double subjectConfidence) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            rows.add(teacherRow("教师" + i, subject, "1380000" + String.format("%04d", i), subjectConfidence));
        }
        return rows;
    }

    private Map<String, Object> teacherRow(
            String name, String subject, String phone, double subjectConfidence) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("name", name);
        values.put("employeeNo", "");
        values.put("phone", phone);
        values.put("email", "");
        values.put("subject", subject);
        Map<String, Object> confidence = new LinkedHashMap<>();
        confidence.put("name", 0.99);
        confidence.put("employeeNo", 1.0);
        confidence.put("phone", 0.99);
        confidence.put("email", 1.0);
        confidence.put("subject", subjectConfidence);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("values", values);
        row.put("confidence", confidence);
        return row;
    }

    private String aiResponse(List<Map<String, Object>> rows) {
        return aiResponse(rows, null);
    }

    /** A raw AI answer carrying a marker, so a test can tell two rounds apart in the audit. */
    private String markedAiResponse(String marker) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("mappings", List.of());
            body.put("rows", List.of(timetableRow("语文", "周一", "1", "张三")));
            body.put("explanation", marker);
            return new ObjectMapper().writeValueAsString(body);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** The stored {@code ai_raw_response} is a JSON array of the raw answers, in plan order. */
    private List<String> rawAnswers(String json) throws Exception {
        return new ObjectMapper().readValue(json, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
    }

    private String aiResponse(List<Map<String, Object>> rows, String suggestedTarget) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            if (suggestedTarget != null) {
                body.put("suggestedTarget", suggestedTarget);
            }
            body.put("mappings", List.of(Map.of(
                    "source", "老师姓名",
                    "target", "name",
                    "note", "原始「老师姓名」列 → 系统 name 字段")));
            body.put("rows", rows);
            body.put("explanation", "根据表头识别姓名与学科，按中国人民常见的教师名单格式完成映射。");
            return new ObjectMapper().writeValueAsString(body);
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

        volatile boolean available = true;
        volatile String response = "{\"mappings\":[],\"rows\":[],\"explanation\":\"\"}";
        volatile java.util.function.Function<AiRequest, String> responder;
        volatile List<String> userMessages = java.util.Collections.synchronizedList(new ArrayList<>());
        volatile AiRequest lastRequest;
        volatile int calls;
        /** Lets a test keep the background parser in PARSING long enough to observe it. */
        volatile long latencyMs;

        void reset() {
            available = true;
            lastRequest = null;
            calls = 0;
            responder = null;
            userMessages.clear();
            response = "{\"mappings\":[],\"rows\":[],\"explanation\":\"\"}";
            latencyMs = 0;
        }

        @Override
        public AiResult chat(AiRequest request) {
            return answer(request);
        }

        @Override
        public AiResult chatWithVision(AiRequest request) {
            return answer(request);
        }

        @Override
        public boolean configured() {
            return available;
        }

        @Override
        public boolean configured(AiTaskType taskType) {
            return available;
        }

        private AiResult answer(AiRequest request) {
            calls++;
            lastRequest = request;
            userMessages.add(request.messages().size() > 1 ? request.messages().get(1).content() : "");
            if (latencyMs > 0) {
                try {
                    Thread.sleep(latencyMs);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
            String payload = responder == null ? response : responder.apply(request);
            return new AiResult(
                    true, payload, null, null,
                    1L, "fake", 2L, "fake-model",
                    10, 20, 5L, payload);
        }
    }
}
