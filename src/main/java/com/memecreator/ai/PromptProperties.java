package com.memecreator.ai;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * How big the "pick the templates" caption prompt may get. Listing every template (hundreds once the harvest runs)
 * made the request ~9,500 tokens, more than Groq's free tier allows per minute; see {@link TemplateShortlist}.
 *
 * @param maxTemplatesInPrompt most templates listed in one prompt (never fewer than the memes asked for)
 * @param keywordSlots         of those, at most this many picked because the topic's words appear in the template
 * @param popularSlots         at most this many well-known templates (Imgflip's latest top 100, weighted by rank)
 * @param maxUsageChars        a template's "usage" text is cut to this many characters in the prompt
 */
@Validated
@ConfigurationProperties(prefix = "meme.ai")
public record PromptProperties(
        @DefaultValue("15") @Min(1) @Max(60) int maxTemplatesInPrompt,
        @DefaultValue("3") @Min(0) int keywordSlots,
        @DefaultValue("6") @Min(0) int popularSlots,
        @DefaultValue("160") @Min(20) int maxUsageChars
) {
}
