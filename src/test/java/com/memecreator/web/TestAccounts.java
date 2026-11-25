package com.memecreator.web;

import com.memecreator.account.AccountPrincipal;
import com.memecreator.account.UserAccount;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.io.Serializable;
import java.util.Arrays;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

/** Signed-in people and accounts for the web security tests. */
final class TestAccounts {

    private TestAccounts() {
    }

    record Principal(long accountId, String signInMethod) implements AccountPrincipal, Serializable {
    }

    /** A signed-in person (any sign-in method), with roles like "USER", "ADMIN". */
    static RequestPostProcessor signedIn(long accountId, String... roles) {
        return authentication(UsernamePasswordAuthenticationToken.authenticated(
                new Principal(accountId, "google"), null,
                Arrays.stream(roles).map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList()));
    }

    /** Like a browser: the request carries a session cookie (CSRF is only checked then, or on sign-in calls). */
    static RequestPostProcessor sessionCookie() {
        return request -> {
            request.setRequestedSessionId("test-session");
            return request;
        };
    }

    /** The terms version the tests treat as current. */
    static final String TERMS = "2026-11-22";

    /** An account that accepted the current terms. */
    static UserAccount account(long id, String email, String name) {
        UserAccount a = mock(UserAccount.class);
        when(a.getTermsVersion()).thenReturn(TERMS);
        when(a.getId()).thenReturn(id);
        when(a.getEmail()).thenReturn(email);
        when(a.getDisplayName()).thenReturn(name);
        when(a.isEmailVerified()).thenReturn(true);
        return a;
    }
}
