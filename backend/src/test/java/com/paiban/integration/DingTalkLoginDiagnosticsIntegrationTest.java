package com.paiban.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import com.paiban.integration.dingtalk.DingTalkApiException;
import com.paiban.integration.dingtalk.DingTalkClient;
import com.paiban.support.ApiIntegrationTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 03 复查整改的有界回归：真实 HTTP Adapter 已清洗过的结构化外部错误，登录失败时仍要在
 * 服务端留下两个数字诊断（errcode / sub_code），否则线上只剩「调用失败」无从排查。
 *
 * <p>边界是公开 HTTP 加日志：唯一被替换的 bean 是第三方 {@link DingTalkClient}（真实
 * settings / identity / provisioner 全部照常装配），登录接口与错误响应走真实链路，
 * 日志用 {@link OutputCaptureExtension} 观察。用例不断言 logger 调用次数，也不触碰内部
 * repository 交互。
 *
 * <p>外部异常里的 action 与 vendor errmsg 都带同一个裸 canary，凭据字样也塞在里面：
 * 只要实现把异常对象、getMessage()、cause 或堆栈写进日志，canary 就会随输出泄露而被抓到。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:paiban_login_diagnostics;MODE=PostgreSQL;"
            + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@ExtendWith(OutputCaptureExtension.class)
class DingTalkLoginDiagnosticsIntegrationTest extends ApiIntegrationTest {

    /** 只可能来自 vendor 异常对象的裸标记；响应、日志、异常文本都不该出现它。 */
    private static final String CANARY = "rawcanary7f3d";

    private static final int VENDOR_ERROR_CODE = 40013;

    private static final int VENDOR_SUB_CODE = 42;

    private static final String VENDOR_ACTION = "exchangeAuthCode(" + CANARY + ")";

    private static final String VENDOR_ERROR_MESSAGE =
            "authCode=" + CANARY + "&token=" + CANARY + "&AppSecret=" + CANARY + " " + CANARY;

    private static final String STABLE_MESSAGE = "钉钉登录暂时不可用，请稍后重试或联系超级管理员";

    /** 服务端诊断行的固定文字；数字之外的部分必须与实现钉住同一句。 */
    private static final String DIAGNOSTIC_MARKER = "钉钉免登外部调用失败";

    @MockitoBean
    private DingTalkClient client;

    @BeforeEach
    void enableDingTalkThroughTheRealAdminEndpoint() {
        resetIntegrationSettings();
        as(adminToken()).body(Map.of(
                        "enabled", true, "appKey", "diagnostics-key", "appSecret", "diagnostics-secret",
                        "agentId", "1000", "corpId", "diagnostics-corp"))
                .when().put("/api/integrations/dingtalk/config").then().statusCode(200);
    }

    /**
     * 结构化外部错误：用户仍拿到固定的 401 / code / message，响应里没有 vendor 数字，
     * 而服务端 WARN 行必须带上 errcode 与 sub_code 两个整数，且不带异常文本的任何片段。
     */
    @Test
    void vendorErrorNumbersReachTheServerLogWithoutLeakingTheVendorPayload(CapturedOutput output) {
        given(client.exchangeAuthCode(anyString())).willThrow(
                new DingTalkApiException(VENDOR_ACTION, VENDOR_ERROR_CODE, VENDOR_SUB_CODE, VENDOR_ERROR_MESSAGE));

        Response login = loginWithAuthCode("fake:diagnostics");

        login.then().statusCode(401)
                .body("status", equalTo(401))
                .body("code", equalTo("DINGTALK_CALL_FAILED"))
                .body("message", equalTo(STABLE_MESSAGE));

        // 诊断只留服务端：响应既不回传 vendor 正文，也没有新增数字诊断字段。
        assertThat(login.asString())
                .doesNotContain(CANARY)
                .doesNotContain(String.valueOf(VENDOR_ERROR_CODE));

        String captured = output.getAll();
        String diagnosticLine = captured.lines()
                .filter(line -> line.contains(DIAGNOSTIC_MARKER))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "钉钉登录调用失败时没有可定位的 WARN 诊断行，实际输出：" + captured));
        assertThat(diagnosticLine)
                .contains("errcode=" + VENDOR_ERROR_CODE)
                .contains("sub_code=" + VENDOR_SUB_CODE);
        // 整段输出里不能有裸 canary、原始 action、vendor errmsg 或异常对象/堆栈。
        assertThat(captured)
                .doesNotContain(CANARY)
                .doesNotContain(VENDOR_ACTION)
                .doesNotContain("authCode=")
                .doesNotContain("AppSecret")
                .doesNotContain("DingTalkApiException");
    }

    /** 未知运行时异常没有数字诊断可言，但旧的安全映射必须原样保留。 */
    @Test
    void unknownRuntimeFailureKeepsTheOldSafeMapping() {
        given(client.exchangeAuthCode(anyString()))
                .willThrow(new IllegalStateException("transport boom " + CANARY));

        loginWithAuthCode("fake:unknown").then().statusCode(401)
                .body("status", equalTo(401))
                .body("code", equalTo("DINGTALK_CALL_FAILED"))
                .body("message", equalTo(STABLE_MESSAGE));
    }

    private Response loginWithAuthCode(String authCode) {
        return RestAssured.given().contentType(ContentType.JSON)
                .body(Map.of("authCode", authCode))
                .when().post("/api/auth/dingtalk");
    }
}
