package com.classroom.modules.assessment.grading;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.Collection;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * D-34: parses a typed number. "12,5" and "12.5" are both 12.5; when both separators appear the LAST one is the decimal separator and the
 * other is a thousands separator ("1.234,5" = 1234.5, "1,234.5" = 1234.5); a separator repeated several times is a thousands separator
 * ("1.234.567"); spaces are ignored. An optional unit suffix must be one of {@code acceptedUnits} (case-insensitive), otherwise the text is not
 * a valid answer.
 */
public final class NumericParser {

    private static final Pattern NUMBER_THEN_REST = Pattern.compile("^([+-]?[\\d\\s.,]*\\d)\\s*(.*)$");

    private NumericParser() {}

    public static Optional<BigDecimal> parse(String text, Collection<String> acceptedUnits) {
        if (text == null) return Optional.empty();
        String s = Normalizer.normalize(text, Normalizer.Form.NFC)
                .replace(' ', ' ').replace(' ', ' ').replace('−', '-')
                .strip();
        if (s.isEmpty()) return Optional.empty();
        Matcher m = NUMBER_THEN_REST.matcher(s);
        if (!m.matches()) return Optional.empty();
        String number = m.group(1).replaceAll("\\s", "");
        String unit = m.group(2).strip();
        if (!unit.isEmpty()) {
            boolean known = acceptedUnits != null && acceptedUnits.stream()
                    .anyMatch(u -> u != null && u.strip().toLowerCase(Locale.ROOT).equals(unit.toLowerCase(Locale.ROOT)));
            if (!known) return Optional.empty();
        }
        return toDecimal(number);
    }

    static Optional<BigDecimal> toDecimal(String raw) {
        String sign = "";
        String s = raw;
        if (s.startsWith("+") || s.startsWith("-")) {
            sign = s.startsWith("-") ? "-" : "";
            s = s.substring(1);
        }
        int lastDot = s.lastIndexOf('.');
        int lastComma = s.lastIndexOf(',');
        String normalized;
        if (lastDot >= 0 && lastComma >= 0) {
            char decimal = lastDot > lastComma ? '.' : ',';
            char thousands = decimal == '.' ? ',' : '.';
            String noThousands = s.replace(String.valueOf(thousands), "");
            if (count(noThousands, decimal) > 1) return Optional.empty();
            normalized = noThousands.replace(decimal, '.');
        } else if (lastDot >= 0 || lastComma >= 0) {
            char sep = lastDot >= 0 ? '.' : ',';
            normalized = count(s, sep) > 1 ? s.replace(String.valueOf(sep), "") : s.replace(sep, '.');
        } else {
            normalized = s;
        }
        if (normalized.isEmpty() || normalized.startsWith(".") && normalized.length() == 1) return Optional.empty();
        try {
            return Optional.of(new BigDecimal(sign + normalized));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static int count(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == c) n++;
        return n;
    }
}
