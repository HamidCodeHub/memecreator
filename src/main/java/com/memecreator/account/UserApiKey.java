package com.memecreator.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** A personal API key. Only its SHA-256 hash is stored; the key is shown once, when created. */
@Entity
@Table(name = "user_api_key")
public class UserApiKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id")
    private Long accountId;

    @Column(name = "key_hash")
    private String keyHash;

    /** "mcu_" plus the first characters, to tell keys apart. */
    private String prefix;

    private String name;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    protected UserApiKey() {
    }

    UserApiKey(Long accountId, String keyHash, String prefix, String name, Instant createdAt) {
        this.accountId = accountId;
        this.keyHash = keyHash;
        this.prefix = prefix;
        this.name = name;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public Long getAccountId() {
        return accountId;
    }

    public String getPrefix() {
        return prefix;
    }

    public String getName() {
        return name;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    void usedAt(Instant at) {
        this.lastUsedAt = at;
    }
}
