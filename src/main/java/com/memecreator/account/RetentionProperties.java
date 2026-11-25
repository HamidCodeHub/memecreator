package com.memecreator.account;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * How long memes made by accounts stay on the server. A meme that isn't a favorite or shared is deleted once it is
 * older than {@code keepFor}; an uploaded photo goes with them unless one of its memes is a favorite (it is needed to
 * edit that meme). The check runs every {@code meme.retention.check-every} (ISO-8601, default PT5M).
 *
 * @param enabled false: nothing is deleted (memes stay until the account is deleted)
 * @param keepFor time to heart, share, download or edit a meme before it is deleted
 */
@ConfigurationProperties(prefix = "meme.retention")
public record RetentionProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("1h") Duration keepFor
) {
}
