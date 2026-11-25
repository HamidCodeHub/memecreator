package com.memecreator.web;

import com.memecreator.account.AccountPrincipal;
import com.memecreator.account.AccountService;
import com.memecreator.account.MemeSet;
import com.memecreator.account.PromptQuota;
import com.memecreator.account.UserAccount;
import com.memecreator.security.GenerationLimiter;
import com.memecreator.security.WebProperties;
import com.memecreator.security.WebUser;
import com.memecreator.service.MemeService;
import com.memecreator.service.MemeService.MemeBatch;
import com.memecreator.service.MemeService.Strategy;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The signed-in web app's API (the memecreator-fe repository). Everything except /providers and /legal needs a signed-in user. */
@Tag(name = "Web app", description = "Endpoints for the signed-in React app")
@RestController
@RequestMapping("/api/web")
public class WebAppController {

    /** Placeholder client id in application.yml; such providers are shown as "not set up yet". */
    static final String NOT_CONFIGURED = "not-configured";
    private static final Map<String, String> LABELS = Map.of("google", "Google", "facebook", "Facebook");

    private final MemeService memes;
    private final GenerationLimiter limiter;
    private final WebProperties props;
    private final ObjectProvider<InMemoryClientRegistrationRepository> registrations;
    private final AccountService accounts;
    private final PromptQuota quota;

    public WebAppController(MemeService memes, GenerationLimiter limiter, WebProperties props,
                            ObjectProvider<InMemoryClientRegistrationRepository> registrations,
                            AccountService accounts, PromptQuota quota) {
        this.quota = quota;
        this.memes = memes;
        this.limiter = limiter;
        this.props = props;
        this.registrations = registrations;
        this.accounts = accounts;
    }

    /** The signed-in person's account; 401 when the session is not an account sign-in or the account is gone. */
    private WebUser currentUser(Authentication auth) {
        if (auth == null || !(auth.getPrincipal() instanceof AccountPrincipal principal)) {
            throw new InsufficientAuthenticationException("not signed in");
        }
        return accounts.find(principal.accountId())
                .map(a -> WebUser.of(principal, a, accounts.currentTermsVersion()))
                .orElseThrow(() -> new InsufficientAuthenticationException("account no longer exists"));
    }

    private static long accountId(WebUser user) {
        return Long.parseLong(user.id().substring("account:".length()));
    }


    /** @param loginUrl where the browser goes to sign in with this provider */
    public record Provider(String id, String label, boolean configured, String loginUrl) {
    }

    @Operation(summary = "Sign-in options", description = "Public. Providers without credentials have configured=false.")
    @GetMapping("/providers")
    public List<Provider> providers() {
        List<Provider> out = new ArrayList<>();
        InMemoryClientRegistrationRepository repo = registrations.getIfAvailable();
        if (repo != null) {
            for (ClientRegistration r : repo) {
                String id = r.getRegistrationId();
                out.add(new Provider(id, LABELS.getOrDefault(id, r.getClientName()),
                        !NOT_CONFIGURED.equals(r.getClientId()), "/oauth2/authorization/" + id));
            }
        }
        out.sort((a, b) -> a.id().compareTo(b.id()));
        return out;
    }

    @Operation(summary = "The signed-in user", description = "401 when not signed in.")
    @GetMapping("/me")
    public WebUser me(Authentication auth) {
        return currentUser(auth);
    }

    /** @param template optional: a template id from GET /api/web/templates; all memes then use it */
    public record WebMemeRequest(
            @NotBlank @Size(max = 300) String topic,
            @Pattern(regexp = MemeController.LANGUAGE_REGEX, message = MemeController.LANGUAGE_MESSAGE) String language,
            @Size(max = 200) String template) {

        /** Without a template (older clients, the public API's original shape). */
        public WebMemeRequest(String topic, String language) {
            this(topic, language, null);
        }
    }

    /** One meme as the browser needs it; no server paths. @param texts the captions, for alt text */
    public record WebMeme(String template, List<String> texts, String url) {
    }

    /** @param promptsLeft prompts left today after this one; null when the plan has no daily limit */
    public record WebMemeSet(String topic, String language, List<WebMeme> memes, Integer promptsLeft) {

        public WebMemeSet(String topic, String language, List<WebMeme> memes) {
            this(topic, language, memes, null);
        }
    }

    /**
     * The signed-in person's plan and today's prompts.
     *
     * @param promptsPerDay    this plan's daily prompts; 0 = no limit
     * @param remainingToday   null when there is no limit
     * @param upgradeAvailable false while Premium can't be bought (payments are not open yet)
     */
    public record PlanView(UserAccount.Plan plan, int promptsPerDay, int usedToday, Integer remainingToday,
                           Instant resetsAt, int freePromptsPerDay, int premiumPromptsPerDay,
                           boolean upgradeAvailable) {
    }

