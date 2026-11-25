package com.memecreator.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** A meme someone marked with the heart. */
@Entity
@Table(name = "favorite")
public class Favorite {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id")
    private Long accountId;

    private String folder;

    private String file;

    private String template;

    /** The meme's texts, one per line. */
    private String caption;

    @Column(name = "created_at")
    private Instant createdAt;

    protected Favorite() {
    }

    Favorite(Long accountId, MemeLibrary.MemeRef ref, String template, String caption, Instant createdAt) {
        this.accountId = accountId;
        this.folder = ref.folder();
        this.file = ref.file();
        this.template = template;
        this.caption = caption;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
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

    /** Where the browser loads the image (signed-in only). */
    public String getUrl() {
        return "/generated/" + folder + "/" + file;
    }
}
