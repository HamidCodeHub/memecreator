package com.memecreator.account;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The public API for people's personal keys ({@code POST /api/v1/memes}).
 *
 * @param freeSetsPerDay    meme sets a FREE account can make through the API per day
 * @param premiumSetsPerDay the same for PREMIUM accounts (no premium plan to buy yet)
 * @param quotaZone         the day resets at midnight in this time zone
 * @param maxKeysPerAccount personal keys one account can have at the same time
 */
@Validated
@ConfigurationProperties(prefix = "meme.api")
public record ApiProperties(
        @DefaultValue("5") @Min(0) int freeSetsPerDay,
        @DefaultValue("100") @Min(0) int premiumSetsPerDay,
        @DefaultValue("Europe/Rome") @NotBlank String quotaZone,
        @DefaultValue("5") @Min(1) @Max(20) int maxKeysPerAccount
) {
}
