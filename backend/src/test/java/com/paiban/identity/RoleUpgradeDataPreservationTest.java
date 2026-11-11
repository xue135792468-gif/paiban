package com.paiban.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

/** Old-school upgrade boundary: V60 changes authorization, not accounts or school data. */
class RoleUpgradeDataPreservationTest {

    @Test
    void oldAdministratorsAndHeadTeachersMigrateWithoutChangingBusinessRows() throws Exception {
        String url = "jdbc:h2:mem:role_upgrade_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        migrate(url, "59");
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
                Statement sql = connection.createStatement()) {
            seedOldSchool(sql);
            Map<String, List<List<String>>> before = businessRows(connection);
            migrate(url, "60");

            assertThat(strings(sql, "select r.code from user_role ur join role r on r.id=ur.role_id"
                    + " join app_user u on u.id=ur.user_id where u.username='admin'"))
                    .containsExactly("super_admin");
            assertThat(strings(sql, "select r.code from user_role ur join role r on r.id=ur.role_id"
                    + " join app_user u on u.id=ur.user_id where u.username='second_old_admin'"))
                    .containsExactly("super_admin");
            assertThat(strings(sql, "select r.code from user_role ur join role r on r.id=ur.role_id"
                    + " join app_user u on u.id=ur.user_id where u.username='old_head'"))
                    .containsExactly("teacher");
            assertThat(strings(sql, "select code from role order by code"))
                    .containsExactly("admin", "super_admin", "teacher");
            assertThat(strings(sql, "select p.code from role_permission rp join role r on r.id=rp.role_id"
                    + " join permission p on p.id=rp.permission_id where r.code='admin'"))
                    .doesNotContain("user:manage", "integration:manage", "audit:view:account");
            assertThat(strings(sql, "select p.code from role_permission rp join role r on r.id=rp.role_id"
                    + " join permission p on p.id=rp.permission_id where r.code='teacher'"))
                    .contains("timetable:view");
            assertThat(strings(sql, "select p.code from role_permission rp join role r on r.id=rp.role_id"
                    + " join permission p on p.id=rp.permission_id where r.code in ('admin','super_admin','teacher')"))
                    .doesNotContain("timetable:view:class", "substitution:view:class");
            assertThat(businessRows(connection)).isEqualTo(before);
            // A repeated upgrade must not change users, bindings, roles, or business data.
            List<String> identities = strings(sql, "select user_id || ':' || role_id from user_role order by user_id,role_id");
            migrate(url, "60");
            assertThat(strings(sql, "select user_id || ':' || role_id from user_role order by user_id,role_id"))
                    .isEqualTo(identities);
            assertThat(businessRows(connection)).isEqualTo(before);
        }
    }

    private static void migrate(String url, String version) {
        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration")
                .target(version).load().migrate();
    }

    private static Map<String, List<List<String>>> businessRows(Connection connection) throws Exception {
        Map<String, List<List<String>>> tables = new LinkedHashMap<>();
        for (String table : List.of("app_user", "teacher", "semester", "period", "admin_class",
                "timetable_draft", "draft_lesson", "timetable_entry", "substitution_request", "substitution",
                "constraint_config", "ai_import_log")) {
            List<List<String>> rows = new ArrayList<>();
            try (Statement sql = connection.createStatement();
                    ResultSet result = sql.executeQuery("select * from " + table + " order by id")) {
                while (result.next()) {
                    List<String> row = new ArrayList<>();
                    for (int column = 1; column <= result.getMetaData().getColumnCount(); column++) {
                        row.add(result.getString(column));
                    }
                    rows.add(row);
                }
            }
            tables.put(table, rows);
        }
        return tables;
    }

    private static List<String> strings(Statement sql, String query) throws Exception {
        List<String> values = new ArrayList<>();
        try (ResultSet result = sql.executeQuery(query)) {
            while (result.next()) values.add(result.getString(1));
        }
        return values;
    }

    private static void seedOldSchool(Statement sql) throws Exception {
        for (String statement : List.of(
                "insert into permission(code,name) values ('user:manage','账号'),('integration:manage','集成'),"
                        + "('timetable:view','全校课表'),('timetable:view:class','本班课表'),('substitution:view:class','本班调代')",
                "insert into role(code,name) values ('admin','教务管理员'),('teacher','教师'),('head_teacher','班主任')",
                "insert into role_permission(role_id,permission_id) select r.id,p.id from role r cross join permission p"
                        + " where r.code='admin' or p.code='timetable:view'"
                        + " or (r.code='head_teacher' and p.code in ('timetable:view:class','substitution:view:class'))",
                "insert into teacher(id,name,dingtalk_user_id,created_at) values (1,'合成绑定教师','synthetic-dd-1',CURRENT_TIMESTAMP)",
                "insert into app_user(username,password_hash,display_name,enabled,teacher_id,created_at) values"
                        + " ('admin','synthetic-hash','原管理员',true,null,CURRENT_TIMESTAMP),"
                        + " ('second_old_admin','synthetic-hash','另一个原管理员',true,null,CURRENT_TIMESTAMP),"
                        + " ('old_head','synthetic-hash','原班主任',true,1,CURRENT_TIMESTAMP),"
                        + " ('disabled_teacher','synthetic-hash','停用账号',false,null,CURRENT_TIMESTAMP)",
                "insert into user_role(user_id,role_id) select u.id,r.id from app_user u cross join role r where"
                        + " (u.username in ('admin','second_old_admin') and r.code='admin')"
                        + " or (u.username='old_head' and r.code='head_teacher')"
                        + " or (u.username='disabled_teacher' and r.code='teacher')",
                "insert into semester(id,name,start_date,end_date,is_current,created_at)"
                        + " values (1,'升级演练',DATE '2030-09-01',DATE '2031-01-20',true,CURRENT_TIMESTAMP)",
                "insert into period(id,semester_id,day_of_week,period_no,label,start_time,end_time)"
                        + " values (1,1,1,1,'第1节',TIME '08:00:00',TIME '08:40:00')",
                "insert into course(id,name,created_at) values (1,'合成数学',CURRENT_TIMESTAMP)",
                "insert into grade(id,semester_id,name) values (1,1,'合成年级')",
                "insert into admin_class(id,semester_id,grade_id,name,head_teacher_id) values (1,1,1,'合成班',1)",
                "insert into timetable_draft(id,semester_id,name,created_at) values (1,1,'保全草稿',CURRENT_TIMESTAMP)",
                "insert into draft_lesson(id,draft_id,assignment_id,lesson_index,course_id,teacher_id,admin_class_id,period_id)"
                        + " values (1,1,1,0,1,1,1,1)",
                "insert into timetable_entry(id,semester_id,draft_id,admin_class_id,course_id,teacher_id,period_id,day_of_week,period_no,created_at)"
                        + " values (1,1,1,1,1,1,1,1,1,CURRENT_TIMESTAMP)",
                "insert into substitution_request(id,semester_id,entry_id,admin_class_id,course_id,original_teacher_id,"
                        + "requester_teacher_id,effective_date,period_id,day_of_week,period_no,status,created_at,updated_at)"
                        + " values (1,1,1,1,1,1,1,DATE '2030-09-02',1,1,1,'EFFECTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                "insert into substitution(id,semester_id,request_id,entry_id,effective_date,lesson_date,original_teacher_id,"
                        + "substitute_teacher_id,status,created_at) values (1,1,1,1,DATE '2030-09-02',DATE '2030-09-02',1,1,'EFFECTIVE',CURRENT_TIMESTAMP)",
                "insert into constraint_config(id,semester_id,type,params_json,weight,hard,enabled)"
                        + " values (1,1,'TEACHER_UNAVAILABLE','{}',100,true,true)",
                "insert into ai_import_log(id,user_id,username,import_type,target_type,status,created_at,updated_at)"
                        + " values (1,1,'admin','TIMETABLE','TIMETABLE','COMPLETED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)")) {
            sql.execute(statement);
        }
    }
}
