package com.memecreator.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.util.List;

@Validated
@ConfigurationProperties(prefix = "meme")
public record MemeProperties(
        @NotBlank String templatesDir,
        @NotBlank String outputDir,
        @DefaultValue("4") @Min(1) @Max(12) int count,
        @DefaultValue("English") @NotBlank String defaultLanguage,
        @DefaultValue({"Impact", "Anton", "Arial Black", "Arial"}) List<String> fonts,
        @DefaultValue("0.92") float jpegQuality,
        /** Where captions go on each template; a Spring resource location (classpath: or file:). */
        @DefaultValue("classpath:template-layouts.yml") String layoutsFile
) {
}
