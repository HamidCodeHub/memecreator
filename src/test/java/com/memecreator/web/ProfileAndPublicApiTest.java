package com.memecreator.web;

import com.memecreator.account.AccountProperties;
import com.memecreator.account.AccountService;
import com.memecreator.account.ApiQuota;
import com.memecreator.account.MemeLibrary;
import com.memecreator.account.MemeSet;
import com.memecreator.account.UserAccount;
import com.memecreator.account.UserApiKeyService;
import com.memecreator.config.MemeProperties;
import com.memecreator.security.ApiKeyStore;
import com.memecreator.security.GenerationLimiter;
import com.memecreator.security.SecurityConfig;
import com.memecreator.security.SecurityProperties;
import com.memecreator.security.UserApiKeyAuthenticator.ApiUser;
import com.memecreator.security.WebProperties;
import com.memecreator.service.MemeService;
import com.memecreator.service.MemeService.GeneratedMeme;
import com.memecreator.service.MemeService.MemeBatch;
import com.memecreator.service.MemeService.Strategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.memecreator.web.TestAccounts.sessionCookie;
import static com.memecreator.web.TestAccounts.signedIn;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Favorites, sharing and personal API keys: who may call what. */
@WebMvcTest(controllers = {ProfileController.class, PublicApiController.class, SharePageController.class})
@Import({SecurityConfig.class, GenerationLimiter.class, ApiKeyStore.class})
@EnableConfigurationProperties({MemeProperties.class, WebProperties.class, SecurityProperties.class,
        AccountProperties.class})
@TestPropertySource(properties = {"meme.web.open-dev-apis=false",
        "meme.security.api-keys-file=target/test-api-keys/profile-test.json"})
class ProfileAndPublicApiTest {

    static final String KEY = "mcu_personal-test-key-000000000000";
    static final String TOPIC = "{\"topic\": \"monday meetings\"}";

    @Autowired
    MockMvc mvc;

    @MockBean
    AccountService accounts;

    @MockBean
    MemeLibrary library;

    @MockBean
    UserApiKeyService apiKeys; // also the X-API-Key check for mcu_ keys

    @MockBean
    ApiQuota quota;

    @MockBean
    MemeService memeService;

    UserAccount account;

    @BeforeEach
    void setUp() {
        account = mock(UserAccount.class);
        when(account.getId()).thenReturn(7L);
        when(account.getPlan()).thenReturn(UserAccount.Plan.FREE);
        when(accounts.find(7L)).thenReturn(Optional.of(account));
        when(accounts.requireCurrentTerms(7L)).thenReturn(account);
        when(apiKeys.authenticate(anyString())).thenReturn(Optional.empty());
        when(apiKeys.authenticate(KEY)).thenReturn(Optional.of(new ApiUser(7L, 1L)));
    }

    @Test
    void favoritesNeedASignedInPersonAndTheCsrfToken() throws Exception {
        String body = "{\"url\": \"/generated/f/01.jpg\", \"template\": \"Drake\", \"texts\": [\"a\"]}";
        mvc.perform(get("/api/web/favorites")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/web/favorites").with(signedIn(7, "USER")).with(sessionCookie())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden()); // no CSRF token
        verify(library, never()).addFavorite(anyLong(), any(), any(), any());

        doThrow(new MemeLibrary.MemeNotFoundException()).when(library)
                .addFavorite(eq(7L), eq("/generated/f/01.jpg"), any(), any());
        mvc.perform(post("/api/web/favorites").with(signedIn(7, "USER")).with(sessionCookie()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());
    }

    @Test
    void personalKeysOnlyReachThePublicApi() throws Exception {
        mvc.perform(get("/api/web/favorites").header("X-API-Key", KEY)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/quota").header("X-API-Key", "mcu_wrong")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/quota").with(signedIn(7, "USER"))).andExpect(status().isForbidden());

        when(quota.usage(7L, UserAccount.Plan.FREE)).thenReturn(new ApiQuota.Usage(5, 2, Instant.EPOCH));
        mvc.perform(get("/api/v1/quota").header("X-API-Key", KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remaining").value(3));
    }

    @Test
    void makingMemesWithAKeyCountsTowardTheDailyLimit() throws Exception {
        when(quota.acquire(7L, UserAccount.Plan.FREE)).thenReturn(new ApiQuota.Usage(5, 5, Instant.EPOCH));
        when(memeService.create(eq("monday meetings"), isNull(), eq(Strategy.AI_PICK), isNull(), eq(6)))
                .thenReturn(new MemeBatch("monday meetings", "English", "E:/out/f",
                        List.of(new GeneratedMeme("Drake", List.of("a"), "/generated/f/01.jpg", "E:/out/f/01.jpg"))));

        mvc.perform(post("/api/v1/memes").header("X-API-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(TOPIC))
                .andExpect(status().isOk())
                .andExpect(header().string("X-RateLimit-Remaining", "0"))
                .andExpect(jsonPath("$.memes[0].url").value("/generated/f/01.jpg"))
                .andExpect(jsonPath("$.memes[0].file").doesNotExist());
        verify(accounts).recordMemeSet(7L, "E:/out/f", "monday meetings", MemeSet.Source.API);
        verify(quota).release(7L);

        when(quota.acquire(7L, UserAccount.Plan.FREE)).thenThrow(new ApiQuota.QuotaExceededException(
                new ApiQuota.Usage(5, 5, Instant.EPOCH), Duration.ofHours(3)));
        mvc.perform(post("/api/v1/memes").header("X-API-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(TOPIC))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "10800"))
                .andExpect(jsonPath("$.code").value("DAILY_LIMIT"));
    }

    @Test
    void sharedMemePagesArePublic() throws Exception {
        when(library.findShare(anyString())).thenReturn(Optional.empty());
        mvc.perform(get("/s/AAAAAAAAAAAAAAAAAAAA")).andExpect(status().isNotFound());
        mvc.perform(get("/s/AAAAAAAAAAAAAAAAAAAA.jpg")).andExpect(status().isNotFound());
    }
}
