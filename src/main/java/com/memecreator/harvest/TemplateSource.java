package com.memecreator.harvest;

import java.util.List;

/** A public list of meme templates. Add a source by implementing this as a Spring bean. */
public interface TemplateSource {

    /** Short lower-case name, used in the catalog and in {@code POST /api/templates/harvest?source=}. */
    String name();

    /** The templates the source offers right now. Throws on network/format errors. */
    List<TemplateCandidate> fetch();

    /** Ranked sources get a daily snapshot of their order in storage-dir/&lt;name&gt;-ranks.jsonl. */
    default boolean ranked() {
        return false;
    }
}
