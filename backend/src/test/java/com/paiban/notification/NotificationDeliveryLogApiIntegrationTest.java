package com.paiban.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;

import com.paiban.foundation.domain.Teacher;
import com.paiban.foundation.domain.TeacherRepository;
import com.paiban.identity.domain.AppUser;
import com.paiban.identity.domain.AppUserRepository;
import com.paiban.identity.domain.Role;
import com.paiban.identity.domain.RoleRepository;
import com.paiban.integration.dingtalk.DingTalkClient;
import com.paiban.notification.domain.NotificationLog;
import com.paiban.notification.domain.NotificationLogRepository;
import com.paiban.support.ApiIntegrationTest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class NotificationDeliveryLogApiIntegrationTest extends ApiIntegrationTest {

    private static final String PASSWORD = "notification-log-test-password";

    @Autowired private AppUserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private TeacherRepository teacherRepository;
    @Autowired private NotificationLogRepository logRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private DingTalkClient dingTalkClient;

    private final List<Long> createdUserIds = new ArrayList<>();
    private final List<Long> createdTeacherIds = new ArrayList<>();

    @AfterEach
    void removeIdentityFixtures() {
        createdUserIds.forEach(userRepository::deleteById);
        createdTeacherIds.forEach(teacherRepository::deleteById);
    }

    @Test
    void academicCanReadOneLogThroughTheSafeWireProjection() {
        Teacher teacher = createTeacher("通知标签教师", "uid-notification-label");
        AppUser reader = createUser("admin", "发送记录 HTTP 用户", null, true);
        NotificationLog saved = logRepository.saveAndFlush(log(
                "dingtalk", null, "uid-notification-label", "APPROVAL_RESULT",
                null, true, null, Instant.parse("2026-11-13T03:04:05Z")));

        Map<String, Object> body = as(login(reader.getUsername(), PASSWORD))
                .queryParam("page", 0)
                .queryParam("size", 20)
                .get("/api/notification-delivery-logs")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getMap("");

        assertThat(body.get("total")).isEqualTo(1);
        assertThat(body.get("page")).isEqualTo(0);
        assertThat(body.get("size")).isEqualTo(20);
        assertThat(body.get("items")).asInstanceOf(
                        org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .hasSize(1);

        @SuppressWarnings("unchecked")
        Map<String, Object> item = ((List<Map<String, Object>>) body.get("items")).getFirst();
        assertThat(item.keySet()).containsExactlyInAnyOrder(
                "id", "channel", "platform", "recipient", "recipientLabel",
                "templateCode", "content", "success", "createdAt", "errorMessage");
        assertThat(item)
                .containsEntry("id", saved.getId().intValue())
                .containsEntry("channel", "dingtalk")
                .containsEntry("platform", "")
                .containsEntry("recipient", "uid-notification-label")
                .containsEntry("recipientLabel", teacher.getName())
                .containsEntry("templateCode", "APPROVAL_RESULT")
                .containsEntry("content", "")
                .containsEntry("success", true)
                .containsEntry("createdAt", "2026-11-13T03:04:05Z")
                .containsEntry("errorMessage", null);
    }

    @Test
    void recipientLabelsUseRealFormatsWithoutGuessing() {
        AppUser reader = createUser("admin", "发送记录 HTTP 用户", null, true);
        Teacher accountTeacher = createTeacher("账号关联教师", null);
        AppUser disabledAccount = createUser(
                "teacher", "停用账号显示名", accountTeacher.getId(), false);
        Teacher markedTeacher = createTeacher("教师标记姓名", null);
        createTeacher("重复 UID 教师甲", "ambiguous-notification-uid");
        createTeacher("重复 UID 教师乙", "ambiguous-notification-uid");

        DateTime createdAt = new DateTime(Instant.parse("2026-11-13T04:00:00Z"));
        logRepository.saveAllAndFlush(List.of(
                log("notification", "system", "user:" + reader.getId(), "USER_MARKER",
                        "", true, null, createdAt.next()),
                log("notification", "system", "teacher:" + markedTeacher.getId(), "TEACHER_MARKER",
                        "", true, null, createdAt.next()),
                log("local", "system", "[" + disabledAccount.getId() + "]", "LOCAL_MARKER",
                        "", true, null, createdAt.next()),
                log("dingtalk", "dingtalk", "ambiguous-notification-uid", "AMBIGUOUS_UID",
                        "", true, null, createdAt.next()),
                log("dingtalk", "dingtalk", "unknown-recipient-format", "UNKNOWN_FORMAT",
                        "", false, "raw vendor error", createdAt.next())));

        Map<String, Object> body = as(login(reader.getUsername(), PASSWORD))
                .queryParam("size", 20)
                .get("/api/notification-delivery-logs")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getMap("");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) body.get("items");
        Map<String, Map<String, Object>> byRecipient = items.stream()
                .collect(Collectors.toMap(
                        item -> (String) item.get("recipient"),
                        Function.identity(),
                        (left, right) -> left,
                        LinkedHashMap::new));

        assertThat(byRecipient.get("user:" + reader.getId()))
                .containsEntry("recipientLabel", "发送记录 HTTP 用户");
        assertThat(byRecipient.get("teacher:" + markedTeacher.getId()))
                .containsEntry("recipientLabel", "教师标记姓名");
        assertThat(byRecipient.get("[" + disabledAccount.getId() + "]"))
                .containsEntry("recipientLabel", "停用账号显示名");
        assertThat(byRecipient.get("ambiguous-notification-uid"))
                .containsEntry("recipientLabel", "ambiguous-notification-uid");
        assertThat(byRecipient.get("unknown-recipient-format"))
                .containsEntry("recipientLabel", "unknown-recipient-format");
    }

    @Test
    void markerLikeTextFollowsTheActualChannelSemantics() {
        AppUser reader = createUser("admin", "内部失败记录账号", null, true);
        Teacher dingTalkUser = createTeacher(
                "同形 Ding UID 教师", "user:" + reader.getId());
        Teacher markerTeacher = createTeacher("教师标记账号", null);
        createTeacher("未知渠道 UID 教师", "unknown-channel-uid");
        logRepository.saveAllAndFlush(List.of(
                log("notification", "system", "user:" + reader.getId(),
                        "INTERNAL_USER_FAILURE", "", false, "unsafe",
                        Instant.parse("2026-11-13T06:00:00Z")),
                log("dingtalk", "dingtalk", "user:" + reader.getId(),
                        "DINGTALK_SAME_SHAPE", "", true, null,
                        Instant.parse("2026-11-13T06:01:00Z")),
                log("unknown", "legacy", "teacher:" + markerTeacher.getId(),
                        "UNKNOWN_CHANNEL", "", true, null,
                        Instant.parse("2026-11-13T06:02:00Z")),
                log("unknown", "dingtalk", "unknown-channel-uid",
                        "UNKNOWN_CHANNEL_WITH_DING_PLATFORM", "", true, null,
                        Instant.parse("2026-11-13T06:03:00Z"))));

        Map<String, Object> body = as(login(reader.getUsername(), PASSWORD))
                .queryParam("size", 20)
                .get("/api/notification-delivery-logs")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getMap("");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) body.get("items");
        Map<String, Map<String, Object>> byTemplate = items.stream()
                .collect(Collectors.toMap(
                        item -> (String) item.get("templateCode"),
                        Function.identity()));

        assertThat(byTemplate.get("INTERNAL_USER_FAILURE"))
                .containsEntry("recipientLabel", "内部失败记录账号");
        assertThat(byTemplate.get("DINGTALK_SAME_SHAPE"))
                .containsEntry("recipientLabel", dingTalkUser.getName());
        assertThat(byTemplate.get("UNKNOWN_CHANNEL"))
                .containsEntry("recipientLabel", "teacher:" + markerTeacher.getId());
        assertThat(byTemplate.get("UNKNOWN_CHANNEL_WITH_DING_PLATFORM"))
                .containsEntry("recipientLabel", "unknown-channel-uid");
    }

    @Test
    void teacherIsForbiddenWhileBothAdministratorRolesCanRead() {
        AppUser teacher = createUser("teacher", "发送记录老师", null, true);
        AppUser academic = createUser("admin", "发送记录教务", null, true);

        as(login(teacher.getUsername(), PASSWORD))
                .get("/api/notification-delivery-logs")
                .then()
                .statusCode(403);
        as(login(academic.getUsername(), PASSWORD))
                .get("/api/notification-delivery-logs")
                .then()
                .statusCode(200);
        as(adminToken())
                .get("/api/notification-delivery-logs")
                .then()
                .statusCode(200);
    }

    @Test
    void paginationUsesDatabaseOrderAndRejectsInvalidBounds() {
        AppUser reader = createUser("admin", "发送记录分页用户", null, true);
        NotificationLog older = logRepository.saveAndFlush(log(
                "local", "system", "[999001]", "OLDER", "",
                true, null, Instant.parse("2026-11-13T01:00:00Z")));
        NotificationLog sameTimeFirst = logRepository.saveAndFlush(log(
                "local", "system", "[999002]", "SAME_FIRST", "",
                true, null, Instant.parse("2026-11-13T02:00:00Z")));
        NotificationLog sameTimeSecond = logRepository.saveAndFlush(log(
                "local", "system", "[999003]", "SAME_SECOND", "",
                true, null, Instant.parse("2026-11-13T02:00:00Z")));
        NotificationLog newest = logRepository.saveAndFlush(log(
                "local", "system", "[999004]", "NEWEST", "",
                true, null, Instant.parse("2026-11-13T03:00:00Z")));
        String token = login(reader.getUsername(), PASSWORD);

        Map<String, Object> firstPage = as(token)
                .queryParam("page", 0)
                .queryParam("size", 2)
                .get("/api/notification-delivery-logs")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getMap("");
        assertThat(firstPage)
                .containsEntry("total", 4)
                .containsEntry("page", 0)
                .containsEntry("size", 2);
        assertThat((List<Map<String, Object>>) firstPage.get("items"))
                .extracting(item -> item.get("id"))
                .containsExactly(
                        newest.getId().intValue(),
                        sameTimeSecond.getId().intValue());

        Map<String, Object> secondPage = as(token)
                .queryParam("page", 1)
                .queryParam("size", 2)
                .get("/api/notification-delivery-logs")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getMap("");
        assertThat(secondPage)
                .containsEntry("total", 4)
                .containsEntry("page", 1)
                .containsEntry("size", 2);
        assertThat((List<Map<String, Object>>) secondPage.get("items"))
                .extracting(item -> item.get("id"))
                .containsExactly(
                        sameTimeFirst.getId().intValue(),
                        older.getId().intValue());

        as(token).queryParam("page", -1)
                .get("/api/notification-delivery-logs").then().statusCode(400);
        as(token).queryParam("size", 0)
                .get("/api/notification-delivery-logs").then().statusCode(400);
        as(token).queryParam("size", 101)
                .get("/api/notification-delivery-logs").then().statusCode(400);
        as(token).queryParam("page", "not-a-number")
                .get("/api/notification-delivery-logs").then().statusCode(400);
        as(token).queryParam("size", 100)
                .get("/api/notification-delivery-logs").then().statusCode(200);
    }

    @Test
    void failureDetailsAreRedactedAndReadDoesNotWriteOrSend() {
        AppUser reader = createUser("admin", "发送记录只读用户", null, true);
        String dangerous =
                "vendor exception access_token=teacher123 account=mobile:13800000000";
        logRepository.saveAllAndFlush(List.of(
                log("dingtalk", "dingtalk", "recipient-success", "SUCCESS_WITH_OLD_ERROR",
                        "", true, dangerous, Instant.parse("2026-11-13T05:00:00Z")),
                log("dingtalk", "dingtalk", "recipient-failure", "FAILURE",
                        "", false, dangerous, Instant.parse("2026-11-13T05:01:00Z"))));
        String token = login(reader.getUsername(), PASSWORD);
        Map<String, Long> tableCountsBefore = tableCounts();
        List<Map<String, Object>> logRowsBefore =
                jdbc.queryForList("select * from notification_log order by id");
        assertThat(jdbc.queryForObject("select count(*) from semester", Long.class)).isZero();
        clearInvocations(dingTalkClient);

        String responseBody = as(token)
                .queryParam("size", 20)
                .get("/api/notification-delivery-logs")
                .then()
                .statusCode(200)
                .extract()
                .asString();
        assertThat(responseBody)
                .doesNotContain(dangerous)
                .doesNotContain("access_token")
                .doesNotContain("13800000000")
                .doesNotContain("vendor exception");

        Map<String, Object> body = io.restassured.path.json.JsonPath
                .from(responseBody).getMap("");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> responseItems =
                (List<Map<String, Object>>) body.get("items");
        Map<String, Map<String, Object>> byTemplate = responseItems.stream()
                .collect(Collectors.toMap(
                        item -> (String) item.get("templateCode"),
                        Function.identity()));
        assertThat(byTemplate.get("SUCCESS_WITH_OLD_ERROR"))
                .containsEntry("errorMessage", null);
        assertThat(byTemplate.get("FAILURE"))
                .containsEntry("errorMessage", "发送失败，请联系管理员");

        assertThat(tableCounts()).isEqualTo(tableCountsBefore);
        assertThat(jdbc.queryForList("select * from notification_log order by id"))
                .isEqualTo(logRowsBefore);
        verifyNoInteractions(dingTalkClient);
    }

    @Test
    void extremePageBoundaryReturnsAnEmptyPageWithoutA500() {
        AppUser reader = createUser("admin", "发送记录极值页用户", null, true);
        logRepository.saveAndFlush(log(
                "local", "system", "[" + reader.getId() + "]", "EXTREME_PAGE",
                "", true, null, Instant.parse("2026-11-13T07:00:00Z")));

        Map<String, Object> body = as(login(reader.getUsername(), PASSWORD))
                .queryParam("page", Integer.MAX_VALUE)
                .queryParam("size", 100)
                .get("/api/notification-delivery-logs")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getMap("");

        assertThat(body)
                .containsEntry("total", 1)
                .containsEntry("page", Integer.MAX_VALUE)
                .containsEntry("size", 100);
        assertThat((List<?>) body.get("items")).isEmpty();
    }

    private AppUser createUser(
            String roleCode, String displayName, Long teacherId, boolean enabled) {
        Role role = roleRepository.findByCode(roleCode).orElseThrow();
        AppUser user = new AppUser();
        user.setUsername("notification_log_" + UUID.randomUUID().toString().substring(0, 8));
        user.setDisplayName(displayName);
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setRoles(Set.of(role));
        user.setTeacherId(teacherId);
        user.setEnabled(enabled);
        AppUser saved = userRepository.saveAndFlush(user);
        createdUserIds.add(saved.getId());
        return saved;
    }

    private Teacher createTeacher(String name, String dingtalkUserId) {
        Teacher teacher = new Teacher();
        teacher.setName(name);
        teacher.setDingtalkUserId(dingtalkUserId);
        Teacher saved = teacherRepository.saveAndFlush(teacher);
        createdTeacherIds.add(saved.getId());
        return saved;
    }

    private NotificationLog log(
            String channel,
            String platform,
            String recipient,
            String templateCode,
            String content,
            boolean success,
            String errorMessage,
            Instant createdAt) {
        NotificationLog log = new NotificationLog();
        log.setChannel(channel);
        log.setPlatform(platform);
        log.setRecipient(recipient);
        log.setTemplateCode(templateCode);
        log.setContent(content);
        log.setSuccess(success);
        log.setErrorMessage(errorMessage);
        log.setCreatedAt(createdAt);
        return log;
    }

    private static final class DateTime {
        private Instant value;

        private DateTime(Instant value) {
            this.value = value;
        }

        private Instant next() {
            Instant result = value;
            value = value.plusSeconds(1);
            return result;
        }
    }

    private Map<String, Long> tableCounts() {
        List<String> tables = jdbc.queryForList(
                "select table_name from information_schema.tables "
                        + "where table_schema = 'public' and table_type = 'BASE TABLE' order by table_name",
                String.class);
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String table : tables) {
            counts.put(table, jdbc.queryForObject(
                    "select count(*) from \"" + table + "\"", Long.class));
        }
        return counts;
    }
}
