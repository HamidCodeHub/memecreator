package com.memecreator.web;

import com.memecreator.account.AccountPrincipal;
import com.memecreator.account.AccountProperties;
import com.memecreator.account.AccountService;
import com.memecreator.account.ApiQuota;
import com.memecreator.account.Favorite;
import com.memecreator.account.MemeLibrary;
import com.memecreator.account.SharedMeme;
import com.memecreator.account.UserAccount;
import com.memecreator.account.UserApiKey;
import com.memecreator.account.UserApiKeyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/** The profile page: favorites, share links and personal API keys. */
@Tag(name = "Profile", description = "Favorites, sharing and personal API keys of the signed-in user")
@RestController
@RequestMapping("/api/web")
public class ProfileController {

    private final AccountService accounts;
    private final MemeLibrary library;
    private final UserApiKeyService apiKeys;
    private final ApiQuota quota;
    private final AccountProperties accountProps;

    public ProfileController(AccountService accounts, MemeLibrary library, UserApiKeyService apiKeys, ApiQuota quota,
                             AccountProperties accountProps) {
        this.accounts = accounts;
        this.library = library;
        this.apiKeys = apiKeys;
        this.quota = quota;
        this.accountProps = accountProps;
    }

    static long accountId(Authentication auth) {
        if (auth == null || !(auth.getPrincipal() instanceof AccountPrincipal p)) {
            throw new InsufficientAuthenticationException("not signed in");
        }
        return p.accountId();
    }

    private UserAccount account(Authentication auth) {
        return accounts.find(accountId(auth))
                .orElseThrow(() -> new InsufficientAuthenticationException("account no longer exists"));
    }

    // ---- favorites --------------------------------------------------------------------------------------------

    /** One of the app's memes, as the results grid has it. */
    public record MemeInput(@NotBlank @Size(max = 600) String url, @Size(max = 200) String template,
                            @Size(max = 10) List<@Size(max = 500) String> texts) {
    }

    public record FavoriteView(long id, String url, String template, List<String> texts, Instant createdAt) {
        static FavoriteView of(Favorite f) {
            List<String> texts = f.getCaption() == null ? List.of() : Arrays.asList(f.getCaption().split("\n"));
            return new FavoriteView(f.getId(), f.getUrl(), f.getTemplate(), texts, f.getCreatedAt());
        }
    }

    @Operation(summary = "My favorites", description = "Newest first.")
    @GetMapping("/favorites")
    public List<FavoriteView> favorites(Authentication auth) {
        return library.favorites(accountId(auth)).stream().map(FavoriteView::of).toList();
    }

    @Operation(summary = "Add to favorites", description = "Only memes this account made. Adding one twice is fine.")
    @PostMapping("/favorites")
    public FavoriteView addFavorite(@Valid @RequestBody MemeInput in, Authentication auth) {
        return FavoriteView.of(library.addFavorite(accountId(auth), in.url(), in.template(), in.texts()));
    }

    @Operation(summary = "Remove from favorites", description = "The image itself stays. 204.")
    @DeleteMapping("/favorites/{id}")
    public ResponseEntity<Void> removeFavorite(@PathVariable long id, Authentication auth) {
        library.removeFavorite(accountId(auth), id);
        return ResponseEntity.noContent().build();
    }

    // ---- sharing ----------------------------------------------------------------------------------------------

    /**
     * @param pageUrl  public page with the meme (and preview tags for Facebook, X, WhatsApp...)
     * @param imageUrl the image alone, public
     */
    public record ShareView(String token, String pageUrl, String imageUrl) {
    }

    @Operation(summary = "Get a public link to one of my memes",
            description = "Anyone with the link can see that meme until the account is deleted.")
    @PostMapping("/shares")
    public ShareView share(@Valid @RequestBody MemeInput in, Authentication auth) {
        long id = accountId(auth);
        accounts.requireCurrentTerms(id);
        SharedMeme s = library.share(id, in.url(), in.template(), in.texts());
        String base = SharePageController.base(accountProps.publicUrl());
        return new ShareView(s.getToken(), base + "/s/" + s.getToken(), base + "/s/" + s.getToken() + ".jpg");
    }

    // ---- personal API keys ------------------------------------------------------------------------------------

    public record KeyView(long id, String name, String prefix, Instant createdAt, Instant lastUsedAt) {
        static KeyView of(UserApiKey k) {
            return new KeyView(k.getId(), k.getName(), k.getPrefix(), k.getCreatedAt(), k.getLastUsedAt());
        }
    }

    public record QuotaView(int limit, int used, int remaining, Instant resetsAt) {
        static QuotaView of(ApiQuota.Usage u) {
            return new QuotaView(u.limit(), u.used(), u.remaining(), u.resetsAt());
        }
    }

    public record ApiKeysView(List<KeyView> keys, QuotaView quota, String plan) {
    }

    @Operation(summary = "My API keys and today's API usage")
    @GetMapping("/api-keys")
    public ApiKeysView apiKeys(Authentication auth) {
        UserAccount a = account(auth);
        return new ApiKeysView(apiKeys.list(a.getId()).stream().map(KeyView::of).toList(),
                QuotaView.of(quota.usage(a.getId(), a.getPlan())), a.getPlan().name());
    }

    public record NewKeyRequest(@Size(max = 60) String name) {
    }

    /** @param key the full key: shown this once only */
    public record NewKeyView(KeyView info, String key) {
    }

    @Operation(summary = "Create an API key", description = "The answer holds the key; it can't be shown again.")
    @PostMapping("/api-keys")
    public ResponseEntity<NewKeyView> createKey(@Valid @RequestBody NewKeyRequest r, Authentication auth) {
        long id = accountId(auth);
        accounts.requireCurrentTerms(id);
        UserApiKeyService.IssuedKey issued = apiKeys.create(id, r.name());
        return ResponseEntity.status(HttpStatus.CREATED).body(new NewKeyView(KeyView.of(issued.info()), issued.key()));
    }

    @Operation(summary = "Revoke an API key", description = "It stops working at once. 204.")
    @DeleteMapping("/api-keys/{id}")
    public ResponseEntity<Void> revokeKey(@PathVariable long id, Authentication auth) {
        apiKeys.revoke(accountId(auth), id);
        return ResponseEntity.noContent().build();
    }
}
