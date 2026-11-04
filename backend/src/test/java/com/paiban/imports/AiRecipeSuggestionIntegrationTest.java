package com.paiban.imports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paiban.ai.AiGateway;
import com.paiban.ai.AiRequest;
import com.paiban.ai.AiResult;
import com.paiban.ai.AiTaskType;
import com.paiban.ai.domain.AiCallLog;
import com.paiban.ai.domain.AiCallLogRepository;
import com.paiban.ai.service.AiCallLogWriter;
import com.paiban.imports.recipe.ImportRecipeVersion;
import com.paiban.imports.recipe.ImportRecipeVersionRepository;
import com.paiban.scheduling.domain.TimetableDraftRepository;
import com.paiban.support.ApiIntegrationTest;
import com.paiban.support.TestData;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

/**
 * 票 07：没有匹配配方时，让 AI 分析表格结构并给出列映射建议。
 *
 * <p>AI 是可选辅助：未配置时接口返回 200 + available=false；模型输出坏掉时返回
 * success=false + raw，手动路径完全不受影响。建议本身必须再走一遍生产预览管线，
 * 确认能产出数据行后才交给页面。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AiRecipeSuggestionIntegrationTest extends ApiIntegrationTest {

    private static final String CSV = "text/csv";
    private static final String[] DAY_LABELS = {"星期一", "星期二", "星期三", "星期四", "星期五"};

    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    private FakeAiGateway fake;

    @Autowired
    private AiCallLogRepository callLogs;

    @Autowired
    private ImportRecipeVersionRepository recipeVersions;

    @Autowired
    private TimetableDraftRepository drafts;

    @BeforeEach
    void resetFake() {
        fake.reset();
    }

    @Test
    void unconfiguredAiAnswers200WithoutCallingTheModel() {
        fake.available = false;

        Response response = suggest(adminToken(), workbookRows("|"), null, null);

        response.then().statusCode(200)
                .body("available", equalTo(false))
                .body("success", equalTo(false))
                .body("error", equalTo(null));
        assertThat(fake.calls()).isZero();
        assertThat(callLogs.count()).isZero();
    }

    @Test
    void validProposalIsAcceptedAndItsMappingPreviewsWithExpectedCounts() throws Exception {
        String admin = adminToken();
        long semesterId = seededSemester(admin);
        fake.respondWith(aiAnswer());

        Response response = suggest(admin, workbookRows("|"), null, null);
        response.then().statusCode(200)
                .body("available", equalTo(true))
                .body("success", equalTo(true))
                .body("suggestion.layout", equalTo("LONG"))
                .body("suggestion.sheetName", equalTo("CSV"))
                .body("suggestion.headerRow", equalTo(0))
                .body("suggestion.compositeColumn", equalTo(0))
                .body("suggestion.courseColumn", equalTo(1))
                .body("suggestion.teacherColumn", equalTo(2))
                .body("explanation", containsString("复合"));
        JsonNode body = mapper.readTree(response.asString());
        assertThat(body.path("previewSummary").path("slotCount").asInt()).isEqualTo(560);
        assertThat(body.path("previewSummary").path("lessonCount").asInt()).isEqualTo(546);
        assertThat(body.path("previewSummary").path("emptySlotCount").asInt()).isEqualTo(14);
        assertThat(body.path("previewSummary").path("teacherCount").asInt()).isEqualTo(48);
        assertThat(body.path("raw").asText()).contains("compositeColumn");

        // 请求本身：DATA_IMPORT 任务、MANUAL_IMPORT 主体，只带表格摘要而不是整本工作簿。
        AiRequest request = fake.lastRequest();
        assertThat(request.taskType()).isEqualTo(AiTaskType.DATA_IMPORT);
        assertThat(request.subjectType()).isEqualTo("MANUAL_IMPORT");
        String userMessage = request.messages().get(1).content();
        assertThat(userMessage).contains("班级|星期|节次", "CSV", "课程");
        assertThat(userMessage.length()).isLessThan(6_000);

        // 建议映射直接交给生产预览接口：数字与建议里的 previewSummary 一致。
        JsonNode preview = preview(
                admin, workbookRows("|"), mappingFrom(body.path("suggestion")), semesterId);
        assertThat(preview.path("slotCount").asInt()).isEqualTo(560);
        assertThat(preview.path("lessonCount").asInt()).isEqualTo(546);
        assertThat(preview.path("emptySlotCount").asInt()).isEqualTo(14);
        assertThat(preview.path("teacherWarnings").findValuesAsText("value")).contains("信息技术");
        assertThat(preview.path("recipeMatch").path("kind").asText()).isEqualTo("NONE");
    }

    @Test
    void markdownFenceIsToleratedButBrokenOrIllegalAnswersFailSoftly() throws Exception {
        String admin = adminToken();
        seededSemester(admin);

        // 包在 markdown 围栏里的合法 JSON：仍然可用。
        fake.respondWith("```json\n" + aiAnswer() + "\n```");
        suggest(admin, workbookRows("|"), null, null).then()
                .statusCode(200)
                .body("success", equalTo(true))
                .body("suggestion.courseColumn", equalTo(1));

        // 完全不是 JSON：200 + success=false + raw，不抛 500。
        fake.respondWith("抱歉，我无法识别这张表格。");
        Response broken = suggest(admin, workbookRows("|"), null, null);
        broken.then().statusCode(200)
                .body("available", equalTo(true))
                .body("success", equalTo(false))
                .body("raw", equalTo("抱歉，我无法识别这张表格。"))
                .body("error", containsString("JSON"));

        // 字段非法（列索引超出范围）：同样降级，不写坏数据。
        fake.respondWith(aiAnswer(99));
        Response illegal = suggest(admin, workbookRows("|"), null, null);
        illegal.then().statusCode(200)
                .body("success", equalTo(false))
                .body("error", containsString("超出范围"));
        assertThat(illegal.jsonPath().getString("raw")).contains("courseColumn");
    }

    @Test
    void savingTheRecipeKeepsTheAiProposalAndTheConfirmedMapping() throws Exception {
        String admin = adminToken();
        long semesterId = seededSemester(admin);
        fake.respondWith(aiAnswer());

        JsonNode suggestionResponse = mapper.readTree(
                suggest(admin, workbookRows("|"), null, null).asString());
        JsonNode suggestion = suggestionResponse.path("suggestion");
        String raw = suggestionResponse.path("raw").asText();
        Map<String, Object> adopted = mappingFrom(suggestion);

        JsonNode preview = preview(admin, workbookRows("|"), adopted, semesterId);
        Map<String, Object> recipe = new LinkedHashMap<>();
        recipe.put("name", "AI 配方");
        recipe.put("layout", "LONG");
        recipe.put("fingerprint", mapper.convertValue(preview.path("fingerprint"), Map.class));
        recipe.put("columns", adopted);
        recipe.put("options", Map.of("delimiter", "|", "ignoreBlankRows", true));
        recipe.put("expected", Map.of(
                "slotCount", 560, "lessonCount", 546, "emptySlotCount", 14,
                "teacherCount", 48, "courseCount", 16));
        recipe.put("aiSuggested", true);
        recipe.put("aiRawResponse", raw);
        recipe.put("aiSuggestedMapping", mapper.convertValue(suggestion, Map.class));

        Response saved = saveRecipe(admin, recipe, workbookRows("|"));
        saved.then().statusCode(200).body("name", equalTo("AI 配方"));
        long recipeId = saved.jsonPath().getLong("id");

        ImportRecipeVersion version = recipeVersions
                .findByRecipeIdAndVersion(recipeId, 1).orElseThrow();
        JsonNode snapshot = mapper.readTree(version.getSnapshotJson());
        assertThat(snapshot.path("aiSuggested").asBoolean()).isTrue();
        assertThat(snapshot.path("aiRawResponse").asText()).contains("compositeColumn");
        assertThat(snapshot.path("aiSuggestedMapping").path("courseColumn").asInt()).isEqualTo(1);
        // 用户最终确认的 mapping 与 AI 原始建议并排存放，二者可分别追溯。
        assertThat(snapshot.path("columns").path("courseColumn").asInt()).isEqualTo(1);
        assertThat(snapshot.path("columns").path("keyColumn").asInt()).isZero();

        // AI 调用写的是现有 ai_call_log，原始响应可查。
        List<AiCallLog> rows = callLogs.findAll().stream()
                .filter(row -> "data_import".equals(row.getTaskType()))
                .toList();
        assertThat(rows).hasSize(1);
        AiCallLog row = rows.get(0);
        assertThat(row.isSuccess()).isTrue();
        assertThat(row.getSubjectType()).isEqualTo("MANUAL_IMPORT");
        assertThat(row.getResponseJson()).contains("compositeColumn");
        assertThat(row.getRequestJson()).contains("班级|星期|节次");
    }

    @Test
    void aiFailureLeavesTheManualPreviewAndCommitWorking() throws Exception {
        String admin = adminToken();
        long semesterId = seededSemester(admin);
        fake.failWith("TIMEOUT", "AI 超时");

        suggest(admin, workbookRows("|"), null, null).then()
                .statusCode(200)
                .body("available", equalTo(true))
                .body("success", equalTo(false))
                .body("error", containsString("AI 超时"));

        JsonNode preview = preview(admin, workbookRows("|"), mapping("|", 1, 2), semesterId);
        assertThat(preview.path("lessonCount").asInt()).isEqualTo(546);

        JsonNode commit = commit(admin, workbookRows("|"), mapping("|", 1, 2), semesterId, "AI 失败后手动导入", false);
        assertThat(commit.path("draftId").asLong()).isPositive();
        assertThat(drafts.findById(commit.path("draftId").asLong())).isPresent();

        List<AiCallLog> rows = callLogs.findAll().stream()
                .filter(row -> "data_import".equals(row.getTaskType()))
                .toList();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).isSuccess()).isFalse();
        assertThat(rows.get(0).getErrorCode()).isEqualTo("TIMEOUT");
    }

    // ---------------------------------------------------------------- fixtures

    /** The shape a model is expected to answer; {@code courseColumn} is overridable. */
    private String aiAnswer() {
        return aiAnswer(1);
    }

    private String aiAnswer(int courseColumn) {
        return """
                {"layout":"LONG","sheetName":"CSV","headerRow":0,"delimiter":"|",
                 "compositeColumn":0,"classColumn":null,"dayColumn":null,"periodColumn":null,
                 "courseColumn":%d,"teacherColumn":2,"ignoreColumns":[],
                 "reasoning":"班级、星期、节次在复合列里，课程和教师各占一列，因此按复合键映射。"}
                """.formatted(courseColumn);
    }

    /** 14 classes x 5 days x 8 periods = 560 rows; Friday period 7 has no course. */
    private byte[] workbookRows(String keySeparator) {
        StringBuilder csv = new StringBuilder("班级|星期|节次,课程,教师\n");
        int row = 0;
        for (int classIndex = 0; classIndex < 14; classIndex++) {
            String className = "八（" + (classIndex + 1) + "）";
            for (int day = 1; day <= 5; day++) {
                for (int period = 1; period <= 8; period++) {
                    String key = className + keySeparator + DAY_LABELS[day - 1] + keySeparator + period;
                    if (day == 5 && period == 7) {
                        row++;
                        csv.append(key).append(",,\n");
                        continue;
                    }
                    String course = String.format("课程%02d", ((classIndex + day + period) % 16) + 1);
                    String teacher = row == 0
                            ? "信息技术"
                            : String.format("教师%02d", ((classIndex * 7 + day * 3 + period) % 47) + 1);
                    row++;
                    csv.append(key).append(',').append(course).append(',').append(teacher).append('\n');
                }
            }
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    private long seededSemester(String admin) {
        long semesterId = TestData.semester(admin);
        as(admin).body(Map.of(
                        "morningCount", 8,
                        "afternoonCount", 0,
                        "eveningCount", 0,
                        "days", List.of(1, 2, 3, 4, 5)))
                .when().post("/api/semesters/" + semesterId + "/periods/default")
                .then().statusCode(200);
        long grade = TestData.grade(admin, semesterId, "八年级");
        for (int i = 1; i <= 14; i++) {
            TestData.adminClass(admin, semesterId, grade, "八（" + i + "）");
        }
        for (int i = 1; i <= 47; i++) {
            TestData.teacher(admin, String.format("教师%02d", i));
        }
        TestData.teacher(admin, "信息技术");
        for (int i = 1; i <= 16; i++) {
            TestData.course(admin, String.format("课程%02d", i));
        }
        return semesterId;
    }

    private Map<String, Object> mappingFrom(JsonNode suggestion) {
        Map<String, Object> mapping = new LinkedHashMap<>();
        mapping.put("sheetName", suggestion.path("sheetName").asText("CSV"));
        mapping.put("headerRow", suggestion.path("headerRow").asInt());
        mapping.put("ignoreBlankRows", true);
        if (suggestion.hasNonNull("compositeColumn")) {
            mapping.put("keyColumn", suggestion.path("compositeColumn").asInt());
            mapping.put("keyParts", List.of("className", "dayOfWeek", "periodNo"));
            mapping.put("delimiter", suggestion.path("delimiter").asText("|"));
        } else {
            mapping.put("classNameColumn", suggestion.path("classColumn").asInt());
            mapping.put("dayOfWeekColumn", suggestion.path("dayColumn").asInt());
            mapping.put("periodNoColumn", suggestion.path("periodColumn").asInt());
        }
        if (suggestion.hasNonNull("courseColumn")) {
            mapping.put("courseColumn", suggestion.path("courseColumn").asInt());
        }
        if (suggestion.hasNonNull("teacherColumn")) {
            mapping.put("teacherColumn", suggestion.path("teacherColumn").asInt());
        }
        return mapping;
    }

    private Map<String, Object> mapping(String delimiter, int courseColumn, int teacherColumn) {
        Map<String, Object> mapping = new LinkedHashMap<>();
        mapping.put("sheetName", "CSV");
        mapping.put("headerRow", 0);
        mapping.put("delimiter", delimiter);
        mapping.put("keyColumn", 0);
        mapping.put("keyParts", List.of("className", "dayOfWeek", "periodNo"));
        mapping.put("courseColumn", courseColumn);
        mapping.put("teacherColumn", teacherColumn);
        mapping.put("ignoreBlankRows", true);
        return mapping;
    }

    private Response suggest(String token, byte[] file, String sheetName, Integer headerRow) {
        var request = RestAssured.given()
                .header("Authorization", "Bearer " + token)
                .multiPart("file", "课表.csv", file, CSV);
        if (sheetName != null) {
            request.multiPart("sheetName", sheetName);
        }
        if (headerRow != null) {
            request.multiPart("headerRow", String.valueOf(headerRow));
        }
        return request.when().post("/api/imports/manual/ai-suggest");
    }

    private JsonNode preview(String admin, byte[] file, Map<String, Object> mapping, long semesterId)
            throws Exception {
        Response response = upload(
                admin, "/api/imports/manual/preview", file, toJson(mapping),
                "{\"semesterId\":" + semesterId + "}");
        response.then().statusCode(200);
        return mapper.readTree(response.asString());
    }

    private JsonNode commit(
            String admin, byte[] file, Map<String, Object> mapping, long semesterId,
            String draftName, boolean applySuggestedRule) throws Exception {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("semesterId", semesterId);
        options.put("draftName", draftName);
        options.put("applySuggestedRule", applySuggestedRule);
        Response response = upload(admin, "/api/imports/manual/commit", file, toJson(mapping), toJson(options));
        response.then().statusCode(200);
        return mapper.readTree(response.asString());
    }

    private Response upload(String token, String path, byte[] file, String mapping, String options) {
        var request = RestAssured.given()
                .header("Authorization", "Bearer " + token)
                .multiPart("file", "课表.csv", file, CSV);
        if (mapping != null) {
            request.multiPart(new io.restassured.builder.MultiPartSpecBuilder(mapping)
                    .controlName("mapping").mimeType("text/plain")
                    .charset(StandardCharsets.UTF_8).build());
        }
        if (options != null) {
            request.multiPart(new io.restassured.builder.MultiPartSpecBuilder(options)
                    .controlName("options").mimeType("text/plain")
                    .charset(StandardCharsets.UTF_8).build());
        }
        return request.when().post(path);
    }

    private String toJson(Object value) throws Exception {
        return mapper.writeValueAsString(value);
    }

    private Response saveRecipe(String token, Map<String, Object> body, byte[] sample) throws Exception {
        return RestAssured.given()
                .header("Authorization", "Bearer " + token)
                .multiPart(new io.restassured.builder.MultiPartSpecBuilder(toJson(body))
                        .controlName("request").mimeType("text/plain")
                        .charset(StandardCharsets.UTF_8).build())
                .multiPart("sampleFile", "课表样例.csv", sample, CSV)
                .when().post("/api/import-recipes");
    }

    @TestConfiguration
    static class FakeAiConfig {

        @Bean
        @Primary
        FakeAiGateway fakeAiGateway(AiCallLogWriter callLogWriter) {
            return new FakeAiGateway(callLogWriter);
        }
    }

    /**
     * Stands in for the production gateway. It writes through the real
     * {@link AiCallLogWriter}, so the audit row is the production one, not a test
     * imitation; nothing here reaches the network.
     */
    static class FakeAiGateway implements AiGateway {

        private final AiCallLogWriter callLogWriter;
        private volatile boolean available = true;
        private volatile String content = "";
        private volatile String errorCode;
        private volatile String errorMessage;
        private volatile AiRequest lastRequest;
        private volatile int calls;

        FakeAiGateway(AiCallLogWriter callLogWriter) {
            this.callLogWriter = callLogWriter;
        }

        void reset() {
            available = true;
            content = "";
            errorCode = null;
            errorMessage = null;
            lastRequest = null;
            calls = 0;
        }

        void respondWith(String json) {
            content = json;
            errorCode = null;
            errorMessage = null;
        }

        void failWith(String code, String message) {
            errorCode = code;
            errorMessage = message;
        }

        int calls() {
            return calls;
        }

        AiRequest lastRequest() {
            return lastRequest;
        }

        @Override
        public AiResult chat(AiRequest request) {
            calls++;
            lastRequest = request;
            AiResult result = errorCode != null
                    ? new AiResult(false, null, errorCode, errorMessage,
                            null, null, null, null, null, null, 7L, null)
                    : new AiResult(true, content, null, null,
                            11L, "fake", 22L, "fake-model", 100, 50, 12L, content);
            callLogWriter.record(request, null, result);
            return result;
        }

        @Override
        public AiResult chatWithVision(AiRequest request) {
            return chat(request);
        }

        @Override
        public boolean configured() {
            return available;
        }

        @Override
        public boolean configured(AiTaskType taskType) {
            return available;
        }
    }
}
