package com.memecreator.harvest;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * One template in storage-dir/catalog.json. Mutable and with public fields: it is a JSON row that the harvester
 * updates in place while it holds the catalog lock.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CatalogEntry {

    public enum Status {
        /** In storage-dir/templates, used by the app. */
        ACTIVE,
        /** In storage-dir/needs-layout: more text boxes than the default layout and no layout written yet. */
        NEEDS_LAYOUT,
        /** File deleted by hand: never downloaded again (nor its near-duplicates). */
        REMOVED,
        /** Not usable (not an image, too small); kept so it isn't downloaded again. */
        REJECTED,
        /** A file in meme.templates-dir (your own folder), indexed so new downloads are compared with it. */
        LOCAL
    }

    /** Copy of the same template from another source (or URL), merged into this entry. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Alias(String key, String name, String imageUrl, Integer distance, Instant seen, String note) {
    }

    /** File-name id ("&lt;id&gt;_&lt;name&gt;.jpg"), numeric so {@code MemeTemplate.fromPath} splits it. */
    public String id;
    public String name;
    /** "imgflip", "memegen", "local", ... */
    public String source;
    public String sourceId;
    public Status status;
    /** ACTIVE/NEEDS_LAYOUT/REMOVED: file name inside its storage folder. LOCAL: absolute path. */
    public String file;
    public String note;

    public String sha256;
    /** Two 64-bit dHashes, "horizontal:vertical" in hex. */
    public String dhash;
    public int width;
    public int height;
    public int boxCount;

    public String imageUrl;
    public String pageUrl;
    /** What the source says about the image's license; "unknown" for meme sites (no license is given). */
    public String license;

    public Instant firstSeen;
    public Instant lastSeen;
    /** Last position in a ranked source's list (Imgflip top 100), null when it dropped out or isn't ranked. */
    public Integer lastRank;
    public Long popularity;

    /** LOCAL only: to re-hash a file only when it changes. */
    public Long fileSize;
    public Long fileModified;

    public List<Alias> aliases = new ArrayList<>();

    public String key() {
        return source + ":" + sourceId;
    }

    @JsonIgnore
    public boolean hasFingerprint() {
        return sha256 != null && dhash != null && width > 0 && height > 0;
    }

    @JsonIgnore
    public ImageFingerprint fingerprint() {
        return ImageFingerprint.fromCatalog(sha256, dhash, width, height);
    }

    public void applyFingerprint(ImageFingerprint fp) {
        sha256 = fp.sha256();
        dhash = fp.hashText();
        width = fp.width();
        height = fp.height();
    }
}
