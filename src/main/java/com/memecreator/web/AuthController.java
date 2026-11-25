package com.memecreator.web;

import com.memecreator.account.AccountService;
import com.memecreator.account.AccountService.InvalidLinkException;
import com.memecreator.account.AccountUserDetails;
import com.memecreator.account.Throttle;
import com.memecreator.account.UserAccount;
import com.memecreator.security.WebUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Locale;

/**
 * Email + password accounts for the web app: sign up, confirm the email, sign in, forgot / reset password.
 * Public (no sign-in needed); every call needs the CSRF token. Google/Facebook sign-in doesn't go through here.
 */
@Tag(name = "Web app: accounts", description = "Email + password sign-up and sign-in")
@RestController
@RequestMapping("/api/web/auth")
public class AuthController {

    static final String CHECK_INBOX = "If the address can be used, an email is on its way. Check your inbox (and spam).";
    private static final Duration HOUR = Duration.ofHours(1);
    private static final Duration QUARTER = Duration.ofMinutes(15);

    private final AccountService accounts;
    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository contextRepository;
    private final Throttle throttle;

    public AuthController(AccountService accounts, AuthenticationManager authenticationManager,
                          SecurityContextRepository contextRepository, Throttle throttle) {
        this.accounts = accounts;
        this.authenticationManager = authenticationManager;
        this.contextRepository = contextRepository;
        this.throttle = throttle;
    }

    /** @param termsVersion the Terms of use / Privacy policy version the person ticked "I accept" for */
    public record SignUpRequest(@NotBlank @Size(max = 320) String email, @NotBlank @Size(max = 200) String password,
                                @Size(max = 120) String name, @NotBlank @Size(max = 40) String termsVersion) {
    }

    public record SignInRequest(@NotBlank @Size(max = 320) String email, @NotBlank @Size(max = 200) String password) {
    }

    public record EmailRequest(@NotBlank @Size(max = 320) String email) {
    }

    public record TokenRequest(@NotBlank @Size(max = 100) String token) {
    }

    public record ResetRequest(@NotBlank @Size(max = 100) String token, @NotBlank @Size(max = 200) String password) {
    }

    public record Message(String message) {
    }

    @Operation(summary = "Create an account", description = "Always 202: a confirmation link is emailed (or, for an "
            + "address that already has an account, a note saying so). Without SMTP settings the email is logged.")
    @PostMapping("/signup")
    public ResponseEntity<Message> signUp(@Valid @RequestBody SignUpRequest r, HttpServletRequest request) {
        String email = AccountService.normalizeEmail(r.email());
        throttle.acquire("signup-ip:" + request.getRemoteAddr(), 10, HOUR);
        throttle.acquire("mail:" + email, 3, HOUR);
        accounts.signUp(email, r.password(), r.name(), r.termsVersion());
        return ResponseEntity.accepted().body(new Message(CHECK_INBOX));
    }

    @Operation(summary = "Confirm the email", description = "The token from the emailed link. Signs the person in.")
    @PostMapping("/verify")
    public WebUser verify(@Valid @RequestBody TokenRequest r, HttpServletRequest request,
                          HttpServletResponse response) {
        throttle.acquire("link-ip:" + request.getRemoteAddr(), 30, HOUR);
        return signInAs(accounts.confirmEmail(r.token()), request, response);
    }

    @Operation(summary = "Send the confirmation link again", description = "Always 202.")
    @PostMapping("/resend")
    public ResponseEntity<Message> resend(@Valid @RequestBody EmailRequest r, HttpServletRequest request) {
        String email = AccountService.normalizeEmail(r.email());
        throttle.acquire("mail-ip:" + request.getRemoteAddr(), 10, HOUR);
        throttle.acquire("mail:" + email, 3, HOUR);
        accounts.resendConfirmation(email);
        return ResponseEntity.accepted().body(new Message(CHECK_INBOX));
    }

