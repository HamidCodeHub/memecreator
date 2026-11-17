package com.memecreator.template;

import com.memecreator.config.MemeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Map;

/**
 * Text-box layouts per template, from meme.layouts-file (default classpath:template-layouts.yml).
 * When the file is on disk it is re-read whenever it changes, so layouts can be tuned while the app runs.
 */
@Component
public class LayoutRepository {

    private static final Logger log = LoggerFactory.getLogger(LayoutRepository.class);

    private final Resource resource;
    private volatile LayoutParser.Parsed parsed;
    private volatile long stamp = Long.MIN_VALUE;

    public LayoutRepository(MemeProperties props, ResourceLoader loader) {
        this.resource = loader.getResource(props.layoutsFile());
        if (!resource.exists()) {
            log.warn("Layout file {} not found; every template uses top/bottom captions", props.layoutsFile());
            this.parsed = new LayoutParser.Parsed(LayoutParser.CLASSIC, Map.of());
        } else {
            this.parsed = read();
            this.stamp = lastModified();
        }
    }

    public TemplateLayout layoutFor(MemeTemplate template) {
        refreshIfChanged();
        LayoutParser.Parsed p = parsed;
        return p.layouts().getOrDefault(template.normalizedName(), p.defaultLayout());
    }

    private void refreshIfChanged() {
        long now = lastModified();
        if (now == stamp || now <= 0) {
            return;
        }
        synchronized (this) {
            if (now == stamp) {
                return;
            }
            try {
                parsed = read();
            } catch (RuntimeException e) {
                // keep the previous layouts so a typo while editing doesn't break meme creation
                log.error("Could not reload {}: {} (keeping the previous layouts)", resource, e.getMessage());
            }
            stamp = now;
        }
    }

    private LayoutParser.Parsed read() {
        try (InputStream in = resource.getInputStream()) {
            LayoutParser.Parsed p = LayoutParser.parse(in);
            log.info("Loaded {} template layouts from {}", p.layouts().size(), resource);
            return p;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read layout file " + resource, e);
        }
    }

    private long lastModified() {
        try {
            return resource.isFile() ? resource.getFile().lastModified() : 0;
        } catch (IOException e) {
            return 0;
        }
    }
}
