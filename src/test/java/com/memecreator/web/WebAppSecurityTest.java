package com.memecreator.web;

import com.memecreator.account.ApiProperties;
import com.memecreator.account.MemeSetRepository;
import com.memecreator.account.PromptQuota;

import com.memecreator.account.AccountProperties;
import com.memecreator.account.AccountService;
import com.memecreator.account.MemeSet;
import com.memecreator.account.UserAccount;
import com.memecreator.ai.AiGatewayException;
import com.memecreator.config.MemeProperties;
import com.memecreator.security.ApiKeyStore;
import com.memecreator.security.GenerationLimiter;
import com.memecreator.security.SecurityConfig;
import com.memecreator.security.SecurityProperties;
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
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static com.memecreator.web.TestAccounts.account;
import static com.memecreator.web.TestAccounts.sessionCookie;
import static com.memecreator.web.TestAccounts.signedIn;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The web app's API with open-dev-apis=true (the old development mode). */
@WebMvcTest(WebAppController.class)
@Import({SecurityConfig.class, GenerationLimiter.class, ApiKeyStore.class, PromptQuota.class})
@EnableConfigurationProperties({MemeProperties.class, WebProperties.class, SecurityProperties.class,
        AccountProperties.class, ApiProperties.class})
@TestPropertySource(properties = {"meme.web.max-sets-per-hour=1", "meme.web.open-dev-apis=true",
        "meme.security.api-keys-file=target/test-api-keys/web-app-security-test.json"})
class WebAppSecurityTest {

    static final String BODY = "{\"topic\": \"monday meetings\"}";

    @Autowired
    MockMvc mvc;

    @MockBean
    MemeSetRepository memeSets;

    @MockBean
    MemeService memeService;

    @MockBean
    AccountService accountService;

    @BeforeEach
    void accounts() {
        when(accountService.find(7L)).thenReturn(Optional.of(account(7, "hamid@example.com", "Hamid")));
        when(accountService.currentTermsVersion()).thenReturn(TestAccounts.TERMS);
    }

