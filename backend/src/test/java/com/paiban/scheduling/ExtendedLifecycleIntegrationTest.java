package com.paiban.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

import com.paiban.common.deletion.RecentDeletion;
import com.paiban.common.deletion.RecentDeletionRepository;
import com.paiban.support.ApiIntegrationTest;
import com.paiban.support.ScheduleFixture;
import com.paiban.support.TestData;
import io.restassured.response.Response;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Ticket 04's extended lifecycle: rule cards, templates, drafts, users, AI providers
 * and DingTalk settings all delete softly, appear in the 7-day recycle bin and restore
 * through the single {@code LifecycleService} seam.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ExtendedLifecycleIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private RecentDeletionRepository recentDeletions;

    @Autowired
    private JdbcTemplate jdbc;

    private record Seed(long semesterId, long gradeId, long classId, long courseId, long teacherId) {}

    private Seed seed(String token) {
        String suffix = TestData.suffix();
        long semesterId = semesterWithPeriods(token);
        long gradeId = TestData.grade(token, semesterId, "初一" + suffix);
        long classId = TestData.adminClass(token, semesterId, gradeId, "初一1班" + suffix);
        long courseId = TestData.course(token, "语文" + suffix);
        long teacherId = TestData.teacher(token, "王老师" + suffix);
        return new Seed(semesterId, gradeId, classId, courseId, teacherId);
    }

    private long semesterWithPeriods(String token) {
        long semesterId = TestData.semester(token);
        as(token).body(Map.of("morningCount", 4, "afternoonCount", 4, "eveningCount", 0))
                .when().post("/api/semesters/" + semesterId + "/periods/default")
                .then().statusCode(200);
        return semesterId;
    }

    private Map<String, Object> entry(long teacherId, int day, int period, String groupKey) {
        return entry(teacherId, day, period, groupKey, true);
    }

    private Map<String, Object> entry(
            long teacherId, int day, int period, String groupKey, boolean enabled) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("params", Map.of("teacherId", teacherId, "dayOfWeek", day, "periodNo", period));
        row.put("enabled", enabled);
        row.put("groupKey", groupKey);
        return row;
    }

    private Response saveUnavailable(String token, Seed seed, List<Map<String, Object>> entries) {
        Map<String, Object> config = Map.of(
                "type", "TEACHER_UNAVAILABLE",
                "weight", 100,
                "hard", true,
                "enabled", true,
                "entries", entries);
        return as(token).queryParam("semesterId", seed.semesterId())
                .body(Map.of("configs", List.of(config)))
                .when().put("/api/constraints");
    }

    private long configId(String token, long semesterId, String type) {
        Response state = as(token).queryParam("semesterId", semesterId)
                .when().get("/api/constraints/state");
        state.then().statusCode(200);
        return state.jsonPath().getLong("configs.find { it.type == '" + type + "' }.id");
    }

    private List<String> groupKeys(String token, long semesterId) {
        Response state = as(token).queryParam("semesterId", semesterId)
                .when().get("/api/constraints/state");
        state.then().statusCode(200);
        return state.jsonPath().getList(
                "configs.find { it.type == 'TEACHER_UNAVAILABLE' }.entries.groupKey");
    }

    /** Live (not recycled) entry rows of one type, with their switch flag. */
    private List<Map<String, Object>> liveEntries(String token, long semesterId, String type) {
        Response state = as(token).queryParam("semesterId", semesterId)
                .when().get("/api/constraints/state");
        state.then().statusCode(200);
        List<Map<String, Object>> rows = state.jsonPath().getList(
                "configs.findAll { it.type == '" + type + "' }"
                        + ".collectMany { it.entries == null ? [] : it.entries }");
        return rows == null ? List.of() : rows;
    }

    private Map<String, Boolean> enabledByGroup(List<Map<String, Object>> rows) {
        Map<String, Boolean> result = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Object group = row.get("groupKey");
            result.put(group == null ? "<legacy>" : String.valueOf(group),
                    Boolean.TRUE.equals(row.get("enabled")));
        }
        return result;
    }

    private String latestBatch(String token, String entityType) {
        return as(token).when().get("/api/recent-deletions")
                .then().statusCode(200)
                .body("entityType", hasItem(entityType))
                .extract().jsonPath()
                .getString("find { it.entityType == '" + entityType + "' }.batchId");
    }

    private void restore(String token, String batchId) {
        as(token).when().post("/api/recent-deletions/" + batchId + "/restore")
                .then().statusCode(200);
    }

    @Test
    void deletingOneRuleCardKeepsOtherCardsAndRestoresTheCard() {
        String token = adminToken();
        Seed seed = seed(token);
        saveUnavailable(token, seed, List.of(
                entry(seed.teacherId(), 1, 1, "card-a:0"),
                entry(seed.teacherId(), 1, 2, "card-a:0"),
                entry(seed.teacherId(), 2, 1, "card-b:0")))
                .then().statusCode(200);
        long configId = configId(token, seed.semesterId(), "TEACHER_UNAVAILABLE");

        as(token).queryParam("cardKey", "card-a")
                .when().delete("/api/rules/" + configId)
                .then().statusCode(200);

        assertThat(groupKeys(token, seed.semesterId())).containsExactly("card-b:0");
        String batchId = latestBatch(token, "CONSTRAINT_RULE");
        assertThat(as(token).when().get("/api/recent-deletions")
                .then().statusCode(200)
                .extract().jsonPath()
                .getList("findAll { it.entityType == 'CONSTRAINT_RULE' }.entityLabel"))
                .isNotEmpty();

        restore(token, batchId);

        assertThat(groupKeys(token, seed.semesterId()))
                .containsExactlyInAnyOrder("card-a:0", "card-a:0", "card-b:0");
        // A restored batch leaves the open recycle list; otherwise the page would keep
        // offering "恢复" for something already back.
        assertThat(as(token).when().get("/api/recent-deletions")
                .then().statusCode(200)
                .extract().jsonPath().getList("batchId"))
                .doesNotContain(batchId);

        List<String> actions = as(token).when().get("/api/foundation/audit-logs")
                .then().statusCode(200).extract().jsonPath().getList("action");
        assertThat(actions).contains("DELETE_CONSTRAINT_RULE", "RESTORE_CONSTRAINT_RULE");
    }

    @Test
    void pausedTeacherMakesReferencingRuleExplicitlyInvalid() {
        String token = adminToken();
        Seed seed = seed(token);
        saveUnavailable(token, seed, List.of(entry(seed.teacherId(), 1, 1, "card-a:0")))
                .then().statusCode(200);

        as(token).when().post("/api/teachers/" + seed.teacherId() + "/disable")
                .then().statusCode(200);

        Response state = as(token).queryParam("semesterId", seed.semesterId())
                .when().get("/api/constraints/state");
        state.then().statusCode(200);
        String reason = state.jsonPath().getString(
                "configs.find { it.type == 'TEACHER_UNAVAILABLE' }.invalidReferences[0]");
        assertThat(reason).contains("教师已停用").contains("王老师");
        String entryReason = state.jsonPath().getString(
                "configs.find { it.type == 'TEACHER_UNAVAILABLE' }.entries[0].invalidReferences[0]");
        assertThat(entryReason).contains("教师已停用");

        as(token).when().post("/api/teachers/" + seed.teacherId() + "/enable")
                .then().statusCode(200);
        Response restored = as(token).queryParam("semesterId", seed.semesterId())
                .when().get("/api/constraints/state");
        restored.then().statusCode(200)
                .body("configs.find { it.type == 'TEACHER_UNAVAILABLE' }.invalidReferences",
                        org.hamcrest.Matchers.empty());
    }

    @Test
    void deletedDraftHidesFromTheListAndRestoresWithItsLessons() {
        String token = adminToken();
        ScheduleFixture.Result world = ScheduleFixture.solved(token, 5);
        int lessons = as(token).when().get("/api/timetable-drafts/" + world.draftId())
                .then().statusCode(200).extract().jsonPath().getList("lessons").size();

        as(token).when().delete("/api/timetable-drafts/" + world.draftId())
                .then().statusCode(200);

        List<Integer> listed = as(token).queryParam("semesterId", world.semesterId())
                .when().get("/api/timetable-drafts")
                .then().statusCode(200).extract().jsonPath().getList("id");
        assertThat(listed).doesNotContain((int) world.draftId());

        restore(token, latestBatch(token, "TIMETABLE_DRAFT"));

        List<Integer> restored = as(token).queryParam("semesterId", world.semesterId())
                .when().get("/api/timetable-drafts")
                .then().statusCode(200).extract().jsonPath().getList("id");
        assertThat(restored).contains((int) world.draftId());
        int restoredLessons = as(token).when()
                .get("/api/timetable-drafts/" + world.draftId())
                .then().statusCode(200).extract().jsonPath().getList("lessons").size();
        assertThat(restoredLessons).isEqualTo(lessons);

        List<String> actions = as(token).when().get("/api/foundation/audit-logs")
                .then().statusCode(200).extract().jsonPath().getList("action");
        assertThat(actions).contains("DELETE_TIMETABLE_DRAFT", "RESTORE_TIMETABLE_DRAFT");
    }

    @Test
    void publishedDraftCannotBeDeletedDirectly() {
        String token = adminToken();
        ScheduleFixture.Result world = ScheduleFixture.published(token, 5);

        as(token).when().delete("/api/timetable-drafts/" + world.draftId())
                .then().statusCode(409);
    }

    @Test
    void usersAreDisabledAndEnabledWithAudit() {
        String token = adminToken();
        long userId = createUser(token, "u" + TestData.suffix(), "pass1234", "teacher");

        as(token).when().post("/api/users/" + userId + "/disable")
                .then().statusCode(200);
        as(token).when().get("/api/users")
                .then().statusCode(200)
                .body("find { it.id == " + userId + " }.enabled", equalTo(false));

        as(token).when().post("/api/users/" + userId + "/enable")
                .then().statusCode(200);
        as(token).when().get("/api/users")
                .then().statusCode(200)
                .body("find { it.id == " + userId + " }.enabled", equalTo(true));

        List<String> actions = as(token).when().get("/api/foundation/audit-logs")
                .then().statusCode(200).extract().jsonPath().getList("action");
        assertThat(actions).contains("DISABLE_USER", "ENABLE_USER");

        long selfId = as(token).when().get("/api/users")
                .then().statusCode(200)
                .extract().jsonPath().getLong("find { it.username == 'admin' }.id");
        as(token).when().post("/api/users/" + selfId + "/disable")
                .then().statusCode(409);
    }

    @Test
    void aiProviderDeletionHidesItsModelsAndRestoresThem() {
        String token = adminToken();
        String name = "测试供应商" + TestData.suffix();
        Response created = as(token).body(Map.of(
                        "name", name,
                        "type", "custom",
                        "protocol", "openai",
                        "baseUrl", "https://example.invalid/v1",
                        "apiKey", "sk-secret",
                        "enabled", true,
                        "models", List.of(Map.of(
                                "modelName", "model-a",
                                "displayName", "模型A",
                                "enabled", true))))
                .when().post("/api/ai-providers");
        created.then().statusCode(200);
        long providerId = created.jsonPath().getLong("id");

        as(token).when().delete("/api/ai-providers/" + providerId)
                .then().statusCode(200);

        List<Integer> providerIds = as(token).when().get("/api/ai-providers")
                .then().statusCode(200).extract().jsonPath().getList("id");
        assertThat(providerIds).doesNotContain((int) providerId);
        as(token).when().get("/api/ai-task-config")
                .then().statusCode(200)
                .body("tasks.configured", not(hasItem(true)));

        restore(token, latestBatch(token, "AI_PROVIDER"));

        Response restored = as(token).when().get("/api/ai-providers/" + providerId);
        restored.then().statusCode(200)
                .body("name", equalTo(name))
                .body("models.size()", equalTo(1))
                .body("models[0].modelName", equalTo("model-a"));

        List<String> auditDetails = as(token).when().get("/api/foundation/audit-logs")
                .then().statusCode(200).extract().jsonPath().getList("detail");
        assertThat(auditDetails).noneMatch(detail -> detail != null && detail.contains("sk-secret"));
    }

    @Test
    void clearingDingtalkConfigSnapshotsAndRestoresIt() {
        String token = adminToken();
        resetIntegrationSettings();
        as(token).body(Map.of(
                        "enabled", true,
                        "appKey", "ding-key",
                        "appSecret", "ding-secret",
                        "agentId", "123",
                        "corpId", "corp-1",
                        "syncStrategy", "dingtalk"))
                .when().put("/api/integrations/dingtalk/config")
                .then().statusCode(200);

        as(token).when().delete("/api/integration/dingtalk")
                .then().statusCode(200);
        as(token).when().get("/api/integrations/dingtalk/config")
                .then().statusCode(200)
                .body("enabled", equalTo(false))
                .body("appKey", equalTo(""))
                .body("hasSecret", equalTo(false));

        String batchId = latestBatch(token, "DINGTALK_CONFIG");
        RecentDeletion dingtalkRow = recentDeletions.findByBatchIdOrderByIdAsc(batchId).stream()
                .filter(row -> "DINGTALK_CONFIG".equals(row.getEntityType()))
                .findFirst()
                .orElseThrow();
        assertThat(dingtalkRow.getSnapshotJson())
                .contains("enc:v1:")
                .doesNotContain("ding-secret");
        List<String> auditDetails = as(token).when().get("/api/foundation/audit-logs")
                .then().statusCode(200).extract().jsonPath().getList("detail");
        assertThat(auditDetails).noneMatch(detail -> detail != null && detail.contains("ding-secret"));

        restore(token, batchId);
        as(token).when().get("/api/integrations/dingtalk/config")
                .then().statusCode(200)
                .body("enabled", equalTo(true))
                .body("appKey", equalTo("ding-key"))
                .body("hasSecret", equalTo(true));
    }

    @Test
    void deletedTemplateHidesAndRestores() {
        String token = adminToken();
        Seed seed = seed(token);
        saveUnavailable(token, seed, List.of(entry(seed.teacherId(), 1, 1, "card-a:0")))
                .then().statusCode(200);
        String name = "模板" + TestData.suffix();
        Response created = as(token).queryParam("semesterId", seed.semesterId())
                .body(Map.of("name", name))
                .when().post("/api/constraint-templates");
        created.then().statusCode(200);
        long templateId = created.jsonPath().getLong("id");

        as(token).when().delete("/api/constraint-templates/" + templateId)
                .then().statusCode(200);
        List<Integer> ids = as(token).when().get("/api/constraint-templates")
                .then().statusCode(200).extract().jsonPath().getList("id");
        assertThat(ids).doesNotContain((int) templateId);

        restore(token, latestBatch(token, "CONSTRAINT_TEMPLATE"));
        List<Integer> restored = as(token).when().get("/api/constraint-templates")
                .then().statusCode(200).extract().jsonPath().getList("id");
        assertThat(restored).contains((int) templateId);
    }

    @Test
    void recycledDraftIsRefusedByValidationSwapPublishWithdrawAndRoomFill() {
        String token = adminToken();
        ScheduleFixture.Result world = ScheduleFixture.solved(token, 5);
        long draftId = world.draftId();
        long lessonId = as(token).when().get("/api/timetable-drafts/" + draftId)
                .then().statusCode(200).extract().jsonPath().getLong("lessons[0].id");

        as(token).when().delete("/api/timetable-drafts/" + draftId)
                .then().statusCode(200);

        as(token).when().get("/api/timetable-drafts/" + draftId + "/validation")
                .then().statusCode(404);
        as(token).when().get("/api/timetable-drafts/" + draftId + "/lessons/"
                        + lessonId + "/swap-candidates")
                .then().statusCode(404);
        as(token).when().get("/api/timetable-drafts/" + draftId + "/lessons/"
                        + lessonId + "/move-targets")
                .then().statusCode(404);
        as(token).when().get("/api/timetable-drafts/" + draftId + "/room-fill/plan")
                .then().statusCode(404);
        as(token).when().post("/api/timetable-drafts/" + draftId + "/publish")
                .then().statusCode(404);
        as(token).when().post("/api/timetable-drafts/" + draftId + "/withdraw")
                .then().statusCode(404);

        // Withdrawal reads the draft through the same live-only lock: a published row
        // that somehow carries deleted_at (crash, manual data fix) is not withdrawable.
        ScheduleFixture.Result published = ScheduleFixture.published(token, 5);
        jdbc.update("update timetable_draft set deleted_at = CURRENT_TIMESTAMP where id = ?",
                published.draftId());
        as(token).when().post("/api/timetable-drafts/" + published.draftId() + "/withdraw")
                .then().statusCode(404);
    }

    @Test
    void restoringARuleCardKeepsTheSwitchStateItWasDeletedWith() {
        String token = adminToken();
        Seed seed = seed(token);
        saveUnavailable(token, seed, List.of(
                entry(seed.teacherId(), 1, 1, "card-a:0", true),
                entry(seed.teacherId(), 2, 1, "card-b:0", false)))
                .then().statusCode(200);
        long configId = configId(token, seed.semesterId(), "TEACHER_UNAVAILABLE");

        // A card that was on comes back on.
        as(token).queryParam("cardKey", "card-a")
                .when().delete("/api/rules/" + configId)
                .then().statusCode(200);
        restore(token, latestBatch(token, "CONSTRAINT_RULE"));
        assertThat(enabledByGroup(liveEntries(token, seed.semesterId(), "TEACHER_UNAVAILABLE")))
                .containsEntry("card-a:0", true)
                .containsEntry("card-b:0", false);

        // A card that was paused before deletion must stay paused after restore.
        as(token).queryParam("cardKey", "card-b")
                .when().delete("/api/rules/" + configId)
                .then().statusCode(200);
        restore(token, latestBatch(token, "CONSTRAINT_RULE"));
        assertThat(enabledByGroup(liveEntries(token, seed.semesterId(), "TEACHER_UNAVAILABLE")))
                .containsEntry("card-b:0", false);
    }

    @Test
    void legacyRuleCardWithoutGroupKeyCanBeDeletedAndRestored() {
        String token = adminToken();
        Seed seed = seed(token);
        saveUnavailable(token, seed, List.of(entry(seed.teacherId(), 1, 1, null)))
                .then().statusCode(200);
        long configId = configId(token, seed.semesterId(), "TEACHER_UNAVAILABLE");
        List<Map<String, Object>> rows = liveEntries(
                token, seed.semesterId(), "TEACHER_UNAVAILABLE");
        assertThat(rows).hasSize(1);
        long entryId = ((Number) Objects.requireNonNull(rows.get(0).get("id"))).longValue();

        as(token)
                .queryParam("cardKey", "entry:" + entryId)
                .queryParam("entryIds", entryId)
                .when().delete("/api/rules/" + configId)
                .then().statusCode(200);
        assertThat(liveEntries(token, seed.semesterId(), "TEACHER_UNAVAILABLE")).isEmpty();

        restore(token, latestBatch(token, "CONSTRAINT_RULE"));
        List<Map<String, Object>> restored = liveEntries(
                token, seed.semesterId(), "TEACHER_UNAVAILABLE");
        assertThat(restored).hasSize(1);
        assertThat(restored.get(0).get("groupKey")).isNull();
        assertThat(enabledByGroup(restored)).containsEntry("<legacy>", true);
    }

    @Test
    void templateSnapshotAndPreviewSkipRecycledRuleRows() {
        String token = adminToken();
        Seed seed = seed(token);
        saveUnavailable(token, seed, List.of(
                entry(seed.teacherId(), 1, 1, "card-a:0"),
                entry(seed.teacherId(), 1, 2, "card-a:0"),
                entry(seed.teacherId(), 2, 1, "card-b:0")))
                .then().statusCode(200);
        long configId = configId(token, seed.semesterId(), "TEACHER_UNAVAILABLE");

        as(token).queryParam("cardKey", "card-a")
                .when().delete("/api/rules/" + configId)
                .then().statusCode(200);

        Response created = as(token).queryParam("semesterId", seed.semesterId())
                .body(Map.of("name", "模板" + TestData.suffix()))
                .when().post("/api/constraint-templates");
        created.then().statusCode(200);
        assertThat(created.jsonPath().getInt("entryCount")).isEqualTo(1);
        long templateId = created.jsonPath().getLong("id");

        as(token).queryParam("semesterId", seed.semesterId())
                .when().post("/api/constraint-templates/" + templateId + "/preview")
                .then().statusCode(200)
                .body("entryCount", equalTo(1));
    }

    @Test
    void editingAUserCannotBypassTheDisableGuard() {
        String token = adminToken();
        long userId = createUser(token, "u" + TestData.suffix(), "pass1234", "teacher");

        as(token).body(Map.of("displayName", "改过名字", "enabled", false))
                .when().put("/api/users/" + userId)
                .then().statusCode(200);
        as(token).when().get("/api/users")
                .then().statusCode(200)
                .body("find { it.id == " + userId + " }.enabled", equalTo(false));

        as(token).body(Map.of("enabled", true))
                .when().put("/api/users/" + userId)
                .then().statusCode(200);
        as(token).when().get("/api/users")
                .then().statusCode(200)
                .body("find { it.id == " + userId + " }.enabled", equalTo(true));

        List<String> actions = as(token).when().get("/api/foundation/audit-logs")
                .then().statusCode(200).extract().jsonPath().getList("action");
        assertThat(actions).contains("DISABLE_USER", "ENABLE_USER");

        long selfId = as(token).when().get("/api/users")
                .then().statusCode(200)
                .extract().jsonPath().getLong("find { it.username == 'admin' }.id");
        as(token).body(Map.of("enabled", false))
                .when().put("/api/users/" + selfId)
                .then().statusCode(409);
        as(token).when().get("/api/users")
                .then().statusCode(200)
                .body("find { it.id == " + selfId + " }.enabled", equalTo(true));
    }

    @Test
    void restoringAProviderOnlyBringsBackTheModelsDeletedWithIt() {
        String token = adminToken();
        String name = "测试供应商" + TestData.suffix();
        Response created = as(token).body(Map.of(
                        "name", name,
                        "type", "custom",
                        "protocol", "openai",
                        "baseUrl", "https://example.invalid/v1",
                        "apiKey", "sk-secret",
                        "enabled", true,
                        "models", List.of(
                                Map.of("modelName", "model-a", "displayName", "模型A", "enabled", true),
                                Map.of("modelName", "model-b", "displayName", "模型B", "enabled", true))))
                .when().post("/api/ai-providers");
        created.then().statusCode(200);
        long providerId = created.jsonPath().getLong("id");
        long modelBId = as(token).when().get("/api/ai-providers/" + providerId)
                .then().statusCode(200)
                .extract().jsonPath()
                .getLong("models.find { it.modelName == 'model-b' }.id");

        // Delete one model on its own first: that recycle decision is not the provider's.
        as(token).when().delete("/api/ai-providers/" + providerId + "/models/" + modelBId)
                .then().statusCode(200);
        as(token).when().delete("/api/ai-providers/" + providerId)
                .then().statusCode(200);

        restore(token, latestBatch(token, "AI_PROVIDER"));

        as(token).when().get("/api/ai-providers/" + providerId)
                .then().statusCode(200)
                .body("models.size()", equalTo(1))
                .body("models[0].modelName", equalTo("model-a"));
        // The separately deleted model is still waiting in the recycle bin.
        as(token).when().get("/api/recent-deletions")
                .then().statusCode(200)
                .body("entityType", hasItem("AI_MODEL"));
    }
}
