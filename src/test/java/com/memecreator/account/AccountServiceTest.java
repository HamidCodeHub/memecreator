package com.memecreator.account;

import com.memecreator.config.MemeProperties;
import com.memecreator.security.SecurityProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** Against a real (in-memory H2) database with the Flyway migrations. */
@DataJpaTest
@Import({AccountService.class, AccountServiceTest.Config.class})
@EnableConfigurationProperties({AccountProperties.class, SecurityProperties.class, MemeProperties.class})
@TestPropertySource(properties = {"meme.security.admin-emails=boss@example.com",
        "meme.templates-dir=target/unused-templates", "meme.output-dir=" + AccountServiceTest.OUTPUT})
class AccountServiceTest {

    static final String TERMS = "2026-11-22";
    static final String OUTPUT = "target/account-service-test-output";

    @TestConfiguration
    static class Config {
        @Bean
        PasswordEncoder passwordEncoder() {
            return PasswordEncoderFactories.createDelegatingPasswordEncoder();
        }
    }

    @Autowired
    AccountService service;

    @Autowired
    UserAccountRepository accounts;

    @Autowired
    PasswordEncoder passwords;

    @Autowired
    MemeSetRepository memeSets;

    @Autowired
    LoginIdentityRepository identities;

    @Autowired
    JdbcTemplate jdbc;

    @MockBean
    AccountMailer mailer;

    /** The token in the last confirmation link mailed to this address. */
    String confirmToken(String email) {
        ArgumentCaptor<String> link = ArgumentCaptor.forClass(String.class);
        verify(mailer, org.mockito.Mockito.atLeastOnce()).confirmEmail(eq(email), link.capture());
        return link.getValue().substring(link.getValue().indexOf("?verify=") + "?verify=".length());
    }

    String resetToken(String email) {
        ArgumentCaptor<String> link = ArgumentCaptor.forClass(String.class);
        verify(mailer, org.mockito.Mockito.atLeastOnce()).resetPassword(eq(email), link.capture());
        return link.getValue().substring(link.getValue().indexOf("?reset=") + "?reset=".length());
    }

    @Test
    void signUpConfirmAndSignIn() {
        service.signUp("  Hamid@Example.com ", "correct horse battery", "Hamid", TERMS);
        UserAccount a = accounts.findByEmail("hamid@example.com").orElseThrow();
        assertFalse(a.isEmailVerified());
        assertFalse(service.loadUserByUsername("hamid@example.com").isEnabled()); // can't sign in yet

        String token = confirmToken("hamid@example.com");
        assertTrue(token.length() >= 40);
        assertTrue(service.confirmEmail(token).isEmailVerified());

        AccountUserDetails user = service.loadUserByUsername("HAMID@example.com");
        assertTrue(user.isEnabled());
        assertTrue(passwords.matches("correct horse battery", user.getPassword()));
        assertEquals("Hamid", accounts.findByEmail("hamid@example.com").orElseThrow().getDisplayName());

        // links work once
        assertThrows(AccountService.InvalidLinkException.class, () -> service.confirmEmail(token));
        assertThrows(AccountService.InvalidLinkException.class, () -> service.confirmEmail("made-up"));
    }

    @Test
    void signingUpAgainWithAConfirmedAddressChangesNothing() {
        service.signUp("hamid@example.com", "correct horse battery", null, TERMS);
        service.confirmEmail(confirmToken("hamid@example.com"));
        String hashBefore = service.loadUserByUsername("hamid@example.com").getPassword();

        service.signUp("hamid@example.com", "attacker password 123", null, TERMS);

        verify(mailer).alreadyRegistered(eq("hamid@example.com"), anyString());
        assertEquals(hashBefore, service.loadUserByUsername("hamid@example.com").getPassword());
    }

