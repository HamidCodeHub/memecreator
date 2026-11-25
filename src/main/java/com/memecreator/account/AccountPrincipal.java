package com.memecreator.account;

/**
 * What every signed-in person's principal knows, however they signed in (password, Google, Facebook):
 * which account they are. Controllers use this instead of looking at the sign-in method.
 */
public interface AccountPrincipal {

    long accountId();

    /** "password", "google" or "facebook". */
    String signInMethod();

    /** Avatar from Google/Facebook, or null. Kept in the session only, never stored. */
    default String picture() {
        return null;
    }
}
