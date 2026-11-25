package com.memecreator.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.memecreator.ai.AiGatewayClient;
import com.memecreator.service.MemeService;
import com.memecreator.service.MemeService.GeneratedMeme;
import com.memecreator.service.MemeService.MemeBatch;
import com.memecreator.service.MemeService.Strategy;
import com.memecreator.template.TemplateLayout;
import com.memecreator.template.TemplateRepository;
import com.memecreator.template.TextBox;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Memes", description = "Generate AI-captioned memes from a topic")
@Validated
@RestController
@RequestMapping("/api")
public class MemeController {

    /** Letters, spaces and a few separators, e.g. "English", "Brazilian Portuguese", "pt-BR". */
    static final String LANGUAGE_REGEX = "^[\\p{L}][\\p{L} ()_-]{1,39}$";
    static final String LANGUAGE_MESSAGE = "language must be a language name like \"English\" (2-40 letters)";

    private final MemeService memeService;
    private final TemplateRepository templates;
    private final AiGatewayClient aiGateway;

    public MemeController(MemeService memeService, TemplateRepository templates, AiGatewayClient aiGateway) {
        this.memeService = memeService;
        this.templates = templates;
        this.aiGateway = aiGateway;
    }

    /** POST /api/memes  {"topic": "monday meetings", "strategy": "AI_PICK", "templates": []} */
    @Operation(summary = "Create memes from a topic",
            description = "The AI picks templates (or uses the ones you pass), writes one text per text box, "
                    + "and the memes are saved to the output folder.")
    @PostMapping("/memes")
    public MemeBatch create(@Valid @RequestBody MemeRequest request) {
        return memeService.create(request.topic(), request.templates(),
                request.strategy() == null ? Strategy.AI_PICK : request.strategy(),
                request.language());
    }

    /** GET /api/memes?topic=monday%20meetings[&strategy=RANDOM][&templates=Drake Hotline Bling,Two Buttons] */
    @Operation(summary = "Create memes from a topic (query-string version)")
    @GetMapping("/memes")
    public MemeBatch createFromQuery(
            @Parameter(description = "What the memes should be about", example = "deploying to production on a Friday")
            @RequestParam @NotBlank @Size(max = 300) String topic,
            @Parameter(description = "AI_PICK: AI chooses templates; RANDOM: random templates")
            @RequestParam(defaultValue = "AI_PICK") Strategy strategy,
            @Parameter(description = "Optional exact template names, comma-separated", example = "Drake Hotline Bling,Two Buttons")
            @RequestParam(required = false) List<String> templates,
            @Parameter(description = "Caption language (default: English)", example = "Italian")
            @RequestParam(required = false) @Pattern(regexp = LANGUAGE_REGEX, message = LANGUAGE_MESSAGE) String language) {
        return memeService.create(topic, templates, strategy, language);
    }

    @Operation(summary = "Render one template with your own texts (no AI call)",
            description = "One text per text box, in box order. Useful for checking a layout from template-layouts.yml.")
    @PostMapping("/memes/render")
    public GeneratedMeme renderManual(@Valid @RequestBody RenderRequest request) {
        return memeService.renderManual(request.template(), request.texts());
    }

    @Operation(summary = "List the models the AI gateway offers",
            description = "Passes through the gateway's GET /v1/models. Use one of these ids as ai-gateway.model.")
    @GetMapping("/ai/models")
    public JsonNode aiModels() {
        return aiGateway.models();
    }

    @Operation(summary = "List the meme templates with their text boxes")
    @GetMapping("/templates")
    public List<TemplateInfo> templates() {
        return templates.findAll().stream().map(t -> {
            TemplateLayout layout = memeService.layoutOf(t);
            List<CharacterInfo> characters = layout.characters().entrySet().stream()
                    .map(e -> new CharacterInfo(e.getKey(), e.getValue(), layout.slotsOf(e.getKey()),
                            e.getKey().equals(layout.defaultCharacter())))
                    .toList();
            return new TemplateInfo(t.id(), t.name(), layout.custom(),
                    layout.boxes().stream().map(TextBox::hint).toList(), layout.faces().size(), characters);
        }).toList();
    }

    @Operation(summary = "Preview a template's text boxes and face slots",
            description = "Returns the template as PNG with every text box (red, #n) and face slot (blue, Fn) outlined. "
                    + "Open it in the browser while editing template-layouts.yml.")
    @GetMapping("/templates/preview")
    public ResponseEntity<byte[]> preview(
            @Parameter(description = "Template name", example = "Drake Hotline Bling")
            @RequestParam @NotBlank String name) {
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(memeService.layoutPreview(name));
    }

    public record MemeRequest(
            @Schema(description = "What the memes should be about", example = "deploying to production on a Friday")
            @NotBlank @Size(max = 300) String topic,
            @Schema(description = "Optional exact template names; when set, these are used instead of AI_PICK/RANDOM",
                    example = "[\"Drake Hotline Bling\", \"This Is Fine\"]")
            List<String> templates,
            @Schema(description = "AI_PICK (default): AI chooses templates; RANDOM: random templates", defaultValue = "AI_PICK")
            Strategy strategy,
            @Schema(description = "Caption language; optional, defaults to English", example = "English", defaultValue = "English")
            @Pattern(regexp = LANGUAGE_REGEX, message = LANGUAGE_MESSAGE) String language) {
    }

    public record RenderRequest(
            @Schema(description = "Template name", example = "Drake Hotline Bling")
            @NotBlank String template,
            @Schema(description = "One text per text box, in order", example = "[\"Writing tests\", \"Testing in production\"]")
            List<@Size(max = 200) String> texts) {
    }

    /**
     * @param customLayout false when the template uses the generic top/bottom layout
     * @param boxes        what goes in each text box, in order
     * @param faceSlots    how many faces a user's face can be pasted onto (0 = not usable for face memes)
     * @param characters   on templates with several people: who they are and which face slots show them
     *                     (empty when every face slot is the same person)
     */
    public record TemplateInfo(String id, String name, boolean customLayout, List<String> boxes, int faceSlots,
                               List<CharacterInfo> characters) {
    }

    /** @param defaultCharacter true for the one that gets the user's face when neither they nor the AI choose */
    public record CharacterInfo(String name, String description, List<Integer> slots, boolean defaultCharacter) {
    }
}
