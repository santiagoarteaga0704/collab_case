package com.collabcase.uml;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class MultiplicityParser {
    private static final Pattern RANGE = Pattern.compile("^(\\d+)\\s*\\.\\.\\s*(\\d+|\\*)$");

    public Multiplicity parse(String raw) {
        if (raw == null || raw.isBlank()) return new Multiplicity(raw, 1, 1, false, false);
        String value = raw.trim().replace("...", "..").replaceAll("\\s+", "");
        if ("*".equals(value)) return new Multiplicity(value, 0, null, true, true);
        if (value.matches("\\d+")) { int exact = Integer.parseInt(value); return new Multiplicity(value, exact, exact, false, true); }
        Matcher matcher = RANGE.matcher(value);
        if (!matcher.matches()) return new Multiplicity(value, null, null, false, false);
        int lower = Integer.parseInt(matcher.group(1));
        Integer upper = "*".equals(matcher.group(2)) ? null : Integer.valueOf(matcher.group(2));
        return new Multiplicity(value, lower, upper, upper == null, true);
    }

    public boolean isMany(String raw) { return parse(raw).unlimited(); }
    public boolean isManyToMany(String source, String target) { return isMany(source) && isMany(target); }
    public record Multiplicity(String normalized, Integer lower, Integer upper, boolean unlimited, boolean valid) {}
}
