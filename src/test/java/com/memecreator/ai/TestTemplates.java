package com.memecreator.ai;

import com.memecreator.config.AiGatewayProperties;
import com.memecreator.config.MemeProperties;
import com.memecreator.template.LayoutRepository;
import com.memecreator.template.MemeTemplate;
import org.springframework.core.io.DefaultResourceLoader;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The real template-layouts.yml, and pools of templates named after it, for prompt tests. */
final class TestTemplates {

    private TestTemplates() {
    }

    static LayoutRepository layouts() {
        return layouts("classpath:template-layouts.yml");
    }

    static LayoutRepository layouts(String location) {
        MemeProperties props = new MemeProperties("templates", "output", 4, "English", List.of("Arial"), 0.92f,
                location, "private", Duration.ofDays(90));
        return new LayoutRepository(props, new DefaultResourceLoader());
    }

    /** Every template name in template-layouts.yml, in file order. */
    @SuppressWarnings("unchecked")
    static List<String> layoutNames() {
        try (InputStream in = TestTemplates.class.getResourceAsStream("/template-layouts.yml")) {
            Map<String, Object> root = new Yaml().load(in);
            return new ArrayList<>(((Map<String, Object>) root.get("layouts")).keySet());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Like the real pool after a harvest: one template per layout (ids 1000, 1001, ...) plus {@code extra} templates
     * without a layout ("Harvested Meme 1", ..., ids 900001, ...), which get the default top/bottom captions.
     */
    static List<MemeTemplate> pool(int extra) {
        List<MemeTemplate> pool = new ArrayList<>();
        List<String> names = layoutNames();
        for (int i = 0; i < names.size(); i++) {
            pool.add(template(String.valueOf(1000 + i), names.get(i)));
        }
        for (int i = 1; i <= extra; i++) {
            pool.add(template(String.valueOf(900_000 + i), "Harvested Meme " + i));
        }
        return pool;
    }

    static MemeTemplate template(String id, String name) {
        return new MemeTemplate(id, name, Path.of(id + "_" + name + ".jpg"));
    }

    static MemeTemplate named(List<MemeTemplate> pool, String name) {
        return pool.stream().filter(t -> t.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no template \"" + name + "\" in template-layouts.yml"));
    }

    static AiGatewayProperties gatewayProps() {
        return new AiGatewayProperties("http://gateway", "/v1/chat/completions", "/v1/models", "key", "auto/caption",
                1.0, 1536, Duration.ofSeconds(5), Duration.ofSeconds(90), null, null, "auto/vision", 1536, null,
                AiGatewayProperties.ImageFormat.AIPROXY, null, 1024, null);
    }
}
