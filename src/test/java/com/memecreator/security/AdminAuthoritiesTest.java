package com.memecreator.security;

import com.memecreator.account.AccountService;
import com.memecreator.account.UserAccount;
import com.memecreator.config.MemeProperties;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Who is an admin: a confirmed email listed in meme.security.admin-emails, however they signed in. */
class AdminAuthoritiesTest {

    final AccountService service = new AccountService(null, null, null, null, null, null,
            new SecurityProperties(List.of("Hamid@Example.com"), "unused.json"), null, null, memeProperties());

    static MemeProperties memeProperties() {
        MemeProperties p = mock(MemeProperties.class);
        when(p.outputDir()).thenReturn("target/unused-output");
        return p;
    }

    Set<String> roles(String email, boolean verified) {
        UserAccount a = mock(UserAccount.class);
        when(a.getEmail()).thenReturn(email);
        when(a.isEmailVerified()).thenReturn(verified);
        return service.authorities(a).stream().map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
    }

    @Test
    void confirmedAdminEmailGetsAdmin() {
        assertEquals(Set.of("ROLE_USER", "ROLE_ADMIN"), roles("hamid@example.com", true));
    }

    @Test
    void everyoneElseIsUser() {
        assertEquals(Set.of("ROLE_USER"), roles("someone@example.com", true));
        assertEquals(Set.of("ROLE_USER"), roles("hamid@example.com", false)); // not confirmed
        assertEquals(Set.of("ROLE_USER"), roles(null, false));
    }
}