    @Operation(summary = "Sign in with email + password",
            description = "401 wrong email or password; 403 with code EMAIL_NOT_CONFIRMED; 429 after too many tries.")
    @PostMapping("/login")
    public WebUser signIn(@Valid @RequestBody SignInRequest r, HttpServletRequest request,
                          HttpServletResponse response) {
        String email = r.email().trim().toLowerCase(Locale.ROOT);
        String byEmail = "login-fail:" + email;
        String byIp = "login-fail-ip:" + request.getRemoteAddr();
        throttle.check(byEmail, 10, QUARTER);
        throttle.check(byIp, 50, QUARTER);
        Authentication auth;
        try {
            auth = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(email, r.password()));
        } catch (DisabledException e) {
            throw e; // right password, email not confirmed yet
        } catch (AuthenticationException e) {
            throttle.record(byEmail);
            throttle.record(byIp);
            throw new BadCredentialsException("Wrong email or password.");
        }
        throttle.reset(byEmail);
        AccountUserDetails principal = (AccountUserDetails) auth.getPrincipal();
        UserAccount account = accounts.find(principal.accountId()).orElseThrow();
        startSession(auth, request, response);
        return WebUser.of(principal, account, accounts.currentTermsVersion());
    }

    @Operation(summary = "Forgot password", description = "Always 202: a reset link is emailed if the address has "
            + "an account.")
    @PostMapping("/forgot")
    public ResponseEntity<Message> forgot(@Valid @RequestBody EmailRequest r, HttpServletRequest request) {
        String email = AccountService.normalizeEmail(r.email());
        throttle.acquire("mail-ip:" + request.getRemoteAddr(), 10, HOUR);
        throttle.acquire("mail:" + email, 3, HOUR);
        accounts.requestPasswordReset(email);
        return ResponseEntity.accepted().body(new Message(CHECK_INBOX));
    }

    @Operation(summary = "Choose a new password", description = "The token from the emailed link. Signs the person in.")
    @PostMapping("/reset")
    public WebUser reset(@Valid @RequestBody ResetRequest r, HttpServletRequest request,
                         HttpServletResponse response) {
        throttle.acquire("link-ip:" + request.getRemoteAddr(), 30, HOUR);
        return signInAs(accounts.resetPassword(r.token(), r.password()), request, response);
    }

    private WebUser signInAs(UserAccount account, HttpServletRequest request, HttpServletResponse response) {
        AccountUserDetails principal = accounts.userDetails(account);
        startSession(UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()),
                request, response);
        return WebUser.of(principal, account, accounts.currentTermsVersion());
    }

    /** Stores the sign-in in the session, under a new session id (no session fixation). */
    private void startSession(Authentication auth, HttpServletRequest request, HttpServletResponse response) {
        if (request.getSession(false) != null) {
            request.changeSessionId();
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);
    }

    // ---- errors -----------------------------------------------------------------------------------------------

    @ExceptionHandler(DisabledException.class)
    ResponseEntity<ProblemDetail> notConfirmed() {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN,
                "Confirm your email first: open the link we sent you.");
        pd.setProperty("code", "EMAIL_NOT_CONFIRMED");
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(pd);
    }

    @ExceptionHandler(BadCredentialsException.class)
    ResponseEntity<ProblemDetail> badCredentials() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Wrong email or password."));
    }

    @ExceptionHandler(InvalidLinkException.class)
    ResponseEntity<ProblemDetail> invalidLink(InvalidLinkException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        pd.setProperty("code", "INVALID_LINK");
        return ResponseEntity.badRequest().body(pd);
    }

    @ExceptionHandler(AccountService.TermsVersionMismatchException.class)
    ResponseEntity<ProblemDetail> termsChanged(AccountService.TermsVersionMismatchException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        pd.setProperty("code", "TERMS_VERSION_CHANGED");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(pd);
    }

    @ExceptionHandler(Throttle.TooManyAttemptsException.class)
    ResponseEntity<ProblemDetail> tooMany(Throttle.TooManyAttemptsException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(e.retryAfter().toSeconds()))
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, e.getMessage()));
    }
}
