package com.collabcase.xmi;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

/** Mapping verified against Apollon 5.3.0 and EA's relationship golden XMI. */
@Component
public class RelationshipTypeMapper {
    public static final String AGGREGATION_NONE = "none";
    public static final String AGGREGATION_SHARED = "shared";
    public static final String AGGREGATION_COMPOSITE = "composite";

    private static final Map<String, Mapping> BY_APOLLON = mappings();

    public Optional<Mapping> fromApollon(String type) {
        return Optional.ofNullable(BY_APOLLON.get(type));
    }

    public Optional<Mapping> fromXmi(String xmiType, String eaType, String eaSubtype,
            String eaDirection, String aggregation) {
        if ("uml:Dependency".equals(xmiType)) return fromApollon("ClassDependency");
        if ("uml:Realization".equals(xmiType) || "uml:InterfaceRealization".equals(xmiType))
            return fromApollon("ClassRealization");
        if ("uml:Generalization".equals(xmiType) || "Generalization".equals(eaType))
            return fromApollon("ClassInheritance");
        if (!"uml:Association".equals(xmiType)) return Optional.empty();
        if (AGGREGATION_COMPOSITE.equals(aggregation) || "Strong".equals(eaSubtype))
            return fromApollon("ClassComposition");
        if (AGGREGATION_SHARED.equals(aggregation) || "Weak".equals(eaSubtype))
            return fromApollon("ClassAggregation");
        if ("Source -> Destination".equals(eaDirection)) return fromApollon("ClassUnidirectional");
        // EA's golden file emits Unspecified for ordinary/bidirectional associations.
        if ("Association".equals(eaType) || eaType == null || eaType.isBlank())
            return fromApollon("ClassBidirectional");
        return Optional.empty();
    }

    public Map<String, Mapping> supportedMappings() {
        return BY_APOLLON;
    }

    private static Map<String, Mapping> mappings() {
        Map<String, Mapping> result = new LinkedHashMap<>();
        add(result, new Mapping("ClassAggregation", "uml:Association", "Aggregation", "Weak", "Source -> Destination", true, AGGREGATION_SHARED));
        add(result, new Mapping("ClassBidirectional", "uml:Association", "Association", "", "Unspecified", true, AGGREGATION_NONE));
        add(result, new Mapping("ClassComposition", "uml:Association", "Aggregation", "Strong", "Source -> Destination", true, AGGREGATION_COMPOSITE));
        add(result, new Mapping("ClassDependency", "uml:Dependency", "Dependency", "", "Source -> Destination", false, AGGREGATION_NONE));
        add(result, new Mapping("ClassInheritance", "uml:Generalization", "Generalization", "", "Source -> Destination", false, AGGREGATION_NONE));
        add(result, new Mapping("ClassRealization", "uml:Realization", "Realisation", "", "Source -> Destination", false, AGGREGATION_NONE));
        add(result, new Mapping("ClassUnidirectional", "uml:Association", "Association", "", "Source -> Destination", true, AGGREGATION_NONE));
        return Map.copyOf(result);
    }

    private static void add(Map<String, Mapping> mappings, Mapping mapping) {
        mappings.put(mapping.apollonType(), mapping);
    }

    public record Mapping(String apollonType, String xmiType, String eaType, String eaSubtype,
            String eaDirection, boolean supportsMultiplicity, String aggregation) {}
}
