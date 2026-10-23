package com.collabcase.teamai.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.collabcase.ai.AiSuggestionValidator;
import com.collabcase.ai.context.AiAttributeContext;
import com.collabcase.ai.context.AiClassContext;
import com.collabcase.ai.context.AiDiagramContext;
import com.collabcase.ai.context.AiRelationshipContext;
import com.collabcase.ai.dto.AiSuggestion;
import com.collabcase.ai.dto.SuggestionType;
import com.collabcase.teamai.assistant.TeamAiReply.Changes;
import com.collabcase.teamai.assistant.TeamAiReply.Member;
import com.collabcase.teamai.assistant.TeamAiReply.NewAttribute;
import com.collabcase.teamai.assistant.TeamAiReply.NewClass;
import com.collabcase.teamai.assistant.TeamAiReply.NewRelation;
import com.collabcase.teamai.assistant.TeamAiReply.RelationChange;

class ChangeTranslatorTests {
    private final ChangeTranslator translator = new ChangeTranslator(new AiSuggestionValidator());

    private final AiDiagramContext ctx = new AiDiagramContext("d1", "Ventas", "ClassDiagram",
            List.of(new AiClassContext("c1", "Cliente", List.of(new AiAttributeContext("a1", "nombre", "String", "nombre: String")), List.of()),
                    new AiClassContext("c2", "Producto", List.of(), List.of())),
            List.of(new AiRelationshipContext("r1", "c1", "Cliente", "c2", "Producto", "ClassBidirectional",
                    "1", "*", null)));

    private Changes classes(NewClass... items) {
        return new Changes(List.of(items), null, null, null);
    }

    @Test
    void cleansTypeGluedToName() {
        var result = translator.translate(classes(new NewClass("Venta",
                List.of(new Member("id: Long", ""), new Member("+ total", "Double")), null)), ctx, "Luis");
        assertThat(result).hasSize(1);
        var attrs = result.getFirst().attributes();
        assertThat(attrs.get(0).name()).isEqualTo("id");
        assertThat(attrs.get(0).dataType()).isEqualTo("Long");
        assertThat(attrs.get(1).name()).isEqualTo("total");
        assertThat(result.getFirst().title()).isEqualTo("Pedido de Luis");
        assertThat(result.getFirst().description()).isEqualTo("Venta");
    }

    @Test
    void mapsSpanishRelationTypesAndMultiplicities() {
        var changes = new Changes(List.of(new NewClass("Venta", null, null)), null, List.of(
                new NewRelation("Venta", "Cliente", "COMPOSICION", "N", "1..N"),
                new NewRelation("Venta", "Producto", "AGREGACION", "0..N", "")), null);
        var result = translator.translate(changes, ctx, "Luis");
        var comp = result.stream().filter(s -> s.type() == SuggestionType.ADD_RELATIONSHIP).toList();
        assertThat(comp).hasSize(2);
        assertThat(comp.get(0).relationshipType()).isEqualTo("ClassComposition");
        assertThat(comp.get(0).sourceCardinality()).isEqualTo("*");
        assertThat(comp.get(0).targetCardinality()).isEqualTo("1..*");
        assertThat(comp.get(1).relationshipType()).isEqualTo("ClassAggregation");
        assertThat(comp.get(1).sourceCardinality()).isEqualTo("0..*");
        assertThat(comp.get(1).targetCardinality()).isNull();
    }

    @Test
    void createsVentaAndDetalleConnectedToExisting() {
        var changes = new Changes(List.of(
                new NewClass("Venta", List.of(new Member("id", "Long"), new Member("fecha", "LocalDateTime"),
                        new Member("clienteId", "Long")), null),
                new NewClass("DetalleVenta", List.of(new Member("id: Long", "Long")), null)),
                null, List.of(
                        new NewRelation("Venta", "DetalleVenta", "AGREGACION", "0..*", "1"),
                        new NewRelation("Venta", "Cliente", "ASOCIACION", "", ""),
                        new NewRelation("DetalleVenta", "Producto", "ASOCIACION", "", "")), null);
        var result = translator.translate(changes, ctx, "Luis");
        assertThat(result.stream().filter(s -> s.type() == SuggestionType.ADD_CLASS)).hasSize(2);
        assertThat(result.stream().filter(s -> s.type() == SuggestionType.ADD_RELATIONSHIP)).hasSize(3);
    }

    @Test
    void existingClassIsNotRecreated() {
        var result = translator.translate(classes(new NewClass("Cliente", null, null),
                new NewClass("Venta", null, null)), ctx, "Luis");
        assertThat(result).extracting(AiSuggestion::className).containsExactly("Venta");
    }

    @Test
    void relationToUnknownClassIsDropped() {
        var changes = new Changes(List.of(new NewClass("Venta", null, null)), null, List.of(
                new NewRelation("Venta", "Fantasma", "ASOCIACION", "", ""),
                new NewRelation("Venta", "Cliente", "ASOCIACION", "", "")), null);
        var result = translator.translate(changes, ctx, "Luis");
        assertThat(result.stream().filter(s -> s.type() == SuggestionType.ADD_RELATIONSHIP)).hasSize(1);
        assertThat(result).hasSize(2);
    }

    @Test
    void addsAttributeToExistingClass() {
        var changes = new Changes(null, List.of(new NewAttribute("Cliente", "email: String", "")), null, null);
        var result = translator.translate(changes, ctx, "Luis");
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().type()).isEqualTo(SuggestionType.ADD_ATTRIBUTE);
        assertThat(result.getFirst().attributeName()).isEqualTo("email");
        assertThat(result.getFirst().attributeType()).isEqualTo("String");
        assertThat(result.getFirst().targetElementId()).isEqualTo("c1");
    }

    @Test
    void relationChangeFindsRelationInEitherDirection() {
        var changes = new Changes(null, null, null, List.of(
                new RelationChange("Producto", "Cliente", "COMPOSICION", "", ""),
                new RelationChange("Producto", "Cliente", "", "1", "N"),
                new RelationChange("Producto", "Nada", "COMPOSICION", "", "")));
        var result = translator.translate(changes, ctx, "Luis");
        assertThat(result).hasSize(2);
        assertThat(result.get(0).type()).isEqualTo(SuggestionType.CHANGE_RELATIONSHIP_TYPE);
        assertThat(result.get(0).targetRelationshipId()).isEqualTo("r1");
        assertThat(result.get(0).suggestedRelationshipType()).isEqualTo("ClassComposition");
        assertThat(result.get(1).type()).isEqualTo(SuggestionType.CHANGE_CARDINALITY);
        // reversed direction: Producto side is the relation's target
        assertThat(result.get(1).sourceCardinality()).isEqualTo("*");
        assertThat(result.get(1).targetCardinality()).isEqualTo("1");
    }
}
