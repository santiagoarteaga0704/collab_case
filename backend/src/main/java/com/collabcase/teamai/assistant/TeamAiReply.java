package com.collabcase.teamai.assistant;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record TeamAiReply(String mensaje, Changes propuesta) {

    public record Changes(List<NewClass> clasesNuevas, List<NewAttribute> atributosNuevos,
            List<NewRelation> relacionesNuevas, List<RelationChange> cambiosRelacion) {
        public Changes {
            clasesNuevas = clasesNuevas == null ? List.of() : clasesNuevas;
            atributosNuevos = atributosNuevos == null ? List.of() : atributosNuevos;
            relacionesNuevas = relacionesNuevas == null ? List.of() : relacionesNuevas;
            cambiosRelacion = cambiosRelacion == null ? List.of() : cambiosRelacion;
        }

        public static Changes empty() {
            return new Changes(null, null, null, null);
        }
    }

    public record NewClass(String nombre, List<Member> atributos, List<Method> metodos) {
        public NewClass {
            atributos = atributos == null ? List.of() : atributos;
            metodos = metodos == null ? List.of() : metodos;
        }
    }

    public record Member(String nombre, String tipo) { }

    public record Method(String nombre, String retorno) { }

    public record NewAttribute(String clase, String nombre, String tipo) { }

    public record NewRelation(String origen, String destino, String tipo, String multOrigen, String multDestino) { }

    public record RelationChange(String origen, String destino, String tipoNuevo, String multOrigen,
            String multDestino) { }

    public TeamAiReply {
        propuesta = propuesta == null ? Changes.empty() : propuesta;
    }

    private static final List<String> RELATION_TYPES = List.of(
            "ASOCIACION", "AGREGACION", "COMPOSICION", "HERENCIA", "DEPENDENCIA", "REALIZACION");

    public static Map<String, Object> jsonSchema() {
        Map<String, Object> string = Map.of("type", "string");
        Map<String, Object> member = obj(props("nombre", string, "tipo", string), "nombre", "tipo");
        Map<String, Object> method = obj(props("nombre", string, "retorno", string), "nombre", "retorno");
        Map<String, Object> newClass = obj(props("nombre", string,
                "atributos", array(member), "metodos", array(method)), "nombre", "atributos", "metodos");
        Map<String, Object> newAttribute = obj(props("clase", string, "nombre", string, "tipo", string),
                "clase", "nombre", "tipo");
        Map<String, Object> type = Map.of("type", "string", "enum", RELATION_TYPES);
        Map<String, Object> newRelation = obj(props("origen", string, "destino", string, "tipo", type,
                "multOrigen", string, "multDestino", string),
                "origen", "destino", "tipo", "multOrigen", "multDestino");
        Map<String, Object> relationChange = obj(props("origen", string, "destino", string, "tipoNuevo", string,
                "multOrigen", string, "multDestino", string), "origen", "destino");
        Map<String, Object> changes = obj(props("clasesNuevas", array(newClass),
                "atributosNuevos", array(newAttribute), "relacionesNuevas", array(newRelation),
                "cambiosRelacion", array(relationChange)),
                "clasesNuevas", "atributosNuevos", "relacionesNuevas", "cambiosRelacion");
        return obj(props("mensaje", string, "propuesta", changes), "mensaje", "propuesta");
    }

    public static Map<String, Object> summarySchema() {
        return obj(props("mensaje", Map.of("type", "string")), "mensaje");
    }

    private static Map<String, Object> array(Map<String, Object> items) {
        return Map.of("type", "array", "items", items);
    }

    private static Map<String, Object> props(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) map.put((String) pairs[i], pairs[i + 1]);
        return map;
    }

    private static Map<String, Object> obj(Map<String, Object> properties, String... required) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", "object");
        map.put("additionalProperties", false);
        map.put("properties", properties);
        map.put("required", List.of(required));
        return map;
    }
}
