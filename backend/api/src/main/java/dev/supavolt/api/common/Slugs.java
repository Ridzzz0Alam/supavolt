package dev.supavolt.api.common;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * URL slugs, with the same rules as the Slugify.Core defaults the .NET API used: strip accents,
 * lowercase, spaces to dashes, drop anything outside {@code [a-z0-9-._]}, collapse dashes.
 */
public final class Slugs {

    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern DENIED = Pattern.compile("[^a-z0-9\\-._]");
    private static final Pattern DASHES = Pattern.compile("-{2,}");

    private Slugs() {
    }

    public static String slugify(String input) {
        var text = MARKS.matcher(Normalizer.normalize(input, Normalizer.Form.NFD)).replaceAll("");
        text = WHITESPACE.matcher(text.trim().toLowerCase(Locale.ROOT)).replaceAll("-");
        text = DENIED.matcher(text).replaceAll("");
        return DASHES.matcher(text).replaceAll("-");
    }

    /** A readable slug made unique with six random hex characters. */
    public static String unique(String input) {
        return slugify(input) + "-" + Tokens.randomHex(6);
    }
}
