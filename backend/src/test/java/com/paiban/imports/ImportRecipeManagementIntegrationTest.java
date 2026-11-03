package com.paiban.imports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paiban.foundation.domain.AuditLog;
import com.paiban.foundation.domain.AuditLogRepository;
import com.paiban.imports.recipe.ImportRecipeRepository;
import com.paiban.support.ApiIntegrationTest;
import com.paiban.support.TestData;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 票 06 的配方管理：保存即样例测试、版本与回退、软删除进最近删除并恢复。
 *
 * <p>样例是一份 4 行的合成 CSV（4 槽 / 3 有课 / 1 空槽 / 2 教师 / 2 课程），
 * 测试只走公开 HTTP 与磁盘上的样例文件，不依赖真实工作簿。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ImportRecipeManagementIntegrationTest extends ApiIntegrationTest {

    private static final String CSV = "text/csv";
    private static final String SAMPLE_NAME = "sample.csv";

    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    private AuditLogRepository auditLogs;

    @Autowired
    private ImportRecipeRepository recipes;

    @Test
    void savesOnlyAfterSampleTestThenKeepsVersionsAndRollsBack() throws Exception {
        String admin = adminToken();
        long semesterId = seededSemester(admin);
        byte[] csv = sampleCsv();

        // 1) 预期数字对不上：409，且不建配方、不写版本。
        Response mismatch = saveRecipe(admin, saveBody("八年级配方", mapping(1, 2), expected(99)), csv);
        mismatch.then().statusCode(409)
                .body("message", containsString("样例测试未通过"))
                .body("differences", hasSize(2));
        assertThat(recipes.findByNameAndDeletedAtIsNull("八年级配方")).isEmpty();
        assertThat(recipes.findByName("八年级配方")).isEmpty();

        // 2) 数字一致：保存成功，样例文件落盘，版本 1。
        Response created = saveRecipe(admin, saveBody("八年级配方", mapping(1, 2), expected(3)), csv);
        created.then().statusCode(200)
                .body("name", equalTo("八年级配方"))
                .body("version", equalTo(1))
                .body("sample.fileName", equalTo(SAMPLE_NAME))
                .body("expected.slotCount", equalTo(4))
                .body("expected.lessonCount", equalTo(3));
        long recipeId = created.jsonPath().getLong("id");

        // 样例文件不通过 /uploads 静态暴露。
        String storedPath = created.jsonPath().getString("sample.path").replace('\\', '/');
        String relative = storedPath.substring(storedPath.indexOf("uploads/"));
        RestAssured.given().when().get("/" + relative).then().statusCode(404);

        // 3) 已存样例可以随时重跑。
        as(admin).when().post("/api/import-recipes/" + recipeId + "/test")
                .then().statusCode(200)
                .body("pass", equalTo(true))
                .body("actual.slotCount", equalTo(4))
                .body("actual.emptySlotCount", equalTo(1));

        // 4) 改列位置但计数不变：保存成功，版本 2。
        Response swapped = updateRecipe(admin, recipeId, patch(mapping(2, 1)), null);
        swapped.then().statusCode(200)
                .body("version", equalTo(2))
                .body("columns.courseColumn", equalTo(2));
        as(admin).when().get("/api/import-recipes/" + recipeId + "/versions")
                .then().statusCode(200)
                .body("$", hasSize(2))
                .body("[0].version", equalTo(2))
                .body("[0].summary", containsString("预期 4 槽"));

        // 5) 改映射导致计数变化：409，版本仍是 2，列映射没被改写。
        Response drift = updateRecipe(admin, recipeId, patch(mapping(9, 2)), null);
        drift.then().statusCode(409)
                .body("differences", hasSize(4))
                .body("actual.lessonCount", equalTo(0));
        as(admin).when().get("/api/import-recipes/" + recipeId + "/versions")
                .then().statusCode(200).body("$", hasSize(2));
        as(admin).when().get("/api/import-recipes/" + recipeId)
                .then().statusCode(200).body("columns.courseColumn", equalTo(2));

        // 6) 回退到 v1：追加 v3，映射回到第一版。
        as(admin).when().post("/api/import-recipes/" + recipeId + "/rollback/1")
                .then().statusCode(200)
                .body("version", equalTo(3))
                .body("columns.courseColumn", equalTo(1));
        as(admin).when().get("/api/import-recipes/" + recipeId + "/versions")
                .then().statusCode(200)
                .body("$", hasSize(3))
                .body("[0].version", equalTo(3));

        // 7) 预览带上配方预期计数：导入页据此判断“与上次差异过大”。
        JsonNode preview = preview(admin, csv, mapping(1, 2), semesterId);
        assertThat(preview.path("recipeMatch").path("kind").asText()).isEqualTo("EXACT");
        assertThat(preview.path("recipeMatch").path("expected").path("slotCount").asInt()).isEqualTo(4);
        assertThat(preview.path("lessonCount").asInt()).isEqualTo(3);
    }

    @Test
    void deleteLandsInRecycleBinAndRestoreBringsTheRecipeBack() throws Exception {
        String admin = adminToken();
        seededSemester(admin);
        byte[] csv = sampleCsv();
        long recipeId = saveRecipe(admin, saveBody("可删配方", mapping(1, 2), expected(3)), csv)
                .jsonPath().getLong("id");

        Response deleted = as(admin).when().delete("/api/import-recipes/" + recipeId);
        deleted.then().statusCode(200);
        String batchId = deleted.jsonPath().getString("batchId");

        as(admin).when().get("/api/import-recipes")
                .then().statusCode(200).body("$", hasSize(0));
        as(admin).when().get("/api/import-recipes/" + recipeId)
                .then().statusCode(404);
        as(admin).when().get("/api/recent-deletions")
                .then().statusCode(200)
                .body("find { it.batchId == '" + batchId + "' }.entityType", equalTo("IMPORT_RECIPE"))
                .body("find { it.batchId == '" + batchId + "' }.entityLabel", equalTo("可删配方"));

        // 恢复：名字改回原值（删除时加了批次后缀），重新出现在列表。
        as(admin).when().post("/api/recent-deletions/" + batchId + "/restore")
                .then().statusCode(200);
        as(admin).when().get("/api/import-recipes")
                .then().statusCode(200)
                .body("$", hasSize(1))
                .body("[0].name", equalTo("可删配方"))
                .body("[0].enabled", equalTo(true));

        List<String> actions = auditLogs.findTop100ByOrderByCreatedAtDesc().stream()
                .map(AuditLog::getAction)
                .toList();
        assertThat(actions).contains("DELETE_IMPORT_RECIPE", "RESTORE_IMPORT_RECIPE");

        // 删除后同名可再建：再次删除把原记录改名，新配方不撞唯一约束。
        as(admin).when().delete("/api/import-recipes/" + recipeId).then().statusCode(200);
        saveRecipe(admin, saveBody("可删配方", mapping(1, 2), expected(3)), csv)
                .then().statusCode(200)
                .body("name", equalTo("可删配方"));
    }

    @Test
    void missingSampleFileIsAConflictNotFiveHundred() throws Exception {
        String admin = adminToken();
        seededSemester(admin);
        byte[] csv = sampleCsv();
        Response created = saveRecipe(admin, saveBody("缺样例配方", mapping(1, 2), expected(3)), csv);
        long recipeId = created.jsonPath().getLong("id");
        String path = created.jsonPath().getString("sample.path");
        assertThat(path).isNotBlank();
        Path stored = Paths.get(path);
        Files.deleteIfExists(stored);

        as(admin).when().post("/api/import-recipes/" + recipeId + "/test")
                .then().statusCode(409)
                .body("message", containsString("样例文件不存在"));
        updateRecipe(admin, recipeId, patch(mapping(2, 1)), null)
                .then().statusCode(409)
                .body("message", containsString("样例文件不存在"));
    }

    // ---------------------------------------------------------------- fixtures

    private byte[] sampleCsv() {
        String csv = "班级|星期|节次,课程,教师\n"
                + "八（1）|星期一|1,语文,张三\n"
                + "八（1）|星期一|2,数学,李四\n"
                + "八（2）|星期一|1,语文,张三\n"
                + "八（2）|星期一|2,,\n";
        return csv.getBytes(StandardCharsets.UTF_8);
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
        TestData.adminClass(admin, semesterId, grade, "八（1）");
        TestData.adminClass(admin, semesterId, grade, "八（2）");
        return semesterId;
    }

    private Map<String, Object> mapping(int courseColumn, int teacherColumn) {
        Map<String, Object> mapping = new LinkedHashMap<>();
        mapping.put("sheetName", "CSV");
        mapping.put("headerRow", 0);
        mapping.put("delimiter", "|");
        mapping.put("keyColumn", 0);
        mapping.put("keyParts", List.of("className", "dayOfWeek", "periodNo"));
        mapping.put("courseColumn", courseColumn);
        mapping.put("teacherColumn", teacherColumn);
        mapping.put("ignoreBlankRows", true);
        return mapping;
    }

    private Map<String, Integer> expected(int lessonCount) {
        Map<String, Integer> expected = new LinkedHashMap<>();
        expected.put("slotCount", 4);
        expected.put("lessonCount", lessonCount);
        expected.put("emptySlotCount", 4 - lessonCount);
        expected.put("teacherCount", 2);
        expected.put("courseCount", 2);
        return expected;
    }

    private Map<String, Object> saveBody(
            String name, Map<String, Object> columns, Map<String, Integer> expected) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("layout", "LONG");
        body.put("columns", columns);
        body.put("options", Map.of("delimiter", "|", "ignoreBlankRows", true));
        body.put("expected", expected);
        return body;
    }

    private Map<String, Object> patch(Map<String, Object> columns) {
        return Map.of("columns", columns);
    }

    private Response saveRecipe(String token, Map<String, Object> body, byte[] sample) throws Exception {
        return RestAssured.given()
                .header("Authorization", "Bearer " + token)
                .multiPart(new io.restassured.builder.MultiPartSpecBuilder(toJson(body))
                        .controlName("request").mimeType("text/plain")
                        .charset(StandardCharsets.UTF_8).build())
                .multiPart("sampleFile", SAMPLE_NAME, sample, CSV)
                .when().post("/api/import-recipes");
    }

    private Response updateRecipe(
            String token, long recipeId, Map<String, Object> patch, byte[] sample) throws Exception {
        var request = RestAssured.given()
                .header("Authorization", "Bearer " + token)
                .multiPart(new io.restassured.builder.MultiPartSpecBuilder(toJson(patch))
                        .controlName("request").mimeType("text/plain")
                        .charset(StandardCharsets.UTF_8).build());
        if (sample != null) {
            request.multiPart("sampleFile", SAMPLE_NAME, sample, CSV);
        }
        return request.when().patch("/api/import-recipes/" + recipeId);
    }

    private JsonNode preview(String token, byte[] file, Map<String, Object> mapping, long semesterId)
            throws Exception {
        Response response = RestAssured.given()
                .header("Authorization", "Bearer " + token)
                .multiPart("file", "课表.csv", file, CSV)
                .multiPart(new io.restassured.builder.MultiPartSpecBuilder(toJson(mapping))
                        .controlName("mapping").mimeType("text/plain")
                        .charset(StandardCharsets.UTF_8).build())
                .multiPart("semesterId", String.valueOf(semesterId))
                .when().post("/api/imports/manual/preview");
        response.then().statusCode(200);
        return mapper.readTree(response.asString());
    }

    private String toJson(Object value) throws Exception {
        return mapper.writeValueAsString(value);
    }
}
