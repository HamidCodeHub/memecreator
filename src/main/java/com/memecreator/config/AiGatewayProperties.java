package com.memecreator.config;

import jakarta.validation.constraints.Min;
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
        /** Caption model: aiproxy's virtual model auto/caption (a failover chain) by default. */
        @DefaultValue("auto/caption") @NotBlank String model,
        @DefaultValue("0.9") double temperature,
        /**
         * Sent as "max_tokens" on caption calls. Groq's free tier counts prompt + max_tokens against 8,000 tokens per
         * minute, so keep it modest; but gpt-oss reasons before answering (those tokens count as output), so far
         * below 1,024 the JSON gets cut off.
         */
        @DefaultValue("1536") @Min(256) int maxTokens,
        @DefaultValue("5s") Duration connectTimeout,
        @DefaultValue("90s") Duration readTimeout,
        /** Optional, sent as "reasoning_effort" (low/medium/high) for reasoning models like gpt-oss. */
        String reasoningEffort,
        /**
         * Optional raw provider-specific fields, sent as "provider_options" (aiproxy merges them into the
         * call to that provider), e.g. groq -> reasoning_effort: low.
         */
        Map<String, Map<String, String>> providerOptions,
        /** Model that can read images, used for photo memes: aiproxy's virtual model auto/vision by default. */
        @DefaultValue("auto/vision") String visionModel,
        /** Sent as "max_tokens" on photo-meme calls (same trade-off as max-tokens). */
        @DefaultValue("1536") @Min(256) int visionMaxTokens,
        /** Like provider-options, but only for vision-model calls. */
        Map<String, Map<String, String>> visionProviderOptions,
        /**
         * How images are put in a message (photo memes and AI face replacement), always as data URIs:
         * AIPROXY = {"type":"image","url":"data:..."} (aiproxy's format; it rejects the OpenAI one),
         * OPENAI = {"type":"image_url","image_url":{"url":"data:..."}} (calling an OpenAI-style API directly).
         */
        @DefaultValue("AIPROXY") ImageFormat imageFormat,
        /**
         * Model that can edit images (images in, an image out), for AI face replacement, e.g.
         * gemini/gemini-2.5-flash-image. Empty: the AI face endpoints are off.
         */
        String imageModel,
        /** The template area sent to the image model is scaled down to at most this many pixels per side. */
        @DefaultValue("1024") int imageEditSize,
        /** Like provider-options, but only for image-model calls (simple key: value pairs). */
        Map<String, Map<String, String>> imageProviderOptions
) {

    public enum ImageFormat { AIPROXY, OPENAI }
}
