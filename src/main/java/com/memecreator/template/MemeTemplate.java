package com.memecreator.template;

import java.nio.file.Path;
import java.util.Locale;

/**
 * A meme template image. Files are named like "181913649_Drake Hotline Bling.jpg":
 * the numeric prefix is the id, the rest is the display name.
 */
public record MemeTemplate(String id, String name, Path path) {

    public static MemeTemplate fromPath(Path path) {
        String file = path.getFileName().toString();
        int dot = file.lastIndexOf('.');
        String base = dot > 0 ? file.substring(0, dot) : file;

        int underscore = base.indexOf('_');
        if (underscore > 0 && base.substring(0, underscore).chars().allMatch(Character::isDigit)) {
            return new MemeTemplate(base.substring(0, underscore), base.substring(underscore + 1).trim(), path);
        }
        return new MemeTemplate(base, base, path);
    }

    /** Lower-case letters and digits only, used for forgiving name matching. */
    public static String normalize(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    public String normalizedName() {
        return normalize(name);
    }
}