    @Test
    void apiAnswers401WhenNotSignedIn() throws Exception {
        mvc.perform(get("/api/web/me")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/web/memes").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void providersArePublicAndShowMissingCredentials() throws Exception {
        mvc.perform(get("/api/web/providers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("facebook"))
                .andExpect(jsonPath("$[0].configured").value(false))
                .andExpect(jsonPath("$[0].loginUrl").value("/oauth2/authorization/facebook"))
                .andExpect(jsonPath("$[1].id").value("google"));
    }

    @Test
    void meReturnsTheAccountAndSetsTheCsrfCookie() throws Exception {
        mvc.perform(get("/api/web/me").with(signedIn(7, "USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("account:7"))
                .andExpect(jsonPath("$.name").value("Hamid"))
                .andExpect(jsonPath("$.email").value("hamid@example.com"))
                .andExpect(jsonPath("$.signInMethod").value("google"))
                .andExpect(jsonPath("$.termsAccepted").value(true))
                .andExpect(jsonPath("$.termsVersion").value(TestAccounts.TERMS))
                .andExpect(cookie().exists("XSRF-TOKEN"));
    }

    @Test
    void deletedAccountIs401() throws Exception {
        mvc.perform(get("/api/web/me").with(signedIn(99, "USER"))).andExpect(status().isUnauthorized());
    }

    @Test
    void makingMemesNeedsTheCsrfTokenAndIsLimitedPerHour() throws Exception {
        when(memeService.create(eq("monday meetings"), isNull(), eq(Strategy.AI_PICK), isNull(), eq(6)))
                .thenReturn(new MemeBatch("monday meetings", "English", "E:/secret/path",
                        List.of(new GeneratedMeme("Drake Hotline Bling", List.of("a", "b"), "/generated/x/01.jpg",
                                "E:/secret/path/01.jpg"))));

        mvc.perform(post("/api/web/memes").with(signedIn(7, "USER")).with(sessionCookie())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/web/memes").with(signedIn(7, "USER")).with(sessionCookie()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memes[0].url").value("/generated/x/01.jpg"))
                .andExpect(jsonPath("$.memes[0].file").doesNotExist())
                .andExpect(jsonPath("$.folder").doesNotExist());
        verify(accountService).recordMemeSet(7L, "E:/secret/path", "monday meetings", MemeSet.Source.WEB);

        mvc.perform(post("/api/web/memes").with(signedIn(7, "USER")).with(sessionCookie()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
    }

    @Test
    void aPickedTemplateIsUsedForAllMemes() throws Exception {
        when(memeService.createOnTemplate("monday meetings", "01", "Italian", 6))
                .thenReturn(new MemeBatch("monday meetings", "Italian", "E:/secret/drake",
                        List.of(new GeneratedMeme("Drake Hotline Bling", List.of("a", "b"), "/generated/d/01.jpg",
                                "E:/secret/drake/01.jpg"))));

        mvc.perform(post("/api/web/memes").with(signedIn(7, "USER")).with(sessionCookie()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"topic\": \"monday meetings\", \"language\": \"Italian\", \"template\": \"01\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memes[0].template").value("Drake Hotline Bling"));
        verify(memeService, never()).create(any(), any(), any(), any(), anyInt());
    }

    @Test
    void makingMemesNeedsTheCurrentTermsAccepted() throws Exception {
        UserAccount old = account(8, "old@example.com", "Old");
        when(old.getTermsVersion()).thenReturn("2020-01-01");
        when(accountService.find(8L)).thenReturn(Optional.of(old));

        mvc.perform(get("/api/web/me").with(signedIn(8, "USER")))
                .andExpect(jsonPath("$.termsAccepted").value(false));
        mvc.perform(post("/api/web/memes").with(signedIn(8, "USER")).with(sessionCookie()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TERMS_NOT_ACCEPTED"));
        verify(memeService, never()).create(any(), any(), any(), any(), anyInt());
    }

    @Test
    void legalIsPublic() throws Exception {
        mvc.perform(get("/api/web/legal"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.termsVersion").value(TestAccounts.TERMS))
                .andExpect(jsonPath("$.privacyUrl").value("/privacy.html"));
    }

    @Test
    void acceptingTheTermsRecordsTheVersion() throws Exception {
        mvc.perform(post("/api/web/terms/accept").with(signedIn(7, "USER")).with(sessionCookie()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\": \"2026-11-22\"}"))
                .andExpect(status().isOk());
        verify(accountService).acceptTerms(7L, "2026-11-22");

        doThrow(new AccountService.TermsVersionMismatchException()).when(accountService).acceptTerms(7L, "2020-01-01");
        mvc.perform(post("/api/web/terms/accept").with(signedIn(7, "USER")).with(sessionCookie()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\": \"2020-01-01\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TERMS_VERSION_CHANGED"));
    }

    @Test
    void deletingTheAccountNeedsTheTypedConfirmationAndTheCsrfToken() throws Exception {
        String confirm = "{\"confirm\": \"DELETE\"}";
        mvc.perform(delete("/api/web/account").with(signedIn(7, "USER")).with(sessionCookie())
                        .contentType(MediaType.APPLICATION_JSON).content(confirm))
                .andExpect(status().isForbidden()); // no CSRF token
        mvc.perform(delete("/api/web/account").with(signedIn(7, "USER")).with(sessionCookie()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"confirm\": \"delete\"}"))
                .andExpect(status().isBadRequest());
        verify(accountService, never()).deleteAccount(anyLong());

        MockHttpSession session = new MockHttpSession();
        mvc.perform(delete("/api/web/account").with(signedIn(7, "USER")).session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(confirm))
                .andExpect(status().isNoContent());
        verify(accountService).deleteAccount(7L);
        assertTrue(session.isInvalid());
    }

    @Test
    void rejectsAnEmptyTopic() throws Exception {
        mvc.perform(post("/api/web/memes").with(signedIn(7, "USER")).with(sessionCookie()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"topic\": \"  \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void devApisStayOpenWhileOpenDevApisIsTrue() throws Exception {
        // No controller for it in this test slice: 404 means security let the request through
        mvc.perform(get("/api/templates")).andExpect(status().isNotFound());
        mvc.perform(post("/api/memes").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isNotFound());
    }

    @Test
    void dailyPromptLimitAnswers429WithoutCallingTheAi() throws Exception {
        when(accountService.find(9L)).thenReturn(Optional.of(account(9, "friend@example.com", "Friend")));
        when(memeSets.countByAccountIdAndSourceAndCreatedAtGreaterThanEqual(eq(9L), eq(MemeSet.Source.WEB), any()))
                .thenReturn(10L);

        mvc.perform(post("/api/web/memes").with(signedIn(9, "USER")).with(sessionCookie()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("DAILY_LIMIT"))
                .andExpect(jsonPath("$.plan").value("FREE"))
                .andExpect(jsonPath("$.limit").value(10));
        verify(memeService, never()).create(any(), any(), any(), any(), anyInt());
    }

    @Test
    void planShowsTodaysPromptsAndThatPremiumCantBeBoughtYet() throws Exception {
        when(memeSets.countByAccountIdAndSourceAndCreatedAtGreaterThanEqual(eq(7L), eq(MemeSet.Source.WEB), any()))
                .thenReturn(3L);

        mvc.perform(get("/api/web/plan").with(signedIn(7, "USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plan").value("FREE"))
                .andExpect(jsonPath("$.promptsPerDay").value(10))
                .andExpect(jsonPath("$.usedToday").value(3))
                .andExpect(jsonPath("$.remainingToday").value(7))
                .andExpect(jsonPath("$.premiumPromptsPerDay").value(0))
                .andExpect(jsonPath("$.upgradeAvailable").value(false));
        mvc.perform(get("/api/web/plan")).andExpect(status().isUnauthorized());
    }

    @Test
    void aiGatewayFailureShowsAPlainMessage() throws Exception {
        when(accountService.find(8L)).thenReturn(Optional.of(account(8, "other@example.com", "Other")));
        when(memeService.create(eq("gateway down"), isNull(), eq(Strategy.AI_PICK), isNull(), eq(6)))
                .thenThrow(new AiGatewayException("Could not reach AI gateway at http://aiproxy:8080/v1/chat"));

        mvc.perform(post("/api/web/memes").with(signedIn(8, "USER")).with(sessionCookie()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"topic\": \"gateway down\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "60"))
                .andExpect(jsonPath("$.code").value("AI_UNAVAILABLE"))
                .andExpect(jsonPath("$.detail").value(ApiExceptionHandler.AI_UNAVAILABLE_MESSAGE));
        verify(accountService, never()).recordMemeSet(eq(8L), any(), any(), any());
    }
}
