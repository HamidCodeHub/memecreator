package com.memecreator.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** A one-time link sent by email. The link carries the token; only its SHA-256 hash is stored here. */
@Entity
@Table(name = "email_token")
public class EmailToken {

    public enum Purpose { VERIFY, RESET }

    @Id
    @Column(name = "token_hash")
    private String tokenHash;

    @Column(name = "account_id")
    private Long accountId;

    @Enumerated(EnumType.STRING)
    private Purpose purpose;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    protected EmailToken() {
    }

    EmailToken(String tokenHash, Long accountId, Purpose purpose, Instant expiresAt) {
        this.tokenHash = tokenHash;
        this.accountId = accountId;
        this.purpose = purpose;
        this.expiresAt = expiresAt;
    }

    public Long getAccountId() {
        return accountId;
    }

    public Purpose getPurpose() {
        return purpose;
    }

    boolean usableAt(Instant now) {
        return usedAt == null && now.isBefore(expiresAt);
    }

    void markUsed(Instant now) {
        usedAt = now;
    }
}
