package com.memecreator.web;

import com.memecreator.photo.Elaboration;
import com.memecreator.photo.PhotoMemeService;
import com.memecreator.photo.PhotoMemeService.PhotoMemeBatch;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;

@Tag(name = "Memes", description = "Generate AI-captioned memes from a topic")
@Validated
@RestController
@RequestMapping("/api")
public class PhotoMemeController {

    private final PhotoMemeService photoMemes;

    public PhotoMemeController(PhotoMemeService photoMemes) {
        this.photoMemes = photoMemes;
    }

    @Operation(summary = "Create memes from your own photo",
            description = "Upload a photo; the AI reads its people, expressions and vibe and writes captions. "
                    + "Without a topic it decides by itself what is funny about the photo. "
                    + "elaboration=NORMAL makes 1 AI call, HIGH makes 2 (detailed description, then captions).")
    @PostMapping(value = "/memes/from-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public PhotoMemeBatch fromImage(
            @Parameter(description = "The photo: JPEG or PNG (GIF/BMP also work), up to 10 MB")
            @RequestPart("image") MultipartFile image,
            @Parameter(description = "Optional: what the memes should be about. Leave empty to let the AI decide.",
                    example = "Monday morning meetings")
            @RequestParam(required = false) @Size(max = 300) String topic,
            @Parameter(description = "Caption language (default: English)", example = "English")
            @RequestParam(required = false)
            @Pattern(regexp = MemeController.LANGUAGE_REGEX, message = MemeController.LANGUAGE_MESSAGE) String language,
            @Parameter(description = "How many memes to make from the photo (1-8)")
            @RequestParam(defaultValue = "4") @Min(1) @Max(8) int count,
            @Parameter(description = "NORMAL = 1 AI call; HIGH = 2 AI calls, usually funnier")
            @RequestParam(defaultValue = "NORMAL") Elaboration elaboration) {
        if (image == null || image.isEmpty()) {
            throw new IllegalArgumentException("Upload a photo in the 'image' field");
        }
        try {
            return photoMemes.create(image.getBytes(), topic, language, count, elaboration);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the upload", e);
        }
    }
}
