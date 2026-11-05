package com.paiban.imports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paiban.ai.domain.AiCallLogRepository;
import com.paiban.foundation.domain.TeacherRepository;
import com.paiban.scheduling.domain.ConstraintConfigRepository;
import com.paiban.scheduling.domain.ConstraintEntryRepository;
import com.paiban.scheduling.domain.DraftLessonRepository;
import com.paiban.scheduling.domain.TimetableDraftRepository;
import com.paiban.support.ApiIntegrationTest;
import com.paiban.support.TestData;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 票 05 的手动选列导入：上传长表 → 复合键拆列 → 预览统计 → 入库生成草稿 →
 * 保存配方 → 第二次同格式自动匹配，全程不调用 AI。
 *
 * <p>测试只走 HTTP 与数据库结果，不依赖任何真实工作簿文件；合成的 560 行等价数据
 * 覆盖真实工作簿的形状（14 班 × 5 天 × 8 节，周五第 7 节空，占位教师“信息技术”）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ManualImportIntegrationTest extends ApiIntegrationTest {

    private static final String CSV = "text/csv";
    private static final String XLSX =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String[] DAY_LABELS = {"星期一", "星期二", "星期三", "星期四", "星期五"};

    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    private TimetableDraftRepository drafts;

    @Autowired
    private DraftLessonRepository draftLessons;

    @Autowired
    private ConstraintConfigRepository constraintConfigs;

    @Autowired
    private ConstraintEntryRepository constraintEntries;

    @Autowired
    private AiCallLogRepository aiCallLogs;

    @Autowired
    private TeacherRepository teachers;

    @Test
    void previewsAndCommitsTheSyntheticWorkbookThenReusesItsRecipe() throws Exception {
        String admin = adminToken();
        long semesterId = seededSemester(admin);
        byte[] csv = workbookRows("|");

        JsonNode preview = preview(admin, csv, mapping("|", 0, 1, 2), semesterId);
        assertThat(preview.path("slotCount").asInt()).isEqualTo(560);
        assertThat(preview.path("lessonCount").asInt()).isEqualTo(546);
        assertThat(preview.path("emptySlotCount").asInt()).isEqualTo(14);
        assertThat(preview.path("teacherCount").asInt()).isEqualTo(48);
        assertThat(preview.path("courseCount").asInt()).isEqualTo(16);
        assertThat(preview.path("suggestedRule").path("type").asText()).isEqualTo("CLASS_UNAVAILABLE");
        assertThat(preview.path("suggestedRule").path("dayOfWeek").asInt()).isEqualTo(5);
        assertThat(preview.path("suggestedRule").path("periodNo").asInt()).isEqualTo(7);
        assertThat(preview.path("suggestedRule").path("text").asText()).contains("周五第 7 节");
        assertThat(preview.path("teacherWarnings").findValuesAsText("value")).contains("信息技术");
        // 这些名字在教师表里已存在，导入不会新建，所以没有需要用户拍板的占位教师。
        assertThat(preview.path("pendingTeachers")).isEmpty();
        assertThat(preview.path("recipeMatch").path("kind").asText()).isEqualTo("NONE");

        JsonNode commit = commit(admin, csv, mapping("|", 0, 1, 2), semesterId, "手动导入课表", true);
        assertThat(commit.path("draftId").asLong()).isPositive();
        assertThat(commit.path("imported").asInt()).isEqualTo(546);
        assertThat(commit.path("skippedEmpty").asInt()).isEqualTo(14);
        assertThat(commit.path("errors")).isEmpty();
        long draftId = commit.path("draftId").asLong();
        assertThat(draftLessons.countByDraftId(draftId)).isEqualTo(546);
        assertThat(drafts.findById(draftId).orElseThrow().getName()).isEqualTo("手动导入课表");
        assertThat(constraintEntries.findBySemesterIdAndTypeOrderByIdAsc(semesterId, "CLASS_UNAVAILABLE"))
                .hasSize(1);
        assertThat(constraintEntries.findBySemesterIdAndTypeOrderByIdAsc(semesterId, "CLASS_UNAVAILABLE")
                .get(0).getParamsJson()).contains("\"dayOfWeek\":5").contains("\"periodNo\":7");
        assertThat(constraintConfigs.findBySemesterIdAndType(semesterId, "CLASS_UNAVAILABLE")).isPresent();
        assertThat(aiCallLogs.count()).isZero();

        // 重复应用同一建议规则：不新增第二条。
        commit(admin, csv, mapping("|", 0, 1, 2), semesterId, "手动导入课表 2", true);
        assertThat(constraintEntries.findBySemesterIdAndTypeOrderByIdAsc(semesterId, "CLASS_UNAVAILABLE"))
                .hasSize(1);

        // 保存配方（指纹来自预览 + 样例文件），第二次上传同格式文件自动 EXACT。
        Response saved = saveRecipe(admin, recipeBody(preview, "真实长表配方"), csv);
        saved.then().statusCode(200).body("name", equalTo("真实长表配方")).body("version", equalTo(1));
        long recipeId = saved.jsonPath().getLong("id");

        JsonNode second = preview(admin, csv, mapping("|", 0, 1, 2), semesterId);
        assertThat(second.path("recipeMatch").path("kind").asText()).isEqualTo("EXACT");
        assertThat(second.path("recipeMatch").path("recipeName").asText()).isEqualTo("真实长表配方");
        assertThat(second.path("recipeMatch").path("mapping").path("courseColumn").asInt()).isEqualTo(1);
        // 自带 mapping 的请求只是“查一下这是哪份配方”，不能算套用。
        assertThat(detail(admin, recipeId).hasNonNull("lastUsedAt")).isFalse();

        // 空 mapping 也能套用配方：这是“第二次同格式不用再选列”的路径。
        JsonNode auto = preview(admin, csv, Map.of(), semesterId);
        assertThat(auto.path("recipeMatch").path("kind").asText()).isEqualTo("EXACT");
        assertThat(auto.path("lessonCount").asInt()).isEqualTo(546);
        assertThat(detail(admin, recipeId).hasNonNull("lastUsedAt")).isTrue();

        // 自带 mapping 入库：配方没有被套用，appliedRecipeId 必须为空。
        JsonNode mapped = commit(admin, csv, mapping("|", 0, 1, 2), semesterId, "手选列导入", false);
        assertThat(mapped.path("draftId").asLong()).isPositive();
        assertThat(mapped.hasNonNull("appliedRecipeId")).isFalse();

        JsonNode reused = commit(admin, csv, Map.of(), semesterId, "配方导入", false);
        assertThat(reused.path("appliedRecipeId").asLong()).isEqualTo(recipeId);

        // 改一列表头 → SIMILAR，且只报这一列，不把后面的列全算成改名。
        byte[] renamed = new String(workbookRows("|"), StandardCharsets.UTF_8)
                .replace("课程,教师", "课程,任课教师")
                .getBytes(StandardCharsets.UTF_8);
        JsonNode similar = preview(admin, renamed, mapping("|", 0, 1, 2), semesterId);
        assertThat(similar.path("recipeMatch").path("kind").asText()).isEqualTo("SIMILAR");
        assertThat(similar.path("recipeMatch").path("differences")).hasSize(1);
        assertThat(similar.path("recipeMatch").path("differences").get(0).path("kind").asText())
                .isEqualTo("RENAMED");
        assertThat(similar.path("recipeMatch").path("differences").get(0).path("column").asText())
                .isEqualTo("3");
        assertThat(similar.path("recipeMatch").path("differences").get(0).path("detail").asText())
                .contains("第 3 列").contains("任课教师");

        // 少一列 / 多一列：列数变了仍是同一份配方，差异按位置说清楚。
        JsonNode removed = matchOnly(admin, withoutColumn(workbookRows("|"), 2));
        assertThat(removed.path("kind").asText()).isEqualTo("SIMILAR");
        assertThat(kinds(removed)).containsExactly("REMOVED");
        assertThat(removed.path("differences").get(0).path("column").asText()).isEqualTo("教师");

        JsonNode added = matchOnly(admin, withColumn(workbookRows("|"), "教室"));
        assertThat(added.path("kind").asText()).isEqualTo("SIMILAR");
        assertThat(kinds(added)).containsExactly("ADDED");
        assertThat(added.path("differences").get(0).path("column").asText()).isEqualTo("教室");

        // 完全无关的文件 → NONE。
        byte[] unrelated = "姓名,电话\n张三,13800000000\n".getBytes(StandardCharsets.UTF_8);
        JsonNode unrelatedPreview = preview(admin, unrelated, Map.of(), semesterId);
        assertThat(unrelatedPreview.path("recipeMatch").path("kind").asText()).isEqualTo("NONE");
    }

    @Test
    void compositeKeyAcceptsDashDelimiter() throws Exception {
        String admin = adminToken();
        long semesterId = seededSemester(admin);
        byte[] dash = workbookRows("-");

        JsonNode preview = preview(admin, dash, mapping("-", 0, 1, 2), semesterId);
        assertThat(preview.path("slotCount").asInt()).isEqualTo(560);
        assertThat(preview.path("lessonCount").asInt()).isEqualTo(546);
        assertThat(preview.path("emptySlotCount").asInt()).isEqualTo(14);
    }

    @Test
    void inspectOffersSheetsAndHeaderCandidates() {
        String admin = adminToken();
        seededSemester(admin);
        Response response = upload(admin, "/api/imports/manual/inspect", workbookRows("|"), null, null);
        response.then().statusCode(200)
                .body("sheets", hasSize(1))
                .body("sheets[0].name", equalTo("CSV"))
                .body("sheets[0].headerRow", equalTo(0))
                .body("sheets[0].headers[0]", equalTo("班级|星期|节次"))
                .body("sheets[0].suggestedMapping.courseColumn", notNullValue());
    }

    /**
     * 有行错误的批次必须零副作用：教师不建、草稿不建、规则不写。教师“王新”本来会被
     * 按名字自动创建，正好用来证明“校验先于写入”。
     */
    @Test
    void rowErrorsLeaveNoTeacherAndNoDraftBehind() throws Exception {
        String admin = adminToken();
        long semesterId = seededSemester(admin);
        byte[] csv = ("班级|星期|节次,课程,教师\n"
                + "八（1）|星期一|1,课程01,王新\n"
                + "八（1）|星期一|2,不存在的课程,王新\n").getBytes(StandardCharsets.UTF_8);

        long teachersBefore = teachers.count();
        long draftsBefore = drafts.count();
        long constraintBefore = constraintEntries.count();

        Response response = commitResponse(
                admin, csv, mapping("|", 0, 1, 2), options(semesterId, "坏数据", true));
        response.then().statusCode(200);
        JsonNode body = mapper.readTree(response.asString());
        assertThat(body.path("errors")).hasSize(1);
        assertThat(body.path("errors").get(0).path("message").asText()).contains("不存在的课程");
        assertThat(body.hasNonNull("draftId")).isFalse();

        assertThat(teachers.count()).isEqualTo(teachersBefore);
        assertThat(teachers.findAllByDeletedAtIsNullOrderByNameAsc())
                .noneMatch(teacher -> "王新".equals(teacher.getName()));
        assertThat(drafts.count()).isEqualTo(draftsBefore);
        assertThat(constraintEntries.count()).isEqualTo(constraintBefore);
    }

    /**
     * 占位教师不能静默创建：没做选择时整批拒绝并给出名单；明确“保留”才创建并告警；
     * 映射到已有教师时既不新建教师也不告警创建。
     */
    @Test
    void placeholderTeacherNeedsAnExplicitDecision() throws Exception {
        String admin = adminToken();
        long semesterId = seededSemester(admin);
        byte[] csv = ("班级|星期|节次,课程,教师\n"
                + "八（1）|星期一|1,课程01,待定\n"
                + "八（1）|星期一|2,课程02,待定\n").getBytes(StandardCharsets.UTF_8);
        long teachersBefore = teachers.count();

        Response refused = commitResponse(
                admin, csv, mapping("|", 0, 1, 2), options(semesterId, "占位未决", false));
        refused.then().statusCode(400)
                .body("message", containsString("待定"))
                .body("pendingTeachers", contains("待定"));
        assertThat(teachers.count()).isEqualTo(teachersBefore);

        // 映射到已有教师：不新建教师，课节挂到映射后的名字上。
        long existingId = teachers.findAllByDeletedAtIsNullOrderByNameAsc().stream()
                .filter(teacher -> "教师01".equals(teacher.getName()))
                .findFirst().orElseThrow().getId();
        Map<String, Object> mapped = options(semesterId, "映射占位", false);
        mapped.put("teacherNameMappings", Map.of("待定", String.valueOf(existingId)));
        JsonNode pendingPreview = preview(admin, csv, mapping("|", 0, 1, 2), semesterId);
        assertThat(pendingPreview.path("pendingTeachers").toString()).contains("待定");

        JsonNode mappedResult = commitWith(admin, csv, mapping("|", 0, 1, 2), mapped);
        assertThat(mappedResult.path("draftId").asLong()).isPositive();
        assertThat(teachers.count()).isEqualTo(teachersBefore);
        assertThat(mappedResult.path("warnings").toString()).doesNotContain("已按占位名创建教师");

        // 明确保留：创建教师并留下可审计的告警。
        Map<String, Object> keep = options(semesterId, "保留占位", false);
        keep.put("keepPlaceholderTeachers", true);
        JsonNode kept = commitWith(admin, csv, mapping("|", 0, 1, 2), keep);
        assertThat(kept.path("draftId").asLong()).isPositive();
        assertThat(teachers.findAllByDeletedAtIsNullOrderByNameAsc())
                .anyMatch(teacher -> "待定".equals(teacher.getName()));
        assertThat(kept.path("warnings").toString()).contains("保留为教师");
    }

    /** 上传口子必须和应用里其它上传一样窄：扩展名、大小、行数都要拦住。 */
    @Test
    void uploadIsGuardedByExtensionSizeAndRowCap() {
        String admin = adminToken();
        seededSemester(admin);

        Response wrongType = RestAssured.given()
                .header("Authorization", "Bearer " + admin)
                .multiPart("file", "课表.txt", workbookRows("|"), "text/plain")
                .when().post("/api/imports/manual/inspect");
        wrongType.then().statusCode(400).body("message", containsString("xlsx / xls / csv"));

        byte[] oversized = new byte[21 * 1024 * 1024];
        Arrays.fill(oversized, (byte) 'a');
        Response tooBig = RestAssured.given()
                .header("Authorization", "Bearer " + admin)
                .multiPart("file", "大文件.csv", oversized, CSV)
                .when().post("/api/imports/manual/inspect");
        tooBig.then().statusCode(400).body("message", containsString("20MB"));

        StringBuilder huge = new StringBuilder("班级|星期|节次,课程,教师\n");
        for (int row = 0; row < 5005; row++) {
            huge.append("八（1）|星期一|1,课程01,教师01\n");
        }
        Response tooManyRows = upload(
                admin, "/api/imports/manual/inspect", huge.toString().getBytes(StandardCharsets.UTF_8),
                null, null);
        tooManyRows.then().statusCode(400).body("message", containsString("行"));
    }

    /**
     * 真实工作簿（默认跳过）：用 `-Dpaiban.realWorkbook=<path>` 指定文件跑一次，
     * 断言规格里的 560 槽 / 546 有课 / 14 空槽 / 48 教师 / 16 课程。
     */
    @Test
    @EnabledIfSystemProperty(named = "paiban.realWorkbook", matches = ".+")
    void realWorkbookMatchesTheSpecNumbers() throws Exception {
        String admin = adminToken();
        long semesterId = TestData.semester(admin);
        byte[] workbook = Files.readAllBytes(
                Paths.get(System.getProperty("paiban.realWorkbook")));

        JsonNode inspected = mapper.readTree(
                uploadNamed(admin, "/api/imports/manual/inspect", workbook, null, null,
                        "课表.xlsx", XLSX).asString());
        String sheetName = inspected.path("sheets").get(0).path("name").asText();
        Map<String, Object> mapping = new LinkedHashMap<>();
        mapping.put("sheetName", sheetName);
        mapping.put("headerRow", -1);
        mapping.put("delimiter", "|");
        mapping.put("keyColumn", 0);
        mapping.put("keyParts", List.of("className", "dayOfWeek", "periodNo"));
        mapping.put("courseColumn", 1);
        mapping.put("teacherColumn", 2);
        mapping.put("ignoreBlankRows", true);

        JsonNode preview = mapper.readTree(uploadNamed(
                admin, "/api/imports/manual/preview", workbook, toJson(mapping),
                "{\"semesterId\":" + semesterId + "}", "课表.xlsx", XLSX).asString());
        assertThat(preview.path("slotCount").asInt()).isEqualTo(560);
        assertThat(preview.path("lessonCount").asInt()).isEqualTo(546);
        assertThat(preview.path("emptySlotCount").asInt()).isEqualTo(14);
        assertThat(preview.path("teacherCount").asInt()).isEqualTo(48);
        assertThat(preview.path("courseCount").asInt()).isEqualTo(16);
        assertThat(preview.path("suggestedRule").path("dayOfWeek").asInt()).isEqualTo(5);
        assertThat(preview.path("suggestedRule").path("periodNo").asInt()).isEqualTo(7);
        assertThat(preview.path("teacherWarnings").findValuesAsText("value")).contains("信息技术");
        // 这些名字在教师表里已存在，导入不会新建，所以没有需要用户拍板的占位教师。
        assertThat(preview.path("pendingTeachers")).isEmpty();
    }

    // ---------------------------------------------------------------- fixtures

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

    private Map<String, Object> mapping(String delimiter, int courseColumn, int teacherColumn) {
        return mapping(delimiter, 0, courseColumn, teacherColumn);
    }

    private Map<String, Object> mapping(String delimiter, int headerRow, int courseColumn, int teacherColumn) {
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
        return commitWith(admin, file, mapping, options(semesterId, draftName, applySuggestedRule));
    }

    private JsonNode commitWith(
            String admin, byte[] file, Map<String, Object> mapping, Map<String, Object> options)
            throws Exception {
        Response response = commitResponse(admin, file, mapping, options);
        response.then().statusCode(200);
        return mapper.readTree(response.asString());
    }

    private Response commitResponse(
            String admin, byte[] file, Map<String, Object> mapping, Map<String, Object> options)
            throws Exception {
        return upload(admin, "/api/imports/manual/commit", file, toJson(mapping), toJson(options));
    }

    private Map<String, Object> options(long semesterId, String draftName, boolean applySuggestedRule) {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("semesterId", semesterId);
        options.put("draftName", draftName);
        options.put("applySuggestedRule", applySuggestedRule);
        return options;
    }

    /** 只问“这是哪份配方”：不带 mapping，服务端自己建议列。 */
    private JsonNode matchOnly(String admin, byte[] file) throws Exception {
        Response response = RestAssured.given()
                .header("Authorization", "Bearer " + admin)
                .multiPart("file", "课表.csv", file, CSV)
                .when().post("/api/import-recipes/match");
        response.then().statusCode(200);
        return mapper.readTree(response.asString());
    }

    private JsonNode detail(String admin, long recipeId) throws Exception {
        Response response = as(admin).when().get("/api/import-recipes/" + recipeId);
        response.then().statusCode(200);
        return mapper.readTree(response.asString());
    }

    private List<String> kinds(JsonNode match) {
        List<String> kinds = new ArrayList<>();
        for (JsonNode difference : match.path("differences")) {
            kinds.add(difference.path("kind").asText());
        }
        return kinds;
    }

    /** 删掉一列：列数变了，配方仍应算 SIMILAR 并说清少了哪列。 */
    private byte[] withoutColumn(byte[] csv, int index) {
        List<String> lines = new ArrayList<>(List.of(new String(csv, StandardCharsets.UTF_8).split("\n")));
        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            if (line.isEmpty()) {
                continue;
            }
            List<String> cells = new ArrayList<>(List.of(line.split(",", -1)));
            cells.remove(index);
            out.append(String.join(",", cells)).append('\n');
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** 末尾加一列：列数变了，配方仍应算 SIMILAR 并说清多了哪列。 */
    private byte[] withColumn(byte[] csv, String header) {
        List<String> lines = new ArrayList<>(List.of(new String(csv, StandardCharsets.UTF_8).split("\n")));
        StringBuilder out = new StringBuilder();
        boolean first = true;
        for (String line : lines) {
            if (line.isEmpty()) {
                continue;
            }
            out.append(line).append(first ? "," + header : ",-").append('\n');
            first = false;
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private Response upload(String token, String path, byte[] file, String mapping, String options) {
        return uploadNamed(token, path, file, mapping, options, "课表.csv", CSV);
    }

    private Response uploadNamed(
            String token, String path, byte[] file, String mapping, String options,
            String fileName, String contentType) {
        var request = RestAssured.given()
                .header("Authorization", "Bearer " + token)
                .multiPart("file", fileName, file, contentType);
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

    /** 06 changed recipe save to multipart: the server re-runs the attached sample. */
    private Response saveRecipe(String token, Map<String, Object> body, byte[] sample) throws Exception {
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
                "lessonCount", 546,
                "emptySlotCount", 14,
                "teacherCount", 48,
                "courseCount", 16));
        body.put("sample", List.of(
                Map.of("row", 1, "className", "八（1）", "dayOfWeek", 1, "periodNo", 1,
                        "courseName", "课程02", "teacherName", "信息技术", "empty", false)));
        return body;
    }
}
