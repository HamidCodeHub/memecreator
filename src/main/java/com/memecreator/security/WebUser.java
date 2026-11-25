package com.memecreator.security;

import com.memecreator.account.AccountPrincipal;
import com.memecreator.account.UserAccount;

/**
 * The signed-in person, as the web app shows them.
 *
 * @param id           stable per account: "account:&lt;id&gt;"
 * @param signInMethod "password", "google" or "facebook" (how they signed in this time)
 * @param name         display name, or null
 * @param email        null when no confirmed email is known
 * @param picture      avatar URL from Google/Facebook, or null
 * @param hasPassword  whether email + password sign-in works for this account
 * @param termsAccepted whether the current Terms of use / Privacy policy were accepted; when false the app asks
 *                      first, and making memes answers 403 TERMS_NOT_ACCEPTED
 * @param termsVersion  the current version, to send back when accepting
 */
public record WebUser(String id, String signInMethod, String name, String email, String picture,
                      boolean hasPassword, boolean termsAccepted, String termsVersion) {

    public static WebUser of(AccountPrincipal principal, UserAccount account, String currentTermsVersion) {
        return new WebUser("account:" + account.getId(), principal.signInMethod(), account.getDisplayName(),
                account.getEmail(), principal.picture(), account.hasPassword(),
                currentTermsVersion != null && currentTermsVersion.equals(account.getTermsVersion()),
                currentTermsVersion);
    }
}
