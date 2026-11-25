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
 * Each face is [x, y, w, h, 5 landmark x,y pairs] plus, on templates with several people, the name of the
 * character from the entry's {@code characters:} map.
 */
public final class LayoutParser {

    /** Classic top/bottom layout, used when the file has no "default" entry. */
    public static final TemplateLayout CLASSIC = new TemplateLayout("", List.of(
            new TextBox(0.03, 0.02, 0.94, 0.26, TextBox.TextColor.WHITE, TextBox.VAlign.TOP, "top text"),
            new TextBox(0.03, 0.72, 0.94, 0.26, TextBox.TextColor.WHITE, TextBox.VAlign.BOTTOM, "bottom text")),
            false);

    /**
     * @param layouts    keyed by {@link MemeTemplate#normalize(String) normalized} template name
     * @param duplicates the "duplicates:" section: normalized name of a hidden template → normalized name of the one
     *                   kept in its place
     */
    public record Parsed(TemplateLayout defaultLayout, Map<String, TemplateLayout> layouts,
                         Map<String, String> duplicates) {

        public Parsed(TemplateLayout defaultLayout, Map<String, TemplateLayout> layouts) {
            this(defaultLayout, layouts, Map.of());
        }
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
            def = layout("default", d, false, CLASSIC);
        }

        Map<String, TemplateLayout> layouts = new LinkedHashMap<>();
        if (rootMap.get("layouts") instanceof Map<?, ?> entries) {
            for (Map.Entry<?, ?> e : entries.entrySet()) {
                String name = String.valueOf(e.getKey());
                if (!(e.getValue() instanceof Map<?, ?> body)) {
                    throw new IllegalArgumentException("layout '" + name + "' must be a map with 'boxes:'");
                }
                layouts.put(MemeTemplate.normalize(name), layout(name, body, true, def));
            }
        }
        return new Parsed(def, layouts, duplicates(rootMap.get("duplicates")));
    }

    /**
     * "duplicates:" maps the template to keep to the names of the same meme to hide, e.g.
     * {@code "Drake Hotline Bling": ["Drake Blank", "No - Yes"]}.
     */
    private static Map<String, String> duplicates(Object section) {
        Map<String, String> hidden = new LinkedHashMap<>();
        if (section == null) {
            return hidden;
        }
        if (!(section instanceof Map<?, ?> entries)) {
            throw new IllegalArgumentException("'duplicates:' must map a template name to a list of names");
        }
        for (Map.Entry<?, ?> e : entries.entrySet()) {
            String kept = MemeTemplate.normalize(String.valueOf(e.getKey()));
            List<?> names = e.getValue() instanceof List<?> l ? l : List.of(e.getValue());
            for (Object n : names) {
                String name = MemeTemplate.normalize(String.valueOf(n));
                if (name.isEmpty() || name.equals(kept)) {
                    continue;
                }
                String before = hidden.put(name, kept);
                if (before != null && !before.equals(kept)) {
                    throw new IllegalArgumentException("duplicates: '" + n + "' is listed under two templates");
                }
            }
        }
        for (String kept : hidden.values()) {
            if (hidden.containsKey(kept)) {
                throw new IllegalArgumentException("duplicates: a template can't be both kept and hidden ('"
                        + kept + "')");
            }
        }
        return hidden;
    }

    /** An entry needs "boxes:" or "faces:"; with only faces it keeps the default top/bottom text boxes. */
    private static TemplateLayout layout(String name, Map<?, ?> body, boolean custom, TemplateLayout fallback) {
        Object usage = body.get("usage");
        Map<String, String> characters = characters(name, body.get("characters"));
        List<FaceSlot> faces = new ArrayList<>();
        if (body.get("faces") instanceof List<?> rawFaces) {
            for (int i = 0; i < rawFaces.size(); i++) {
                faces.add(face(name, i + 1, rawFaces.get(i), characters));
            }
        }
        for (String c : characters.keySet()) {
            if (faces.stream().noneMatch(f -> c.equals(f.character()))) {
                throw new IllegalArgumentException("layout '" + name + "': character '" + c + "' has no face slot");
            }
        }
        if (!(body.get("boxes") instanceof List<?> rawBoxes) || rawBoxes.isEmpty()) {
            if (faces.isEmpty()) {
                throw new IllegalArgumentException("layout '" + name + "' has no boxes and no faces");
            }
            return new TemplateLayout(usage == null ? "" : usage.toString(), fallback.boxes(), false, faces, characters);
        }
        List<TextBox> boxes = new ArrayList<>();
        for (int i = 0; i < rawBoxes.size(); i++) {
            boxes.add(box(name, i + 1, rawBoxes.get(i)));
        }
        return new TemplateLayout(usage == null ? "" : usage.toString(), boxes, custom, faces, characters);
    }

    private static Map<String, String> characters(String name, Object raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null) {
            return out;
        }
        if (!(raw instanceof Map<?, ?> m)) {
            throw new IllegalArgumentException("layout '" + name + "': 'characters' must be a map of name: description");
        }
        for (Map.Entry<?, ?> e : m.entrySet()) {
            String key = String.valueOf(e.getKey()).strip();
            if (!key.matches("[A-Za-z0-9_-]{1,30}")) {
                throw new IllegalArgumentException("layout '" + name + "': character name '" + key
                        + "' may only use letters, digits, '-' and '_'");
            }
            out.put(key, e.getValue() == null ? key : String.valueOf(e.getValue()).strip());
        }
        return out;
    }

    private static FaceSlot face(String name, int index, Object raw, Map<String, String> characters) {
        String where = "layout '" + name + "' face " + index;
        if (!(raw instanceof List<?> v) || (v.size() != 14 && v.size() != 15)) {
            throw new IllegalArgumentException(where + ": expected 14 numbers [x, y, w, h, then 5 landmark x,y pairs]"
                    + " and, with 'characters:', the character's name");
        }
        String character = v.size() == 15 ? String.valueOf(v.get(14)).strip() : null;
        if (!characters.isEmpty() && character == null) {
            throw new IllegalArgumentException(where + ": this template has characters " + characters.keySet()
                    + ", so every face needs one as its 15th value");
        }
        if (character != null && !characters.containsKey(character)) {
            throw new IllegalArgumentException(where + ": unknown character '" + character + "'"
                    + (characters.isEmpty() ? " (add a 'characters:' map)" : "; known: " + characters.keySet()));
        }
        try {
            List<Double> lm = new ArrayList<>();
            for (int i = 4; i < 14; i++) {
                lm.add(number(v.get(i)));
            }
            return new FaceSlot(number(v.get(0)), number(v.get(1)), number(v.get(2)), number(v.get(3)), lm, character);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(where + ": " + e.getMessage(), e);
        }
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
