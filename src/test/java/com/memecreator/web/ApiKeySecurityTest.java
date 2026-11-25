package com.memecreator.web;

import com.memecreator.account.ApiProperties;
import com.memecreator.account.MemeSetRepository;
import com.memecreator.account.PromptQuota;

import com.memecreator.account.AccountProperties;
import com.memecreator.account.AccountService;
import com.memecreator.config.MemeProperties;
import com.memecreator.harvest.HarvestProperties;
import com.memecreator.harvest.TemplateHarvester;
import com.memecreator.security.ApiKeyStore;
import com.memecreator.security.ApiKeyStore.Role;
import com.memecreator.security.GenerationLimiter;
import com.memecreator.security.SecurityConfig;
import com.memecreator.security.SecurityProperties;
import com.memecreator.security.WebProperties;
import com.memecreator.service.MemeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** open-dev-apis=false (the default now): who may call what, with API keys and with sign-in. */
@WebMvcTest(controllers = {WebAppController.class, TemplateHarvestController.class, ApiKeyAdminController.class})
@Import({SecurityConfig.class, GenerationLimiter.class, ApiKeyStore.class, PromptQuota.class})
@EnableConfigurationProperties({MemeProperties.class, WebProperties.class, SecurityProperties.class,
        AccountProperties.class, HarvestProperties.class, ApiProperties.class})
class ApiKeySecurityTest {

    static final Path KEYS;
    static final String SERVICE_KEY;
    static final String ADMIN_KEY;

    static {
        try {
            KEYS = Files.createTempDirectory("api-keys").resolve("api-keys.json");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        ApiKeyStore store = new ApiKeyStore(KEYS);
        SERVICE_KEY = store.issue("footballmeme", EnumSet.of(Role.SERVICE)).key();
        ADMIN_KEY = store.issue("hamid-admin", EnumSet.of(Role.ADMIN)).key();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("meme.security.api-keys-file", KEYS::toString);
        r.add("meme.web.open-dev-apis", () -> "false");
    }

    @Autowired
    MockMvc mvc;

    @MockBean
    MemeSetRepository memeSets;

    @MockBean
    MemeService memeService;

    @MockBean
    TemplateHarvester harvester;

    @MockBean
    AccountService accountService;

    @Test
    void withoutCredentialsEveryApiAnswers401() throws Exception {
        mvc.perform(get("/api/templates/harvest")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/memes").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/web/providers")).andExpect(status().isOk());
    }

    @Test
    void unknownKeyIs401EvenWhereAnonymousWouldBeAllowed() throws Exception {
        mvc.perform(get("/api/templates/harvest").header("X-API-Key", "mck_wrong")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/web/providers").header("X-API-Key", "mck_wrong")).andExpect(status().isUnauthorized());
    }

    @Test
    void serviceKeyCanMakeMemesButNotAdminister() throws Exception {
        // No MemeController in this test slice: 404 means security let the request through, without a CSRF token
        mvc.perform(post("/api/memes").header("X-API-Key", SERVICE_KEY)
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isNotFound());
        mvc.perform(get("/api/templates").header("X-API-Key", SERVICE_KEY)).andExpect(status().isNotFound());
        mvc.perform(get("/generated/x/01.jpg").header("X-API-Key", SERVICE_KEY)).andExpect(status().isNotFound());

        mvc.perform(get("/api/templates/harvest").header("X-API-Key", SERVICE_KEY)).andExpect(status().isForbidden());
        mvc.perform(post("/api/memes/from-image").header("X-API-Key", SERVICE_KEY)).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/api-keys").header("X-API-Key", SERVICE_KEY)).andExpect(status().isForbidden());
        mvc.perform(get("/api/web/me").header("X-API-Key", SERVICE_KEY)).andExpect(status().isForbidden());
    }

    @Test
    void adminKeyCanAdministerWithoutCsrf() throws Exception {
        mvc.perform(get("/api/templates/harvest").header("X-API-Key", ADMIN_KEY)).andExpect(status().isOk());
        mvc.perform(post("/api/templates/harvest?source=imgflip").header("X-API-Key", ADMIN_KEY))
                .andExpect(status().isOk());
        mvc.perform(get("/api/admin/api-keys").header("X-API-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.client == 'footballmeme')].roles[0]").value("SERVICE"));
        mvc.perform(post("/api/admin/api-keys").header("X-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"client\": \"test-script\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value(org.hamcrest.Matchers.startsWith("mck_")))
                .andExpect(jsonPath("$.roles[0]").value("SERVICE"));
    }

    @Test
    void signedInPeopleGetTheWebAppOnlyUnlessAdmin() throws Exception {
        org.mockito.Mockito.when(accountService.find(7L))
                .thenReturn(java.util.Optional.of(TestAccounts.account(7, "someone@example.com", null)));
        org.mockito.Mockito.when(accountService.currentTermsVersion()).thenReturn(TestAccounts.TERMS);
        var user = TestAccounts.signedIn(7, "USER");
        var admin = TestAccounts.signedIn(7, "USER", "ADMIN");

        mvc.perform(get("/api/web/me").with(user)).andExpect(status().isOk());
        mvc.perform(get("/api/templates/harvest").with(user)).andExpect(status().isForbidden());
        mvc.perform(get("/api/templates/harvest").with(admin)).andExpect(status().isOk());
        // With a session cookie, changing requests need the CSRF token
        mvc.perform(post("/api/templates/harvest").with(admin).with(TestAccounts.sessionCookie()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/templates/harvest").with(admin).with(TestAccounts.sessionCookie()).with(csrf()))
                .andExpect(status().isOk());
    }
}
