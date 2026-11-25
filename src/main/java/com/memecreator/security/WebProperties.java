package com.memecreator.security;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The signed-in web app (the memecreator-fe repository): sign-in with Google or Meta, then POST /api/web/memes.
 */
@Validated
@ConfigurationProperties(prefix = "meme.web")
public record WebProperties(
        /** Memes per request from the web app. */
        @DefaultValue("6") @Min(1) @Max(12) int memesPerSet,
        /** Per signed-in user, sliding one-hour window. Each set is one or two AI calls. */
        @DefaultValue("20") @Min(1) int maxSetsPerHour,
        /**
         * Prompts (meme sets from a topic or a photo) a FREE account can make per day in the web app; the day resets
         * at midnight in meme.api.quota-zone. 0 = no daily limit. Set it in config/application.yml or with
         * MEME_WEB_FREEPROMPTSPERDAY.
         */
        @DefaultValue("10") @Min(0) int freePromptsPerDay,
        /** The same for PREMIUM accounts. 0 = no daily limit. */
        @DefaultValue("0") @Min(0) int premiumPromptsPerDay,
        /**
         * false (default): every API needs a signed-in person or an API key, per role (see SecurityConfig.rules).
         * true: the old development mode, where only /api/web and /api/admin are protected.
         */
        @DefaultValue("false") boolean openDevApis,
        /** Where the browser lands after signing in (relative = same host the sign-in started from). */
        @DefaultValue("/") @NotBlank String afterLoginUrl
) {
}
