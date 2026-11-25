package com.memecreator.security;

import com.memecreator.account.AccountPrincipal;
import com.memecreator.account.AccountService;
import com.memecreator.account.UserAccount;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.io.Serial;
import java.util.Collection;
import java.util.Map;

/**
 * After Google or Facebook confirms who someone is, find or create their memecreator account (see
 * {@link AccountService#signInWithProvider}) and put its id and roles into the session's principal.
 */
final class AccountOAuth2UserServices {

    private AccountOAuth2UserServices() {
    }

    /** Google (OpenID Connect). */
    static OAuth2UserService<OidcUserRequest, OidcUser> oidc(AccountService accounts) {
        OidcUserService delegate = new OidcUserService();
        return request -> {
            OidcUser user = delegate.loadUser(request);
            String provider = request.getClientRegistration().getRegistrationId();
            UserAccount account = accounts.signInWithProvider(provider, user.getSubject(), user.getEmail(),
                    Boolean.TRUE.equals(user.getEmailVerified()), user.getFullName());
            return new AccountOidcUser(accounts.authorities(account), user.getIdToken(), user.getUserInfo(),
                    account.getId(), provider, user.getPicture());
        };
    }

    /** Facebook (plain OAuth2). Facebook only shares an email the person has confirmed with it. */
    static OAuth2UserService<OAuth2UserRequest, OAuth2User> oauth2(AccountService accounts) {
        DefaultOAuth2UserService delegate = new DefaultOAuth2UserService();
        return request -> {
            OAuth2User user = delegate.loadUser(request);
            String provider = request.getClientRegistration().getRegistrationId();
            String nameKey = request.getClientRegistration().getProviderDetails().getUserInfoEndpoint()
                    .getUserNameAttributeName();
            Map<String, Object> a = user.getAttributes();
            String email = a.get("email") instanceof String s ? s : null;
            String name = a.get("name") instanceof String s ? s : null;
            UserAccount account = accounts.signInWithProvider(provider, user.getName(), email, email != null, name);
            return new AccountOAuth2User(accounts.authorities(account), a, nameKey, account.getId(), provider,
                    picture(a.get("picture")));
        };
    }

    /** Facebook sends {"data": {"url": ...}}. */
    private static String picture(Object value) {
        if (value instanceof Map<?, ?> m && m.get("data") instanceof Map<?, ?> data && data.get("url") != null) {
            return data.get("url").toString();
        }
        return value instanceof String s ? s : null;
    }

    static final class AccountOidcUser extends DefaultOidcUser implements AccountPrincipal {
        @Serial
        private static final long serialVersionUID = 1L;
        private final long accountId;
        private final String provider;
        private final String picture;

        AccountOidcUser(Collection<? extends GrantedAuthority> authorities, OidcIdToken idToken,
                        OidcUserInfo userInfo, long accountId, String provider, String picture) {
            super(authorities, idToken, userInfo);
            this.accountId = accountId;
            this.provider = provider;
            this.picture = picture;
        }

        @Override
        public long accountId() {
            return accountId;
        }

        @Override
        public String signInMethod() {
            return provider;
        }

        @Override
        public String picture() {
            return picture;
        }
    }

    static final class AccountOAuth2User extends DefaultOAuth2User implements AccountPrincipal {
        @Serial
        private static final long serialVersionUID = 1L;
        private final long accountId;
        private final String provider;
        private final String picture;

        AccountOAuth2User(Collection<? extends GrantedAuthority> authorities, Map<String, Object> attributes,
                          String nameKey, long accountId, String provider, String picture) {
            super(authorities, attributes, nameKey);
            this.accountId = accountId;
            this.provider = provider;
            this.picture = picture;
        }

        @Override
        public long accountId() {
            return accountId;
        }

        @Override
        public String signInMethod() {
            return provider;
        }

        @Override
        public String picture() {
            return picture;
        }
    }
}