    @Operation(summary = "My plan and today's prompts", description = "FREE or PREMIUM, the daily prompt limit "
            + "(meme.web.free-prompts-per-day / premium-prompts-per-day, 0 = none) and what's left today.")
    @GetMapping("/plan")
    public PlanView plan(Authentication auth) {
        WebUser user = currentUser(auth);
        long accountId = accountId(user);
        PromptQuota.Usage u = quota.usage(accountId, planOf(accountId));
        return new PlanView(u.plan(), u.limit(), u.used(), u.remaining(), u.resetsAt(),
                quota.limitFor(UserAccount.Plan.FREE), quota.limitFor(UserAccount.Plan.PREMIUM), false);
    }

    private UserAccount.Plan planOf(long accountId) {
        return accounts.find(accountId).map(UserAccount::getPlan).orElse(UserAccount.Plan.FREE);
    }

    @Operation(summary = "Make a set of memes", description = "meme.web.memes-per-set memes (6) about the topic; "
            + "the AI picks the templates. Each set is one prompt: meme.web.free-prompts-per-day per day on the "
            + "FREE plan (429 DAILY_LIMIT after that), and at most meme.web.max-sets-per-hour per hour.")
    @PostMapping("/memes")
    public WebMemeSet create(@Valid @RequestBody WebMemeRequest request, Authentication auth) {
        WebUser user = currentUser(auth);
        if (!user.termsAccepted()) {
            throw new AccountService.TermsNotAcceptedException();
        }
        String userId = user.id();
        long accountId = accountId(user);
        PromptQuota.Usage usage = quota.acquire(accountId, planOf(accountId)); // 429 DAILY_LIMIT when used up
        MemeBatch batch;
        try {
            limiter.acquire(userId);
            try {
                batch = request.template() == null || request.template().isBlank()
                        ? memes.create(request.topic(), null, Strategy.AI_PICK, request.language(),
                        props.memesPerSet())
                        : memes.createOnTemplate(request.topic(), request.template().strip(), request.language(),
                        props.memesPerSet());
            } catch (RuntimeException e) {
                limiter.release(userId); // a failed set doesn't count
                throw e;
            }
            accounts.recordMemeSet(accountId, batch.folder(), batch.topic(), MemeSet.Source.WEB);
        } finally {
            quota.release(accountId); // a recorded set is now counted from the database
        }
        accounts.recordMemes(accountId, batch.memes().stream()
                .map(m -> new AccountService.MemeRecipe(m.url(), m.template(), null, m.texts())).toList());
        return new WebMemeSet(batch.topic(), batch.language(), batch.memes().stream()
                .map(m -> new WebMeme(m.template(), m.texts(), m.url()))
                .toList(), usage.remaining());
    }

    // ---- terms and account ------------------------------------------------------------------------------------

    /** @param termsVersion current version of terms.html / privacy.html */
    public record Legal(String termsVersion, String termsUrl, String privacyUrl, String deletionUrl) {
    }

    @Operation(summary = "Current legal documents", description = "Public.")
    @GetMapping("/legal")
    public Legal legal() {
        return new Legal(accounts.currentTermsVersion(), "/terms.html", "/privacy.html", "/data-deletion.html");
    }

    public record AcceptTermsRequest(@NotBlank @Size(max = 40) String version) {
    }

    @Operation(summary = "Accept the Terms of use and Privacy policy",
            description = "The version shown to the person; 409 if it is no longer the current one.")
    @PostMapping("/terms/accept")
    public WebUser acceptTerms(@Valid @RequestBody AcceptTermsRequest r, Authentication auth) {
        WebUser user = currentUser(auth);
        accounts.acceptTerms(accountId(user), r.version());
        return currentUser(auth);
    }

    /** @param confirm must be "DELETE": the person typed it */
    public record DeleteAccountRequest(@NotBlank @Pattern(regexp = "DELETE", message = "Type DELETE to confirm.")
                                       String confirm) {
    }

    @Operation(summary = "Delete my account", description = "Deletes the account, its sign-in links and the memes "
            + "it made, then signs out. Can't be undone. 204.")
    @DeleteMapping("/account")
    public ResponseEntity<Void> deleteAccount(@Valid @RequestBody DeleteAccountRequest r, Authentication auth,
                                              HttpServletRequest request) {
        WebUser user = currentUser(auth);
        accounts.deleteAccount(accountId(user));
        SecurityContextHolder.clearContext();
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(AccountService.TermsVersionMismatchException.class)
    ResponseEntity<ProblemDetail> termsChanged(AccountService.TermsVersionMismatchException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        pd.setProperty("code", "TERMS_VERSION_CHANGED");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(pd);
    }

    @ExceptionHandler(InsufficientAuthenticationException.class)
    ResponseEntity<ProblemDetail> notSignedIn() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Sign in first."));
    }
}
