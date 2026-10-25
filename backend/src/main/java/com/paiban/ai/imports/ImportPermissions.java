package com.paiban.ai.imports;

import com.paiban.ai.AiGateway;
import com.paiban.ai.AiTaskType;
import com.paiban.common.web.ApiException;
import com.paiban.identity.domain.AppUser;
import com.paiban.identity.domain.Permissions;
import com.paiban.identity.service.UserService;
import org.springframework.stereotype.Component;

/**
 * The two gates every import channel shares: the AI task must be configured, and writing a
 * timetable needs {@code timetable:edit} even though the import page itself only asks for
 * {@code foundation:manage}. Kept together so a workbook import and a single-target import
 * cannot drift apart on either message.
 */
@Component
public class ImportPermissions {

    private final AiGateway gateway;
    private final UserService users;

    public ImportPermissions(AiGateway gateway, UserService users) {
        this.gateway = gateway;
        this.users = users;
    }

    public void requireConfigured() {
        if (!gateway.configured(AiTaskType.DATA_IMPORT)) {
            throw ApiException.badRequest("请先在设置中配置 AI 供应商与模型");
        }
    }

    public void requireTimetableEdit(String username) {
        AppUser user = users.getByUsername(username);
        if (user == null || !users.hasPermission(user, Permissions.TIMETABLE_EDIT)) {
            throw ApiException.forbidden("没有权限导入课表");
        }
    }

    /**
     * The period table is the school's institutional data, so appending to it is not covered
     * by the import page's own permission: it needs {@code semester:manage} as well.
     */
    public void requireSemesterManage(String username) {
        AppUser user = users.getByUsername(username);
        if (user == null || !users.hasPermission(user, Permissions.SEMESTER_MANAGE)) {
            throw ApiException.forbidden("没有权限修改学期与作息");
        }
    }
}
