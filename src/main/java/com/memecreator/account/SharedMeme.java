package com.memecreator.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** A meme someone chose to share: public at /s/&lt;token&gt; until the account is deleted. */
@Entity
@Table(name = "shared_meme")
public class SharedMeme {

    @Id
    private String token;

    @Column(name = "account_id")
    private Long accountId;

    private String folder;

    private String file;

    private String template;

    private String caption;

    @Column(name = "created_at")
    private Instant createdAt;

    protected SharedMeme() {
    }

    SharedMeme(String token, Long accountId, MemeLibrary.MemeRef ref, String template, String caption,
               Instant createdAt) {
        this.token = token;
        this.accountId = accountId;
        this.folder = ref.folder();
        this.file = ref.file();
        this.template = template;
        this.caption = caption;
        this.createdAt = createdAt;
    }

    public String getToken() {
        return token;
    }

    public Long getAccountId() {
        return accountId;
    }

    public String getFolder() {
        return folder;
    }

    public String getFile() {
        return file;
    }

    public String getTemplate() {
        return template;
    }

    public String getCaption() {
        return caption;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
