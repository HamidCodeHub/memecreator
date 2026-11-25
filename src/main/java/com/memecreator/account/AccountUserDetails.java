package com.memecreator.account;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.io.Serial;
import java.util.Collection;
import java.util.List;

/** An email + password account, as Spring Security sees it. Unconfirmed accounts are "disabled". */
public final class AccountUserDetails implements UserDetails, AccountPrincipal {

    @Serial
    private static final long serialVersionUID = 1L;

    private final long accountId;
    private final String email;
    private final transient String passwordHash;
    private final boolean emailVerified;
    private final List<GrantedAuthority> authorities;

    public AccountUserDetails(long accountId, String email, String passwordHash, boolean emailVerified,
                              List<GrantedAuthority> authorities) {
        this.accountId = accountId;
        this.email = email;
        this.passwordHash = passwordHash;
        this.emailVerified = emailVerified;
        this.authorities = List.copyOf(authorities);
    }

    @Override
    public long accountId() {
        return accountId;
    }

    @Override
    public String signInMethod() {
        return "password";
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    /** Not confirmed yet: Spring Security answers DisabledException, the app says "confirm your email first". */
    @Override
    public boolean isEnabled() {
        return emailVerified;
    }
}
