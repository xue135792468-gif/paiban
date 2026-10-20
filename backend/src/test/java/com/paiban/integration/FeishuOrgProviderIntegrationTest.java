package com.paiban.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.paiban.common.settings.SettingsService;
import com.paiban.foundation.domain.AuditLogRepository;
import com.paiban.foundation.domain.Teacher;
import com.paiban.foundation.domain.TeacherRepository;
import com.paiban.integration.feishu.FakeFeishuClient;
import com.paiban.integration.feishu.FeishuUser;
import com.paiban.integration.org.FeishuOrgDataProvider;
import com.paiban.integration.org.OrgSyncResult;
import com.paiban.support.ApiIntegrationTest;
import com.paiban.support.TestData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Review fixes for the Feishu directory provider: a failed fetch must be audited and
 * must never be mistaken for "everyone left", and partial contact events must not
 * wipe locally maintained fields.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class FeishuOrgProviderIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private FeishuOrgDataProvider orgProvider;

    @Autowired
    private FakeFeishuClient fakeClient;

    @Autowired
    private TeacherRepository teachers;

    @Autowired
    private AuditLogRepository auditLogs;

    @BeforeEach
    void baseline() {
        resetIntegrationSettings();
        fakeClient.failDirectory(false);
        fakeClient.clearMessages();
        settingsService.put(SettingsService.FEISHU_ENABLED, "true");
        settingsService.put(SettingsService.FEISHU_SYNC_STRATEGY, "feishu");
        settingsService.put(SettingsService.ACTIVE_PLATFORM, SettingsService.PLATFORM_FEISHU);
    }

    @AfterEach
    void resetFakeClient() {
        fakeClient.failDirectory(false);
    }

    private Teacher teacher(String name, String employeeNo, String phone, String email) {
        Teacher teacher = new Teacher();
        teacher.setName(name);
        teacher.setEmployeeNo(employeeNo);
        teacher.setPhone(phone);
        teacher.setEmail(email);
        teacher.setFeishuUserId("fs-" + TestData.suffix());
        teacher.setFeishuOpenId("ou-" + TestData.suffix());
        teacher.setActive(true);
        return teachers.save(teacher);
    }

    private Teacher reload(Teacher teacher) {
        return teachers.findById(teacher.getId()).orElseThrow();
    }

    @Test
    void remoteFetchFailureIsAuditedAndDoesNotDeactivateTeachers() {
        Teacher bound = teacher("张三", "EMP-" + TestData.suffix(), "13900000001", "zhang@example.com");
        fakeClient.failDirectory(true);

        OrgSyncResult result = orgProvider.sync();

        assertThat(result.created()).isZero();
        assertThat(result.updated()).isZero();
        assertThat(result.deactivated()).isZero();
        assertThat(result.message()).contains("拉取飞书通讯录失败");
        assertThat(auditLogs.findAll())
                .filteredOn(log -> "FEISHU_SYNC".equals(log.getAction()))
                .anySatisfy(log -> assertThat(log.getDetail()).contains("拉取飞书通讯录失败"));
        // A failed fetch is not an empty directory: nobody may be deactivated.
        assertThat(reload(bound).isActive()).isTrue();
    }

    @Test
    void partialEventDoesNotClearExistingFieldsUnderLocalStrategy() {
        Teacher existing = teacher("原姓名", "EMP-" + TestData.suffix(), "13900000001", "old@example.com");
        settingsService.put(SettingsService.FEISHU_SYNC_STRATEGY, "local");

        orgProvider.applyEvent(
                "contact.user.updated_v3",
                new FeishuUser(existing.getFeishuUserId(), existing.getFeishuOpenId(), null, null, null, null));

        Teacher after = reload(existing);
        assertThat(after.getName()).isEqualTo("原姓名");
        assertThat(after.getEmployeeNo()).isEqualTo(existing.getEmployeeNo());
        assertThat(after.getPhone()).isEqualTo("13900000001");
        assertThat(after.getEmail()).isEqualTo("old@example.com");
        assertThat(after.isActive()).isTrue();
    }

    @Test
    void eventRespectsLocalAndFeishuStrategies() {
        Teacher existing = teacher("原姓名", "EMP-" + TestData.suffix(), "13900000001", "old@example.com");
        String employeeNo = existing.getEmployeeNo();

        // local: non-blank remote values must not overwrite local ones.
        settingsService.put(SettingsService.FEISHU_SYNC_STRATEGY, "local");
        orgProvider.applyEvent("contact.user.updated_v3", new FeishuUser(
                existing.getFeishuUserId(), existing.getFeishuOpenId(), "远端姓名", "13700000009", "remote@example.com", null));
        Teacher local = reload(existing);
        assertThat(local.getName()).isEqualTo("原姓名");
        assertThat(local.getPhone()).isEqualTo("13900000001");
        assertThat(local.getEmail()).isEqualTo("old@example.com");

        // feishu: non-blank values overwrite; an absent field still leaves the stored value intact.
        settingsService.put(SettingsService.FEISHU_SYNC_STRATEGY, "feishu");
        orgProvider.applyEvent("contact.user.updated_v3", new FeishuUser(
                existing.getFeishuUserId(), existing.getFeishuOpenId(), "远端姓名", "13700000009", "remote@example.com", null));
        Teacher remote = reload(existing);
        assertThat(remote.getName()).isEqualTo("远端姓名");
        assertThat(remote.getPhone()).isEqualTo("13700000009");
        assertThat(remote.getEmail()).isEqualTo("remote@example.com");
        assertThat(remote.getEmployeeNo()).isEqualTo(employeeNo);
    }

    @Test
    void deletedEventStillDeactivatesTeacher() {
        Teacher existing = teacher("李四", "EMP-" + TestData.suffix(), null, null);

        orgProvider.applyEvent("contact.user.deleted_v3", new FeishuUser(
                existing.getFeishuUserId(), existing.getFeishuOpenId(), null, null, null, null));

        assertThat(reload(existing).isActive()).isFalse();
    }
}
