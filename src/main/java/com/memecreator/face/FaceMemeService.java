package com.memecreator.face;

import com.memecreator.photo.ImagePreparer;
import com.memecreator.service.MemeService;
import com.memecreator.service.MemeService.Decorator;
import com.memecreator.service.MemeService.GeneratedMeme;
import com.memecreator.service.MemeService.MemeBatch;
import com.memecreator.service.MemeService.Strategy;
import com.memecreator.template.MemeTemplate;
import com.memecreator.template.TemplateLayout;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Memes with the user's face pasted onto the template's faces.
 * <ul>
 *   <li>{@link #withAiCaptions}: the AI writes the captions first (1 call, text only; the face never goes to
 *       the AI), then the face is pasted and the captions drawn.</li>
 *   <li>{@link #withOwnCaptions}: the user's own captions (or none), 0 AI calls.</li>
 * </ul>
 * With {@link FaceStyle#AI} an image-editing model replaces each face (one more AI call per face), and then the
 * user's cut-out head does go to the AI provider; with every other style the photo never leaves the server.
 * On templates with several people (Anakin and Padme, the distracted boyfriend...) the face goes on one character:
 * the {@code slots} the user chose, else the {@code character} they chose, else the one the AI picked to fit its
 * joke, else the template's default. The face photo is stored privately via {@link FaceStore} and can be reused
 * by its id.
 */
@Service
public class FaceMemeService {

    private final MemeService memes;
    private final FaceStore store;
    private final FaceDetector detector;
    private final MeshFaceSwapper swapper;
    private final ImagePreparer preparer;

    public FaceMemeService(MemeService memes, FaceStore store, FaceDetector detector, MeshFaceSwapper swapper,
                           ImagePreparer preparer) {
        this.memes = memes;
        this.store = store;
        this.detector = detector;
        this.swapper = swapper;
        this.preparer = preparer;
    }

    /** Either a new upload (with the user's confirmations) or the id of a face uploaded before. */
    public record FaceInput(byte[] upload, String faceId, boolean confirmedAdult, boolean confirmedConsent) {
    }

    /**
     * @param face     the character the user's face was put on (null on templates where everyone is the same person)
     * @param slots    the face slots that got the user's face (1-based)
     * @param warnings hints, e.g. that the photo is turned too far for one of the faces
     */
    public record FaceMeme(String template, List<String> texts, String url, String file, String face,
                           List<Integer> slots, List<String> warnings) {
    }

    public record FaceMemeBatch(String faceId, int aiCalls, String topic, String language, List<FaceMeme> memes) {
    }

    public FaceMemeBatch withAiCaptions(FaceInput input, String topic, List<String> templateNames, String language,
                                        int count, List<Integer> slots, String character, FaceStyle style) {
        Face face = loadFace(input);
        List<MemeTemplate> pool = memes.allTemplates().stream()
                .filter(t -> memes.layoutOf(t).hasFaces())
                .toList();
        String folder = newFolder();
        Map<String, Placement> placed = new ConcurrentHashMap<>();
        MemeBatch batch = memes.create(topic, templateNames, Strategy.AI_PICK, language, pool,
                paste(face, slots, character, style, false, placed), true, folder, count);
        store.addMemeFolder(face.id(), folder);
        return new FaceMemeBatch(face.id(), 1 + imageCalls(placed), batch.topic(), batch.language(),
                withPlacements(batch.memes(), placed));
    }

    public FaceMemeBatch withOwnCaptions(FaceInput input, String templateName, List<String> texts, List<Integer> slots,
                                         String character, FaceStyle style) {
        TemplateLayout layout = memes.layoutOf(templateName);
        if (!layout.hasFaces()) {
            throw new IllegalArgumentException("Template \"" + templateName + "\" has no face slots; "
                    + "GET /api/templates lists the templates with faces");
        }
        if (character != null && !character.isBlank() && (slots == null || slots.isEmpty())
                && layout.findCharacter(character).isEmpty()) {
            throw new IllegalArgumentException(layout.hasCharacters()
                    ? "Template \"" + templateName + "\" has no character \"" + character + "\"; choose one of "
                            + layout.characters().keySet()
                    : "Template \"" + templateName + "\" has only one person; leave 'character' out");
        }
        Face face = loadFace(input);
        String folder = newFolder();
        Map<String, Placement> placed = new ConcurrentHashMap<>();
        GeneratedMeme meme = memes.renderManual(templateName, texts, paste(face, slots, character, style, true, placed), folder);
        store.addMemeFolder(face.id(), folder);
        return new FaceMemeBatch(face.id(), imageCalls(placed), null, null, withPlacements(List.of(meme), placed));
    }

    private record Face(String id, BufferedImage image, DetectedFace landmarks) {
    }

    /** Where the face went on one template. */
    private record Placement(String character, List<Integer> slots, List<String> warnings, int aiCalls) {
    }

    private Face loadFace(FaceInput in) {
        String id;
        byte[] jpeg;
        if (in.upload() != null && in.upload().length > 0) {
            if (!in.confirmedAdult() || !in.confirmedConsent()) {
                throw new IllegalArgumentException("To upload a face, confirm that you are 18 or older "
                        + "(confirmAdult=true) and that this is your face or you have the person's permission "
                        + "(confirmConsent=true)");
            }
            // decode, check and shrink; re-encoding as JPEG also strips EXIF data such as GPS location
            jpeg = ImagePreparer.toJpeg(preparer.prepare(in.upload()).forRendering());
            Face probe = detect(null, jpeg);   // refuse photos without a face before storing anything
            id = store.save(jpeg, true, true).id();
            return new Face(id, probe.image(), probe.landmarks());
        }
        if (in.faceId() == null || in.faceId().isBlank()) {
            throw new IllegalArgumentException("Send a face photo in 'face', or the 'faceId' of one uploaded before");
        }
        id = in.faceId().strip();
        jpeg = store.photo(id).orElseThrow(() -> new IllegalArgumentException("Unknown or deleted faceId: " + in.faceId()));
        return detect(id, jpeg);
    }

    private Face detect(String id, byte[] jpeg) {
        BufferedImage img;
        try {
            img = ImageIO.read(new ByteArrayInputStream(jpeg));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<DetectedFace> faces = detector.detect(img);
        if (faces.isEmpty()) {
            throw new IllegalArgumentException("No face found in the photo. Use a clear, front-facing photo "
                    + "with the whole face visible.");
        }
        return new Face(id, img, faces.get(0));   // the biggest face
    }

    /**
     * Pastes the face on the chosen slots and remembers where it went.
     *
     * @param strict true when the user picked the template: an unknown character is an error, not a fallback
     */
    private Decorator paste(Face face, List<Integer> slots, String character, FaceStyle style, boolean strict,
                            Map<String, Placement> placed) {
        return (template, image, caption) -> {
            TemplateLayout layout = memes.layoutOf(template);
            List<String> warnings = new ArrayList<>();
            String who;
            List<Integer> chosen;
            if (slots != null && !slots.isEmpty()) {
                chosen = slots.stream().distinct().filter(n -> n >= 1 && n <= layout.faces().size()).toList();
                if (chosen.isEmpty()) {
                    throw new IllegalArgumentException("Template \"" + template.name() + "\" has face slots 1-"
                            + layout.faces().size() + "; none of " + slots + " exist");
                }
                who = sameCharacter(layout, chosen);
            } else {
                who = chooseCharacter(layout, character, caption.face(), strict, warnings);
                chosen = layout.slotsOf(who);
            }
            List<DetectedFace> targets = chosen.stream()
                    .map(n -> DetectedFace.fromSlot(layout.faces().get(n - 1), image.getWidth(), image.getHeight()))
                    .toList();
            MeshFaceSwapper.Result result = swapper.paste(image, targets, chosen, face.image(), face.landmarks(),
                    style == null ? FaceStyle.HEAD : style);
            warnings.addAll(result.warnings());
            placed.put(template.name(), new Placement(who, chosen, List.copyOf(warnings), result.aiCalls()));
            return result.image();
        };
    }

    /** The user's choice, else the AI's, else the template's default; null when the template has no characters. */
    private static String chooseCharacter(TemplateLayout layout, String requested, String aiChoice, boolean strict,
                                          List<String> warnings) {
        if (!layout.hasCharacters()) {
            return null;
        }
        if (requested != null && !requested.isBlank()) {
            Optional<String> c = layout.findCharacter(requested);
            if (c.isPresent()) {
                return c.get();
            }
            if (strict) {
                throw new IllegalArgumentException("No character \"" + requested + "\"; choose one of "
                        + layout.characters().keySet());
            }
            warnings.add("This template has no character \"" + requested + "\"; used "
                    + layout.findCharacter(aiChoice).orElse(layout.defaultCharacter()) + " instead.");
        }
        return layout.findCharacter(aiChoice).orElse(layout.defaultCharacter());
    }

    private static String sameCharacter(TemplateLayout layout, List<Integer> chosen) {
        List<String> who = chosen.stream().map(n -> layout.faces().get(n - 1).character()).distinct().toList();
        return who.size() == 1 ? who.get(0) : null;
    }

    private static List<FaceMeme> withPlacements(List<GeneratedMeme> generated, Map<String, Placement> placed) {
        return generated.stream().map(m -> {
            Placement p = placed.getOrDefault(m.template(), new Placement(null, List.of(), List.of(), 0));
            return new FaceMeme(m.template(), m.texts(), m.url(), m.file(), p.character(), p.slots(), p.warnings());
        }).toList();
    }

    /** AI image edits made (FaceStyle.AI: one per replaced face). */
    private static int imageCalls(Map<String, Placement> placed) {
        return placed.values().stream().mapToInt(Placement::aiCalls).sum();
    }

    /** Random folder name, so meme URLs with someone's face can't be guessed. */
    private static String newFolder() {
        return "face-" + UUID.randomUUID();
    }
}
