package com.paiban.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;

import com.paiban.scheduling.domain.DraftLesson;
import com.paiban.scheduling.domain.DraftLessonRepository;
import com.paiban.scheduling.domain.TimetableDraft;
import com.paiban.scheduling.domain.TimetableDraftRepository;
import com.paiban.support.ApiIntegrationTest;
import com.paiban.support.ScheduleFixture;
import com.paiban.support.TestData;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** Public workflow acceptance; repositories only seed a deterministic imported draft. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class WeeklyNoClassPublishedAcceptanceTest extends ApiIntegrationTest {
    @Autowired private TimetableDraftRepository drafts;
    @Autowired private DraftLessonRepository lessons;

    @Test
    void permissionsVersionConflictAndDeletionPreserveThePublishedBaseline() {
        Fixture f = fixture();
        List<Map<String, Object>> baseline = ScheduleFixture.publishedEntries(f.admin(), f.classId());
        long version = version(f);
        as(f.teacher()).queryParam("semesterId", f.semester()).queryParam("baseVersion", version)
                .body(Map.of("configs", List.of(ban(f)))).put("/api/constraints").then().statusCode(403);
        as(f.teacher()).queryParam("semesterId", f.semester())
                .body(Map.of("configs", List.of(ban(f)))).post("/api/constraints/preview")
                .then().statusCode(403);
        assertThat(version(f)).isEqualTo(version);
        save(f, version, List.of(ban(f)));
        as(f.admin()).queryParam("semesterId", f.semester()).queryParam("baseVersion", version)
                .body(Map.of("configs", List.of())).put("/api/constraints").then().statusCode(409);
        assertThat(version(f)).isEqualTo(version + 1);
        as(f.admin()).get("/api/timetable-drafts/" + f.draft()).then().statusCode(200)
                .body("lessons", hasSize(2)).body("blockedSlots", hasSize(1));
        assertThat(ScheduleFixture.publishedEntries(f.admin(), f.classId())).isEqualTo(baseline);
        save(f, version(f), List.of());
        as(f.admin()).get("/api/timetable-drafts/" + f.draft()).then().statusCode(200)
                .body("lessons", hasSize(2)).body("blockedSlots", hasSize(0));
        assertThat(ScheduleFixture.publishedEntries(f.admin(), f.classId())).isEqualTo(baseline);
    }

    @Test
    void aNewBanRejectsNewPlansAndAnAlreadyGeneratedPlanWithoutPartialChanges() {
        Fixture f = fixture();
        List<Map<String, Object>> baseline = ScheduleFixture.publishedEntries(f.admin(), f.classId());

        // 先生成一个候选置换环并预览：凭证绑定的是「加约束之前」的课表快照。
        long plan = as(f.admin()).body(Map.of("entryId", f.sourceEntry(),
                        "lessonDate", f.monday().toString(), "targetDate", f.monday().toString(),
                        "targetPeriodId", f.targetPeriod(), "reason", "留空验收"))
                .when().post("/api/substitution-workbench/swap-plans")
                .then().statusCode(200).body("plans", hasSize(1))
                .extract().jsonPath().getLong("plans[0].planId");
        long voucher = as(f.admin()).body(Map.of("entryId", f.sourceEntry(),
                        "lessonDate", f.monday().toString(), "planId", plan, "reason", "留空验收"))
                .when().post("/api/substitution-workbench/swap-previews")
                .then().statusCode(200).extract().jsonPath().getLong("voucherId");

        save(f, version(f), List.of(ban(f)));

        // 已生成的方案不能再生效：确认时被硬约束整单拒绝，零写入、凭证不消费。
        as(f.admin()).body(Map.of())
                .when().post("/api/substitution-workbench/previews/" + voucher + "/confirm-swap")
                .then().statusCode(409)
                .body("message", org.hamcrest.Matchers.containsString("CLASS_UNAVAILABLE"));
        as(f.admin()).get("/api/substitutions").then().statusCode(200).body("$", hasSize(0));
        assertThat(ScheduleFixture.publishedEntries(f.admin(), f.classId())).isEqualTo(baseline);

        // 加约束后重新生成候选：不再产出方案，并说明是哪条硬约束。
        var generated = as(f.admin()).body(Map.of("entryId", f.sourceEntry(),
                        "lessonDate", f.monday().toString(), "targetDate", f.monday().toString(),
                        "targetPeriodId", f.targetPeriod(), "reason", "留空验收"))
                .when().post("/api/substitution-workbench/swap-plans")
                .then().statusCode(200).body("plans", hasSize(0)).extract().response();
        assertThat(generated.asString()).contains("CLASS_UNAVAILABLE");
    }

    private Map<String, Object> ban(Fixture f) {
        return Map.of("type", "CLASS_UNAVAILABLE", "enabled", true, "hard", true, "weight", 100,
                "entries", List.of(Map.of("params", Map.of("classId", f.classId(),
                        "dayOfWeek", 1, "periodNo", 2), "groupKey", "weekly-published")));
    }

    private long version(Fixture f) {
        return as(f.admin()).queryParam("semesterId", f.semester()).get("/api/constraints/state")
                .then().statusCode(200).extract().jsonPath().getLong("version");
    }

    private void save(Fixture f, long version, List<Map<String, Object>> configs) {
        as(f.admin()).queryParam("semesterId", f.semester()).queryParam("baseVersion", version)
                .body(Map.of("configs", configs)).put("/api/constraints").then().statusCode(200);
    }

    private Fixture fixture() {
        String admin = adminToken();
        String suffix = TestData.suffix();
        LocalDate monday = ScheduleFixture.nextMonday();
        long semester = TestData.semesterStarting(admin, monday);
        as(admin).body(Map.of("morningCount", 2, "afternoonCount", 0, "eveningCount", 0,
                        "days", List.of(1)))
                .post("/api/semesters/" + semester + "/periods/default").then().statusCode(200);
        List<Map<String, Object>> periods = as(admin).get("/api/semesters/" + semester + "/periods")
                .then().statusCode(200).extract().jsonPath().getList("$");
        long grade = TestData.grade(admin, semester, "验收级" + suffix);
        long klass = TestData.adminClass(admin, semester, grade, "验收班" + suffix);
        long teacher = TestData.teacher(admin, "验收教师" + suffix);
        long other = TestData.teacher(admin, "另一教师" + suffix);
        long course = TestData.course(admin, "验收语文" + suffix);
        long otherCourse = TestData.course(admin, "验收数学" + suffix);
        TimetableDraft draft = new TimetableDraft();
        draft.setSemesterId(semester);
        draft.setName("固定留空发布边界" + suffix);
        draft.setStatus("DRAFT");
        draft.setCreatedBy("test");
        drafts.save(draft);
        for (int i = 0; i < 2; i++) {
            DraftLesson row = new DraftLesson();
            row.setDraftId(draft.getId());
            // V45 identity is (draft, assignment, lesson index): each row is its own lesson.
            row.setAssignmentId((long) i + 1);
            row.setLessonIndex(1);
            row.setAdminClassId(klass);
            row.setCourseId(i == 0 ? course : otherCourse);
            row.setTeacherId(i == 0 ? teacher : other);
            row.setStudentCount(45);
            row.setPeriodId(((Number) periods.get(i).get("id")).longValue());
            lessons.save(row);
        }
        as(admin).post("/api/timetable-drafts/" + draft.getId() + "/publish").then().statusCode(200);
        long source = ScheduleFixture.publishedEntries(admin, klass).stream()
                .filter(row -> ((Number) row.get("courseId")).longValue() == course)
                .mapToLong(row -> ((Number) row.get("id")).longValue()).findFirst().orElseThrow();
        String username = "weekly_" + suffix;
        as(admin).body(Map.of("username", username, "password", "test-pass-123", "displayName", username,
                        "roleCodes", List.of("teacher"), "teacherId", teacher))
                .post("/api/users").then().statusCode(200);
        return new Fixture(admin, login(username, "test-pass-123"), semester, klass, draft.getId(), source,
                ((Number) periods.get(1).get("id")).longValue(), monday);
    }

    private record Fixture(String admin, String teacher, long semester, long classId,
                           long draft, long sourceEntry, long targetPeriod, LocalDate monday) {}
}
