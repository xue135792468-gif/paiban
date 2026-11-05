package com.paiban.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import com.paiban.foundation.domain.Period;
import com.paiban.foundation.domain.PeriodRepository;
import com.paiban.scheduling.domain.ConstraintConfig;
import com.paiban.scheduling.domain.ConstraintConfigRepository;
import com.paiban.scheduling.domain.ConstraintEntry;
import com.paiban.scheduling.domain.ConstraintEntryRepository;
import com.paiban.scheduling.domain.ConstraintMigrationMap;
import com.paiban.scheduling.domain.ConstraintMigrationMapRepository;
import com.paiban.scheduling.domain.ConstraintMigrationRunRepository;
import com.paiban.scheduling.domain.ConstraintType;
import com.paiban.scheduling.domain.DraftLesson;
import com.paiban.scheduling.domain.DraftLessonRepository;
import com.paiban.scheduling.domain.TimetableDraft;
import com.paiban.scheduling.domain.TimetableDraftRepository;
import com.paiban.scheduling.service.ConstraintMigrationService;
import com.paiban.support.ApiIntegrationTest;
import com.paiban.support.TestData;
import io.restassured.response.Response;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Ticket 11 acceptance: the stored 26-type configuration migrates to the 19-type
 * catalogue without deleting a row, the 7 retired types are paused, a dangling
 * reference becomes a REVIEW item instead of a silent pause, a second run is
 * idempotent, and the before/after draft verdict is recorded with the same numbers
 * for the retained types.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ConstraintMigrationIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private ConstraintConfigRepository configRepository;

    @Autowired
    private ConstraintEntryRepository entryRepository;

    @Autowired
    private ConstraintMigrationMapRepository mapRepository;

    @Autowired
    private ConstraintMigrationRunRepository runRepository;

    @Autowired
    private TimetableDraftRepository draftRepository;

    @Autowired
    private DraftLessonRepository draftLessonRepository;

    @Autowired
    private PeriodRepository periodRepository;

    @Test
    void mappingTableCoverstheWholeCatalogue() {
        assertThat(ConstraintType.values()).hasSize(26);
        assertThat(ConstraintType.activeValues()).hasSize(19);
        assertThat(ConstraintMigrationService.TYPE_ACTIONS).hasSize(26);
        for (ConstraintType type : ConstraintType.values()) {
            assertThat(ConstraintMigrationService.TYPE_ACTIONS.get(type))
                    .as(type.name())
                    .isEqualTo(type.isRetired()
                            ? ConstraintMigrationService.Action.DISABLE
                            : ConstraintMigrationService.Action.RETAIN);
        }
        assertThat(ConstraintMigrationService.TYPE_ACTIONS.values())
                .doesNotContain(ConstraintMigrationService.Action.CONVERT);
    }

    @Test
    void runDisablesSevenTypesKeepsNineteenAndRecordsBeforeAfter() {
        String token = adminToken();
        long semesterId = semesterWithPeriods(token);
        long classA = TestData.adminClass(token, semesterId,
                TestData.grade(token, semesterId, "七年级" + TestData.suffix()),
                "七1班" + TestData.suffix());
        long classB = TestData.adminClass(token, semesterId,
                TestData.grade(token, semesterId, "七年级B" + TestData.suffix()),
                "七2班" + TestData.suffix());
        long teacher = TestData.teacher(token, "迁移老师" + TestData.suffix());
        long course = TestData.course(token, "迁移课程" + TestData.suffix());
        long room = TestData.room(token, "迁移教室" + TestData.suffix());
        insertAllConfigs(semesterId);
        long reviewEntryId = danglingEntry(semesterId);

        Response preview = as(token).queryParam("semesterId", semesterId)
                .when().get("/api/constraint-migration/preview")
                .then().statusCode(200).extract().response();

        assertThat(preview.jsonPath().getInt("configCount")).isEqualTo(26);
        assertThat(preview.jsonPath().getInt("entryCount")).isEqualTo(1);
        assertThat(preview.jsonPath().getInt("retained")).isEqualTo(19);
        assertThat(preview.jsonPath().getInt("converted")).isZero();
        assertThat(preview.jsonPath().getInt("disabled")).isEqualTo(7);
        assertThat(preview.jsonPath().getInt("review")).isEqualTo(1);
        List<Map<String, Object>> previewRows = preview.jsonPath().getList("rows");
        assertThat(previewRows).hasSize(27);

        Map<String, Object> disabledRow = row(previewRows, "ROOM_CONFLICT", 0L);
        assertThat(disabledRow.get("action")).isEqualTo("DISABLE");
        assertThat(disabledRow.get("newType")).isNull();
        assertThat(disabledRow.get("requiresReview")).isEqualTo(false);

        Map<String, Object> retainedRow = row(previewRows, "TEACHER_UNAVAILABLE", 0L);
        assertThat(retainedRow.get("action")).isEqualTo("RETAIN");
        assertThat(retainedRow.get("newType")).isEqualTo("TEACHER_UNAVAILABLE");

        Map<String, Object> reviewRow = row(previewRows, "TEACHER_UNAVAILABLE", reviewEntryId);
        assertThat(reviewRow.get("action")).isEqualTo("REVIEW");
        assertThat(reviewRow.get("requiresReview")).isEqualTo(true);
        assertThat(String.valueOf(reviewRow.get("note"))).contains("teacherId");

        long draftId = draftWithTeacherConflict(semesterId, classA, classB, teacher, course, room);
        List<String> beforeCodes = conflictCodes(token, draftId);

        Response run = as(token).queryParam("semesterId", semesterId)
                .when().post("/api/constraint-migration/run")
                .then().statusCode(200).extract().response();

        assertThat(run.jsonPath().getString("status")).isEqualTo("DONE");
        assertThat(run.jsonPath().getInt("retained")).isEqualTo(19);
        assertThat(run.jsonPath().getInt("disabled")).isEqualTo(7);
        assertThat(run.jsonPath().getInt("review")).isEqualTo(1);
        assertThat(run.jsonPath().getList("rows")).hasSize(27);

        assertThat(run.jsonPath().getBoolean("before.skipped")).isFalse();
        assertThat(run.jsonPath().getInt("before.lessons")).isEqualTo(2);
        assertThat(run.jsonPath().getInt("before.hardConflicts")).isGreaterThan(0);
        assertThat(run.jsonPath().getInt("after.hardConflicts"))
                .isEqualTo(run.jsonPath().getInt("before.hardConflicts"));
        assertThat(run.jsonPath().getInt("after.softPenalty"))
                .isEqualTo(run.jsonPath().getInt("before.softPenalty"));
        assertThat(run.jsonPath().getString("after.settingsFingerprint"))
                .isEqualTo(run.jsonPath().getString("before.settingsFingerprint"));
        assertThat(run.jsonPath().getInt("before.enabledConfigCount")).isEqualTo(26);
        assertThat(run.jsonPath().getInt("after.enabledConfigCount")).isEqualTo(19);

        // Every old row is still there; only the 7 retired types flipped enabled.
        List<ConstraintConfig> stored = configRepository.findBySemesterId(semesterId);
        assertThat(stored).hasSize(26);
        assertThat(stored.stream().filter(row -> !row.isEnabled())
                .map(ConstraintConfig::getType))
                .containsExactlyInAnyOrderElementsOf(retiredTypeNames());
        assertThat(stored.stream().filter(ConstraintConfig::isEnabled)
                .map(ConstraintConfig::getType))
                .containsExactlyInAnyOrderElementsOf(activeTypeNames());
        assertThat(stored.stream()
                .filter(row -> activeTypeNames().contains(row.getType()))
                .allMatch(row -> "{}".equals(row.getParamsJson())))
                .isTrue();

        ConstraintEntry entry = entryRepository.findById(reviewEntryId).orElseThrow();
        assertThat(entry.isEnabled()).isTrue();
        assertThat(entry.getParamsJson()).contains("999999");

        assertThat(conflictCodes(token, draftId)).isEqualTo(beforeCodes);
        assertThat(conflictCodes(token, draftId)).doesNotContain("ROOM_CAPACITY_FIT");

        List<ConstraintMigrationMap> mapRows =
                mapRepository.findBySemesterIdOrderByOldTypeAscOldEntryIdAsc(semesterId);
        assertThat(mapRows).hasSize(27);
        assertThat(mapRows.stream()
                .map(row -> row.getOldType() + "#" + row.getOldEntryId()).distinct().count())
                .isEqualTo(27);
        assertThat(mapRows.stream()
                .filter(row -> "ROOM_CAPACITY_FIT".equals(row.getOldType()))
                .findFirst().orElseThrow().getAction()).isEqualTo("DISABLE");

        // A later full save from the rule page only carries the 19 active types; the
        // paused old rows must survive it (ticket 09 deferred this to ticket 11).
        long version = as(token).queryParam("semesterId", semesterId)
                .when().get("/api/constraints/state")
                .then().statusCode(200).extract().jsonPath().getLong("version");
        as(token).queryParam("semesterId", semesterId)
                .queryParam("baseVersion", version)
                .body(Map.of("configs", activeConfigRequests()))
                .when().put("/api/constraints")
                .then().statusCode(200);
        assertThat(configRepository.findBySemesterId(semesterId)).hasSize(26);
        assertThat(configRepository.findBySemesterId(semesterId).stream()
                .filter(row -> !row.isEnabled()).count()).isEqualTo(7);
    }

    @Test
    void secondRunUpdatesTheSameMapRowsAndChangesNothing() {
        String token = adminToken();
        long semesterId = semesterWithPeriods(token);
        insertAllConfigs(semesterId);
        long reviewEntryId = danglingEntry(semesterId);

        as(token).queryParam("semesterId", semesterId)
                .when().post("/api/constraint-migration/run")
                .then().statusCode(200);
        long firstRunCount = runRepository.count();
        as(token).queryParam("semesterId", semesterId)
                .when().post("/api/constraint-migration/run")
                .then().statusCode(200);

        assertThat(runRepository.count()).isEqualTo(firstRunCount + 1);
        assertThat(mapRepository
                .findBySemesterIdOrderByOldTypeAscOldEntryIdAsc(semesterId)).hasSize(27);
        assertThat(configRepository.findBySemesterId(semesterId).stream()
                .filter(row -> !row.isEnabled()).count()).isEqualTo(7);
        assertThat(entryRepository.findById(reviewEntryId).orElseThrow().isEnabled()).isTrue();

        Response report = as(token).queryParam("semesterId", semesterId)
                .when().get("/api/constraint-migration/report")
                .then().statusCode(200).extract().response();
        assertThat(report.jsonPath().getString("status")).isEqualTo("DONE");
        assertThat(report.jsonPath().getList("rows")).hasSize(27);
        assertThat(report.jsonPath().getInt("review")).isEqualTo(1);
    }

    @Test
    void retiredEntriesArePausedAndRemainVisible() {
        String token = adminToken();
        long semesterId = semesterWithPeriods(token);
        ConstraintConfig retired = new ConstraintConfig();
        retired.setSemesterId(semesterId);
        retired.setType("ROOM_CONFLICT");
        retired.setParamsJson("{}");
        retired.setWeight(100);
        retired.setHard(true);
        retired.setEnabled(true);
        configRepository.save(retired);
        ConstraintEntry retiredEntry = new ConstraintEntry();
        retiredEntry.setSemesterId(semesterId);
        retiredEntry.setType("ROOM_CONFLICT");
        retiredEntry.setParamsJson("{}");
        retiredEntry.setEnabled(true);
        entryRepository.save(retiredEntry);

        as(token).queryParam("semesterId", semesterId)
                .when().post("/api/constraint-migration/run")
                .then().statusCode(200);

        assertThat(configRepository.findById(retired.getId()).orElseThrow().isEnabled()).isFalse();
        assertThat(entryRepository.findById(retiredEntry.getId()).orElseThrow().isEnabled())
                .isFalse();
    }

    @Test
    void semesterWithoutDraftStillRunsAndMarksTheComparisonSkipped() {
        String token = adminToken();
        long semesterId = TestData.semester(token);

        Response run = as(token).queryParam("semesterId", semesterId)
                .when().post("/api/constraint-migration/run")
                .then().statusCode(200).extract().response();

        assertThat(run.jsonPath().getString("status")).isEqualTo("DONE");
        assertThat(run.jsonPath().getBoolean("before.skipped")).isTrue();
        assertThat(run.jsonPath().getBoolean("after.skipped")).isTrue();
        assertThat(run.jsonPath().getInt("before.lessons")).isZero();
    }

    @Test
    void reportIsNoContentBeforeTheFirstRun() {
        String token = adminToken();
        long semesterId = TestData.semester(token);

        as(token).queryParam("semesterId", semesterId)
                .when().get("/api/constraint-migration/report")
                .then().statusCode(204);
    }

    // ------------------------------------------------- ticket 11 review (P0/P1)

    /**
     * Pausing the seven types is a configuration write: the version the advanced page
     * and every cached preview compare against must move, otherwise the page keeps
     * rendering its pre-migration 26-type view.
     */
    @Test
    void runAdvancesTheVersionAndTheAdvancedPageNoLongerSeesTheSevenTypes() {
        String token = adminToken();
        long semesterId = semesterWithPeriods(token);
        insertAllConfigs(semesterId);
        long before = stateVersion(token, semesterId);

        as(token).queryParam("semesterId", semesterId)
                .when().post("/api/constraint-migration/run").then().statusCode(200);

        assertThat(stateVersion(token, semesterId)).isGreaterThan(before);
        List<Map<String, Object>> configs = enabledStateConfigs(token, semesterId);
        assertThat(configs).extracting(row -> String.valueOf(row.get("type")))
                .containsExactlyInAnyOrderElementsOf(activeTypeNames());
        assertThat(configs).extracting(row -> String.valueOf(row.get("type")))
                .doesNotContainAnyElementsOf(retiredTypeNames());
    }

    /** Omitting the parameter means "the current semester", the same as every other rule read. */
    @Test
    void omittedSemesterParamResolvesTheCurrentSemester() {
        String token = adminToken();
        long semesterId = semesterWithPeriods(token);
        insertAllConfigs(semesterId);

        Response preview = as(token).when().get("/api/constraint-migration/preview")
                .then().statusCode(200).extract().response();
        assertThat(preview.jsonPath().getLong("semesterId")).isEqualTo(semesterId);
        assertThat(preview.jsonPath().getInt("disabled")).isEqualTo(7);

        Response run = as(token).when().post("/api/constraint-migration/run")
                .then().statusCode(200).extract().response();
        assertThat(run.jsonPath().getLong("semesterId")).isEqualTo(semesterId);

        Response report = as(token).when().get("/api/constraint-migration/report")
                .then().statusCode(200).extract().response();
        assertThat(report.jsonPath().getLong("semesterId")).isEqualTo(semesterId);
    }

    /** Without a current semester the omission is a 400 in plain language, never a 500. */
    @Test
    void omittedSemesterWithoutACurrentOneIsABadRequestOnAllThreeEndpoints() {
        String token = adminToken();
        as(token).body(Map.of(
                        "name", "无当前学期" + TestData.suffix(),
                        "startDate", "2025-09-01",
                        "endDate", "2026-01-20"))
                .when().post("/api/semesters").then().statusCode(200);

        for (String path : List.of("/api/constraint-migration/preview",
                "/api/constraint-migration/report")) {
            Response response = as(token).when().get(path).then().extract().response();
            assertThat(response.statusCode()).as(path).isEqualTo(400);
            assertThat(response.asString()).as(path).contains("当前学期");
        }
        Response run = as(token).when().post("/api/constraint-migration/run")
                .then().extract().response();
        assertThat(run.statusCode()).isEqualTo(400);
        assertThat(run.asString()).contains("当前学期");
    }

    /**
     * A key the type's schema does not declare is a rule no evaluator reads. Reporting
     * it as RETAIN would claim the migration preserved something it did not understand.
     */
    @Test
    void aMisspelledParameterKeyIsListedForReviewAndLeftUntouched() {
        String token = adminToken();
        long semesterId = semesterWithPeriods(token);
        insertAllConfigs(semesterId);
        ConstraintEntry typo = new ConstraintEntry();
        typo.setSemesterId(semesterId);
        typo.setType("TEACHER_UNAVAILABLE");
        typo.setGroupKey("typo-slot");
        typo.setParamsJson("{\"teacher_id\":123,\"dayOfWeek\":2,\"periodNo\":2}");
        typo.setEnabled(true);
        long entryId = entryRepository.save(typo).getId();

        Response preview = as(token).queryParam("semesterId", semesterId)
                .when().get("/api/constraint-migration/preview")
                .then().statusCode(200).extract().response();
        assertThat(preview.jsonPath().getInt("review")).isEqualTo(1);
        Map<String, Object> reviewRow =
                row(preview.jsonPath().getList("rows"), "TEACHER_UNAVAILABLE", entryId);
        assertThat(reviewRow.get("action")).isEqualTo("REVIEW");
        assertThat(String.valueOf(reviewRow.get("note"))).contains("teacher_id");

        as(token).queryParam("semesterId", semesterId)
                .when().post("/api/constraint-migration/run").then().statusCode(200);

        ConstraintEntry stored = entryRepository.findById(entryId).orElseThrow();
        assertThat(stored.isEnabled()).isTrue();
        assertThat(stored.getParamsJson()).contains("teacher_id");
    }

    /**
     * The before/after evidence: a retained rule still produces the same hard conflict,
     * and the digest of every retained row is identical. The settings fingerprint alone
     * proves little, because it skips paused rows by design.
     */
    @Test
    void aRetainedRuleKeepsItsDraftConflictAndTheRetainedDigest() {
        String token = adminToken();
        long semesterId = semesterWithPeriods(token);
        long gradeId = TestData.grade(token, semesterId, "七年级" + TestData.suffix());
        long classId = TestData.adminClass(token, semesterId, gradeId, "七1班" + TestData.suffix());
        long teacher = TestData.teacher(token, "保留规则老师" + TestData.suffix());
        long course = TestData.course(token, "保留规则课程" + TestData.suffix());
        long room = TestData.room(token, "保留规则教室" + TestData.suffix());
        insertAllConfigs(semesterId);
        long entryId = groupedUnavailableEntry(semesterId, teacher);
        long draftId = draftWithLesson(semesterId, classId, course, teacher, room);

        List<String> before = conflictCodes(token, draftId);
        assertThat(before).contains("TEACHER_UNAVAILABLE");

        Response run = as(token).queryParam("semesterId", semesterId)
                .when().post("/api/constraint-migration/run")
                .then().statusCode(200).extract().response();

        assertThat(run.jsonPath().getString("after.retainedDigest"))
                .isEqualTo(run.jsonPath().getString("before.retainedDigest"));
        assertThat(run.jsonPath().getInt("after.retainedRows"))
                .isEqualTo(run.jsonPath().getInt("before.retainedRows"));
        // 19 retained type rows plus the one retained entry row.
        assertThat(run.jsonPath().getInt("before.retainedRows"))
                .isEqualTo(ConstraintType.activeValues().size() + 1);
        assertThat(run.jsonPath().getInt("before.hardConflicts")).isGreaterThan(0);
        assertThat(conflictCodes(token, draftId)).isEqualTo(before);
        assertThat(entryRepository.findById(entryId).orElseThrow().isEnabled()).isTrue();
    }

    // ------------------------------------------------------------------ helpers

    private List<String> activeTypeNames() {
        return ConstraintType.activeValues().stream().map(ConstraintType::name).toList();
    }

    /** What the rule page sends: every active type, no retired type, no entry rows. */
    private List<Map<String, Object>> activeConfigRequests() {
        return ConstraintType.activeValues().stream()
                .map(type -> Map.<String, Object>of(
                        "type", type.name(),
                        "weight", type.getDefaultWeight(),
                        "hard", type.isDefaultHard(),
                        "enabled", true,
                        "entries", List.of()))
                .toList();
    }

    private List<String> retiredTypeNames() {
        return java.util.Arrays.stream(ConstraintType.values())
                .filter(ConstraintType::isRetired).map(ConstraintType::name).toList();
    }

    private long semesterWithPeriods(String token) {
        long semesterId = TestData.semester(token);
        as(token).body(Map.of("morningCount", 4, "afternoonCount", 4, "eveningCount", 0))
                .when().post("/api/semesters/" + semesterId + "/periods/default")
                .then().statusCode(200);
        return semesterId;
    }

    /** All 26 type rows, including the two the semester listener already created. */
    private void insertAllConfigs(long semesterId) {
        for (ConstraintType type : ConstraintType.values()) {
            ConstraintConfig config = configRepository
                    .findBySemesterIdAndType(semesterId, type.name())
                    .orElseGet(ConstraintConfig::new);
            config.setSemesterId(semesterId);
            config.setType(type.name());
            config.setParamsJson("{}");
            config.setWeight(type.getDefaultWeight());
            config.setHard(type.isDefaultHard());
            config.setEnabled(true);
            configRepository.save(config);
        }
    }

    /** One retained entry whose teacher id does not exist: the REVIEW case. */
    private long danglingEntry(long semesterId) {
        ConstraintEntry entry = new ConstraintEntry();
        entry.setSemesterId(semesterId);
        entry.setType("TEACHER_UNAVAILABLE");
        entry.setParamsJson("{\"teacherId\":999999,\"dayOfWeek\":1,\"periodNo\":1}");
        entry.setEnabled(true);
        return entryRepository.save(entry).getId();
    }

    /** One well-formed retained entry: grouped, every reference alive, hard via the type. */
    private long groupedUnavailableEntry(long semesterId, long teacherId) {
        ConstraintEntry entry = new ConstraintEntry();
        entry.setSemesterId(semesterId);
        entry.setType("TEACHER_UNAVAILABLE");
        entry.setGroupKey("slot-1");
        entry.setParamsJson("{\"teacherId\":" + teacherId
                + ",\"dayOfWeek\":1,\"periodNo\":1}");
        entry.setEnabled(true);
        return entryRepository.save(entry).getId();
    }

    private long stateVersion(String token, long semesterId) {
        return as(token).queryParam("semesterId", semesterId)
                .when().get("/api/constraints/state")
                .then().statusCode(200).extract().jsonPath().getLong("version");
    }

    private List<Map<String, Object>> enabledStateConfigs(String token, long semesterId) {
        List<Map<String, Object>> configs = as(token).queryParam("semesterId", semesterId)
                .when().get("/api/constraints/state")
                .then().statusCode(200).extract().jsonPath().getList("configs");
        return configs.stream()
                .filter(row -> Boolean.TRUE.equals(row.get("enabled")))
                .toList();
    }

    /** The newest DRAFT holds one lesson in the slot the retained rule forbids. */
    private long draftWithLesson(long semesterId, long classId, long courseId, long teacherId,
                                 long roomId) {
        Period first = periodRepository.findBySemesterIdOrderByDayOfWeekAscPeriodNoAsc(semesterId)
                .stream()
                .filter(period -> period.getDayOfWeek() == 1 && period.getPeriodNo() == 1)
                .findFirst().orElseThrow();
        TimetableDraft draft = new TimetableDraft();
        draft.setSemesterId(semesterId);
        draft.setName("保留规则草稿" + TestData.suffix());
        draft.setStatus("DRAFT");
        draft.setCreatedBy("test");
        draft.setCreatedAt(Instant.now());
        long draftId = draftRepository.save(draft).getId();
        placeLesson(draftId, classId, courseId, teacherId, first.getId(), roomId, 1);
        return draftId;
    }

    private long draftWithTeacherConflict(long semesterId, long classA, long classB,
                                          long teacher, long course, long room) {
        List<Period> periods = periodRepository
                .findBySemesterIdOrderByDayOfWeekAscPeriodNoAsc(semesterId);
        Period first = periods.stream()
                .filter(period -> period.getDayOfWeek() == 1 && period.getPeriodNo() == 1)
                .findFirst().orElseThrow();
        TimetableDraft draft = new TimetableDraft();
        draft.setSemesterId(semesterId);
        draft.setName("迁移草稿" + TestData.suffix());
        draft.setStatus("DRAFT");
        draft.setCreatedBy("test");
        draft.setCreatedAt(Instant.now());
        long draftId = draftRepository.save(draft).getId();
        placeLesson(draftId, classA, course, teacher, first.getId(), room, 1);
        placeLesson(draftId, classB, course, teacher, first.getId(), room, 2);
        return draftId;
    }

    private void placeLesson(long draftId, long classId, long courseId, long teacherId,
                             long periodId, long roomId, int index) {
        DraftLesson lesson = new DraftLesson();
        lesson.setDraftId(draftId);
        lesson.setAssignmentId(0L);
        lesson.setLessonIndex(index);
        lesson.setCourseId(courseId);
        lesson.setTeacherId(teacherId);
        lesson.setAdminClassId(classId);
        lesson.setStudentCount(45);
        lesson.setPeriodId(periodId);
        lesson.setRoomId(roomId);
        draftLessonRepository.save(lesson);
    }

    private List<String> conflictCodes(String token, long draftId) {
        return as(token)
                .when().get("/api/timetable-drafts/" + draftId + "/validation")
                .then().statusCode(200).extract().jsonPath().getList("conflicts.type", String.class);
    }

    private Map<String, Object> row(List<Map<String, Object>> rows, String oldType, long entryId) {
        return rows.stream()
                .filter(row -> oldType.equals(row.get("oldType")))
                .filter(row -> ((Number) row.get("oldEntryId")).longValue() == entryId)
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少迁移行: " + oldType + "#" + entryId));
    }
}
