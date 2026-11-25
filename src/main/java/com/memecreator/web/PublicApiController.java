package com.memecreator.web;

import com.memecreator.account.AccountService;
import com.memecreator.account.ApiQuota;
import com.memecreator.account.MemeSet;
import com.memecreator.account.UserAccount;
import com.memecreator.security.UserApiKeyAuthenticator.ApiUser;
import com.memecreator.security.WebProperties;
import com.memecreator.service.MemeService;
import com.memecreator.service.MemeService.MemeBatch;
import com.memecreator.service.MemeService.Strategy;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The API people call with their personal key (X-API-Key: mcu_...), created on their profile page. Free accounts
 * can make meme.api.free-sets-per-day sets a day (5). Images: GET the returned url with the same header.
 */
@Tag(name = "Public API", description = "Personal API keys (X-API-Key: mcu_...). Daily limit per account.")
@RestController
@RequestMapping("/api/v1")
public class PublicApiController {

    static final String LIMIT_HEADER = "X-RateLimit-Limit";
    static final String REMAINING_HEADER = "X-RateLimit-Remaining";

    private final MemeService memes;
    private final AccountService accounts;
    private final ApiQuota quota;
    private final WebProperties web;

    public PublicApiController(MemeService memes, AccountService accounts, ApiQuota quota, WebProperties web) {
        this.memes = memes;
        this.accounts = accounts;
        this.quota = quota;
        this.web = web;
    }

    private static ApiUser caller(Authentication auth) {
        if (auth == null || !(auth.getPrincipal() instanceof ApiUser user)) {
            throw new InsufficientAuthenticationException("personal API key needed");
        }
        return user;
    }

    @Operation(summary = "Make a set of memes", description = "Same as the website: 6 memes, the AI picks the "
            + "templates, or all on one \"template\" (an id from the templates page). Counts toward the daily "
            + "limit (429 after it; X-RateLimit-* headers show what's left).")
    @PostMapping("/memes")
    public ResponseEntity<WebAppController.WebMemeSet> create(
            @Valid @RequestBody WebAppController.WebMemeRequest request, Authentication auth) {
        ApiUser user = caller(auth);
        UserAccount account = accounts.requireCurrentTerms(user.accountId());
        ApiQuota.Usage usage = quota.acquire(account.getId(), account.getPlan());
        try {
            MemeBatch batch = request.template() == null || request.template().isBlank()
                    ? memes.create(request.topic(), null, Strategy.AI_PICK, request.language(), web.memesPerSet())
                    : memes.createOnTemplate(request.topic(), request.template().strip(), request.language(),
                    web.memesPerSet());
            accounts.recordMemeSet(account.getId(), batch.folder(), batch.topic(), MemeSet.Source.API);
            accounts.recordMemes(account.getId(), batch.memes().stream()
                    .map(m -> new AccountService.MemeRecipe(m.url(), m.template(), null, m.texts())).toList());
            return ResponseEntity.ok()
                    .header(LIMIT_HEADER, Integer.toString(usage.limit()))
                    .header(REMAINING_HEADER, Integer.toString(usage.remaining()))
                    .body(new WebAppController.WebMemeSet(batch.topic(), batch.language(), batch.memes().stream()
                            .map(m -> new WebAppController.WebMeme(m.template(), m.texts(), m.url()))
                            .toList()));
        } finally {
            quota.release(account.getId());
        }
    }

    public record QuotaView(int limit, int used, int remaining, java.time.Instant resetsAt) {
    }

    @Operation(summary = "Today's usage", description = "Doesn't count toward the limit.")
    @GetMapping("/quota")
    public QuotaView quota(Authentication auth) {
        UserAccount account = accounts.find(caller(auth).accountId())
                .orElseThrow(() -> new InsufficientAuthenticationException("account no longer exists"));
        ApiQuota.Usage u = quota.usage(account.getId(), account.getPlan());
        return new QuotaView(u.limit(), u.used(), u.remaining(), u.resetsAt());
    }
}
