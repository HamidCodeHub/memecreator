package com.memecreator.render;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The site name in the corner of every generated meme.
 *
 * @param enabled false: no watermark
 * @param text    what is written, e.g. the domain
 */
@Validated
@ConfigurationProperties(prefix = "meme.watermark")
public record WatermarkProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("memesforlaughs.com") @NotBlank @Size(max = 40) String text
) {
}
