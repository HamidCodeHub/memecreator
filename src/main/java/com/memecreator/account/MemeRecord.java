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

/**
 * How one meme was made, so its words can be edited: a template and its texts, or the set's uploaded photo with a
 * caption style. An edit becomes a new record and a new file; {@link #getAiFile()} is the AI's original.
 */
@Entity
@Table(name = "meme")
public class MemeRecord {

    public enum Kind { TEMPLATE, PHOTO }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id")
    private Long accountId;

    private String folder;

    private String file;

    @Enumerated(EnumType.STRING)
    private Kind kind;

    /** TEMPLATE: the template's name. */
    private String template;

    /** PHOTO: the caption style's name. */
    @Column(name = "photo_style")
    private String photoStyle;

    /** JSON array. */
    private String texts;

    @Column(name = "ai_texts")
    private String aiTexts;

    @Column(name = "ai_file")
    private String aiFile;

    @Column(name = "created_at")
    private Instant createdAt;

    protected MemeRecord() {
    }

    public MemeRecord(Long accountId, String folder, String file, Kind kind, String template, String photoStyle,
                      String texts, String aiTexts, String aiFile, Instant createdAt) {
        this.accountId = accountId;
        this.folder = folder;
        this.file = file;
        this.kind = kind;
        this.template = template;
        this.photoStyle = photoStyle;
        this.texts = texts;
        this.aiTexts = aiTexts;
        this.aiFile = aiFile;
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

    public Kind getKind() {
        return kind;
    }

    public String getTemplate() {
        return template;
    }

    public String getPhotoStyle() {
        return photoStyle;
    }

    public String getTexts() {
        return texts;
    }

    public String getAiTexts() {
        return aiTexts;
    }

    public String getAiFile() {
        return aiFile;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getUrl() {
        return "/generated/" + folder + "/" + file;
    }
}
