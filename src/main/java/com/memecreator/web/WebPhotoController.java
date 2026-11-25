package com.memecreator.web;

import com.memecreator.account.AccountPrincipal;
import com.memecreator.account.AccountService;
import com.memecreator.account.MemeSet;
import com.memecreator.account.PromptQuota;
import com.memecreator.account.UserAccount;
import com.memecreator.photo.Elaboration;
import com.memecreator.photo.PhotoMemeService;
import com.memecreator.photo.PhotoMemeService.PhotoMemeBatch;
import com.memecreator.security.GenerationLimiter;
import com.memecreator.security.WebProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Set;

/**
 * "Use your own photo" in the web app: people upload a photo and get memes made from it. With a topic the captions
 * are about it; without one the AI decides what is funny about the photo. Counts as one set toward the hourly limit.
 * The upload is kept in the set's folder only as long as needed (see MemeRetention: deleted after
 * meme.retention.keep-for unless one of its memes is a favorite) and is never used as a template.
 */
@Tag(name = "Web app", description = "Endpoints for the signed-in web app")
@Validated
@RestController
@RequestMapping("/api/web")
public class WebPhotoController {

    private static final Set<String> IMAGE_TYPES = Set.of("image/jpeg", "image/png", "image/gif", "image/bmp",
            "image/webp");

    private final PhotoMemeService photoMemes;
    private final GenerationLimiter limiter;
    private final AccountService accounts;
    private final WebProperties props;
    private final PromptQuota quota;

    public WebPhotoController(PhotoMemeService photoMemes, GenerationLimiter limiter, AccountService accounts,
                              WebProperties props, PromptQuota quota) {
        this.quota = quota;
        this.photoMemes = photoMemes;
        this.limiter = limiter;
        this.accounts = accounts;
        this.props = props;
    }

    @Operation(summary = "Make memes from my photo", description = "multipart/form-data: image (JPEG, PNG, GIF, BMP; "
            + "up to 10 MB), optional topic and language. Same answer as POST /api/web/memes.")
    @PostMapping(value = "/photo-memes", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public WebAppController.WebMemeSet create(
            @RequestPart("image") MultipartFile image,
            @RequestParam(required = false) @Size(max = 300) String topic,
            @RequestParam(required = false)
            @Pattern(regexp = MemeController.LANGUAGE_REGEX, message = MemeController.LANGUAGE_MESSAGE) String language,
            Authentication auth) {
        if (auth == null || !(auth.getPrincipal() instanceof AccountPrincipal principal)) {
            throw new InsufficientAuthenticationException("not signed in");
        }
        UserAccount account = accounts.requireCurrentTerms(principal.accountId());
        if (image == null || image.isEmpty()) {
            throw new IllegalArgumentException("Choose a photo to upload.");
        }
        if (image.getContentType() != null && !IMAGE_TYPES.contains(image.getContentType().toLowerCase())) {
            throw new IllegalArgumentException("That file isn't a photo. Upload a JPEG or PNG image.");
        }
        String userId = "account:" + account.getId();
        PromptQuota.Usage usage = quota.acquire(account.getId(), account.getPlan()); // 429 DAILY_LIMIT
        PhotoMemeBatch batch;
        try {
            limiter.acquire(userId);
            try {
                batch = photoMemes.create(image.getBytes(), topic, language, props.memesPerSet(),
                        Elaboration.NORMAL);
            } catch (IOException e) {
                limiter.release(userId);
                throw new UncheckedIOException("Could not read the upload", e);
            } catch (RuntimeException e) {
                limiter.release(userId); // a failed set doesn't count
                throw e;
            }
            accounts.recordMemeSet(account.getId(), batch.folder(),
                    batch.topic() == null ? "(photo) " + Path.of(batch.folder()).getFileName() : batch.topic(),
                    MemeSet.Source.WEB);
        } finally {
            quota.release(account.getId());
        }
        accounts.recordMemes(account.getId(), batch.memes().stream()
                .map(m -> new AccountService.MemeRecipe(m.url(), null, m.style().name(), m.texts())).toList());
        return new WebAppController.WebMemeSet(batch.topic() == null ? "" : batch.topic(), batch.language(),
                batch.memes().stream()
                        .map(m -> new WebAppController.WebMeme("Your photo", m.texts(), m.url()))
                        .toList(), usage.remaining());
    }
}
