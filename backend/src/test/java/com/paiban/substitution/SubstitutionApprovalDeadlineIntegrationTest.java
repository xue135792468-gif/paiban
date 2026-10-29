package com.paiban.substitution;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

import com.paiban.support.ApiIntegrationTest;
import com.paiban.support.TestData;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 首次批准的开课截止复核（规格第 6 节）：「此规则用于创建/提交、批准、直接生效、
 * 恢复基线与再次调整」。修订批准早已复核；本类钉住另外两条批准路径——
 * 普通代课批准与首次调课方案整单批准——它们同样不得跨过开课时刻。
 *
 * <p>fixture 故意锚在过去学期（2025-09-01 那一周）+ 真实时钟：对当前时间而言
 * 这批课全部已开课，任何批准都必须被 409 拒绝、旧安排保持生效。
 * 仍可批准的正路径由未来学期 fixture 的既有流程覆盖（见 SubstitutionIntegrationTest）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SubstitutionApprovalDeadlineIntegrationTest extends ApiIntegrationTest {

    private static final LocalDate MONDAY = LocalDate.of(2025, 9, 1);

    @Test
    void firstCoverApprovalRejectedAfterLessonStart() {
        String admin = adminToken();
        var fx = AdminSubstitutionIntegrationTest.buildFixture(admin, TestData.suffix(), MONDAY);

        long requestId = as(admin).body(Map.of(
                        "entryId", fx.entryAId(),
                        "effectiveDate", fx.lessonDate().toString(),
                        "reason", "截止测试首次代课",
                        "expectedSubstituteTeacherId", fx.teacherCId()))
                .when().post("/api/substitution-requests")
                .then().statusCode(200)
                .extract().jsonPath().getLong("id");

        as(admin).body(Map.of("substituteTeacherId", fx.teacherCId(), "comment", "同意"))
                .when().post("/api/substitution-requests/" + requestId + "/approve")
                .then().statusCode(409)
                .body("message", containsString("开课"));
    }

    @Test
    void firstSwapPlanApprovalRejectedAfterLessonStart() {
        String admin = adminToken();
        var fx = AdminSwapIntegrationTest.buildFixture(admin, TestData.suffix(), MONDAY, false, false);

        long requestId = as(admin).body(Map.of(
                        "entryId", fx.entryChineseId(),
                        "effectiveDate", fx.wednesday().toString(),
                        "reason", "截止测试首次调课",
                        "type", "SWAP",
                        "targetPeriodId", fx.fridayPeriodId()))
                .when().post("/api/substitution-requests")
                .then().statusCode(200)
                .body("type", equalTo("SWAP"))
                .extract().jsonPath().getLong("id");

        long planId = as(admin).when().post("/api/substitution-requests/" + requestId + "/plans")
                .then().statusCode(200)
                .extract().jsonPath().getLong("plans[0].id");

        as(admin).when().post("/api/substitution-plans/" + planId + "/approve")
                .then().statusCode(409)
                .body("message", containsString("开课"));
    }
}
