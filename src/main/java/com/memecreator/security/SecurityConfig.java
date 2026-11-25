package com.memecreator.security;

import com.memecreator.account.AccountService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.util.matcher.AndRequestMatcher;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Two ways in, same endpoints:
 * <ul>
 *   <li><b>Programs</b> (footballmeme, scripts) send {@code X-API-Key}: stateless, no session, no CSRF. People
 *       can also create personal keys ("mcu_") on their profile page: role API_USER, only {@code /api/v1/**}.
 *       Roles come from the key: SERVICE or ADMIN. Keys: {@link ApiKeyStore}, issued with {@link ApiKeyTool} or
 *       {@code POST /api/admin/api-keys}.</li>
 *   <li><b>People</b> sign in with Google or Meta (OAuth2/OpenID Connect, handled entirely here) or with email +
 *       password ({@code /api/web/auth/*}). Either way the browser only holds the HttpOnly session cookie, and the
 *       session holds an {@link com.memecreator.account.AccountPrincipal}. Everyone gets USER; a confirmed email
 *       listed in meme.security.admin-emails also gets ADMIN.</li>
 * </ul>
 * What each role may call is decided once, in {@link #rules}, for both.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    public static final String USER = "USER";
    public static final String SERVICE = "SERVICE";
    public static final String ADMIN = "ADMIN";
    /** A person calling the public API with their personal key. */
    public static final String API_USER = "API_USER";

    /** Requests with an API key. */
    @Bean
    @Order(1)
    SecurityFilterChain apiKeyFilterChain(HttpSecurity http, ApiKeyStore keys, WebProperties web,
                                          ObjectProvider<UserApiKeyAuthenticator> userKeys) throws Exception {
        http.securityMatcher(request -> request.getHeader(ApiKeyAuthenticationFilter.HEADER) != null)
                .addFilterBefore(new ApiKeyAuthenticationFilter(keys, userKeys.getIfAvailable()),
                        AnonymousAuthenticationFilter.class)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable) // no cookies involved: nothing another site could forge
                .requestCache(AbstractHttpConfigurer::disable)
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .authorizeHttpRequests(auth -> rules(auth, web.openDevApis()));
        return http.build();
    }

    /** Browsers: Google/Meta sign-in and the session cookie. */
    @Bean
    @Order(2)
    SecurityFilterChain browserFilterChain(HttpSecurity http, WebProperties web, AccountService accounts,
                                           SecurityContextRepository contextRepository) throws Exception {
        RequestMatcher api = new AntPathRequestMatcher("/api/**");
        RequestMatcher webApi = new AntPathRequestMatcher("/api/web/**");
        RequestMatcher signInApi = new OrRequestMatcher(new AntPathRequestMatcher("/api/web/auth/**"),
                new AntPathRequestMatcher("/api/web/contact"));

        http.authorizeHttpRequests(auth -> rules(auth, web.openDevApis()));
        http.securityContext(c -> c.securityContextRepository(contextRepository));

        http.oauth2Login(login -> login
                .userInfoEndpoint(u -> u
                        .oidcUserService(AccountOAuth2UserServices.oidc(accounts))     // Google
                        .userService(AccountOAuth2UserServices.oauth2(accounts)))      // Facebook
                .successHandler(successHandler(web.afterLoginUrl()))
                .failureHandler(new SimpleUrlAuthenticationFailureHandler(withParam(web.afterLoginUrl(), "login=failed"))));

        http.logout(logout -> logout
                .logoutRequestMatcher(new AntPathRequestMatcher("/api/web/logout", "POST"))
                .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)));

        // APIs answer 401 so the React app can show its sign-in screen; pages get the provider choice
        http.exceptionHandling(e -> e.defaultAuthenticationEntryPointFor(
                new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED), api));

        CookieCsrfTokenRepository csrfCookie = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfCookie.setCookieCustomizer(c -> c.sameSite("Lax"));
        // CSRF only matters for requests that carry the session cookie (a forged cross-site request would). Without
        // one, a program that forgot its API key gets a clear 401 instead of "invalid CSRF token". Sign-in, sign-up
        // and the contact form always need the token (no "login CSRF": signing a victim into someone else's account). While the
        // dev APIs are open they don't use the session, so then only the web app's calls need the token.
        RequestMatcher withSession = request -> request.getRequestedSessionId() != null;
        RequestMatcher needsToken = new OrRequestMatcher(withSession, signInApi);
        RequestMatcher csrfProtected = web.openDevApis()
                ? new AndRequestMatcher(CsrfFilter.DEFAULT_CSRF_MATCHER, needsToken, webApi)
                : new AndRequestMatcher(CsrfFilter.DEFAULT_CSRF_MATCHER, needsToken);
        http.csrf(csrf -> csrf
                .csrfTokenRepository(csrfCookie)
                .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler())
                .requireCsrfProtectionMatcher(csrfProtected));

        return http.build();
    }

    /**
     * Who may call what. First match wins.
     *
     * @param openDevApis true: only the web app's and admin endpoints are protected (the old development mode)
     */
    static void rules(AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry auth,
                      boolean openDevApis) {
        auth.requestMatchers("/api/web/providers", "/api/web/legal", "/api/web/auth/**", "/api/web/contact", "/error")
                .permitAll();
        auth.requestMatchers("/s/**").permitAll();                  // memes people chose to share: public
        auth.requestMatchers("/api/web/**").hasRole(USER);          // people only: rate-limited per account
        auth.requestMatchers("/api/v1/**").hasRole(API_USER);       // personal API keys: daily quota per account
        auth.requestMatchers("/api/admin/**").hasRole(ADMIN);
        if (openDevApis) {
            auth.anyRequest().permitAll();
            return;
        }
        // Users' photos and faces (GDPR), the template harvest, the AI model list: admin only for now
        auth.requestMatchers("/api/memes/from-image", "/api/memes/with-face/**", "/api/faces/**",
                "/api/templates/harvest", "/api/templates/harvest/**", "/api/ai/**").hasRole(ADMIN);
        // Making memes and reading templates: programs (footballmeme) and admins
        auth.requestMatchers("/api/memes", "/api/memes/render", "/api/templates", "/api/templates/preview")
                .hasAnyRole(SERVICE, ADMIN);
        // The rendered images: anyone signed in or with a key
        auth.requestMatchers("/generated/**").authenticated();
        // Everything else (Swagger, the old test page at /, ...): admin
        auth.anyRequest().hasRole(ADMIN);
    }

    /** bcrypt for new passwords; the "{bcrypt}" prefix in stored hashes leaves room to change it later. */
    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /** Where a sign-in is kept between requests: the HTTP session. Also used by AuthController. */
    @Bean
    SecurityContextRepository securityContextRepository() {
        return new DelegatingSecurityContextRepository(new RequestAttributeSecurityContextRepository(),
                new HttpSessionSecurityContextRepository());
    }

    /** Email + password sign-in. */
    @Bean
    AuthenticationManager authenticationManager(AccountService accounts, PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(passwordEncoder);
        provider.setUserDetailsService(accounts);
        // Check "email not confirmed" only after the password: otherwise a wrong password would reveal that an
        // unconfirmed account exists for the address
        provider.setPreAuthenticationChecks(user -> {
        });
        provider.setPostAuthenticationChecks(user -> {
            if (!user.isEnabled()) {
                throw new DisabledException("email not confirmed");
            }
        });
        return new ProviderManager(provider);
    }

    private static SimpleUrlAuthenticationSuccessHandler successHandler(String url) {
        SimpleUrlAuthenticationSuccessHandler handler = new SimpleUrlAuthenticationSuccessHandler(url);
        handler.setAlwaysUseDefaultTargetUrl(true); // never bounce back to an API URL saved before sign-in
        return handler;
    }

    private static String withParam(String url, String param) {
        return url + (url.contains("?") ? "&" : "?") + param;
    }
}
