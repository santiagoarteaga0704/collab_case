package com.collabcase.vision;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Deja las cardinalidades leídas de la imagen en notación UML. Algunos modelos no devuelven el
 * asterisco literal y escriben "multi", "many", "n" o "multiplicity_star" en su lugar.
 */
final class CardinalityNormalizer {
    private static final Pattern MANY = Pattern.compile("\\*|n|m|many|muchos|multi.*|multiplicity_?star|star");

    private CardinalityNormalizer() {
    }

    static String normalize(String value) {
        if (value == null) return null;
        String text = value.strip();
        int range = text.indexOf("..");
        if (range >= 0) {
            return text.substring(0, range).strip() + ".." + upper(text.substring(range + 2).strip());
        }
        return upper(text);
    }

    private static String upper(String bound) {
        return MANY.matcher(bound.toLowerCase(Locale.ROOT)).matches() ? "*" : bound;
    }
}
