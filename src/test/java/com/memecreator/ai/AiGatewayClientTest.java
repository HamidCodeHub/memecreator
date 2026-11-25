package com.memecreator.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.memecreator.config.AiGatewayProperties.ImageFormat;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiGatewayClientTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void readsOpenAiShape() throws Exception {
        var json = mapper.readTree("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"hi\"}}]}");
        assertThat(AiGatewayClient.extractText(json)).isEqualTo("hi");
    }

    @Test
    void readsAnthropicShape() throws Exception {
        var json = mapper.readTree("{\"content\":[{\"type\":\"text\",\"text\":\"he\"},{\"type\":\"text\",\"text\":\"llo\"}]}");
        assertThat(AiGatewayClient.extractText(json)).isEqualTo("hello");
    }

    @Test
    void readsTheImageOfAnImageAnswer() throws Exception {
        var aiproxy = mapper.readTree("""
                {"choices":[{"message":{"role":"assistant","content":[
                  {"type":"text","text":"Here you go"},
                  {"type":"image","url":"data:image/png;base64,iVBORw0KGgo=","media_type":"image/png"}]}}]}""");
        var openAi = mapper.readTree("""
                {"choices":[{"message":{"content":[{"type":"image_url","image_url":{"url":"data:image/jpeg;base64,/9j/"}}]}}]}""");
        var openRouter = mapper.readTree("""
                {"choices":[{"message":{"content":"done","images":[{"type":"image_url","image_url":{"url":"data:image/png;base64,AA=="}}]}}]}""");
        var textOnly = mapper.readTree("{\"choices\":[{\"message\":{\"content\":\"I can't edit photos of real people\"}}]}");

        assertThat(AiGatewayClient.extractImage(aiproxy)).isEqualTo("data:image/png;base64,iVBORw0KGgo=");
        assertThat(AiGatewayClient.extractText(aiproxy)).isEqualTo("Here you go");
        assertThat(AiGatewayClient.extractImage(openAi)).isEqualTo("data:image/jpeg;base64,/9j/");
        assertThat(AiGatewayClient.extractImage(openRouter)).isEqualTo("data:image/png;base64,AA==");
        assertThat(AiGatewayClient.extractImage(textOnly)).isNull();
    }

    @Test
    void readsPlainShape() throws Exception {
        var json = mapper.readTree("{\"output_text\":\"plain\"}");
        assertThat(AiGatewayClient.extractText(json)).isEqualTo("plain");
    }

    @Test
    void aSilentFallbackToAMockModelIsAFailure() throws Exception {
        var mock = mapper.readTree("{\"model\":\"mock/mock-fast\",\"choices\":[]}");
        assertThatThrownBy(() -> AiGatewayClient.rejectMockFallback("groq/openai/gpt-oss-120b", mock))
                .isInstanceOf(AiGatewayException.class)
                .hasMessageContaining("mock/mock-fast");
        // asked for on purpose (local testing): fine
        assertThatCode(() -> AiGatewayClient.rejectMockFallback("mock/mock-fast", mock)).doesNotThrowAnyException();
        var real = mapper.readTree("{\"model\":\"groq/openai/gpt-oss-120b\"}");
        assertThatCode(() -> AiGatewayClient.rejectMockFallback("groq/openai/gpt-oss-120b", real))
                .doesNotThrowAnyException();
    }

    @Test
    void everyCallSendsMaxTokensAndJsonMode() {
        Map<String, Object> body = AiGatewayClient.chatBody("auto/caption", 1.0, 1536, null,
                Map.of("groq", Map.of("reasoning_effort", "low")), "system", "user");
        assertThat(body).containsEntry("model", "auto/caption")
                .containsEntry("max_tokens", 1536)
                .containsEntry("response_format", Map.of("type", "json_object"))
                .containsKey("provider_options")
                .doesNotContainKey("reasoning_effort");
    }

    @Test
    void photosGoAsDataUrisInTheConfiguredImagePart() {
        String uri = "data:image/jpeg;base64,/9j/";
        List<Map<String, Object>> aiproxy = AiGatewayClient.visionContent("caption this", uri, ImageFormat.AIPROXY);
        assertThat(aiproxy).hasSize(2);
        assertThat(aiproxy.get(0)).containsEntry("type", "text").containsEntry("text", "caption this");
        assertThat(aiproxy.get(1)).containsOnly(Map.entry("type", "image"), Map.entry("url", uri));

        assertThat(AiGatewayClient.visionContent("x", uri, ImageFormat.OPENAI).get(1))
                .containsEntry("type", "image_url").containsEntry("image_url", Map.of("url", uri));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> AiGatewayClient.visionContent("x", "https://example.com/cat.jpg", ImageFormat.AIPROXY));
    }

    @Test
    void estimatesInputTokensFromTheTextOnly() {
        assertThat(AiGatewayClient.estimateInputTokens("12345678", "1234")).isEqualTo(3);
        assertThat(AiGatewayClient.estimateInputTokens("1234",
                AiGatewayClient.visionContent("12345678", "data:image/jpeg;base64," + "A".repeat(10_000),
                        ImageFormat.AIPROXY)))
                .isEqualTo(3);
    }
}
