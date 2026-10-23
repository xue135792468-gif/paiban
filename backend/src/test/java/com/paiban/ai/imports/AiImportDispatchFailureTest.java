package com.paiban.ai.imports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.paiban.support.ApiIntegrationTest;
import io.restassured.response.Response;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.TaskExecutor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * The background parser lives on its own single-threaded pool: when that pool refuses the
 * job (queue full, shutting down), the session must not be left claiming it is parsing
 * with nobody working on it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(AiImportIntegrationTest.FakeAiConfig.class)
class AiImportDispatchFailureTest extends ApiIntegrationTest {

    private static final String XLSX =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    /** A pool that refuses every job, the way a saturated import queue does. */
    @MockitoBean(name = "importExecutor")
    private TaskExecutor importExecutor;

    @Autowired
    private AiImportLogRepository importLogs;

    @Test
    void anUploadThatCannotBeQueuedEndsAsAFailedSessionWithAConflict() throws Exception {
        doThrow(new RejectedExecutionException("队列已满")).when(importExecutor).execute(any());
        String admin = adminToken();

        Response upload = upload(admin, "/api/ai/import/excel?target=timetable",
                "一年级课表.xlsx", timetableExcelBytes(4));

        // The caller is told the queue refused the work, not handed a PARSING session
        // that no thread will ever finish.
        upload.then().statusCode(409).body("message", containsString("系统繁忙"));

        List<AiImportLog> sessions = importLogs.findAll();
        assertThat(sessions).hasSize(1);
        AiImportLog stored = sessions.get(0);
        assertThat(stored.getStatus()).isEqualTo(AiImportLog.FAILED);
        assertThat(stored.getAiExplanation()).contains("系统繁忙");
        // The plan is still on the row, so the page can say what was never parsed.
        assertThat(stored.getTotalBatches()).isEqualTo(4);
        assertThat(stored.getCurrentBatch()).isNull();
    }

    @Test
    void aRetryThatCannotBeQueuedGoesBackToPartialWithAConflict() {
        doThrow(new RejectedExecutionException("队列已满")).when(importExecutor).execute(any());
        String admin = adminToken();
        long sessionId = partialSession();

        as(admin).when().post("/api/ai/import/sessions/" + sessionId + "/retry")
                .then().statusCode(409)
                .body("message", containsString("系统繁忙"));

        // Not PARSING: the retry never started, so the session is still the partial one
        // the user can try again (or abandon).
        AiImportLog stored = importLogs.findById(sessionId).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(AiImportLog.PARTIAL);
        assertThat(stored.getAiExplanation()).contains("系统繁忙");
        as(admin).when().get("/api/ai/import/sessions/" + sessionId)
                .then().statusCode(200)
                .body("status", equalTo("PARTIAL"))
                .body("failedBatches[0].label", equalTo("一年级2班"));
    }

    /** A session left {@code PARTIAL} by an earlier round, with one class still failing. */
    private long partialSession() {
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
                + "\"sourceRows\":[[\"一年级1班\",\"语文\"]]},"
                + "{\"index\":2,\"label\":\"一年级2班\",\"status\":\"FAILED\","
                + "\"reason\":\"模型返回为空\",\"sourceRows\":[[\"一年级2班\",\"数学\"]]}]");
        log.setCreatedAt(Instant.now());
        log.setUpdatedAt(Instant.now());
        return importLogs.save(log).getId();
    }

    private Response upload(String token, String path, String name, byte[] bytes) {
        return io.restassured.RestAssured.given()
                .header("Authorization", "Bearer " + token)
                .multiPart("file", name, bytes, XLSX)
                .when().post(path);
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
            for (int i = 0; i < dataRows; i++) {
                Row row = sheet.createRow(2 + i);
                row.createCell(0).setCellValue("一年级" + (i + 1) + "班");
                row.createCell(1).setCellValue("语文");
            }
            workbook.write(out);
            return out.toByteArray();
        }
    }
}
