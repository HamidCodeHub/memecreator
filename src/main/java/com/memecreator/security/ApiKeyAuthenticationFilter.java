package com.memecreator.security;

import com.memecreator.security.ApiKeyStore.ApiClient;
import com.memecreator.security.UserApiKeyAuthenticator.ApiUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Authenticates requests that carry an {@code X-API-Key} header: service keys ("mck_", {@link ApiKeyStore}) and
 * people's personal keys ("mcu_", {@link UserApiKeyAuthenticator}). A wrong key is a 401 right away — it never falls
 * back to anonymous access. Not a Spring bean on purpose: as a bean, Spring Boot would also register it as a
 * plain servlet filter for every request.
 */
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-API-Key";
    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthenticationFilter.class);

    private final ApiKeyStore store;
    private final UserApiKeyAuthenticator userKeys;

    public ApiKeyAuthenticationFilter(ApiKeyStore store) {
        this(store, null);
    }

    /** @param userKeys personal keys; null when not available (tests) */
    public ApiKeyAuthenticationFilter(ApiKeyStore store, UserApiKeyAuthenticator userKeys) {
        this.store = store;
        this.userKeys = userKeys;
    }

    /** A person calling with their personal key: role API_USER, principal {@link ApiUser}. */
    public static final class ApiUserAuthentication extends AbstractAuthenticationToken {
        private final ApiUser user;

        public ApiUserAuthentication(ApiUser user) {
            super(List.of(new SimpleGrantedAuthority("ROLE_" + SecurityConfig.API_USER)));
            this.user = user;
            setAuthenticated(true);
        }

        @Override
        public Object getCredentials() {
            return null;
        }

        @Override
        public Object getPrincipal() {
            return user;
        }

        @Override
        public String getName() {
            return "account:" + user.accountId();
        }
    }

    /** The authenticated program. {@link #getName()} is the client name, e.g. "footballmeme". */
    public static final class ApiKeyAuthentication extends AbstractAuthenticationToken {
        private final ApiClient client;

        ApiKeyAuthentication(ApiClient client) {
            super(client.roles().stream().map(r -> new SimpleGrantedAuthority("ROLE_" + r.name())).toList());
            this.client = client;
            setAuthenticated(true);
        }

        @Override
        public Object getCredentials() {
            return null; // the key is never kept in the security context
        }

        @Override
        public Object getPrincipal() {
            return client.client();
        }

        public ApiClient client() {
            return client;
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String key = request.getHeader(HEADER);
        if (key == null) {
            chain.doFilter(request, response);
            return;
        }
        String trimmed = key.trim();
        AbstractAuthenticationToken auth = null;
        if (trimmed.startsWith(UserApiKeyAuthenticator.PREFIX)) {
            if (userKeys != null) {
                auth = userKeys.authenticate(trimmed).map(ApiUserAuthentication::new).orElse(null);
            }
        } else {
            Optional<ApiClient> client = store.authenticate(trimmed);
            auth = client.map(ApiKeyAuthentication::new).orElse(null);
        }
        if (auth == null) {
            log.warn("Rejected an unknown API key from {} for {} {}", request.getRemoteAddr(), request.getMethod(),
                    request.getRequestURI());
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.getWriter().write("{\"status\":401,\"title\":\"Unauthorized\",\"detail\":\"Unknown or revoked API key\"}");
            return;
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
