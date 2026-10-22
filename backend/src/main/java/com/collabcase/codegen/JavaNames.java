package com.collabcase.codegen;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class JavaNames {
    private static final Set<String> RESERVED = Set.of("abstract","assert","boolean","break","byte","case","catch","char","class","const","continue","default","do","double","else","enum","extends","final","finally","float","for","goto","if","implements","import","instanceof","int","interface","long","native","new","package","private","protected","public","return","short","static","strictfp","super","switch","synchronized","this","throw","throws","transient","try","void","volatile","while","record","sealed","permits","var","yield");

    public String className(String value) { String[] words = words(value); StringBuilder result = new StringBuilder(); for (String word : words) if (!word.isBlank()) result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1)); return valid(result.toString()); }
    public String fieldName(String value) { String name = className(value); if (name == null || name.isBlank()) return null; name = Character.toLowerCase(name.charAt(0)) + name.substring(1); return RESERVED.contains(name) ? name + "Value" : name; }
    public String artifactId(String value) { String text = ascii(value).replaceAll("([a-z0-9])([A-Z])", "$1-$2").replaceAll("[^A-Za-z0-9]+", "-").replaceAll("^-+|-+$", "").toLowerCase(Locale.ROOT); return text.isBlank() ? "generated-backend" : text; }
    public String packageSegment(String value) { String text = ascii(value).replaceAll("[^A-Za-z0-9]+", "").toLowerCase(Locale.ROOT); if (text.isBlank()) text = "application"; if (Character.isDigit(text.charAt(0))) text = "app" + text; return RESERVED.contains(text) ? text + "app" : text; }
    public String tableName(String value) { String text = ascii(value).replaceAll("([a-z0-9])([A-Z])", "$1_$2").replaceAll("[^A-Za-z0-9]+", "_").replaceAll("^_+|_+$", "").toLowerCase(Locale.ROOT); return text.isBlank() ? null : text; }
    public String path(String value) { return "/api/" + tableName(value); }
    private String valid(String value) { if (value == null || value.isBlank() || !Character.isJavaIdentifierStart(value.charAt(0))) return null; for (int i=1;i<value.length();i++) if (!Character.isJavaIdentifierPart(value.charAt(i))) return null; return RESERVED.contains(value.toLowerCase(Locale.ROOT)) ? value + "Type" : value; }
    private String[] words(String value) { String text = ascii(value).replaceAll("([a-z0-9])([A-Z])", "$1 $2").replaceAll("[^A-Za-z0-9]+", " ").trim(); return text.isBlank() ? new String[0] : text.split("\\s+"); }
    private String ascii(String value) { return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD).replaceAll("\\p{M}", ""); }
}
