package com.memecreator.account;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.memecreator.account.EmailToken.Purpose;
import com.memecreator.config.MemeProperties;
import com.memecreator.security.SecurityProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Accounts: sign-up with email + password (confirmed by an emailed link), password reset, and linking Google /
 * Facebook sign-ins to accounts.
 * <p>
 * Answers never reveal whether an email has an account: sign-up, "send the link again" and "forgot password"
 * behave the same for known and unknown addresses; only the email that arrives differs.
 */
@Service
@Transactional
public class AccountService implements UserDetailsService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);
    /** Deliberately loose: the confirmation email is the real check. */
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]{1,64}@[^@\\s]+\\.[^@\\s]{2,}$");
    /** bcrypt reads at most 72 bytes; longer passwords would be silently cut. */
    static final int MAX_PASSWORD_BYTES = 72;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserAccountRepository accounts;
    private final LoginIdentityRepository identities;
    private final EmailTokenRepository tokens;
    private final PasswordEncoder passwords;
    private final AccountMailer mailer;
    private final AccountProperties props;
    private final SecurityProperties security;
    private final MemeSetRepository memeSets;
    private final MemeRecordRepository memeRecords;
    private final Path outputDir;
    private final Clock clock;

    public AccountService(UserAccountRepository accounts, LoginIdentityRepository identities,
                          EmailTokenRepository tokens, PasswordEncoder passwords, AccountMailer mailer,
                          AccountProperties props, SecurityProperties security, MemeSetRepository memeSets,
                          MemeRecordRepository memeRecords, MemeProperties memeProps) {
        this.accounts = accounts;
        this.identities = identities;
        this.tokens = tokens;
        this.passwords = passwords;
        this.mailer = mailer;
        this.props = props;
        this.security = security;
        this.memeSets = memeSets;
        this.memeRecords = memeRecords;
        this.outputDir = Path.of(memeProps.outputDir()).toAbsolutePath().normalize();
        this.clock = Clock.systemUTC();
    }

    /** The version of the Terms of use / Privacy policy people must accept now. */
    public String currentTermsVersion() {
        return props.termsVersion();
    }

    /** Whether this account accepted the current Terms of use / Privacy policy. */
    public boolean acceptedCurrentTerms(UserAccount a) {
        return props.termsVersion().equals(a.getTermsVersion());
    }

    /** Making memes, sharing and API keys need the current terms accepted. */
    public static final class TermsNotAcceptedException extends RuntimeException {
        public TermsNotAcceptedException() {
            super("Accept the current Terms of use and Privacy policy first.");
        }
    }

    /** The account, if it accepted the current terms; throws {@link TermsNotAcceptedException} otherwise. */
    @Transactional(readOnly = true)
    public UserAccount requireCurrentTerms(long accountId) {
        UserAccount a = accounts.findById(accountId).orElseThrow(TermsNotAcceptedException::new);
        if (!acceptedCurrentTerms(a)) {
            throw new TermsNotAcceptedException();
        }
        return a;
    }

    /** The terms shown changed while the person was reading them (or the client is old): reload and accept again. */
    public static final class TermsVersionMismatchException extends RuntimeException {
        public TermsVersionMismatchException() {
            super("The Terms of use were updated. Reload the page and accept the current version.");
        }
    }

    /** A link in an email was wrong, used or expired. */
    public static final class InvalidLinkException extends RuntimeException {
        InvalidLinkException(String message) {
            super(message);
        }
    }

    // ---- sign-up and confirmation -----------------------------------------------------------------------------

    /**
     * Starts a sign-up: new (or still unconfirmed) addresses get a confirmation link; confirmed ones get a "you
     * already have an account" email instead. The caller answers the same in every case.
     */
    public void signUp(String rawEmail, String password, String displayName, String acceptedTermsVersion) {
        String email = normalizeEmail(rawEmail);
        checkPassword(password);
        checkTermsVersion(acceptedTermsVersion);
        String name = cleanName(displayName);
        Optional<UserAccount> existing = accounts.findByEmail(email);
        if (existing.isPresent() && existing.get().isEmailVerified()) {
            mailer.alreadyRegistered(email, link("reset", newToken(existing.get(), Purpose.RESET)));
            return;
        }
        UserAccount account = existing.orElseGet(() ->
                accounts.save(new UserAccount(email, name, null, false, clock.instant())));
        // Unconfirmed: whoever confirms the address owns the account, with the password chosen last
        account.setPasswordHash(passwords.encode(password));
        if (name != null) {
            account.setDisplayName(name);
        }
        account.acceptTerms(acceptedTermsVersion, clock.instant());
        mailer.confirmEmail(email, link("verify", newToken(account, Purpose.VERIFY)));
    }

    /** Opens a confirmation link: the address is confirmed and the account can sign in. */
    public UserAccount confirmEmail(String token) {
        EmailToken t = use(token, Purpose.VERIFY);
        UserAccount account = accounts.findById(t.getAccountId())
                .orElseThrow(() -> new InvalidLinkException("This account no longer exists."));
        account.setEmailVerified(true);
        tokens.useAll(account.getId(), Purpose.VERIFY, clock.instant());
        return account;
    }

    /** "Send the link again" for an unconfirmed sign-up. Silently does nothing for other addresses. */
    public void resendConfirmation(String rawEmail) {
        String email = normalizeEmail(rawEmail);
        accounts.findByEmail(email)
                .filter(a -> !a.isEmailVerified() && a.hasPassword())
                .ifPresent(a -> mailer.confirmEmail(email, link("verify", newToken(a, Purpose.VERIFY))));
    }

    // ---- password reset ---------------------------------------------------------------------------------------

    /** "Forgot password": any account with this address gets a reset link (Google-only accounts can add one). */
    public void requestPasswordReset(String rawEmail) {
        String email = normalizeEmail(rawEmail);
        accounts.findByEmail(email)
                .ifPresent(a -> mailer.resetPassword(email, link("reset", newToken(a, Purpose.RESET))));
    }

    /** Sets the new password; opening the emailed link also proves the address, so it counts as confirmed. */
    public UserAccount resetPassword(String token, String newPassword) {
        checkPassword(newPassword);
        EmailToken t = use(token, Purpose.RESET);
        UserAccount account = accounts.findById(t.getAccountId())
                .orElseThrow(() -> new InvalidLinkException("This account no longer exists."));
        account.setPasswordHash(passwords.encode(newPassword));
        account.setEmailVerified(true);
        tokens.useAll(account.getId(), Purpose.RESET, clock.instant());
        tokens.useAll(account.getId(), Purpose.VERIFY, clock.instant());
        return account;
    }

    // ---- Google / Facebook ------------------------------------------------------------------------------------

    /**
     * The account for a Google/Facebook sign-in: the linked one, else the one with the same confirmed email, else
     * a new one.
     * <p>
     * Linking to an existing but unconfirmed email + password account removes its password: someone else may
     * have signed up with this address first, hoping to share the account once its owner arrives.
     */
    public UserAccount signInWithProvider(String provider, String subject, String rawEmail, boolean emailVerified,
                                          String displayName) {
        Instant now = clock.instant();
        Optional<LoginIdentity> identity = identities.findByProviderAndSubject(provider, subject);
        if (identity.isPresent()) {
            UserAccount account = accounts.findById(identity.get().getAccountId()).orElseThrow();
            if (account.getDisplayName() == null) {
                account.setDisplayName(cleanName(displayName));
            }
            return account;
        }
        String email = rawEmail == null || rawEmail.isBlank() ? null : rawEmail.trim().toLowerCase(Locale.ROOT);
        UserAccount account = null;
        if (email != null && emailVerified) {
            account = accounts.findByEmail(email).orElse(null);
            if (account != null && !account.isEmailVerified()) {
                account.setPasswordHash(null);
                account.setEmailVerified(true);
                account.acceptTerms(null, null); // accepted by whoever signed up, not necessarily this person
                tokens.useAll(account.getId(), Purpose.VERIFY, now);
                log.info("Account {}: unconfirmed password removed, linked to {}", account.getId(), provider);
            }
        }
        if (account == null) {
            // An unconfirmed address is not kept: it must not block that address for a real sign-up
            String keep = email != null && emailVerified ? email : null;
            account = accounts.save(new UserAccount(keep, cleanName(displayName), null, keep != null, now));
        }
        identities.save(new LoginIdentity(provider, subject, account.getId(), now));
        return account;
    }

    // ---- terms, meme sets, deletion ---------------------------------------------------------------------------

    /** Records that the account accepted this version of the Terms of use / Privacy policy, and when. */
    public UserAccount acceptTerms(long accountId, String version) {
        checkTermsVersion(version);
        UserAccount account = accounts.findById(accountId).orElseThrow();
        account.acceptTerms(version, clock.instant());
        log.info("Account {} accepted terms {}", accountId, version);
        return account;
    }

    /**
     * Admin only: puts the account with this email on FREE or PREMIUM (no payments yet; used for alpha testers).
     *
     * @throws IllegalArgumentException when no account has this email
     */
    public UserAccount setPlan(String rawEmail, UserAccount.Plan plan) {
        String email = rawEmail == null ? "" : rawEmail.trim().toLowerCase(Locale.ROOT);
        UserAccount account = accounts.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("No account with the email " + email));
        account.setPlan(plan);
        log.info("Account {} is now on the {} plan", account.getId(), plan);
        return account;
    }

    /** Remembers which folder holds a set made by this account, so it goes when the account goes. */
    public void recordMemeSet(long accountId, String folderPath, String topic, MemeSet.Source source) {
        memeSets.save(new MemeSet(accountId, Path.of(folderPath).getFileName().toString(), topic, source,
                clock.instant()));
    }

    /**
     * How one meme of a set was made, so its words can be edited later.
     *
     * @param url        "/generated/&lt;folder&gt;/&lt;file&gt;"
     * @param template   for a template meme: its name; null for a photo meme
     * @param photoStyle for a photo meme: its caption style's name; null for a template meme
     */
    public record MemeRecipe(String url, String template, String photoStyle, List<String> texts) {
    }

    /** Remembers each meme's recipe (best effort: a failure here must not lose the memes already made). */
    public void recordMemes(long accountId, List<MemeRecipe> recipes) {
        for (MemeRecipe r : recipes) {
            try {
                String path = r.url().startsWith("/generated/") ? r.url().substring("/generated/".length()) : r.url();
                int slash = path.indexOf('/');
                String folder = path.substring(0, slash);
                String file = path.substring(slash + 1);
                String json = JSON.writeValueAsString(r.texts() == null ? List.of() : r.texts());
                memeRecords.save(new MemeRecord(accountId, folder, file,
                        r.template() != null ? MemeRecord.Kind.TEMPLATE : MemeRecord.Kind.PHOTO,
                        r.template(), r.photoStyle(), json, json, file, clock.instant()));
            } catch (RuntimeException | JsonProcessingException e) {
                log.warn("Could not record meme {}: {}", r.url(), e.toString());
            }
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Deletes the account: its row and, by cascade, its sign-in links, email links and meme-set records; then the
     * meme folders it made. Folder deletion is best effort: a failure is logged, not shown (the account is gone).
     */
    public void deleteAccount(long accountId) {
        List<String> folders = memeSets.findByAccountId(accountId).stream().map(MemeSet::getFolder).toList();
        accounts.deleteById(accountId);
        accounts.flush();
        int deleted = 0;
        for (String folder : folders) {
            if (deleteFolder(folder)) {
                deleted++;
            }
        }
        log.info("Account {} deleted, with {} of {} meme folders", accountId, deleted, folders.size());
    }

    /** Only folders directly inside meme.output-dir; never the folder itself or anything outside it. */
    private boolean deleteFolder(String folder) {
        Path dir = outputDir.resolve(folder).normalize();
        if (!dir.startsWith(outputDir) || dir.equals(outputDir) || !dir.getParent().equals(outputDir)) {
            log.warn("Not deleting {}: outside {}", dir, outputDir);
            return false;
        }
        if (!Files.isDirectory(dir)) {
            return false;
        }
        try (var walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
            return true;
        } catch (IOException | UncheckedIOException e) {
            log.warn("Could not delete meme folder {}: {}", dir, e.toString());
            return false;
        }
    }

    // ---- reading ----------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Optional<UserAccount> find(long id) {
        return accounts.findById(id);
    }

    /** For password sign-in. Accounts without a password (Google/Facebook only) can't sign in this way. */
    @Override
    @Transactional(readOnly = true)
    public AccountUserDetails loadUserByUsername(String rawEmail) {
        String email = rawEmail == null ? "" : rawEmail.trim().toLowerCase(Locale.ROOT);
        UserAccount a = accounts.findByEmail(email).filter(UserAccount::hasPassword)
                .orElseThrow(() -> new UsernameNotFoundException("no password account"));
        return userDetails(a);
    }

    public AccountUserDetails userDetails(UserAccount a) {
        return new AccountUserDetails(a.getId(), a.getEmail(), a.getPasswordHash(), a.isEmailVerified(),
                authorities(a));
    }

    /** USER for everyone; ADMIN for a confirmed address listed in meme.security.admin-emails. */
    public List<GrantedAuthority> authorities(UserAccount a) {
        List<GrantedAuthority> out = new ArrayList<>();
        out.add(new SimpleGrantedAuthority("ROLE_USER"));
        if (a.isEmailVerified() && security.isAdminEmail(a.getEmail())) {
            out.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        }
        return out;
    }

    /** Nightly clean-up: old links, and email + password sign-ups never confirmed (privacy policy: 7 days). */
    @Scheduled(cron = "0 10 3 * * *")
    public void deleteExpiredLinks() {
        int n = tokens.deleteExpiredBefore(clock.instant().minus(Duration.ofDays(1)));
        if (n > 0) {
            log.info("Deleted {} expired email links", n);
        }
        int u = accounts.deleteUnconfirmedCreatedBefore(clock.instant().minus(props.unconfirmedTtl()));
        if (u > 0) {
            log.info("Deleted {} sign-ups never confirmed", u);
        }
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    /** Lower-case, trimmed, plausible address; throws IllegalArgumentException otherwise. */
    public static String normalizeEmail(String raw) {
        String email = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (email.length() > 320 || !EMAIL.matcher(email).matches()) {
            throw new IllegalArgumentException("Enter a valid email address.");
        }
        return email;
    }

    private void checkTermsVersion(String version) {
        if (!props.termsVersion().equals(version)) {
            throw new TermsVersionMismatchException();
        }
    }

    void checkPassword(String password) {
        if (password == null || password.length() < props.minPasswordLength()) {
            throw new IllegalArgumentException(
                    "Use at least " + props.minPasswordLength() + " characters for your password.");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw new IllegalArgumentException("Use at most 64 characters for your password.");
        }
    }

    private static String cleanName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String n = name.strip().replaceAll("\\p{Cntrl}", "");
        return n.length() > 120 ? n.substring(0, 120) : n;
    }

    private String newToken(UserAccount account, Purpose purpose) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Duration ttl = purpose == Purpose.VERIFY ? props.verifyLinkTtl() : props.resetLinkTtl();
        tokens.save(new EmailToken(hash(token), account.getId(), purpose, clock.instant().plus(ttl)));
        return token;
    }

    private EmailToken use(String token, Purpose purpose) {
        if (token == null || token.isBlank() || token.length() > 100) {
            throw new InvalidLinkException("This link is not valid.");
        }
        EmailToken t = tokens.findById(hash(token.trim()))
                .filter(x -> x.getPurpose() == purpose)
                .orElseThrow(() -> new InvalidLinkException("This link is not valid."));
        Instant now = clock.instant();
        if (!t.usableAt(now)) {
            throw new InvalidLinkException(purpose == Purpose.VERIFY
                    ? "This confirmation link has expired or was already used. Sign in, or ask for a new link."
                    : "This password link has expired or was already used. Ask for a new one.");
        }
        t.markUsed(now);
        return t;
    }

    private String link(String param, String token) {
        String base = props.publicUrl().endsWith("/") ? props.publicUrl() : props.publicUrl() + "/";
        return base + "?" + param + "=" + token;
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
