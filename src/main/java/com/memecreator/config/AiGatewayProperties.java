package com.memecreator.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.Map;

@Validated
@ConfigurationProperties(prefix = "ai-gateway")
public record AiGatewayProperties(
        @NotBlank String baseUrl,
        @DefaultValue("/v1/chat/completions") String chatPath,
        @DefaultValue("/v1/models") String modelsPath,
        String apiKey,
        @NotBlank String model,
        @DefaultValue("0.9") double temperature,
        @DefaultValue("800") int maxTokens,
        @DefaultValue("5s") Duration connectTimeout,
        @DefaultValue("90s") Duration readTimeout,
        /** Optional, sent as "reasoning_effort" (low/medium/high) for reasoning models like gpt-oss. */
        String reasoningEffort,
        /** When true, sends response_format={"type":"json_object"} so the model must answer with JSON. */
        @DefaultValue("false") boolean jsonMode,
        /**
         * Optional raw provider-specific fields, sent as "provider_options" (aiproxy merges them into the
         * call to that provider), e.g. groq -> reasoning_effort: low.
         */
        Map<String, Map<String, String>> providerOptions
) {
}
