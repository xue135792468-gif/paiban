package com.paiban.ai.imports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paiban.ai.AiGateway;
import com.paiban.ai.AiRequest;
import com.paiban.ai.AiResult;
import com.paiban.ai.AiTaskType;
import com.paiban.foundation.domain.Period;
import com.paiban.foundation.domain.PeriodRepository;
import com.paiban.support.ApiIntegrationTest;
import com.paiban.support.TestData;
import io.restassured.response.Response;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
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
 * Ticket 08 through the public REST contract: a real workbook references period numbers the
 * semester does not have, the preview offers the computed gap list, the user confirms it, and
 * the periods are appended without touching a single existing row.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class MissingPeriodsIntegrationTest extends ApiIntegrationTest {

    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String DATA_MARKER = "数据（JSON）：";

    @Autowired
    private FakeAiGateway fake;

    @Autowired
    private PeriodRepository periods;

    @Autowired
    private TimetableImportService timetableImports;

    @Autowired
    private AiImportLogRepository logs;

    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicInteger planRounds = new AtomicInteger();

    @BeforeEach
    void resetFake() {
        fake.reset();
        planRounds.set(0);
    }

    @AfterEach
    void cleanUploadedSources() {
        for (AiImportLog log : fake.logs()) {
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
    void previewListsThePeriodsTheFileReferencesButTheSemesterLacks() throws Exception {
        Fixture f = workbookSession();

        Response plan = missingPeriods(f.token(), f.sessionId());

        plan.then().statusCode(200)
                .body("semesterId", equalTo((int) f.semesterId()))
                .body("published", equalTo(false))
                .body("items", hasSize(3))
                .body("items[0].dayOfWeek", equalTo(1))
                .body("items[0].periodNo", equalTo(9))
                .body("items[0].status", equalTo("PROPOSED"))
                .body("items[0].label", equalTo("第9节"))
                .body("items[0].section", equalTo("AFTERNOON"))
                .body("items[0].startTime", equalTo("17:40:00"))
                .body("items[0].endTime", equalTo("18:25:00"))
                .body("items[1].periodNo", equalTo(10))
                .body("items[1].startTime", equalTo("18:35:00"))
                .body("items[2].dayOfWeek", equalTo(2))
                .body("items[2].startTime", equalTo("17:40:00"));
    }

    @Test
    void confirmingAppendsOnlyTheMissingSlotsAndKeepsEveryExistingRow() throws Exception {
        Fixture f = workbookSession();
        Map<String, String> before = periodSnapshot(f.semesterId());
        Response plan = missingPeriods(f.token(), f.sessionId()).then().statusCode(200).extract().response();
        int beforeCount = periods.findBySemesterIdOrderByDayOfWeekAscPeriodNoAsc(f.semesterId()).size();

        Response applied = apply(f.token(), f.sessionId(), applyBody(f.semesterId(), plan));

        applied.then().statusCode(200).body("created", equalTo(3)).body("plan.items", hasSize(0));
        assertThat(periods.findBySemesterIdOrderByDayOfWeekAscPeriodNoAsc(f.semesterId()))
                .hasSize(beforeCount + 3);
        // Identity, label and times of every pre-existing row are exactly what they were.
        Map<String, String> after = periodSnapshot(f.semesterId());
        before.forEach((key, value) -> assertThat(after.get(key)).isEqualTo(value));
        Period added = periods.findBySemesterIdOrderByDayOfWeekAscPeriodNoAsc(f.semesterId()).stream()
                .filter(period -> period.getDayOfWeek() == 1 && period.getPeriodNo() == 9)
                .findFirst().orElseThrow();
        assertThat(added.getStartTime()).isEqualTo(LocalTime.of(17, 40));
        assertThat(added.getEndTime()).isEqualTo(LocalTime.of(18, 25));

        // The blocker the commit used to hit is gone: the draft is created now.
        as(f.token()).when().post("/api/ai/import/sessions/" + f.sessionId() + "/commit")
                .then().statusCode(200)
                .body("errors", hasSize(0));
        assertThat(as(f.token()).when().post("/api/ai/import/sessions/" + f.sessionId() + "/commit")
                .then().statusCode(409));
    }

    @Test
    void confirmingTwiceDoesNotCreateThePeriodsTwice() throws Exception {
        Fixture f = workbookSession();
        Response plan = missingPeriods(f.token(), f.sessionId()).then().statusCode(200).extract().response();
        apply(f.token(), f.sessionId(), applyBody(f.semesterId(), plan)).then().statusCode(200);
        int afterFirst = periods.findBySemesterIdOrderByDayOfWeekAscPeriodNoAsc(f.semesterId()).size();

        apply(f.token(), f.sessionId(), applyBody(f.semesterId(), plan))
                .then().statusCode(200).body("created", equalTo(0)).body("plan.items", hasSize(0));

        assertThat(periods.findBySemesterIdOrderByDayOfWeekAscPeriodNoAsc(f.semesterId()))
                .hasSize(afterFirst);
    }

    @Test
    void aPlanWhoseAnchorsChangedBeforeConfirmingIsRefused() throws Exception {
        Fixture f = workbookSession();
        Response plan = missingPeriods(f.token(), f.sessionId()).then().statusCode(200).extract().response();
        int before = periods.findBySemesterIdOrderByDayOfWeekAscPeriodNoAsc(f.semesterId()).size();

        // The anchors moved: the last afternoon period now ends 15 minutes later.
        saveEightPeriodsWithLateLastSlot(f.token(), f.semesterId());

        apply(f.token(), f.sessionId(), applyBody(f.semesterId(), plan))
                .then().statusCode(409)
                .body("message", containsString("方案已过期"));

        assertThat(periods.findBySemesterIdOrderByDayOfWeekAscPeriodNoAsc(f.semesterId()))
                .hasSize(before);
    }

    @Test
    void aPublishedSemesterExplainsWhyItCannotBeFilled() throws Exception {
        String token = adminToken();
        long semesterId = TestData.semester(token);
        as(token).body(Map.of("morningCount", 2, "afternoonCount", 0, "eveningCount", 0))
                .when().post("/api/semesters/" + semesterId + "/periods/default")
                .then().statusCode(200);
        long gradeId = TestData.grade(token, semesterId, "一年级");
        TestData.adminClass(token, semesterId, gradeId, "一年级1班");
        TestData.course(token, "语文");
        TestData.teacher(token, "张三");
        TestData.room(token, "101");
        TimetableImportService.Result imported = timetableImports.importRows(
                List.of(row("一年级1班", "周一", "第1节", "语文", "张三", "101")), null, "admin");
        assertThat(imported.errors()).isEmpty();
        as(token).when().post("/api/timetable-drafts/" + imported.draftId() + "/publish")
                .then().statusCode(200);
        int before = periods.findBySemesterIdOrderByDayOfWeekAscPeriodNoAsc(semesterId).size();

        scriptPlan();
        // Two rows (six non-empty cells) so the profiler does not mistake the sheet for a note.
        Response preview = upload(token, workbook(Map.of("数据源", List.of(
                List.of("八（1）|星期一|3", "语文", "张三"),
                List.of("八（1）|星期一|4", "数学", "李四"),
                List.of("八（1）|星期一|5", "英语", "王五")))));
        long sessionId = preview.jsonPath().getLong("sessionId");

        missingPeriods(token, sessionId).then().statusCode(200)
                .body("published", equalTo(true))
                .body("items", hasSize(3))
                .body("items[0].status", equalTo("MANUAL"))
                .body("items[0].reason", containsString("已发布"));

        apply(token, sessionId, Map.of(
                "semesterId", semesterId,
                "items", List.of(Map.of(
                        "dayOfWeek", 1, "periodNo", 3,
                        "startTime", "09:50", "endTime", "10:35"))))
                .then().statusCode(409);

        assertThat(periods.findBySemesterIdOrderByDayOfWeekAscPeriodNoAsc(semesterId)).hasSize(before);
    }

    @Test
    void fillingPeriodsNeitherReparsesNorCallsTheModelAgain() throws Exception {
        Fixture f = workbookSession();
        int callsAfterPreview = fake.calls;
        assertThat(callsAfterPreview).isPositive();

        Response plan = missingPeriods(f.token(), f.sessionId()).then().statusCode(200).extract().response();
        apply(f.token(), f.sessionId(), applyBody(f.semesterId(), plan)).then().statusCode(200);
        missingPeriods(f.token(), f.sessionId()).then().statusCode(200).body("items", hasSize(0));
        // Re-reading the session preview is a database read, not a parse.
        as(f.token()).get("/api/ai/import/sessions/" + f.sessionId()).then().statusCode(200);

        assertThat(fake.calls).isEqualTo(callsAfterPreview);
    }

    @Test
    void aSlotNoRuleCanProposeCannotBeWrittenThroughTheApi() throws Exception {
        Fixture f = workbookOnWeekdays();
        Response plan = missingPeriods(f.token(), f.sessionId()).then().statusCode(200).extract().response();
        assertThat(plan.jsonPath().getList("items", Map.class)).hasSize(1);
        assertThat(plan.jsonPath().getString("items[0].status")).isEqualTo("MANUAL");
        int before = periods.findBySemesterIdOrderByDayOfWeekAscPeriodNoAsc(f.semesterId()).size();

        // Guessing a time for a slot no rule can derive must be refused, not written.
        apply(f.token(), f.sessionId(), Map.of(
                "semesterId", f.semesterId(),
                "items", List.of(Map.of(
                        "dayOfWeek", 7, "periodNo", 9,
                        "startTime", "17:40", "endTime", "18:25"))))
                .then().statusCode(409);

        assertThat(periods.findBySemesterIdOrderByDayOfWeekAscPeriodNoAsc(f.semesterId()))
                .hasSize(before);
    }

    @Test
    void aSessionWithoutPreviewDataReportsAnErrorInsteadOfAnEmptyDiff() throws Exception {
        Fixture f = workbookSession();
        AiImportLog log = logs.findById(f.sessionId()).orElseThrow();
        log.setPreviewJson(null);
        logs.save(log);

        // "Nothing is missing" would be a silent success on data we never read.
        missingPeriods(f.token(), f.sessionId())
                .then().statusCode(409)
                .body("message", containsString("预览数据不可用"));

        apply(f.token(), f.sessionId(), Map.of(
                        "semesterId", f.semesterId(),
                        "items", List.of(Map.of(
                                "dayOfWeek", 1, "periodNo", 9,
                                "startTime", "17:40", "endTime", "18:25"))))
                .then().statusCode(409)
                .body("message", containsString("预览数据不可用"));
    }

    /** A workbook that uses Sunday's 9th period, which no day of the semester has. */
    private Fixture workbookOnWeekdays() throws Exception {
        String token = adminToken();
        long semesterId = TestData.semester(token);
        as(token).body(Map.of(
                        "morningCount", 4, "afternoonCount", 4, "eveningCount", 0,
                        "days", List.of(1, 2, 3, 4, 5)))
                .when().post("/api/semesters/" + semesterId + "/periods/default")
                .then().statusCode(200);
        scriptPlan();
        Response preview = upload(token, workbook(Map.of("数据源", List.of(
                List.of("八（1）|星期日|9", "语文", "张三"),
                List.of("八（1）|星期一|1", "数学", "李四"),
                List.of("八（2）|星期二|2", "英语", "王五")))));
        preview.then().statusCode(200);
        return new Fixture(token, semesterId, preview.jsonPath().getLong("sessionId"));
    }

    // ------------------------------------------------------------------ fixture

    private record Fixture(String token, long semesterId, long sessionId) {}

    /** Eight default periods per weekday, and a workbook that uses periods 9, 10 and 9. */
    private Fixture workbookSession() throws Exception {
        String token = adminToken();
        long semesterId = TestData.semester(token);
        as(token).body(Map.of(
                        "morningCount", 4, "afternoonCount", 4, "eveningCount", 0,
                        "days", List.of(1, 2, 3, 4, 5)))
                .when().post("/api/semesters/" + semesterId + "/periods/default")
                .then().statusCode(200);
        scriptPlan();
        Response preview = upload(token, workbook(Map.of("数据源", List.of(
                List.of("八（1）|星期一|9", "语文", "张三"),
                List.of("八（1）|星期一|10", "数学", "李四"),
                List.of("八（2）|星期二|9", "英语", "王五")))));
        preview.then().statusCode(200).body("rows", hasSize(3));
        return new Fixture(token, semesterId, preview.jsonPath().getLong("sessionId"));
    }

    private Response missingPeriods(String token, long sessionId) {
        return as(token).when().get("/api/ai/import/sessions/" + sessionId + "/missing-periods");
    }

    private Response apply(String token, long sessionId, Map<String, Object> body) {
        return as(token).body(body).when().post("/api/ai/import/sessions/" + sessionId + "/missing-periods");
    }

    /** The slots the preview proposed, exactly as the user would confirm them. */
    private Map<String, Object> applyBody(long semesterId, Response plan) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Object raw : plan.jsonPath().getList("items")) {
            Map<?, ?> item = (Map<?, ?>) raw;
            items.add(Map.of(
                    "dayOfWeek", item.get("dayOfWeek"),
                    "periodNo", item.get("periodNo"),
                    "startTime", item.get("startTime"),
                    "endTime", item.get("endTime")));
        }
        return Map.of("semesterId", semesterId, "items", items);
    }

    /** "day-period" → "id|label|start|end", to prove existing rows are untouched. */
    private Map<String, String> periodSnapshot(long semesterId) {
        Map<String, String> snapshot = new LinkedHashMap<>();
        for (Period period : periods.findBySemesterIdOrderByDayOfWeekAscPeriodNoAsc(semesterId)) {
            snapshot.put(period.getDayOfWeek() + "-" + period.getPeriodNo(),
                    period.getId() + "|" + period.getLabel() + "|" + period.getStartTime()
                            + "|" + period.getEndTime());
        }
        return snapshot;
    }

    /** Writes the same 8 slots the default schedule makes, but the last one ends later. */
    private void saveEightPeriodsWithLateLastSlot(String token, long semesterId) {
        List<Map<String, Object>> slots = new ArrayList<>();
        String[][] times = {
                {"08:00", "08:45"}, {"08:55", "09:40"}, {"09:50", "10:35"}, {"10:45", "11:30"},
                {"14:00", "14:45"}, {"14:55", "15:40"}, {"15:50", "16:35"}, {"17:00", "17:45"}};
        for (int i = 0; i < times.length; i++) {
            slots.add(Map.of(
                    "periodNo", i + 1,
                    "label", "第" + (i + 1) + "节",
                    "section", i < 4 ? "MORNING" : "AFTERNOON",
                    "startTime", times[i][0],
                    "endTime", times[i][1],
                    "days", List.of(1, 2, 3, 4, 5)));
        }
        as(token).body(Map.of("slots", slots))
                .when().put("/api/semesters/" + semesterId + "/periods")
                .then().statusCode(200);
    }

    // ------------------------------------------------------------------- stub

    private boolean isPlanner(AiRequest request) {
        return !request.messages().isEmpty() && request.messages().get(0).content().contains("只读工具");
    }

    private void scriptPlan() {
        fake.responder = request -> {
            if (!isPlanner(request)) {
                return extractionAnswer(request);
            }
            int round = planRounds.incrementAndGet();
            return switch (round) {
                case 1 -> "{\"tool\":\"list_sheets\"}";
                case 2 -> "{\"tool\":\"read_sheet\",\"args\":{\"sheet\":\"数据源\",\"offset\":0,\"limit\":5}}";
                default -> finishAction();
            };
        };
    }

    private String finishAction() {
        Map<String, Object> packed = new LinkedHashMap<>();
        packed.put("className", "列1·班级");
        packed.put("dayOfWeek", "列1·星期");
        packed.put("periodNo", "列1·节次");
        packed.put("courseName", "列2");
        packed.put("teacherName", "列3");
        Map<String, Object> sheet = new LinkedHashMap<>();
        sheet.put("sheet", "数据源");
        sheet.put("category", "timetable");
        sheet.put("columns", packed);
        sheet.put("note", "无表头，复合列已拆分");
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("sheets", List.of(sheet));
        args.put("explanation", "数据源逐节课。");
        return json(Map.of("tool", "finish", "args", args));
    }

    /** Reads the batch the extractor sent and copies each mapped column into its field. */
    private String extractionAnswer(AiRequest request) {
        try {
            String prompt = request.messages().get(1).content();
            int marker = prompt.indexOf(DATA_MARKER);
            JsonNode payload = mapper.readTree(prompt.substring(marker + DATA_MARKER.length()).trim());
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
            return json(body);
        } catch (Exception ex) {
            throw new IllegalStateException("无法按表结构构造回答", ex);
        }
    }

    // ------------------------------------------------------------------ helpers

    private Response upload(String token, byte[] bytes) throws Exception {
        Response accepted = io.restassured.RestAssured.given()
                .header("Authorization", "Bearer " + token)
                .multiPart("file", "缺节工作簿.xlsx", bytes, XLSX)
                .queryParam("target", "workbook")
                .when().post("/api/ai/import/excel");
        accepted.then().statusCode(200);
        // The workbook upload answers PARSING; these assertions are about the settled preview.
        long sessionId = accepted.jsonPath().getLong("sessionId");
        for (int attempt = 0; attempt < 300; attempt++) {
            Response session = as(token).when().get("/api/ai/import/sessions/" + sessionId);
            session.then().statusCode(200);
            if (!"PARSING".equals(session.jsonPath().getString("status"))) {
                return session;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("后台解析在 30 秒内没有结束: session " + sessionId);
    }

    private byte[] workbook(Map<String, List<List<String>>> sheets) throws IOException {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
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

    private static ImportDtos.RowDto row(
            String className, String day, String period, String course, String teacher, String room) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("className", className);
        values.put("dayOfWeek", day);
        values.put("periodNo", period);
        values.put("courseName", course);
        values.put("teacherName", teacher);
        values.put("roomName", room);
        return new ImportDtos.RowDto(values, Map.of(), List.of());
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
        FakeAiGateway fakeAiGateway(AiImportLogRepository logs) {
            return new FakeAiGateway(logs);
        }
    }

    static class FakeAiGateway implements AiGateway {

        private final AiImportLogRepository logs;
        volatile java.util.function.Function<AiRequest, String> responder;
        volatile int calls;

        FakeAiGateway(AiImportLogRepository logs) {
            this.logs = logs;
        }

        List<AiImportLog> logs() {
            return logs.findAll();
        }

        void reset() {
            responder = null;
            calls = 0;
        }

        @Override
        public AiResult chat(AiRequest request) {
            calls++;
            String payload = responder == null
                    ? "{\"tool\":\"finish\",\"args\":{\"sheets\":[]}}"
                    : responder.apply(request);
            return new AiResult(
                    true, payload, null, null,
                    1L, "fake", 2L, "fake-model",
                    10, 20, 5L, payload);
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
