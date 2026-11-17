package com.memecreator.template;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Where the captions of one template go.
 *
 * @param usage  optional explanation of the joke format, shown to the AI
 * @param boxes  caption areas, in the order the AI fills them
 * @param custom false for the generic top/bottom layout used when a template has no entry
 */
public record TemplateLayout(String usage, List<TextBox> boxes, boolean custom) {

    public TemplateLayout {
        usage = usage == null ? "" : usage.strip();
        boxes = List.copyOf(boxes);
        if (boxes.isEmpty()) {
            throw new IllegalArgumentException("a layout needs at least one text box");
        }
    }

    /** One-line description for the prompt, e.g. "2 texts: 1) the thing Drake rejects; 2) the thing Drake approves". */
    public String describeForAi() {
        String texts = IntStream.range(0, boxes.size())
                .mapToObj(i -> (i + 1) + ") " + boxes.get(i).hint())
                .collect(Collectors.joining("; "));
        String d = boxes.size() + (boxes.size() == 1 ? " text: " : " texts: ") + texts;
        return usage.isEmpty() ? d : d + ". Format: " + usage;
    }
}
