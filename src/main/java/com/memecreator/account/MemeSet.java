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

/** A set of memes made in the web app, owned by an account. The images live in meme.output-dir/&lt;folder&gt;. */
@Entity
@Table(name = "meme_set")
public class MemeSet {

    /** Where the set was made. API sets count toward the daily API quota. */
    public enum Source { WEB, API }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id")
    private Long accountId;

    /** Folder name inside meme.output-dir (not a full path). */
    private String folder;

    private String topic;

    @Enumerated(EnumType.STRING)
    private Source source = Source.WEB;

    @Column(name = "created_at")
    private Instant createdAt;

    protected MemeSet() {
    }

    public MemeSet(Long accountId, String folder, String topic, Source source, Instant createdAt) {
        this.accountId = accountId;
        this.folder = folder;
        this.topic = topic.length() > 300 ? topic.substring(0, 300) : topic;
        this.source = source;
        this.createdAt = createdAt;
    }

    public Source getSource() {
        return source;
    }

    public Long getAccountId() {
        return accountId;
    }

    public String getFolder() {
        return folder;
    }

    public String getTopic() {
        return topic;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
