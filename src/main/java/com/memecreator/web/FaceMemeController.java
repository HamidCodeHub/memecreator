package com.memecreator.web;

import com.memecreator.face.FaceMemeService;
import com.memecreator.face.FaceStyle;
import com.memecreator.face.FaceMemeService.FaceInput;
import com.memecreator.face.FaceMemeService.FaceMemeBatch;
import com.memecreator.face.FaceStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

@Tag(name = "Memes with your face", description = "Paste your face onto meme templates")
@Validated
@RestController
@RequestMapping("/api")
public class FaceMemeController {

    static final String CHARACTER_REGEX = "^[A-Za-z0-9_-]{1,30}$";

    private final FaceMemeService faceMemes;
    private final FaceStore store;

    public FaceMemeController(FaceMemeService faceMemes, FaceStore store) {
        this.faceMemes = faceMemes;
        this.store = store;
    }

    @Operation(summary = "Memes with your face, captions by the AI",
            description = "Your face is pasted onto the faces of photo templates and the AI writes the captions "
                    + "(1 AI call, text only: your photo is not sent to the AI). On templates with several people the "
                    + "AI also picks whose face becomes yours, to fit its joke. Send a new photo in 'face' "
                    + "(with confirmAdult and confirmConsent) or reuse an earlier one with 'faceId'.")
    @PostMapping(value = "/memes/with-face", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public FaceMemeBatch withAi(
            @Parameter(description = "Your face photo (JPEG/PNG, clear and front-facing). Optional if faceId is given.")
            @RequestPart(value = "face", required = false) MultipartFile face,
            @Parameter(description = "Id of a face uploaded before, instead of a new photo")
            @RequestParam(required = false) String faceId,
            @Parameter(description = "Required with a new photo: you are 18 or older")
            @RequestParam(defaultValue = "false") boolean confirmAdult,
            @Parameter(description = "Required with a new photo: it's your face, or you have the person's permission")
            @RequestParam(defaultValue = "false") boolean confirmConsent,
            @Parameter(description = "Optional: what the memes should be about", example = "Monday morning meetings")
            @RequestParam(required = false) @Size(max = 300) String topic,
            @Parameter(description = "Optional template names (must have face slots); otherwise the AI picks")
            @RequestParam(required = false) List<String> templates,
            @Parameter(description = "Caption language (default: English)")
            @RequestParam(required = false)
            @Pattern(regexp = MemeController.LANGUAGE_REGEX, message = MemeController.LANGUAGE_MESSAGE) String language,
            @Parameter(description = "How many memes (1-8)")
            @RequestParam(defaultValue = "4") @Min(1) @Max(8) int count,
            @Parameter(description = "Optional: on templates with several people, whose face to replace "
                    + "(e.g. padme; see 'characters' in GET /api/templates). Default: the AI picks per joke")
            @RequestParam(required = false) @Pattern(regexp = CHARACTER_REGEX) String character,
            @Parameter(description = "Optional: exact face slots to replace (1-based, see the template preview); "
                    + "overrides 'character'")
            @RequestParam(required = false) List<Integer> slots,
            @Parameter(description = "HEAD (default): your whole head (hair, ears, hat) cut out and pasted over the "
                    + "template's head, the classic meme look. FULL: your face warped in, reaching up the forehead. "
                    + "BOLD: your face shape and skin tone at the template face's size. NATURAL: your face melts into "
                    + "the template's face", example = "HEAD")
            @RequestParam(required = false) @Pattern(regexp = "(?i)^(HEAD|FULL|BOLD|NATURAL)$") String faceStyle) {
        return faceMemes.withAiCaptions(input(face, faceId, confirmAdult, confirmConsent),
                topic, templates, language, count, slots, character, FaceStyle.parse(faceStyle));
    }

    @Operation(summary = "Meme with your face and your own captions (no AI)",
            description = "Pastes your face onto one template and draws your texts, one per text box. "
                    + "Leave texts empty for just the face. 0 AI calls.")
    @PostMapping(value = "/memes/with-face/render", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public FaceMemeBatch withOwnCaptions(
            @RequestPart(value = "face", required = false) MultipartFile face,
            @RequestParam(required = false) String faceId,
            @RequestParam(defaultValue = "false") boolean confirmAdult,
            @RequestParam(defaultValue = "false") boolean confirmConsent,
            @Parameter(description = "Template name", example = "Hide the Pain Harold")
            @RequestParam @NotBlank String template,
            @Parameter(description = "Your captions, one per text box in order (optional)")
            @RequestParam(required = false) List<@Size(max = 200) String> texts,
            @Parameter(description = "Optional: on templates with several people, whose face to replace "
                    + "(default: the template's main character)", example = "padme")
            @RequestParam(required = false) @Pattern(regexp = CHARACTER_REGEX) String character,
            @Parameter(description = "Optional: exact face slots to replace (1-based); overrides 'character'")
            @RequestParam(required = false) List<Integer> slots,
            @Parameter(description = "HEAD (default): your whole head (hair, ears, hat) cut out and pasted over the "
                    + "template's head, the classic meme look. FULL: your face warped in, reaching up the forehead. "
                    + "BOLD: your face shape and skin tone at the template face's size. NATURAL: your face melts into "
                    + "the template's face", example = "HEAD")
            @RequestParam(required = false) @Pattern(regexp = "(?i)^(HEAD|FULL|BOLD|NATURAL)$") String faceStyle) {
        return faceMemes.withOwnCaptions(input(face, faceId, confirmAdult, confirmConsent), template, texts, slots,
                character, FaceStyle.parse(faceStyle));
    }

    @Operation(summary = "Memes with your face put in by AI, captions by the AI",
            description = "Like POST /api/memes/with-face, but an image-editing AI model puts your face in: your head "
                    + "is cut out of your photo here, then the AI fits it into each template face (pose, expression, "
                    + "light). This sends your cut-out head to the AI provider. AI calls: 1 for the captions plus 1 per "
                    + "replaced face (e.g. 2 on Anakin & Padme with the face on Anakin), each costs money with "
                    + "paid image models. Needs ai-gateway.image-model.")
    @PostMapping(value = "/memes/with-face/ai", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public FaceMemeBatch aiFaceWithAiCaptions(
            @Parameter(description = "Your face photo (JPEG/PNG, clear, whole head visible). Optional if faceId is given.")
            @RequestPart(value = "face", required = false) MultipartFile face,
            @Parameter(description = "Id of a face uploaded before, instead of a new photo")
            @RequestParam(required = false) String faceId,
            @Parameter(description = "Required with a new photo: you are 18 or older")
            @RequestParam(defaultValue = "false") boolean confirmAdult,
            @Parameter(description = "Required with a new photo: it's your face, or you have the person's permission")
            @RequestParam(defaultValue = "false") boolean confirmConsent,
            @Parameter(description = "Optional: what the memes should be about", example = "Monday morning meetings")
            @RequestParam(required = false) @Size(max = 300) String topic,
            @Parameter(description = "Optional template names (must have face slots); otherwise the AI picks")
            @RequestParam(required = false) List<String> templates,
            @Parameter(description = "Caption language (default: English)")
            @RequestParam(required = false)
            @Pattern(regexp = MemeController.LANGUAGE_REGEX, message = MemeController.LANGUAGE_MESSAGE) String language,
            @Parameter(description = "How many memes (1-8); each costs 1 image edit per replaced face")
            @RequestParam(defaultValue = "4") @Min(1) @Max(8) int count,
            @Parameter(description = "Optional: on templates with several people, whose face to replace")
            @RequestParam(required = false) @Pattern(regexp = CHARACTER_REGEX) String character,
            @Parameter(description = "Optional: exact face slots to replace (1-based); overrides 'character'")
            @RequestParam(required = false) List<Integer> slots) {
        return faceMemes.withAiCaptions(input(face, faceId, confirmAdult, confirmConsent),
                topic, templates, language, count, slots, character, FaceStyle.AI);
    }

    @Operation(summary = "Meme with your face put in by AI and your own captions",
            description = "Like POST /api/memes/with-face/render, but an image-editing AI model puts your face in "
                    + "(your cut-out head is sent to the AI provider). AI calls: 1 per replaced face.")
    @PostMapping(value = "/memes/with-face/ai/render", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public FaceMemeBatch aiFaceWithOwnCaptions(
            @RequestPart(value = "face", required = false) MultipartFile face,
            @RequestParam(required = false) String faceId,
            @RequestParam(defaultValue = "false") boolean confirmAdult,
            @RequestParam(defaultValue = "false") boolean confirmConsent,
            @Parameter(description = "Template name", example = "Hide the Pain Harold")
            @RequestParam @NotBlank String template,
            @Parameter(description = "Your captions, one per text box in order (optional)")
            @RequestParam(required = false) List<@Size(max = 200) String> texts,
            @Parameter(description = "Optional: on templates with several people, whose face to replace", example = "padme")
            @RequestParam(required = false) @Pattern(regexp = CHARACTER_REGEX) String character,
            @Parameter(description = "Optional: exact face slots to replace (1-based); overrides 'character'")
            @RequestParam(required = false) List<Integer> slots) {
        return faceMemes.withOwnCaptions(input(face, faceId, confirmAdult, confirmConsent), template, texts, slots,
                character, FaceStyle.AI);
    }

    @Operation(summary = "Download a stored face photo", description = "For the person who uploaded it (GDPR access).")
    @GetMapping("/faces/{id}")
    public ResponseEntity<byte[]> photo(@PathVariable String id) {
        return store.photo(id)
                .map(bytes -> ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).body(bytes))
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "Delete a face photo and every meme made from it", description = "GDPR erasure.")
    @DeleteMapping("/faces/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        return store.delete(id) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    private static FaceInput input(MultipartFile face, String faceId, boolean adult, boolean consent) {
        try {
            byte[] bytes = face == null || face.isEmpty() ? null : face.getBytes();
            return new FaceInput(bytes, faceId, adult, consent);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the upload", e);
        }
    }
}
