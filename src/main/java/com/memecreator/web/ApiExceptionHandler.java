package com.memecreator.web;

import com.memecreator.account.AccountService;
import com.memecreator.account.ApiQuota;
import com.memecreator.account.MemeLibrary;
import com.memecreator.account.PromptQuota;
import com.memecreator.account.UserApiKeyService;
import com.memecreator.ai.AiGatewayException;
import com.memecreator.security.GenerationLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** What people see when the AI gateway (aiproxy) or the model behind it fails. */
    static final String AI_UNAVAILABLE_MESSAGE = "The server isn't responding right now. Please try again later.";

    /**
     * The AI gateway failed: unreachable, timed out, an error from the provider, an unusable answer. People using
     * the site or the public API get a plain 503 AI_UNAVAILABLE (the details only go to the log); the developer
     * and admin APIs keep the technical 502 so problems can be debugged.
     */
    @ExceptionHandler(AiGatewayException.class)
    ResponseEntity<ProblemDetail> aiGateway(AiGatewayException e, HttpServletRequest request) {
        log.warn("AI gateway problem on {}: {}", request.getRequestURI(), e.getMessage());
        String path = request.getRequestURI();
        if (path.startsWith("/api/web/") || path.startsWith("/api/v1/")) {
            ProblemDetail pd = problem(HttpStatus.SERVICE_UNAVAILABLE, "Not responding", AI_UNAVAILABLE_MESSAGE);
            pd.setProperty("code", "AI_UNAVAILABLE");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .header(HttpHeaders.RETRY_AFTER, "60")
                    .body(pd);
        }
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(problem(HttpStatus.BAD_GATEWAY, "AI gateway error", e.getMessage()));
    }

    /** The web app's daily prompts are used up (meme.web.free-prompts-per-day). */
    @ExceptionHandler(PromptQuota.PromptLimitException.class)
    ResponseEntity<ProblemDetail> promptLimit(PromptQuota.PromptLimitException e) {
        ProblemDetail pd = problem(HttpStatus.TOO_MANY_REQUESTS, "Daily limit reached", e.getMessage());
        pd.setProperty("code", "DAILY_LIMIT");
        pd.setProperty("plan", e.usage().plan());
        pd.setProperty("limit", e.usage().limit());
        pd.setProperty("resetsAt", e.usage().resetsAt().toString());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(Math.max(1, e.retryAfter().toSeconds())))
                .body(pd);
    }

    @ExceptionHandler({IllegalArgumentException.class, ConstraintViolationException.class})
    ProblemDetail badRequest(RuntimeException e) {
        return problem(HttpStatus.BAD_REQUEST, "Bad request", e.getMessage());
    }

    @ExceptionHandler(AccountService.TermsNotAcceptedException.class)
    ProblemDetail termsNotAccepted(AccountService.TermsNotAcceptedException e) {
        ProblemDetail pd = problem(HttpStatus.FORBIDDEN, "Terms not accepted", e.getMessage());
        pd.setProperty("code", "TERMS_NOT_ACCEPTED");
        return pd;
    }

    @ExceptionHandler(MemeLibrary.MemeNotFoundException.class)
    ProblemDetail memeNotFound(MemeLibrary.MemeNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Meme not found", e.getMessage());
    }

    @ExceptionHandler({MemeLibrary.TooManyFavoritesException.class, UserApiKeyService.TooManyKeysException.class})
    ProblemDetail tooMany(RuntimeException e) {
        return problem(HttpStatus.CONFLICT, "Limit reached", e.getMessage());
    }

    @ExceptionHandler(ApiQuota.QuotaExceededException.class)
    ResponseEntity<ProblemDetail> quota(ApiQuota.QuotaExceededException e) {
        ProblemDetail pd = problem(HttpStatus.TOO_MANY_REQUESTS, "Daily limit reached", e.getMessage());
        pd.setProperty("code", "DAILY_LIMIT");
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(Math.max(1, e.retryAfter().toSeconds())))
                .header(PublicApiController.LIMIT_HEADER, Integer.toString(e.usage().limit()))
                .header(PublicApiController.REMAINING_HEADER, "0")
                .body(pd);
    }

    /** Too many sets this hour (the web app's generator and photo memes). */
    @ExceptionHandler(GenerationLimiter.LimitReachedException.class)
    ResponseEntity<ProblemDetail> limitReached(GenerationLimiter.LimitReachedException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
        pd.setTitle("Hourly limit reached");
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(e.retryAfter().toSeconds()))
                .body(pd);
    }

    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail illegalState(IllegalStateException e) {
        log.error("Server misconfiguration", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Server error", e.getMessage());
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setTitle(title);
        return pd;
    }
}
