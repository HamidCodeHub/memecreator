package com.memecreator.web;

import com.memecreator.account.ApiProperties;
import com.memecreator.account.MemeSetRepository;
import com.memecreator.account.PromptQuota;

import com.memecreator.account.AccountProperties;
import com.memecreator.account.AccountService;
import com.memecreator.account.AccountUserDetails;
import com.memecreator.account.Throttle;
import com.memecreator.config.MemeProperties;
import com.memecreator.security.ApiKeyStore;
import com.memecreator.security.GenerationLimiter;
import com.memecreator.security.SecurityConfig;
import com.memecreator.security.SecurityProperties;
import com.memecreator.security.WebProperties;
import com.memecreator.service.MemeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Email + password sign-in through the real security setup (accounts mocked). */
@WebMvcTest({AuthController.class, WebAppController.class})
@Import({SecurityConfig.class, GenerationLimiter.class, ApiKeyStore.class, Throttle.class, PromptQuota.class})
@EnableConfigurationProperties({MemeProperties.class, WebProperties.class, SecurityProperties.class,
        AccountProperties.class, ApiProperties.class})
@TestPropertySource(properties = "meme.security.api-keys-file=target/test-api-keys/auth-controller-test.json")
class AuthControllerTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    PasswordEncoder passwords;

    @MockBean
    MemeSetRepository memeSets;

    @MockBean
    AccountService accounts;

    @MockBean
    MemeService memeService;

    String hash;

    @BeforeEach
    void users() {
        hash = passwords.encode("correct horse battery");
        doThrow(new UsernameNotFoundException("none")).when(accounts).loadUserByUsername(anyString());
        doReturn(user(7, "hamid@example.com", true)).when(accounts).loadUserByUsername("hamid@example.com");
        doReturn(user(8, "new@example.com", false)).when(accounts).loadUserByUsername("new@example.com");
        when(accounts.find(7L)).thenReturn(Optional.of(TestAccounts.account(7, "hamid@example.com", "Hamid")));
        when(accounts.currentTermsVersion()).thenReturn(TestAccounts.TERMS);
    }

    AccountUserDetails user(long id, String email, boolean confirmed) {
        return new AccountUserDetails(id, email, hash, confirmed, List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    static String login(String email, String password) {
        return "{\"email\": \"" + email + "\", \"password\": \"" + password + "\"}";
    }

    @Test
    void signInStartsASessionThatTheAppCanUse() throws Exception {
        MvcResult result = mvc.perform(post("/api/web/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(login("Hamid@Example.com", "correct horse battery")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("account:7"))
                .andExpect(jsonPath("$.signInMethod").value("password"))
                .andReturn();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);

        mvc.perform(get("/api/web/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Hamid"));
    }

    @Test
    void signInNeedsTheCsrfToken() throws Exception {
        mvc.perform(post("/api/web/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(login("hamid@example.com", "correct horse battery")))
                .andExpect(status().isForbidden());
    }

    @Test
    void wrongPasswordAndUnknownEmailLookTheSame() throws Exception {
        mvc.perform(post("/api/web/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(login("hamid@example.com", "wrong password!!")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Wrong email or password."));
        mvc.perform(post("/api/web/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(login("nobody@example.com", "whatever pw 123")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Wrong email or password."));
    }

    @Test
    void unconfirmedAccountIsOnlyToldSoWithTheRightPassword() throws Exception {
        mvc.perform(post("/api/web/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(login("new@example.com", "correct horse battery")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("EMAIL_NOT_CONFIRMED"));
        mvc.perform(post("/api/web/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(login("new@example.com", "wrong password!!")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tooManyWrongPasswordsAreBlocked() throws Exception {
        for (int i = 0; i < 10; i++) {
            mvc.perform(post("/api/web/auth/login").with(csrf()).with(r -> {
                        r.setRemoteAddr("10.0.0.9");
                        return r;
                    })
                    .contentType(MediaType.APPLICATION_JSON).content(login("guess@example.com", "guess number " + i)));
        }
        mvc.perform(post("/api/web/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(login("guess@example.com", "one more try")))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void signUpAlwaysGivesTheSameAnswer() throws Exception {
        mvc.perform(post("/api/web/auth/signup").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"Someone@Example.com\", \"password\": \"long enough pw\", \"name\": \"S\", "
                                + "\"termsVersion\": \"2026-11-22\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.message").value(AuthController.CHECK_INBOX));
        verify(accounts).signUp(eq("someone@example.com"), eq("long enough pw"), eq("S"), eq("2026-11-22"));

        mvc.perform(post("/api/web/auth/signup").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"not an email\", \"password\": \"long enough pw\", "
                                + "\"termsVersion\": \"2026-11-22\"}"))
                .andExpect(status().isBadRequest());

        // Without ticking "I accept"
        mvc.perform(post("/api/web/auth/signup").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"a@example.com\", \"password\": \"long enough pw\"}"))
                .andExpect(status().isBadRequest());
    }
}
