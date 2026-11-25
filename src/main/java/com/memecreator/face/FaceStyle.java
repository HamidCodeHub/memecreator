package com.memecreator.face;

/** How the user's face is put onto a template face. */
public enum FaceStyle {

    /**
     * An image-editing AI model puts the user's cut-out head into the template face, matching pose, expression and
     * light. Sends the user's photo to the AI provider; only through the /with-face/ai endpoints.
     */
    AI,

    /**
     * The classic meme cut-out: the user's whole head (face, hair, ears, hat) cut out of their photo and pasted over
     * the template's head, a bit bigger, turned to match, without blending. Obvious on purpose.
     */
    HEAD,

    /**
     * As much of the user as possible: their own face shape, a bit bigger than the template face, reaching past the
     * face outline up the forehead and out at the cheeks and jaw, in mostly their own skin tone. The least
     * realistic, the most recognisable.
     */
    FULL,

    /**
     * Clearly the user: their own face shape (chin, jaw, face width) and mostly their own skin tone. The template face
     * only decides where the face goes, its size, tilt and direction. Less realistic, more recognisable.
     */
    BOLD,

    /** Melts into the template: the face takes the template face's shape, skin tone and lighting. */
    NATURAL;

    /** Lenient parse for request parameters: null or blank means the default, HEAD. */
    public static FaceStyle parse(String value) {
        if (value == null || value.isBlank()) {
            return HEAD;
        }
        try {
            return valueOf(value.strip().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("faceStyle must be HEAD, FULL, BOLD or NATURAL (AI has its own endpoints)");
        }
    }
}
