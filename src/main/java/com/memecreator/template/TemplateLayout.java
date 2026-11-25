package com.memecreator.template;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Where the captions of one template go.
 *
 * @param usage      optional explanation of the joke format, shown to the AI
 * @param boxes      caption areas, in the order the AI fills them
 * @param custom     false for the generic top/bottom layout used when a template has no own text boxes
 * @param faces      where a user's face can be pasted (empty for cartoons and templates without faces)
 * @param characters the people on a template with several of them, name → description, in file order; the first
 *                   one is the default target for the user's face. Empty when every face slot is the same person.
 */
public record TemplateLayout(String usage, List<TextBox> boxes, boolean custom, List<FaceSlot> faces,
                             Map<String, String> characters) {

    public TemplateLayout {
        usage = usage == null ? "" : usage.strip();
        boxes = List.copyOf(boxes);
        faces = faces == null ? List.of() : List.copyOf(faces);
        characters = characters == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(characters));
        if (boxes.isEmpty()) {
            throw new IllegalArgumentException("a layout needs at least one text box");
        }
    }

    public TemplateLayout(String usage, List<TextBox> boxes, boolean custom, List<FaceSlot> faces) {
        this(usage, boxes, custom, faces, Map.of());
    }

    public TemplateLayout(String usage, List<TextBox> boxes, boolean custom) {
        this(usage, boxes, custom, List.of(), Map.of());
    }

    public boolean hasFaces() {
        return !faces.isEmpty();
    }

    /** True when the template has several people and the user's face goes on only one of them. */
    public boolean hasCharacters() {
        return !characters.isEmpty();
    }

    /** The character the user's face goes on when nobody chose one; null when the template has no characters. */
    public String defaultCharacter() {
        return characters.isEmpty() ? null : characters.keySet().iterator().next();
    }

    /** The character with this name, case-insensitive, as it is spelled in the layout file. */
    public Optional<String> findCharacter(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String wanted = name.strip().toLowerCase(Locale.ROOT);
        return characters.keySet().stream().filter(c -> c.toLowerCase(Locale.ROOT).equals(wanted)).findFirst();
    }

    /** 1-based numbers of the face slots that show this character (all slots when the template has no characters). */
    public List<Integer> slotsOf(String character) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < faces.size(); i++) {
            if (!hasCharacters() || faces.get(i).character().equals(character)) {
                out.add(i + 1);
            }
        }
        return out;
    }

    /** One-line description for the prompt, e.g. "2 texts: 1) the thing Drake rejects; 2) the thing Drake approves". */
    public String describeForAi() {
        return describeForAi(Integer.MAX_VALUE);
    }

    /** Same, with the usage text cut to {@code maxUsageChars} characters (an ellipsis marks the cut). */
    public String describeForAi(int maxUsageChars) {
        String texts = IntStream.range(0, boxes.size())
                .mapToObj(i -> (i + 1) + ") " + boxes.get(i).hint())
                .collect(Collectors.joining("; "));
        String d = boxes.size() + (boxes.size() == 1 ? " text: " : " texts: ") + texts;
        if (usage.isEmpty()) {
            return d;
        }
        String u = usage.length() <= maxUsageChars ? usage : usage.substring(0, Math.max(0, maxUsageChars - 1)).strip() + "…";
        return d + ". Format: " + u;
    }

    /** E.g. "anakin = Anakin, the young man...; padme = Padme, ..." (empty when the template has no characters). */
    public String describeCharactersForAi() {
        return characters.entrySet().stream()
                .map(e -> e.getKey() + " = " + e.getValue())
                .collect(Collectors.joining("; "));
    }
}
