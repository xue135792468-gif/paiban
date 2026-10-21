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
import com.paiban.identity.domain.PermissionRepository;
import com.paiban.identity.domain.Permissions;
import com.paiban.identity.domain.Role;
import com.paiban.identity.domain.RoleRepository;
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

        Response preview = upload(admin, "/api/ai/import/excel", "教师名单.xlsx", excelBytes(23), XLSX);
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
                .body("issues[0].candidates", org.hamcrest.Matchers.hasItem("一年级1班"));
    }

    @Test
    void teacherPreviewHasNoMappingIssues() {
        String admin = adminToken();
        fake.response = aiResponse(List.of(teacherRow("张三", "语文", "", 0.99)));

        as(admin).body(Map.of("text", "张三老师教语文"))
                .when().post("/api/ai/import/text")
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
                .when().post("/api/ai/import/text");
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
    void creatingAClassRequiresAGradeId() {
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
                .when().post("/api/ai/import/text")
                .then().statusCode(200)
                .body("targetType", equalTo("teacher"))
                .body("suggestedTarget", equalTo("timetable"));
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
                admin, "/api/ai/import/excel", "教师.csv", csv.getBytes(StandardCharsets.UTF_8), "text/csv");
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
                .when().post("/api/ai/import/text");
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

        Response preview = upload(admin, "/api/ai/import/image", "课表照片.png", image, "image/png");
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

        Response preview = upload(admin, "/api/ai/import/excel", "教师.csv", bytes, "text/csv");
        preview.then().statusCode(200).body("rows", hasSize(1));
        assertThat(fake.lastRequest.messages().get(1).content()).contains("赵六");
    }

    @Test
    void validationFailureBlocksTheWholeCommit() {
        String admin = adminToken();
        Map<String, Object> missingName = teacherRow("", "语文", "13800000005", 0.9);
        fake.response = aiResponse(List.of(missingName, teacherRow("孙七", "物理", "", 0.9)));

        Response preview = as(admin).body(Map.of("text", "某老师没写名字"))
                .when().post("/api/ai/import/text");
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
                .when().post("/api/ai/import/text");
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
                .when().post("/api/ai/import/text")
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
        volatile AiRequest lastRequest;
        volatile int calls;

        void reset() {
            available = true;
            lastRequest = null;
            calls = 0;
            response = "{\"mappings\":[],\"rows\":[],\"explanation\":\"\"}";
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
            return new AiResult(
                    true, response, null, null,
                    1L, "fake", 2L, "fake-model",
                    10, 20, 5L, response);
        }
    }
}
