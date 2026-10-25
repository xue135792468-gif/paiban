package com.paiban.ai.imports.workbook;

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
import com.paiban.foundation.domain.AdminClassRepository;
import com.paiban.foundation.domain.CourseRepository;
import com.paiban.foundation.domain.TeacherRepository;
import com.paiban.scheduling.domain.DraftLessonRepository;
import com.paiban.scheduling.domain.TimetableDraftRepository;
import com.paiban.support.ApiIntegrationTest;
import com.paiban.support.TestData;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
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
 * The phase-11 workbook pipeline through the REST contract: upload a real-looking workbook,
 * let a scripted gateway run the tool loop, get a categorized preview, confirm, and check
 * what landed in the database. Assertions are about what the user sees and what was written,
 * never about internal call order beyond the tool trace the ticket asks to persist.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class WorkbookImportIntegrationTest extends ApiIntegrationTest {

    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String DATA_MARKER = "数据（JSON）：";

    @Autowired
    private FakeAiGateway fake;

    @Autowired
    private TeacherRepository teachers;

    @Autowired
    private CourseRepository courses;

    @Autowired
    private AdminClassRepository adminClasses;

    @Autowired
    private TimetableDraftRepository drafts;

    @Autowired
    private DraftLessonRepository draftLessons;

    @Autowired
    private com.paiban.ai.imports.AiImportLogRepository importLogs;

    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicInteger planRounds = new AtomicInteger();

    @BeforeEach
    void resetFake() {
        fake.reset();
        planRounds.set(0);
    }

    @AfterEach
    void cleanUploadedSources() {
        for (com.paiban.ai.imports.AiImportLog log : fake.logs()) {
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
    void looksLikeARealWorkbookAndPreviewsEachSheet() throws Exception {
        String admin = adminToken();
        TestData.semester(admin);
        long semesterId = currentSemester(admin);
        generateDefaultPeriods(admin, semesterId);
        scriptPlan();

        Response preview = upload(admin, workbook(realisticSheets()));

        preview.then().statusCode(200)
                .body("targetType", equalTo("workbook"))
                .body("workbook.structureConfirmed", equalTo(true))
                .body("workbook.sheets", hasSize(4))
                .body("rows", hasSize(5))
                .body("warnings", org.hamcrest.Matchers.hasItem(containsString("空课位 1 个")))
                .body("toolTrace", hasSize(3))
                .body("toolTrace[0].tool", equalTo("list_sheets"))
                .body("toolTrace[1].tool", equalTo("read_sheet"))
                .body("toolTrace[2].tool", equalTo("finish"));
        assertThat(sheet(preview, "使用说明").path("skippedReason").asText()).contains("说明");
        assertThat(sheet(preview, "数据源").path("category").asText()).isEqualTo("timetable");
        assertThat(sheet(preview, "教师名单").path("category").asText()).isEqualTo("teacher");
        assertThat(sheet(preview, "数据源").path("recognizedRows").asInt()).isEqualTo(5);

        // The audit columns the spec asks for: the tool trace and the raw answers.
        long sessionId = preview.jsonPath().getLong("sessionId");
        com.paiban.ai.imports.AiImportLog log = importLogs.findById(sessionId).orElseThrow();
        assertThat(log.getToolTraceJson()).contains("list_sheets").contains("read_sheet").contains("finish");
        assertThat(log.getAiRawResponse()).contains("values");
    }

    @Test
    void headerlessPackedColumnsAreSplitAndFirstRowSurvives() throws Exception {
        String admin = adminToken();
        TestData.semester(admin);
        scriptPlan();

        Response preview = upload(admin, workbook(Map.of(
                "数据源", List.of(
                        List.of("八（1）|星期一|1", "语文", "张三"),
                        List.of("八（1）|星期一|2", "数学", "李四"),
                        List.of("八（2）|星期二|1", "英语", "王五")))));

        preview.then().statusCode(200).body("rows", hasSize(3));
        assertThat(preview.jsonPath().getString("rows[0].values.className")).isEqualTo("八（1）");
        assertThat(preview.jsonPath().getString("rows[0].values.dayOfWeek")).isEqualTo("星期一");
        assertThat(preview.jsonPath().getString("rows[0].values.periodNo")).isEqualTo("1");
        assertThat(preview.jsonPath().getString("rows[2].values.className")).isEqualTo("八（2）");
    }

    @Test
    void duplicateSlotsAcrossSheetsCollapseAndIncompleteDuplicatesAreReported() throws Exception {
        String admin = adminToken();
        TestData.semester(admin);
        scriptPlan();

        Response preview = upload(admin, workbook(Map.of(
                "数据源", List.of(
                        List.of("八（1）|星期一|1", "语文", "张三"),
                        List.of("八（1）|星期一|1", "语文", ""),
                        List.of("八（1）|星期二|1", "数学", "李四")),
                "班级课表", List.of(
                        List.of("班级", "星期", "节次", "课程", "教师"),
                        List.of("八（1）", "周一", "第1节", "语文", "张三")))));

        preview.then().statusCode(200).body("rows", hasSize(2));
        assertThat(preview.jsonPath().getList("warnings", String.class))
                .anySatisfy(warning -> assertThat(warning).contains("信息不全"));
    }

    @Test
    void confirmWritesTeachersCoursesClassesAndADraft() throws Exception {
        String admin = adminToken();
        TestData.semester(admin);
        long semesterId = currentSemester(admin);
        generateDefaultPeriods(admin, semesterId);
        scriptPlan();

        Response preview = upload(admin, workbook(realisticSheets()));
        long sessionId = preview.jsonPath().getLong("sessionId");

        Response commit = as(admin).when().post("/api/ai/import/sessions/" + sessionId + "/commit");

        commit.then().statusCode(200)
                .body("imported", equalTo(5))
                .body("categories", hasSize(4));
        assertThat(commit.jsonPath().getList("categories.category", String.class))
                .containsExactly("teacher", "course", "class", "timetable");
        assertThat(commit.jsonPath().getLong("draftId")).isPositive();
        assertThat(teachers.findAll()).extracting(teacher -> teacher.getName())
                .contains("张三", "李四", "王五");
        assertThat(courses.findAll()).extracting(course -> course.getName())
                .contains("语文", "数学", "英语");
        assertThat(adminClasses.findBySemesterIdOrderByGradeIdAscNameAsc(semesterId))
                .extracting(adminClass -> adminClass.getName())
                .containsExactlyInAnyOrder("八（1）", "八（2）");
        Long draftId = commit.jsonPath().getLong("draftId");
        assertThat(draftLessons.findAll().stream()
                .filter(lesson -> draftId.equals(lesson.getDraftId())).count()).isEqualTo(5);
        assertThat(drafts.findById(draftId).orElseThrow().getStatus()).isEqualTo("DRAFT");

        // A committed session is closed: nothing may be written twice.
        as(admin).when().post("/api/ai/import/sessions/" + sessionId + "/commit")
                .then().statusCode(409);
    }

    @Test
    void existingNamesAreCompletedButNeverOverwritten() throws Exception {
        String admin = adminToken();
        TestData.semester(admin);
        long semesterId = currentSemester(admin);
        generateDefaultPeriods(admin, semesterId);
        long teacherId = TestData.teacher(admin, "张三");
        String originalEmployeeNo = teachers.findById(teacherId).orElseThrow().getEmployeeNo();
        TestData.course(admin, "语文");
        Long gradeId = TestData.grade(admin, semesterId, "八年级");
        TestData.adminClass(admin, semesterId, gradeId, "八（1）");
        scriptPlan();

        Response preview = upload(admin, workbook(realisticSheets()));
        long sessionId = preview.jsonPath().getLong("sessionId");
        as(admin).when().post("/api/ai/import/sessions/" + sessionId + "/commit").then().statusCode(200);

        // The import completes blank fields only: the school's own employee number stays.
        assertThat(teachers.findById(teacherId).orElseThrow().getEmployeeNo())
                .isEqualTo(originalEmployeeNo)
                .isNotEqualTo("T-1001");
        assertThat(teachers.findById(teacherId).orElseThrow().getPhone()).isEqualTo("13800000001");
        assertThat(teachers.findAll().stream()
                .filter(teacher -> teacher.getName().equals("张三")).count()).isEqualTo(1);
        assertThat(courses.findAll().stream()
                .filter(course -> course.getName().equals("语文")).count()).isEqualTo(1);
        assertThat(adminClasses.findBySemesterIdOrderByGradeIdAscNameAsc(semesterId)).hasSize(2);
    }

    @Test
    void sixRoundsWithoutFinishStillYieldsAFlaggedPreview() throws Exception {
        String admin = adminToken();
        TestData.semester(admin);
        fake.responder = request -> isPlanner(request)
                ? "{\"tool\":\"list_sheets\"}"
                : extractionAnswer(request);

        Response preview = upload(admin, workbook(Map.of(
                "数据源", List.of(
                        List.of("八（1）|星期一|1", "语文", "张三"),
                        List.of("八（1）|星期一|2", "数学", "李四"),
                        List.of("八（2）|星期二|1", "英语", "王五")))));

        preview.then().statusCode(200)
                .body("workbook.structureConfirmed", equalTo(false))
                .body("rows", hasSize(3))
                .body("toolTrace", hasSize(6));
        assertThat(preview.jsonPath().getList("warnings", String.class))
                .anySatisfy(warning -> assertThat(warning).contains("结构未确认"));
        assertThat(fake.calls).isEqualTo(6 + 1);
    }

    @Test
    void aFiveHundredAndSixtyRowSheetPreviewsAsFiveHundredAndFortySixLessons() throws Exception {
        String admin = adminToken();
        TestData.semester(admin);
        long semesterId = currentSemester(admin);
        generateDefaultPeriods(admin, semesterId);
        scriptPlan();

        Response preview = upload(admin, workbook(Map.of("数据源", realShapedRows())));

        preview.then().statusCode(200).body("rows", hasSize(546));
        assertThat(preview.jsonPath().getList("warnings", String.class))
                .anySatisfy(warning -> assertThat(warning).contains("空课位 14 个"));
        // 560 rows at 40 per call is 14 extraction calls; the plan took 3 tool rounds.
        assertThat(fake.calls).isEqualTo(3 + 14);
    }

    @Test
    void aWidePivotSheetDeclaredAsTimetableIsSkippedInsteadOfBlocking() throws Exception {
        String admin = adminToken();
        TestData.semester(admin);
        long semesterId = currentSemester(admin);
        generateDefaultPeriods(admin, semesterId);
        // The real workbook's 班级课表 / 教师课表 / 每日考勤表 are wide grids: periods as
        // rows, weekdays (or classes) as columns. Reading them as slots would block.
        fake.responder = request -> {
            if (!isPlanner(request)) {
                return extractionAnswer(request);
            }
            int round = planRounds.incrementAndGet();
            return round < 2 ? "{\"tool\":\"list_sheets\"}" : widePivotFinishAction();
        };

        Response preview = upload(admin, workbook(Map.of(
                "数据源", List.of(
                        List.of("八（1）|星期一|1", "语文", "金林墨"),
                        List.of("八（1）|星期一|2", "数学", "周小媚"),
                        List.of("八（2）|星期二|1", "英语", "虞文茜")),
                "班级课表", List.of(
                        List.of("八年级班级课表"),
                        List.of("请选择班级：", "八（2）"),
                        List.of("节次", "星期一", "星期二", "星期三", "星期四", "星期五"),
                        List.of("第一节", "数学  周小媚", "英语  虞文茜", "科学  徐良贵", "语文  金林墨", "历史地理  贾方俊"),
                        List.of("第二节", "语文  金林墨", "数学  周小媚", "英语  虞文茜", "科学  徐良贵", "语文  金林墨")))));

        preview.then().statusCode(200).body("rows", hasSize(3));
        assertThat(preview.jsonPath().getList("issues.field", String.class)).isEmpty();
        JsonNode pivot = sheet(preview, "班级课表");
        assertThat(pivot.path("category").asText()).isEqualTo("unknown");
        assertThat(pivot.path("skippedReason").asText()).contains("宽表");
        assertThat(pivot.path("newRows").asInt()).isZero();
    }

    @Test
    void toleratesTheWeakModelAnswersActuallyObservedInProduction() throws Exception {
        String admin = adminToken();
        TestData.semester(admin);
        long semesterId = currentSemester(admin);
        generateDefaultPeriods(admin, semesterId);
        // The configured flash model answers with `name` instead of `sheet`, Chinese
        // categories, nested column objects, and one JSON object per line for the data.
        fake.responder = request -> {
            if (!isPlanner(request)) {
                return oneObjectPerLine(request);
            }
            int round = planRounds.incrementAndGet();
            return round < 2 ? "{\"tool\":\"list_sheets\"}" : weakModelFinishAction();
        };

        Response preview = upload(admin, workbook(Map.of(
                "数据源", List.of(
                        List.of("八（1）|星期一|1", "语文", "金林墨"),
                        List.of("八（1）|星期一|2", "数学", "周小媚"),
                        List.of("八（2）|星期二|1", "英语", "虞文茜")),
                "班级课表", List.of(
                        List.of("八年级班级课表"),
                        List.of("请选择班级：", "八（2）"),
                        List.of("节次", "星期一", "星期二", "星期三", "星期四", "星期五"),
                        List.of("第一节", "数学  周小媚", "英语  虞文茜", "科学  徐良贵", "语文  金林墨", "历史地理  贾方俊")))));

        preview.then().statusCode(200).body("rows", hasSize(3));
        assertThat(preview.jsonPath().getList("issues.field", String.class)).isEmpty();
        assertThat(sheet(preview, "数据源").path("category").asText()).isEqualTo("timetable");
        assertThat(sheet(preview, "班级课表").path("category").asText()).isEqualTo("unknown");
        assertThat(preview.jsonPath().getString("rows[0].values.teacherName")).isEqualTo("金林墨");
    }

    @Test
    void missingSemesterIsAPageLevelBlocker() throws Exception {
        String admin = adminToken();
        scriptPlan();

        Response preview = upload(admin, workbook(Map.of(
                "数据源", List.of(
                        List.of("八（1）|星期一|1", "语文", "张三"),
                        List.of("八（1）|星期一|2", "数学", "李四"),
                        List.of("八（2）|星期二|1", "英语", "王五")))));

        preview.then().statusCode(200)
                .body("issues[0].field", equalTo("semester"))
                .body("issues[0].message", containsString("设置当前学期"));
    }

    @Test
    void aSemesterWithoutPeriodsOffersTheDefaultSchedule() throws Exception {
        String admin = adminToken();
        TestData.semester(admin);
        scriptPlan();

        Response preview = upload(admin, workbook(Map.of(
                "数据源", List.of(
                        List.of("八（1）|星期一|1", "语文", "张三"),
                        List.of("八（1）|星期一|2", "数学", "李四"),
                        List.of("八（2）|星期二|1", "英语", "王五")))));

        preview.then().statusCode(200)
                .body("issues[0].field", equalTo("periods"))
                .body("issues[0].message", containsString("默认作息"));
    }

    // ---------------------------------------------------------------- fixtures

    private Map<String, List<List<String>>> realisticSheets() {
        return Map.of(
                "数据源", List.of(
                        List.of("八（1）|星期一|1", "语文", "张三"),
                        List.of("八（1）|星期一|2", "数学", "李四"),
                        List.of("八（1）|星期五|7", "", ""),
                        List.of("八（2）|星期二|1", "英语", "王五"),
                        List.of("八（2）|星期二|2", "语文", "张三")),
                "班级课表", List.of(
                        List.of("班级", "星期", "节次", "课程", "教师"),
                        List.of("八（1）", "星期一", "1", "语文", "张三"),
                        List.of("八（1）", "星期一", "2", "数学", "李四"),
                        List.of("八（2）", "星期二", "1", "英语", "王五"),
                        List.of("八（2）", "星期二", "2", "语文", "张三"),
                        List.of("八（2）", "星期三", "1", "数学", "李四")),
                "教师名单", List.of(
                        List.of("姓名", "工号", "手机", "邮箱", "学科"),
                        List.of("张三", "T-1001", "13800000001", "zhangsan@example.com", "语文"),
                        List.of("李四", "T-1002", "13800000002", "lisi@example.com", "数学")),
                "使用说明", List.of(
                        List.of("使用说明"),
                        List.of("1. 本表由教务系统导出"),
                        List.of("2. 请勿修改列顺序")));
    }

    /** 14 classes x 5 days x 8 periods, with 星期五第7节 left empty for each class. */
    private List<List<String>> realShapedRows() {
        List<String> days = List.of("星期一", "星期二", "星期三", "星期四", "星期五");
        List<String> courses = List.of("语文", "数学", "英语");
        List<String> teachers = List.of("张三", "李四", "王五");
        List<List<String>> rows = new ArrayList<>();
        for (int klass = 1; klass <= 14; klass++) {
            for (int day = 0; day < days.size(); day++) {
                for (int period = 1; period <= 8; period++) {
                    String key = "八（" + klass + "）|" + days.get(day) + "|" + period;
                    if (day == 4 && period == 7) {
                        rows.add(List.of(key, "", ""));
                        continue;
                    }
                    int index = (klass + day + period) % courses.size();
                    rows.add(List.of(key, courses.get(index), teachers.get(index)));
                }
            }
        }
        return rows;
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

    // ------------------------------------------------------------------- stub

    private boolean isPlanner(AiRequest request) {
        return request.messages().size() > 0 && request.messages().get(0).content().contains("只读工具");
    }

    /** list_sheets → read_sheet → finish, so the trace covers every planned tool call. */
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
        Map<String, Object> wide = new LinkedHashMap<>();
        wide.put("className", "班级");
        wide.put("dayOfWeek", "星期");
        wide.put("periodNo", "节次");
        wide.put("courseName", "课程");
        wide.put("teacherName", "教师");
        Map<String, Object> teacher = new LinkedHashMap<>();
        teacher.put("name", "姓名");
        teacher.put("employeeNo", "工号");
        teacher.put("phone", "手机");
        teacher.put("email", "邮箱");
        teacher.put("subject", "学科");
        List<Map<String, Object>> sheets = List.of(
                sheetPlan("数据源", "timetable", packed, "无表头，复合列已拆分"),
                sheetPlan("班级课表", "timetable", wide, "一行一节课"),
                sheetPlan("教师名单", "teacher", teacher, "教师名单"));
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("sheets", sheets);
        args.put("explanation", "数据源为无表头的查询表，班级课表是透视视图，教师名单提供教师字段。");
        return json(Map.of("tool", "finish", "args", args));
    }

    /** The plan the flash model produced on the real file: name key, Chinese category. */
    private String weakModelFinishAction() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("列1", Map.of("split_into", List.of("班级", "星期", "节次")));
        List<Map<String, Object>> sheets = List.of(
                sheetPlanNamed("数据源", "原始数据", nested, "无表头，复合列已拆分"),
                sheetPlanNamed("班级课表", "班级课表", Map.of("节次", Map.of()), "按班级查询的宽表"));
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("sheets", sheets);
        args.put("explanation", "数据源逐节课；班级课表是宽表。");
        return json(Map.of("tool", "finish", "args", args));
    }

    private Map<String, Object> sheetPlanNamed(
            String sheet, String category, Map<String, Object> columns, String note) {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("name", sheet);
        plan.put("category", category);
        plan.put("columns", columns);
        plan.put("note", note);
        return plan;
    }

    /** One JSON object per line, no `rows` wrapper: what the flash model returned. */
    private String oneObjectPerLine(AiRequest request) {
        try {
            String prompt = request.messages().get(1).content();
            int marker = prompt.indexOf(DATA_MARKER);
            JsonNode payload = mapper.readTree(prompt.substring(marker + DATA_MARKER.length()).trim());
            List<String> fields = new ArrayList<>();
            payload.path("columns").fieldNames().forEachRemaining(fields::add);
            StringBuilder answer = new StringBuilder();
            for (JsonNode row : payload.path("rows")) {
                Map<String, String> values = new LinkedHashMap<>();
                for (int i = 0; i < fields.size(); i++) {
                    values.put(fields.get(i), row.path(i).asText(""));
                }
                if (answer.length() > 0) {
                    answer.append('\n');
                }
                answer.append(json(values));
            }
            return answer.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("无法按表结构构造回答", ex);
        }
    }

    private String widePivotFinishAction() {
        Map<String, Object> pivotColumns = new LinkedHashMap<>();
        pivotColumns.put("periodNo", "节次");
        List<Map<String, Object>> sheets = List.of(
                sheetPlan("数据源", "timetable", Map.of(
                        "className", "列1·班级",
                        "dayOfWeek", "列1·星期",
                        "periodNo", "列1·节次",
                        "courseName", "列2",
                        "teacherName", "列3"), "无表头"),
                sheetPlan("班级课表", "timetable", pivotColumns, "按班级查询的宽表"));
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("sheets", sheets);
        args.put("explanation", "数据源逐节课；班级课表是宽表。");
        return json(Map.of("tool", "finish", "args", args));
    }

    private Map<String, Object> sheetPlan(
            String sheet, String category, Map<String, Object> columns, String note) {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("sheet", sheet);
        plan.put("category", category);
        plan.put("columns", columns);
        plan.put("note", note);
        return plan;
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

    private Response upload(String token, byte[] bytes) {
        return RestAssured.given()
                .header("Authorization", "Bearer " + token)
                .multiPart("file", "真实教务工作簿.xlsx", bytes, XLSX)
                .queryParam("target", "workbook")
                .when().post("/api/ai/import/excel");
    }

    private JsonNode sheet(Response preview, String name) throws Exception {
        for (JsonNode sheet : mapper.readTree(preview.body().asString()).path("workbook").path("sheets")) {
            if (sheet.path("sheet").asText().equals(name)) {
                return sheet;
            }
        }
        throw new AssertionError("预览里没有工作表：" + name);
    }

    private long currentSemester(String token) {
        Response response = as(token).when().get("/api/semesters/current");
        response.then().statusCode(200);
        return response.jsonPath().getLong("id");
    }

    private void generateDefaultPeriods(String token, long semesterId) {
        as(token)
                .body(Map.of(
                        "morningCount", 4, "afternoonCount", 4, "eveningCount", 0,
                        "days", List.of(1, 2, 3, 4, 5)))
                .when().post("/api/semesters/" + semesterId + "/periods/default")
                .then().statusCode(200);
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
        FakeAiGateway fakeAiGateway(com.paiban.ai.imports.AiImportLogRepository logs) {
            return new FakeAiGateway(logs);
        }
    }

    static class FakeAiGateway implements AiGateway {

        private final com.paiban.ai.imports.AiImportLogRepository logs;
        volatile java.util.function.Function<AiRequest, String> responder;
        volatile int calls;

        FakeAiGateway(com.paiban.ai.imports.AiImportLogRepository logs) {
            this.logs = logs;
        }

        List<com.paiban.ai.imports.AiImportLog> logs() {
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
