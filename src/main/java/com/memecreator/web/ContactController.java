package com.memecreator.web;

import com.memecreator.account.AccountPrincipal;
import com.memecreator.account.Throttle;
import com.memecreator.contact.ContactService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The "Contact us" form. Public (signed-out people can write too); needs the CSRF token; 5 messages an hour. */
@Tag(name = "Contact", description = "The contact form")
@RestController
@RequestMapping("/api/web/contact")
public class ContactController {

    private final ContactService contact;

    public ContactController(ContactService contact) {
        this.contact = contact;
    }

    /** @param website a field people never see: filled in only by spam bots */
    public record ContactRequest(@NotBlank @Email @Size(max = 320) String email,
                                 @NotBlank @Size(max = 150) String subject,
                                 @NotBlank @Size(min = 5, max = 5000) String message,
                                 @Size(max = 200) String website) {
    }

    public record Sent(String message) {
    }

    @Operation(summary = "Send a message to us", description = "202. We answer by email.")
    @PostMapping
    public ResponseEntity<Sent> send(@Valid @RequestBody ContactRequest r, Authentication auth,
                                     HttpServletRequest request) {
        if (r.website() == null || r.website().isBlank()) {
            Long accountId = auth != null && auth.getPrincipal() instanceof AccountPrincipal p ? p.accountId() : null;
            contact.send(r.email(), r.subject(), r.message(), request.getRemoteAddr(), accountId);
        }
        // Bots get the same answer, so they don't learn they were spotted
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new Sent("Thanks! Your message was sent. We'll answer at " + r.email().strip() + "."));
    }

    @ExceptionHandler(Throttle.TooManyAttemptsException.class)
    ResponseEntity<ProblemDetail> tooMany(Throttle.TooManyAttemptsException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS,
                "You've sent several messages already. " + e.getMessage());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(Math.max(1, e.retryAfter().toSeconds())))
                .body(pd);
    }

    @ExceptionHandler(ContactService.NotSentException.class)
    ResponseEntity<ProblemDetail> notSent(ContactService.NotSentException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage()));
    }
}
