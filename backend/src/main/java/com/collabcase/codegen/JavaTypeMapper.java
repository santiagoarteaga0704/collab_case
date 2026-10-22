package com.collabcase.codegen;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class JavaTypeMapper {
    private static final Map<String,String> TYPES = Map.ofEntries(
            Map.entry("string","String"), Map.entry("text","String"), Map.entry("integer","Integer"), Map.entry("int","Integer"),
            Map.entry("long","Long"), Map.entry("boolean","Boolean"), Map.entry("bool","Boolean"), Map.entry("double","Double"),
            Map.entry("float","Float"), Map.entry("decimal","BigDecimal"), Map.entry("bigdecimal","BigDecimal"),
            Map.entry("date","LocalDate"), Map.entry("localdate","LocalDate"), Map.entry("datetime","LocalDateTime"),
            Map.entry("localdatetime","LocalDateTime"), Map.entry("uuid","UUID"), Map.entry("void","void"));
    public Result map(String umlType, Set<String> entityNames) { String normalized = umlType == null ? "" : umlType.trim(); String known = TYPES.get(normalized.toLowerCase(Locale.ROOT)); if (known != null) return new Result(known, null); if (entityNames.contains(normalized)) return new Result(normalized, null); return new Result("String", "Tipo " + (normalized.isBlank() ? "desconocido" : normalized) + " mapeado a String."); }
    public record Result(String javaType, String warning) {}
}