    @Test
    void passwordReset() {
        service.signUp("hamid@example.com", "correct horse battery", null, TERMS);
        service.requestPasswordReset("hamid@example.com");
        String token = resetToken("hamid@example.com");

        UserAccount a = service.resetPassword(token, "a brand new password");
        assertTrue(a.isEmailVerified()); // opening the emailed link proves the address
        assertTrue(passwords.matches("a brand new password",
                service.loadUserByUsername("hamid@example.com").getPassword()));
        assertThrows(AccountService.InvalidLinkException.class, () -> service.resetPassword(token, "another password!"));
        // a confirmation link can't be used as a reset link
        assertThrows(AccountService.InvalidLinkException.class,
                () -> service.resetPassword(confirmToken("hamid@example.com"), "another password!"));
    }

    @Test
    void unknownAddressesGetNoEmailButTheSameAnswer() {
        service.requestPasswordReset("nobody@example.com");
        service.resendConfirmation("nobody@example.com");
        verify(mailer, never()).resetPassword(anyString(), anyString());
        verify(mailer, never()).confirmEmail(anyString(), anyString());
    }

    @Test
    void passwordAndEmailRules() {
        assertThrows(IllegalArgumentException.class,
                () -> service.signUp("hamid@example.com", "short", null, TERMS));
        assertThrows(IllegalArgumentException.class,
                () -> service.signUp("hamid@example.com", "x".repeat(73), null, TERMS));
        assertThrows(IllegalArgumentException.class,
                () -> service.signUp("not-an-email", "long enough pw", null, TERMS));
        assertThrows(UsernameNotFoundException.class, () -> service.loadUserByUsername("nobody@example.com"));
    }

    @Test
    void googleSignInCreatesOrReusesTheAccount() {
        UserAccount first = service.signInWithProvider("google", "g-1", "Hamid@Example.com", true, "Hamid Y");
        UserAccount again = service.signInWithProvider("google", "g-1", "hamid@example.com", true, "Hamid Y");
        assertEquals(first.getId(), again.getId());
        assertTrue(first.isEmailVerified());
        assertFalse(first.hasPassword());
        assertThrows(UsernameNotFoundException.class, () -> service.loadUserByUsername("hamid@example.com"));

        // Facebook with the same confirmed email: the same account
        UserAccount facebook = service.signInWithProvider("facebook", "f-9", "hamid@example.com", true, "Hamid");
        assertEquals(first.getId(), facebook.getId());

        // A Google-only account can add a password through "forgot password"
        service.requestPasswordReset("hamid@example.com");
        service.resetPassword(resetToken("hamid@example.com"), "now with a password");
        assertTrue(service.loadUserByUsername("hamid@example.com").isEnabled());
    }

    @Test
    void someoneElsesUnconfirmedSignUpLosesItsPasswordWhenTheOwnerArrives() {
        service.signUp("victim@example.com", "attacker knows this", null, TERMS); // never confirmed
        UserAccount owner = service.signInWithProvider("google", "g-2", "victim@example.com", true, "Victim");
        assertTrue(owner.isEmailVerified());
        assertFalse(owner.hasPassword());
        assertThrows(UsernameNotFoundException.class, () -> service.loadUserByUsername("victim@example.com"));
        // and the attacker's old confirmation link no longer works
        assertThrows(AccountService.InvalidLinkException.class,
                () -> service.confirmEmail(confirmToken("victim@example.com")));
    }

    @Test
    void unconfirmedProviderEmailIsNotKept() {
        service.signUp("someone@example.com", "their own password", null, TERMS);
        UserAccount other = service.signInWithProvider("facebook", "f-1", "someone@example.com", false, "X");
        assertNull(other.getEmail());
        UserAccount theirs = accounts.findByEmail("someone@example.com").orElseThrow();
        assertNotEquals(theirs.getId(), other.getId());
        assertTrue(theirs.hasPassword());
    }

    @Test
    void adminRoleNeedsAConfirmedListedEmail() {
        UserAccount boss = service.signInWithProvider("google", "g-3", "boss@example.com", true, "Boss");
        assertTrue(service.authorities(boss).stream().map(GrantedAuthority::getAuthority)
                .anyMatch("ROLE_ADMIN"::equals));
    }

    // ---- terms and deletion -----------------------------------------------------------------------------------

