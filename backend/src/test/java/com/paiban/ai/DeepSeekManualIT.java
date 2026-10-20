package com.paiban.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.paiban.support.ApiIntegrationTest;
import com.paiban.support.TestData;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Manual smoke test against the real DeepSeek API. CI never sets
 * {@code DEEPSEEK_API_KEY}, so this class is skipped there.
 *
 * <p>Run with: {@code DEEPSEEK_API_KEY=sk-... ./mvnw -Dtest=DeepSeekManualIT test}
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = ".+")
class DeepSeekManualIT extends ApiIntegrationTest {

    @Autowired
    private AiGateway gateway;

    @Test
    void realDeepSeekChatReturnsContent() {
        String admin = adminToken();
        long providerId = as(admin).body(Map.of(
                        "name", "deepseek-manual-" + TestData.suffix(),
                        "type", "preset",
                        "baseUrl", "https://api.deepseek.com",
                        "apiKey", System.getenv("DEEPSEEK_API_KEY"),
                        "enabled", true))
                .when().post("/api/ai-providers")
                .then().statusCode(200)
                .extract().jsonPath().getLong("id");
        as(admin).body(Map.of(
                        "modelName", "deepseek-chat",
                        "displayName", "DeepSeek Chat",
                        "visionCapable", false,
                        "enabled", true))
                .when().post("/api/ai-providers/" + providerId + "/models")
                .then().statusCode(200);

        AiResult result = gateway.chat(new AiRequest(
                AiTaskType.SUBSTITUTION_RECOMMEND,
                List.of(
                        new AiMessage("system", "只回复两个字：收到"),
                        new AiMessage("user", "连通性测试")),
                null,
                new AiOptions(0.0, 16, false)));

        assertThat(result.success()).as(result.errorMessage()).isTrue();
        assertThat(result.content()).isNotBlank();
    }

    @Test
    void realDeepSeekVisionCallWhenAVisionModelIsConfigured() throws Exception {
        String visionModel = System.getenv("DEEPSEEK_VISION_MODEL");
        Assumptions.assumeTrue(
                visionModel != null && !visionModel.isBlank(),
                "设置 DEEPSEEK_VISION_MODEL 后才运行视觉手动测试");

        String admin = adminToken();
        long providerId = as(admin).body(Map.of(
                        "name", "deepseek-vision-" + TestData.suffix(),
                        "type", "preset",
                        "baseUrl", "https://api.deepseek.com",
                        "apiKey", System.getenv("DEEPSEEK_API_KEY"),
                        "enabled", true))
                .when().post("/api/ai-providers")
                .then().statusCode(200)
                .extract().jsonPath().getLong("id");
        as(admin).body(Map.of(
                        "modelName", visionModel,
                        "displayName", "DeepSeek Vision",
                        "visionCapable", true,
                        "enabled", true))
                .when().post("/api/ai-providers/" + providerId + "/models")
                .then().statusCode(200);

        BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);

        AiResult result = gateway.chatWithVision(new AiRequest(
                AiTaskType.DATA_IMPORT,
                List.of(new AiMessage("user", "描述这张图片")),
                List.of(new AiImage("image/png", bytes.toByteArray())),
                AiOptions.NONE));

        assertThat(result.success()).as(result.errorMessage()).isTrue();
        assertThat(result.content()).isNotBlank();
    }
}
