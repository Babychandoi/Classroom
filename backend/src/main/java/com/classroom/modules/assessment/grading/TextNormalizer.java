package com.classroom.modules.assessment.grading;

import com.classroom.modules.assessment.doc.Normalization;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * D-34: Vietnamese-safe text normalization for answer matching.
 * <ul>
 *   <li>always: Unicode NFC (an NFD "Hà" equals an NFC "Hà"), non-breaking spaces to spaces, curly quotes to straight ones, trim;</li>
 *   <li>optional: collapse runs of whitespace, case-insensitive ({@code Locale.ROOT}), strip trailing punctuation {@code .!?…,;:};</li>
 *   <li>diacritics are NEVER stripped: "Hà Nội" != "Ha Noi", "mà" != "ma".</li>
 * </ul>
 */
public final class TextNormalizer {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern TRAILING_PUNCTUATION = Pattern.compile("[.!?…,;:]+$");

    private TextNormalizer() {}

    public static String normalize(String text, Normalization options) {
        if (text == null) return "";
        Normalization o = options == null ? Normalization.NONE : options;
        String s = Normalizer.normalize(text, Normalizer.Form.NFC)
                .replace(' ', ' ').replace(' ', ' ').replace(' ', ' ')
                .replace('‘', '\'').replace('’', '\'').replace('‚', '\'').replace('‛', '\'')
                .replace('“', '"').replace('”', '"').replace('„', '"')
                .strip();
        if (o.collapseWhitespace()) {
            s = WHITESPACE.matcher(s).replaceAll(" ");
        }
        if (o.stripTrailingPunctuation()) {
            s = TRAILING_PUNCTUATION.matcher(s).replaceAll("").strip();
        }
        if (o.caseInsensitive()) {
            s = s.toLowerCase(Locale.ROOT);
        }
        return s;
    }

    public static boolean matches(String answer, String accepted, Normalization options) {
        String a = normalize(answer, options);
        return !a.isEmpty() && a.equals(normalize(accepted, options));
    }

    public static boolean isBlank(String text) {
        return normalize(text, Normalization.NONE).isEmpty();
    }
}
