package com.paiban.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paiban.ai.domain.AiCallLogRepository;
import com.paiban.ai.imports.AiImportLog;
import com.paiban.ai.imports.AiImportLogRepository;
import com.paiban.scheduling.domain.ConstraintConfigRepository;
import com.paiban.scheduling.domain.ConstraintEntryRepository;
import com.paiban.scheduling.domain.DraftLessonRepository;
import com.paiban.substitution.domain.Substitution;
import com.paiban.substitution.domain.SubstitutionRepository;
import com.paiban.support.ApiIntegrationTest;
import com.paiban.support.ScheduleFixture;
import com.paiban.support.TestData;
import com.paiban.timetable.domain.TimetableEntryRepository;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 票 16 的全量验收：用一份与真实工作簿同规格的 560 槽合成 fixture 串起完整主链。
 *
 * <p>本类不读 D 盘物理文件，也不依赖任何外部文件：本文件内合成一份与真实工作簿
 * 同规格的长表（14 个班 × 5 天 × 8 节 = 560 个槽位，周五第 7 节全空，共 546 节
 * 有课、14 个空槽、48 名教师、16 门课程），因此断言的核心是「按行数、主体不丢」：
 * 预览统计、入库课节数、发布后的基线行数、撤回再发布、调代叠加、规则增删与回收站
 * 恢复、以及全链结束后旧数据仍在。D 盘物理文件的数字由
 * {@code ManualImportIntegrationTest.realWorkbookMatchesTheSpecNumbers} 单独验收。
 *
 * <p>票 15 收敛掉的审批链端点不再出现，本测试也不去伪造它们；直接生效的调代走
 * 票据 15 之后保留的 {@code POST /api/substitution-requests} 自助路径。
 * (f2) 用工作台「恢复基线」验证调代可撤销。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class FullChainAcceptanceTest extends ApiIntegrationTest {

    private static final String CSV = "text/csv";
    private static final String[] DAY_LABELS = {"星期一", "星期二", "星期三", "星期四", "星期五"};
    private static final int CLASS_COUNT = 14;
    private static final int LESSON_COUNT = 546;

    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    private DraftLessonRepository draftLessons;

    @Autowired
    private TimetableEntryRepository entries;

    @Autowired
    private ConstraintConfigRepository constraintConfigs;

    @Autowired
    private ConstraintEntryRepository constraintEntries;

    @Autowired
    private SubstitutionRepository substitutions;

    @Autowired
    private AiCallLogRepository aiCallLogs;

    @Autowired
    private AiImportLogRepository aiImportLogs;

    @Test
    void realWorkbookMainChainPassesEndToEnd() throws Exception {
        String admin = adminToken();
        String suffix = TestData.suffix();

        // (a) 学期 + 默认作息：未来学期（调代截止按真实时钟判定），每天 8 节、周一至周五。
        long semesterId = seededSemester(admin);
        LocalDate monday = ScheduleFixture.nextMonday();

        // 旧数据不丢失的对照组：手工导入不写 ai_import_log，这里预置一条历史会话行，
        // 全链跑完后它必须仍然在库（票 16「旧数据不丢失」）。
        AiImportLog legacyImport = new AiImportLog();
        legacyImport.setUsername("admin");
        legacyImport.setImportType("WORKBOOK");
        legacyImport.setTargetType("TIMETABLE");
        legacyImport.setFileName("历史导入.xlsx");
        legacyImport.setStatus(AiImportLog.COMMITTED);
        legacyImport.setRowCount(560);
        legacyImport.setImportedCount(LESSON_COUNT);
        long legacyImportId = aiImportLogs.save(legacyImport).getId();

        byte[] csv = workbookRows("|");

        // (b) 首次导入预览：560 槽 / 546 有课 / 14 空槽 / 48 教师 / 16 课程。
        JsonNode preview = preview(admin, csv, mapping("|", 0, 1, 2), semesterId);
        assertThat(preview.path("slotCount").asInt()).isEqualTo(560);
        assertThat(preview.path("lessonCount").asInt()).isEqualTo(LESSON_COUNT);
        assertThat(preview.path("emptySlotCount").asInt()).isEqualTo(14);
        assertThat(preview.path("teacherCount").asInt()).isEqualTo(48);
        assertThat(preview.path("courseCount").asInt()).isEqualTo(16);
        assertThat(preview.path("suggestedRule").path("dayOfWeek").asInt()).isEqualTo(5);
        assertThat(preview.path("suggestedRule").path("periodNo").asInt()).isEqualTo(7);

        // (c) 入库成草稿：546 节课写入，草稿行数回查一致。
        JsonNode commit = commit(admin, csv, mapping("|", 0, 1, 2), semesterId, "真实工作簿导入", true);
        assertThat(commit.path("draftId").asLong()).isPositive();
        assertThat(commit.path("imported").asInt()).isEqualTo(LESSON_COUNT);
        assertThat(commit.path("skippedEmpty").asInt()).isEqualTo(14);
        assertThat(commit.path("errors")).isEmpty();
        long draftId = commit.path("draftId").asLong();
        assertThat(draftLessons.countByDraftId(draftId)).isEqualTo(LESSON_COUNT);

        // (d) 第二次同格式导入：保存配方后不再选列，命中的是配方而不是 AI。
        Response saved = saveRecipe(admin, recipeBody(preview, "真实长表配方"), csv);
        saved.then().statusCode(200);
        long recipeId = saved.jsonPath().getLong("id");

        JsonNode second = preview(admin, csv, mapping("|", 0, 1, 2), semesterId);
        assertThat(second.path("recipeMatch").path("kind").asText()).isEqualTo("EXACT");
        assertThat(second.path("recipeMatch").path("recipeName").asText()).isEqualTo("真实长表配方");

        // 不带 mapping 的第二次预览/入库才是真正的“套用配方”。
        JsonNode auto = preview(admin, csv, Map.of(), semesterId);
        assertThat(auto.path("recipeMatch").path("kind").asText()).isEqualTo("EXACT");
        assertThat(auto.path("lessonCount").asInt()).isEqualTo(LESSON_COUNT);
        assertThat(recipeDetail(admin, recipeId).hasNonNull("lastUsedAt")).isTrue();
        JsonNode reused = commit(admin, csv, Map.of(), semesterId, "配方导入", false);
        assertThat(reused.path("appliedRecipeId").asLong()).isEqualTo(recipeId);
        // 手工导入的整条链路都不调用 AI：调用日志必须为空。
        assertThat(aiCallLogs.count()).isZero();

        // (e) 发布 → 撤回 → 重新发布，基线行数始终 546。
        as(admin).body(Map.of())
                .when().post("/api/timetable-drafts/" + draftId + "/publish")
                .then().statusCode(200)
                .body("entryCount", org.hamcrest.Matchers.equalTo(LESSON_COUNT));
        assertThat(entries.countBySemesterId(semesterId)).isEqualTo(LESSON_COUNT);

        as(admin).when().post("/api/timetable-drafts/" + draftId + "/withdraw")
                .then().statusCode(200)
                .body("removedEntries", org.hamcrest.Matchers.equalTo(LESSON_COUNT))
                .body("cancelledSubstitutions", org.hamcrest.Matchers.equalTo(0));
        assertThat(entries.countBySemesterId(semesterId)).isZero();
        as(admin).when().get("/api/timetable-drafts/" + draftId)
                .then().statusCode(200)
                .body("draft.status", org.hamcrest.Matchers.equalTo("DRAFT"));

        as(admin).body(Map.of())
                .when().post("/api/timetable-drafts/" + draftId + "/publish")
                .then().statusCode(200)
                .body("entryCount", org.hamcrest.Matchers.equalTo(LESSON_COUNT));
        assertThat(entries.countBySemesterId(semesterId)).isEqualTo(LESSON_COUNT);

        // (f) 调代直接生效（票 15 保留的自助路径）：提交后叠加到已发布课表上。
        long classId = classIdByName(admin, semesterId, "八（1）");
        Map<String, Object> slot = entryAt(admin, classId, 1, 1);
        long entryId = number(slot, "id");
        long substituteTeacherId = TestData.teacher(admin, "代课老师" + suffix);
        Response submitted = as(admin).body(Map.of(
                        "entryId", entryId,
                        "effectiveDate", monday.toString(),
                        "reason", "验收代课",
                        "expectedSubstituteTeacherId", substituteTeacherId))
                .when().post("/api/substitution-requests");
        submitted.then().statusCode(200)
                .body("entryId", org.hamcrest.Matchers.equalTo((int) entryId))
                .body("substituteTeacherId", org.hamcrest.Matchers.equalTo((int) substituteTeacherId))
                .body("status", org.hamcrest.Matchers.equalTo("EFFECTIVE"));

        List<Map<String, Object>> dated = as(admin).queryParam("date", monday.toString())
                .when().get("/api/timetable/class/" + classId)
                .then().statusCode(200)
                .extract().jsonPath().getList("$");
        assertThat(dated).anySatisfy(row -> {
            assertThat(number(row, "id")).isEqualTo(entryId);
            assertThat(row.get("substituted")).isEqualTo(true);
        });
        assertThat(substitutions.findBySemesterIdOrderByEffectiveDateDesc(semesterId)).hasSize(1);
        assertThat(entries.countBySemesterId(semesterId)).isEqualTo(LESSON_COUNT);

        // (f2) 调代可撤销 = 工作台「恢复基线」（票 16 第 4 项）：对已代课的实例再发一单，
        // 把任课教师换回基线教师；旧生效行转 CANCELLED，新行 EFFECTIVE，历史保留。
        Response candidates = as(admin)
                .queryParam("entryId", entryId)
                .queryParam("lessonDate", monday.toString())
                .when().get("/api/substitution-workbench/candidates");
        candidates.then().statusCode(200);
        long baselineTeacherId = candidates.jsonPath().getLong("baselineTeacherId");
        long currentTeacherId = candidates.jsonPath().getLong("currentTeacherId");
        // 基线教师 = 基线条目的原任课教师；当前教师已被 (f) 换成代课老师。
        assertThat(baselineTeacherId).isEqualTo(number(slot, "teacherId"));
        assertThat(currentTeacherId).isEqualTo(substituteTeacherId);
        // 前置确认：代课确实改变了任课教师（由上面两行可直接推出，保留作 f2 语义的显式声明）。
        assertThat(baselineTeacherId).isNotEqualTo(currentTeacherId);

        Response restorePreview = as(admin).body(Map.of(
                        "entryId", entryId,
                        "lessonDate", monday.toString(),
                        "substituteTeacherId", baselineTeacherId,
                        "reason", "恢复基线/撤销代课"))
                .when().post("/api/substitution-workbench/previews");
        restorePreview.then().statusCode(200);
        long voucherId = restorePreview.jsonPath().getLong("voucherId");
        assertThat(restorePreview.jsonPath().getLong("previousTeacherId"))
                .isEqualTo(substituteTeacherId);

        as(admin).body(Map.of("comment", "撤销代课，恢复基线"))
                .when().post("/api/substitution-workbench/previews/" + voucherId + "/confirm")
                .then().statusCode(200)
                .body("status", org.hamcrest.Matchers.equalTo("EFFECTIVE"));

        // 撤销生效：带日期视图里这一节的当前实际教师回到基线教师。产品口径是
        // 「恢复基线」（再发一单把教师换回原任课教师），读模型按最新 EFFECTIVE
        // 覆盖行展示，所以这一节仍带调代标记，但实际授课教师已是基线教师。
        List<Map<String, Object>> restored = as(admin).queryParam("date", monday.toString())
                .when().get("/api/timetable/class/" + classId)
                .then().statusCode(200)
                .extract().jsonPath().getList("$");
        assertThat(restored).anySatisfy(row -> {
            assertThat(number(row, "id")).isEqualTo(entryId);
            assertThat(number(row, "teacherId")).isEqualTo(baselineTeacherId);
        });

        // 历史保留：旧代课行转 CANCELLED，恢复基线的新行 EFFECTIVE；课表行数不变。
        assertThat(substitutions.findBySemesterIdAndStatus(semesterId, Substitution.CANCELLED))
                .hasSize(1)
                .allSatisfy(row -> assertThat(row.getSubstituteTeacherId())
                        .isEqualTo(substituteTeacherId));
        assertThat(substitutions.findBySemesterIdAndStatus(semesterId, Substitution.EFFECTIVE))
                .hasSize(1)
                .allSatisfy(row -> assertThat(row.getSubstituteTeacherId())
                        .isEqualTo(baselineTeacherId));
        assertThat(entries.countBySemesterId(semesterId)).isEqualTo(LESSON_COUNT);

        // (g) 规则积木：创建一条带全局默认与教师例外的规则，再删除整条类型。
        long version = as(admin).queryParam("semesterId", semesterId)
                .when().get("/api/constraints/state")
                .then().statusCode(200)
                .extract().jsonPath().getLong("version");
        List<Map<String, Object>> configs = existingConfigRequests(admin, semesterId);
        configs.add(teacherMaxPerDayConfig(substituteTeacherId));
        as(admin).queryParam("semesterId", semesterId).queryParam("baseVersion", version)
                .body(Map.of("configs", configs))
                .when().put("/api/constraints")
                .then().statusCode(200);

        long ruleConfigId = ruleConfigId(admin, semesterId, "TEACHER_MAX_PER_DAY");
        assertThat(constraintEntries.findBySemesterIdAndTypeOrderByIdAsc(
                semesterId, "TEACHER_MAX_PER_DAY")).hasSize(2);

        String deleteBatch = as(admin)
                .when().delete("/api/rules/" + ruleConfigId)
                .then().statusCode(200)
                .body("deletedCount", org.hamcrest.Matchers.equalTo(2))
                .extract().jsonPath().getString("batchId");
        assertThat(deleteBatch).isNotBlank();
        assertThat(ruleConfigIdOrNull(admin, semesterId, "TEACHER_MAX_PER_DAY")).isNull();
        // 规则增删不影响已发布课表。
        assertThat(entries.countBySemesterId(semesterId)).isEqualTo(LESSON_COUNT);

        // (h) 7 天回收站：删除批次可查，恢复后规则回来且已发布课表行数不变。
        List<Map<String, Object>> recent = as(admin)
                .when().get("/api/recent-deletions")
                .then().statusCode(200)
                .extract().jsonPath().getList("$");
        assertThat(recent).anySatisfy(row -> {
            assertThat(row.get("batchId")).isEqualTo(deleteBatch);
            assertThat(row.get("entityType")).isEqualTo("CONSTRAINT_RULE");
            assertThat(row.get("status")).isEqualTo("PENDING");
        });

        as(admin).when().post("/api/recent-deletions/" + deleteBatch + "/restore")
                .then().statusCode(200);
        long restoredConfigId = ruleConfigId(admin, semesterId, "TEACHER_MAX_PER_DAY");
        assertThat(restoredConfigId).isEqualTo(ruleConfigId);
        assertThat(constraintEntries.findBySemesterIdAndTypeOrderByIdAsc(
                semesterId, "TEACHER_MAX_PER_DAY"))
                .filteredOn(row -> row.getDeletedAt() == null)
                .hasSize(2);
        assertThat(entries.countBySemesterId(semesterId)).isEqualTo(LESSON_COUNT);

        // (i) 汇总：旧数据不丢失——课表 546 节、调代历史保留（恢复基线新行 EFFECTIVE +
        // 被撤销的旧行 CANCELLED）、规则行仍在、导入会话仍在。
        assertThat(entries.countBySemesterId(semesterId)).isEqualTo(LESSON_COUNT);
        List<Substitution> overlays =
                substitutions.findBySemesterIdOrderByEffectiveDateDesc(semesterId);
        assertThat(overlays)
                .filteredOn(row -> Substitution.EFFECTIVE.equals(row.getStatus()))
                .isNotEmpty()
                .allSatisfy(row -> assertThat(row.getSubstituteTeacherId())
                        .isEqualTo(baselineTeacherId));
        assertThat(overlays)
                .filteredOn(row -> Substitution.CANCELLED.equals(row.getStatus()))
                .isNotEmpty()
                .allSatisfy(row -> assertThat(row.getSubstituteTeacherId())
                        .isEqualTo(substituteTeacherId));
        assertThat(constraintConfigs.findBySemesterIdAndType(semesterId, "TEACHER_MAX_PER_DAY"))
                .isPresent();
        assertThat(constraintEntries.findBySemesterId(semesterId)).isNotEmpty();
        assertThat(aiImportLogs.findById(legacyImportId)).isPresent();
        assertThat(aiImportLogs.count()).isEqualTo(1);
    }

    // ---------------------------------------------------------------- main-chain fixtures

    /** 14 班 × 5 天 × 8 节 = 560 行；周五第 7 节没有课。与真实工作簿同规格。 */
    private byte[] workbookRows(String keySeparator) {
        StringBuilder csv = new StringBuilder("班级|星期|节次,课程,教师\n");
        int row = 0;
        for (int classIndex = 0; classIndex < CLASS_COUNT; classIndex++) {
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
        long semesterId = TestData.semesterStarting(admin, ScheduleFixture.nextMonday());
        as(admin).body(Map.of(
                        "morningCount", 8,
                        "afternoonCount", 0,
                        "eveningCount", 0,
                        "days", List.of(1, 2, 3, 4, 5)))
                .when().post("/api/semesters/" + semesterId + "/periods/default")
                .then().statusCode(200);
        long grade = TestData.grade(admin, semesterId, "八年级");
        for (int i = 1; i <= CLASS_COUNT; i++) {
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

    private Map<String, Object> mapping(String delimiter, int courseColumn, int teacherColumn) {
        return mapping(delimiter, 0, courseColumn, teacherColumn);
    }

    private Map<String, Object> mapping(
            String delimiter, int headerRow, int courseColumn, int teacherColumn) {
        Map<String, Object> mapping = new LinkedHashMap<>();
        mapping.put("sheetName", "CSV");
        mapping.put("headerRow", headerRow);
        mapping.put("delimiter", delimiter);
        mapping.put("keyColumn", 0);
        mapping.put("keyParts", List.of("className", "dayOfWeek", "periodNo"));
        mapping.put("courseColumn", courseColumn);
        mapping.put("teacherColumn", teacherColumn);
        mapping.put("ignoreBlankRows", true);
        return mapping;
    }

    private JsonNode preview(String admin, byte[] file, Map<String, Object> mapping, long semesterId)
            throws Exception {
        Response response = upload(admin, "/api/imports/manual/preview", file, toJson(mapping),
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
        Response response = upload(admin, "/api/imports/manual/commit", file, toJson(mapping),
                toJson(options));
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

    private Response saveRecipe(String token, Map<String, Object> body, byte[] sample)
            throws Exception {
        return RestAssured.given()
                .header("Authorization", "Bearer " + token)
                .multiPart(new io.restassured.builder.MultiPartSpecBuilder(toJson(body))
                        .controlName("request").mimeType("text/plain")
                        .charset(StandardCharsets.UTF_8).build())
                .multiPart("sampleFile", "课表样例.csv", sample, CSV)
                .when().post("/api/import-recipes");
    }

    private Map<String, Object> recipeBody(JsonNode preview, String name) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("layout", "LONG");
        body.put("fingerprint", mapper.convertValue(preview.path("fingerprint"), Map.class));
        body.put("columns", mapping("|", 0, 1, 2));
        body.put("options", Map.of("delimiter", "|", "ignoreBlankRows", true));
        body.put("expected", Map.of(
                "slotCount", 560,
                "lessonCount", LESSON_COUNT,
                "emptySlotCount", 14,
                "teacherCount", 48,
                "courseCount", 16));
        body.put("sample", List.of(
                // 与 fixture 首行一致：八（1）周一第 1 节的课程是 (0+1+1)%16+1 = 课程03。
                Map.of("row", 1, "className", "八（1）", "dayOfWeek", 1, "periodNo", 1,
                        "courseName", "课程03", "teacherName", "信息技术", "empty", false)));
        return body;
    }

    private JsonNode recipeDetail(String admin, long recipeId) throws Exception {
        Response response = as(admin).when().get("/api/import-recipes/" + recipeId);
        response.then().statusCode(200);
        return mapper.readTree(response.asString());
    }

    private String toJson(Object value) throws Exception {
        return mapper.writeValueAsString(value);
    }

    // ---------------------------------------------------------------- main-chain helpers

    private long classIdByName(String token, long semesterId, String name) {
        List<Map<String, Object>> rows = as(token).queryParam("semesterId", semesterId)
                .when().get("/api/classes")
                .then().statusCode(200)
                .extract().jsonPath().getList("$");
        return rows.stream()
                .filter(row -> name.equals(row.get("name")))
                .map(row -> number(row, "id"))
                .findFirst()
                .orElseThrow();
    }

    private Map<String, Object> entryAt(String token, long classId, int dayOfWeek, int periodNo) {
        List<Map<String, Object>> rows = as(token)
                .when().get("/api/timetable/class/" + classId)
                .then().statusCode(200)
                .extract().jsonPath().getList("$");
        return rows.stream()
                .filter(row -> number(row, "dayOfWeek") == dayOfWeek
                        && number(row, "periodNo") == periodNo)
                .findFirst()
                .orElseThrow();
    }

    /** 当前配置转回请求形态，避免整表保存时误删导入生成的 CLASS_UNAVAILABLE。 */
    private List<Map<String, Object>> existingConfigRequests(String token, long semesterId) {
        List<Map<String, Object>> rows = as(token).queryParam("semesterId", semesterId)
                .when().get("/api/constraints")
                .then().statusCode(200)
                .extract().jsonPath().getList("$");
        List<Map<String, Object>> configs = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> config = new LinkedHashMap<>();
            config.put("type", row.get("type"));
            config.put("weight", row.get("weight"));
            config.put("hard", row.get("hard"));
            config.put("enabled", row.get("enabled"));
            List<Map<String, Object>> entries = new ArrayList<>();
            Object rawEntries = row.get("entries");
            if (rawEntries instanceof List<?> list) {
                for (Object item : list) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> entry = (Map<String, Object>) item;
                    Map<String, Object> copy = new LinkedHashMap<>();
                    copy.put("id", entry.get("id"));
                    copy.put("params", entry.get("params"));
                    copy.put("enabled", entry.get("enabled"));
                    copy.put("hard", entry.get("hard"));
                    copy.put("weight", entry.get("weight"));
                    copy.put("groupKey", entry.get("groupKey"));
                    entries.add(copy);
                }
            }
            config.put("entries", entries);
            configs.add(config);
        }
        return configs;
    }

    /** 全局默认（教师留空）+ 一名教师的例外，二者共用同一类型但作用域不同。 */
    private Map<String, Object> teacherMaxPerDayConfig(long teacherId) {
        List<Map<String, Object>> rows = List.of(
                Map.of("params", Map.of("maxPerDay", 6), "enabled", true, "hard", true,
                        "groupKey", "acceptance-global"),
                Map.of("params", Map.of("teacherId", teacherId, "maxPerDay", 2),
                        "enabled", true, "hard", true, "groupKey", "acceptance-exception"));
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("type", "TEACHER_MAX_PER_DAY");
        config.put("weight", 60);
        config.put("hard", true);
        config.put("enabled", true);
        config.put("entries", rows);
        return config;
    }

    private long ruleConfigId(String token, long semesterId, String type) {
        Long id = ruleConfigIdOrNull(token, semesterId, type);
        assertThat(id).as("规则类型 %s 应该可见", type).isNotNull();
        return id;
    }

    private Long ruleConfigIdOrNull(String token, long semesterId, String type) {
        List<Map<String, Object>> rows = as(token).queryParam("semesterId", semesterId)
                .when().get("/api/constraints")
                .then().statusCode(200)
                .extract().jsonPath().getList("$");
        return rows.stream()
                .filter(row -> type.equals(row.get("type")))
                .map(row -> number(row, "id"))
                .findFirst()
                .orElse(null);
    }

    private static long number(Map<String, Object> row, String field) {
        return ((Number) row.get(field)).longValue();
    }
}
