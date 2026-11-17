package com.memecreator.template;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads template-layouts.yml. Each box is a list: [x, y, width, height, color, valign, "hint"].
 */
public final class LayoutParser {

    /** Classic top/bottom layout, used when the file has no "default" entry. */
    public static final TemplateLayout CLASSIC = new TemplateLayout("", List.of(
            new TextBox(0.03, 0.02, 0.94, 0.26, TextBox.TextColor.WHITE, TextBox.VAlign.TOP, "top text"),
            new TextBox(0.03, 0.72, 0.94, 0.26, TextBox.TextColor.WHITE, TextBox.VAlign.BOTTOM, "bottom text")),
            false);

    /** @param layouts keyed by {@link MemeTemplate#normalize(String) normalized} template name */
    public record Parsed(TemplateLayout defaultLayout, Map<String, TemplateLayout> layouts) {
    }

    private LayoutParser() {
    }

    public static Parsed parse(InputStream in) {
        Object root = new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
        if (!(root instanceof Map<?, ?> rootMap)) {
            throw new IllegalArgumentException("layout file must be a YAML map with 'layouts:'");
        }

        TemplateLayout def = CLASSIC;
        if (rootMap.get("default") instanceof Map<?, ?> d) {
            def = layout("default", d, false);
        }

        Map<String, TemplateLayout> layouts = new LinkedHashMap<>();
        if (rootMap.get("layouts") instanceof Map<?, ?> entries) {
            for (Map.Entry<?, ?> e : entries.entrySet()) {
                String name = String.valueOf(e.getKey());
                if (!(e.getValue() instanceof Map<?, ?> body)) {
                    throw new IllegalArgumentException("layout '" + name + "' must be a map with 'boxes:'");
                }
                layouts.put(MemeTemplate.normalize(name), layout(name, body, true));
            }
        }
        return new Parsed(def, layouts);
    }

    private static TemplateLayout layout(String name, Map<?, ?> body, boolean custom) {
        Object usage = body.get("usage");
        if (!(body.get("boxes") instanceof List<?> rawBoxes) || rawBoxes.isEmpty()) {
            throw new IllegalArgumentException("layout '" + name + "' has no boxes");
        }
        List<TextBox> boxes = new ArrayList<>();
        for (int i = 0; i < rawBoxes.size(); i++) {
            boxes.add(box(name, i + 1, rawBoxes.get(i)));
        }
        return new TemplateLayout(usage == null ? "" : usage.toString(), boxes, custom);
    }

    private static TextBox box(String name, int index, Object raw) {
        String where = "layout '" + name + "' box " + index;
        if (!(raw instanceof List<?> v) || v.size() < 4) {
            throw new IllegalArgumentException(where + ": expected [x, y, width, height, color, valign, hint]");
        }
        try {
            return new TextBox(
                    number(v.get(0)), number(v.get(1)), number(v.get(2)), number(v.get(3)),
                    v.size() > 4 ? TextBox.TextColor.valueOf(upper(v.get(4))) : TextBox.TextColor.WHITE,
                    v.size() > 5 ? TextBox.VAlign.valueOf(upper(v.get(5))) : TextBox.VAlign.MIDDLE,
                    v.size() > 6 ? String.valueOf(v.get(6)) : "");
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(where + ": " + e.getMessage(), e);
        }
    }

    private static double number(Object o) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        return Double.parseDouble(String.valueOf(o));
    }

    private static String upper(Object o) {
        return String.valueOf(o).strip().toUpperCase(Locale.ROOT);
    }
}
