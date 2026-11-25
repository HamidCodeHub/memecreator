package com.memecreator.harvest;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Template harvesting: downloads new meme templates from public template APIs into {@link #storageDir()}.
 *
 * <pre>
 * storage-dir/
 *   templates/      ready to use (the app picks templates from here too when use-as-templates is true)
 *   needs-layout/   3+ text boxes and no entry in template-layouts.yml yet; moved to templates/ once one exists
 *   catalog.json    every template seen, its source, fingerprints and the duplicates it absorbed
 *   imgflip-ranks.jsonl  one line per Imgflip run: the day's top-100 order (trend history)
 * </pre>
 */
@Validated
@ConfigurationProperties(prefix = "meme.harvest")
public record HarvestProperties(
        /** Turns the scheduled runs on/off. The manual endpoint works either way. */
        @DefaultValue("true") boolean enabled,
        @NotBlank String storageDir,
        /** Also serve the harvested templates (storage-dir/templates) next to meme.templates-dir. */
        @DefaultValue("true") boolean useAsTemplates,
        /** Run every source once shortly after start-up (in the background). */
        @DefaultValue("false") boolean runOnStartup,
        /** Imgflip's top 100 changes daily. */
        @DefaultValue("0 15 4 * * *") String imgflipCron,
        /** memegen.link's list grows slowly. */
        @DefaultValue("0 45 4 * * MON") String memegenCron,
        @DefaultValue("https://api.imgflip.com/get_memes") String imgflipUrl,
        @DefaultValue("https://api.memegen.link/templates/") String memegenUrl,
        /**
         * Two images count as the same template when both perceptual hashes (64 bits each) differ in at most this
         * many bits and their aspect ratios are close. 0 = exact copies only; above ~14 different templates start
         * to collide.
         */
        @DefaultValue("10") @Min(0) @Max(20) int maxHashDistance,
        /** Images whose shorter side is smaller than this are skipped. */
        @DefaultValue("250") @Min(1) int minImageSide,
        /** A near-duplicate replaces a harvested image when it has at least this many times more pixels. */
        @DefaultValue("1.5") double replaceWhenLargerBy,
        @DefaultValue("15MB") DataSize maxImageSize,
        /** Pause between image downloads, to be polite to the hosts. */
        @DefaultValue("300ms") Duration downloadDelay,
        @DefaultValue("10s") Duration connectTimeout,
        @DefaultValue("30s") Duration readTimeout,
        @DefaultValue("memecreator-template-harvester/1.0 (+https://github.com/HamidCodeHub/memecreator)") String userAgent
) {
}
