package com.memecreator.account;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Email + password accounts.
 *
 * @param publicUrl       where the web app runs; email links point here ({@code <publicUrl>/?verify=...})
 * @param mailFrom        sender of confirmation and reset emails
 * @param verifyLinkTtl   how long a confirmation link works
 * @param resetLinkTtl    how long a password-reset link works
 * @param minPasswordLength shortest password accepted (the longest is 64: bcrypt only reads 72 bytes)
 * @param termsVersion    current version of the Terms of use + Privacy policy (memecreator-fe/public/terms.html,
 *                        privacy.html). Change it when they change: everyone is asked to accept again.
 * @param unconfirmedTtl  email + password sign-ups never confirmed are deleted after this
 */
@Validated
@ConfigurationProperties(prefix = "meme.accounts")
public record AccountProperties(
        @DefaultValue("http://localhost:5173") @NotBlank String publicUrl,
        @DefaultValue("Memes for Laughs <no-reply@localhost>") @NotBlank String mailFrom,
        @DefaultValue("24h") Duration verifyLinkTtl,
        @DefaultValue("1h") Duration resetLinkTtl,
        @DefaultValue("10") @Min(8) int minPasswordLength,
        @DefaultValue("2026-11-22") @NotBlank String termsVersion,
        @DefaultValue("7d") Duration unconfirmedTtl
) {
}
