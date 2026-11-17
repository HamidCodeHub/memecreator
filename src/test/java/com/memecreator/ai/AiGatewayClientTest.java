package com.memecreator.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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
    void readsPlainShape() throws Exception {
        var json = mapper.readTree("{\"output_text\":\"plain\"}");
        assertThat(AiGatewayClient.extractText(json)).isEqualTo("plain");
    }
}
