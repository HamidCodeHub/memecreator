package com.memecreator.web;

import com.memecreator.account.AccountPrincipal;
import com.memecreator.account.Throttle;
import com.memecreator.edit.MemeEditor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Changing the words of one of my memes: what can be changed, a preview, and saving it as a new meme. */
@Tag(name = "Web app", description = "Endpoints for the signed-in web app")
@RestController
@RequestMapping("/api/web/edits")
public class EditController {

    private final MemeEditor editor;

    public EditController(MemeEditor editor) {
        this.editor = editor;
    }

    private static long accountId(Authentication auth) {
        if (auth == null || !(auth.getPrincipal() instanceof AccountPrincipal p)) {
            throw new InsufficientAuthenticationException("not signed in");
        }
        return p.accountId();
    }

    /** @param url the meme as the app shows it ("/generated/...") */
    public record EditRequest(@NotBlank @Size(max = 600) String url,
                              @Size(max = 10) List<@Size(max = 400) String> texts) {
    }

    @Operation(summary = "What can be changed in a meme", description = "Its texts with a label each, and the AI's "
            + "version. 404 for memes made before editing existed.")
    @GetMapping
    public MemeEditor.EditInfo info(@RequestParam @Size(max = 600) String url, Authentication auth) {
        return editor.info(accountId(auth), url);
    }

    @Operation(summary = "Preview a meme with other words", description = "JPEG; nothing is saved.")
    @PostMapping(value = "/preview", produces = MediaType.IMAGE_JPEG_VALUE)
    public ResponseEntity<byte[]> preview(@Valid @RequestBody EditRequest r, Authentication auth) {
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .cacheControl(CacheControl.noStore())
                .body(editor.preview(accountId(auth), r.url(), r.texts()));
    }

    @Operation(summary = "Save a meme with other words", description = "Saved as a new meme next to the original, "
            + "which stays as it is (links already shared don't change). Answers the new meme.")
    @PostMapping
    public MemeEditor.Edited save(@Valid @RequestBody EditRequest r, Authentication auth) {
        return editor.save(accountId(auth), r.url(), r.texts());
    }

    @ExceptionHandler(MemeEditor.NotEditableException.class)
    ResponseEntity<ProblemDetail> notEditable(MemeEditor.NotEditableException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        pd.setProperty("code", "NOT_EDITABLE");
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(pd);
    }

    @ExceptionHandler(Throttle.TooManyAttemptsException.class)
    ResponseEntity<ProblemDetail> tooMany(Throttle.TooManyAttemptsException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(Math.max(1, e.retryAfter().toSeconds())))
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS,
                        "You've edited a lot this hour. " + e.getMessage()));
    }
}