    @Test
    void signUpRecordsTheAcceptedTermsAndRejectsAnOldVersion() {
        assertThrows(AccountService.TermsVersionMismatchException.class,
                () -> service.signUp("t@example.com", "long enough pw", null, "2020-01-01"));
        assertTrue(accounts.findByEmail("t@example.com").isEmpty());

        service.signUp("t@example.com", "long enough pw", null, TERMS);
        UserAccount a = accounts.findByEmail("t@example.com").orElseThrow();
        assertEquals(TERMS, a.getTermsVersion());
        assertTrue(a.getTermsAcceptedAt() != null);
        assertTrue(service.acceptedCurrentTerms(a));
    }

    @Test
    void googleAccountsAcceptTheTermsInTheApp() {
        UserAccount a = service.signInWithProvider("google", "g-9", "g@example.com", true, "G");
        assertFalse(service.acceptedCurrentTerms(a));
        service.acceptTerms(a.getId(), TERMS);
        assertTrue(service.acceptedCurrentTerms(accounts.findById(a.getId()).orElseThrow()));
        assertThrows(AccountService.TermsVersionMismatchException.class, () -> service.acceptTerms(a.getId(), "x"));
    }

    @Test
    void takingOverAnUnconfirmedSignUpDropsItsTermsAcceptance() {
        service.signUp("victim2@example.com", "attacker knows this", null, TERMS);
        UserAccount owner = service.signInWithProvider("google", "g-10", "victim2@example.com", true, "V");
        assertNull(accounts.findById(owner.getId()).orElseThrow().getTermsVersion());
    }

    @Test
    void deletingTheAccountRemovesItsRowsAndMemeFoldersOnly() throws Exception {
        Path out = Path.of(OUTPUT).toAbsolutePath();
        Path mine = Files.createDirectories(out.resolve("20261122-100000_cats"));
        Files.writeString(mine.resolve("01_drake.jpg"), "image");
        Path other = Files.createDirectories(out.resolve("20261122-100001_dogs"));

        UserAccount a = service.signInWithProvider("google", "g-11", "del@example.com", true, "D");
        service.requestPasswordReset("del@example.com"); // an email link row too
        service.recordMemeSet(a.getId(), mine.toString(), "cats", MemeSet.Source.WEB);
        accounts.flush(); // before plain SQL: pending JPA updates would overwrite it
        // A bad record must never reach outside the output folder
        jdbc.update("insert into meme_set (account_id, folder, topic, created_at) values (?, '..', 'x', current_timestamp)",
                a.getId());

        service.deleteAccount(a.getId());

        assertTrue(accounts.findById(a.getId()).isEmpty());
        assertTrue(identities.findByProviderAndSubject("google", "g-11").isEmpty());
        assertTrue(memeSets.findByAccountId(a.getId()).isEmpty());
        assertEquals(0, jdbc.queryForObject("select count(*) from email_token where account_id = ?",
                Integer.class, a.getId()));
        assertFalse(Files.exists(mine));
        assertTrue(Files.isDirectory(other));
        assertTrue(Files.isDirectory(out));
    }

    @Test
    void signUpsNeverConfirmedAreDeletedAfterAWeek() {
        service.signUp("old@example.com", "long enough pw", null, TERMS);
        service.signUp("recent@example.com", "long enough pw", null, TERMS);
        accounts.flush(); // before plain SQL: pending JPA updates would overwrite it
        jdbc.update("update user_account set created_at = dateadd('DAY', -8, current_timestamp) "
                + "where email = 'old@example.com'");
        UserAccount google = service.signInWithProvider("google", "g-12", "keep@example.com", true, "K");
        accounts.flush();
        jdbc.update("update user_account set created_at = dateadd('DAY', -30, current_timestamp) where id = ?",
                google.getId());

        service.deleteExpiredLinks();

        assertTrue(accounts.findByEmail("old@example.com").isEmpty());
        assertTrue(accounts.findByEmail("recent@example.com").isPresent());
        assertTrue(accounts.findById(google.getId()).isPresent());
    }
}
