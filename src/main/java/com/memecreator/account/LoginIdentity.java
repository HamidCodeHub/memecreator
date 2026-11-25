package com.memecreator.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** A Google or Facebook sign-in linked to an account. */
@Entity
@Table(name = "login_identity")
public class LoginIdentity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String provider;

    /** The provider's id for the person (Google "sub", Facebook app-scoped "id"). */
    private String subject;

    @Column(name = "account_id")
    private Long accountId;

    @Column(name = "created_at")
    private Instant createdAt;

    protected LoginIdentity() {
    }

    LoginIdentity(String provider, String subject, Long accountId, Instant createdAt) {
        this.provider = provider;
        this.subject = subject;
        this.accountId = accountId;
        this.createdAt = createdAt;
    }

    public String getProvider() {
        return provider;
    }

    public String getSubject() {
        return subject;
    }

    public Long getAccountId() {
        return accountId;
    }
}
