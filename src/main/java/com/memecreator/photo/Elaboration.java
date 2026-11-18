package com.memecreator.photo;

/** How much AI work goes into memes from an uploaded photo. */
public enum Elaboration {
    /** 1 AI call: the vision model looks at the photo and writes the captions directly. */
    NORMAL,
    /**
     * 2 AI calls: the vision model describes the photo in detail, then the text model writes the captions
     * from that description. Usually funnier, but slower and uses more of the rate limit.
     */
    HIGH
}
