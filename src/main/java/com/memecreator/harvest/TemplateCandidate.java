package com.memecreator.harvest;

/**
 * A template offered by a source, before it is downloaded and checked for duplicates.
 *
 * @param source     source name, e.g. "imgflip"
 * @param sourceId   the source's own id (stable across runs)
 * @param name       display name, e.g. "Drake Hotline Bling"
 * @param imageUrl   blank image (no captions)
 * @param pageUrl    where the template is described, or null
 * @param boxCount   number of text boxes the source says the format has, 0 if unknown
 * @param rank       position in the source's list (1 = first), 0 if the list is not ranked
 * @param popularity source-specific popularity (Imgflip: captions made recently), 0 if unknown
 */
public record TemplateCandidate(String source, String sourceId, String name, String imageUrl, String pageUrl,
                                int boxCount, int rank, long popularity) {

    public String key() {
        return source + ":" + sourceId;
    }
}
