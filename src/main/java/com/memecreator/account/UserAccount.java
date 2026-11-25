package com.memecreator.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** A person using the web app. See V1__accounts.sql. */
@Entity
@Table(name = "user_account")
public class UserAccount {

    public enum Plan { FREE, PREMIUM }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Lower-case, or null when the account came from a provider that shared no confirmed email. */
    private String email;

    @Column(name = "display_name")
    private String displayName;

    @Column(name = "password_hash")
    private String passwordHash;

    @Column(name = "email_verified")
    private boolean emailVerified;

    @Enumerated(EnumType.STRING)
    private Plan plan = Plan.FREE;

    @Column(name = "created_at")
    private Instant createdAt;

    /** Version of the Terms of use / Privacy policy accepted (e.g. "2026-11-22"), or null. */
    @Column(name = "terms_version")
    private String termsVersion;

    @Column(name = "terms_accepted_at")
    private Instant termsAcceptedAt;

    protected UserAccount() {
    }

    UserAccount(String email, String displayName, String passwordHash, boolean emailVerified, Instant createdAt) {
        this.email = email;
        this.displayName = displayName;
        this.passwordHash = passwordHash;
        this.emailVerified = emailVerified;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    void setEmail(String email) {
        this.email = email;
    }

    public String getDisplayName() {
        return displayName;
    }

    void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    String getPasswordHash() {
        return passwordHash;
    }

    void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public boolean hasPassword() {
        return passwordHash != null;
    }

    public boolean isEmailVerified() {
        return emailVerified;
    }

    void setEmailVerified(boolean emailVerified) {
        this.emailVerified = emailVerified;
    }

    public Plan getPlan() {
        return plan;
    }

    void setPlan(Plan plan) {
        this.plan = plan;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getTermsVersion() {
        return termsVersion;
    }

    public Instant getTermsAcceptedAt() {
        return termsAcceptedAt;
    }

    void acceptTerms(String version, Instant at) {
        this.termsVersion = version;
        this.termsAcceptedAt = at;
    }
}
