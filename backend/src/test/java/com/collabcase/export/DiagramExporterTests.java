package com.collabcase.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.collabcase.ai.context.AiAttributeContext;
import com.collabcase.ai.context.AiClassContext;
import com.collabcase.ai.context.AiDiagramContext;
import com.collabcase.ai.context.AiMethodContext;
import com.collabcase.ai.context.AiRelationshipContext;

class DiagramExporterTests {
    private final PlantUmlDiagramExporter plant = new PlantUmlDiagramExporter();
    private final SqlDiagramExporter sql = new SqlDiagramExporter();

    @Test void exportsClassAttributesMethodsAndSpecialName() {
        var item = clazz("1", "Detalle de Venta", attribute("id", "Long", "- id: Long"),
                new AiMethodContext("m", "calcular", "BigDecimal", "+ calcular(): BigDecimal"));
        assertThat(plant.export(context(List.of(item), List.of())).content())
                .contains("class \"Detalle de Venta\" as C_1", "- id: Long", "+ calcular(): BigDecimal");
    }

    @Test void exportsAssociationAndCardinalities() {
        String output = plant.export(twoClasses("ClassBidirectional", "1", "0..*")).content();
        assertThat(output).contains("\"1\" -- \"0..*\"");
    }

    @Test void mapsAggregationCompositionAndInheritance() {
        assertThat(plant.export(twoClasses("ClassAggregation", null, null)).content()).contains(" o-- ");
        assertThat(plant.export(twoClasses("ClassComposition", null, null)).content()).contains(" *-- ");
        assertThat(plant.export(twoClasses("ClassInheritance", null, null)).content()).contains(" --|> ");
    }

    @Test void exportsSimpleSqlClassAndKnownTypes() {
        var item = clazz("1", "Cliente", attribute("id", "Long", "- id: Long"), null);
        assertThat(sql.export(context(List.of(item), List.of())).content())
                .contains("CREATE TABLE \"cliente\"", "\"id\" BIGINT PRIMARY KEY");
    }

    @Test void createsOneToManyForeignKey() {
        String output = sql.export(twoClasses("ClassBidirectional", "1", "0..*")).content();
        assertThat(output).contains("\"cliente_id\" BIGINT NOT NULL", "REFERENCES \"cliente\" (\"id\")");
    }

    @Test void createsManyToManyJoinTable() {
        String output = sql.export(twoClasses("ClassBidirectional", "*", "*")).content();
        assertThat(output).contains("CREATE TABLE \"cliente_reserva\"", "PRIMARY KEY",
                "CONSTRAINT \"fk_cliente_reserva_cliente\"", "CONSTRAINT \"fk_cliente_reserva_reserva\"");
    }

    @Test void createsManyToManyJoinTableForUnlimitedRangesOnly() {
        assertThat(sql.export(twoClasses("ClassUnidirectional", "0..*", "1..*")).content())
                .contains("CREATE TABLE \"cliente_reserva\"");
        assertThat(sql.export(twoClasses("ClassBidirectional", "1", "1")).content())
                .doesNotContain("CREATE TABLE \"cliente_reserva\"");
    }

    @Test void compositionManyToManyDoesNotCreateJoinTable() {
        ExportResult result = sql.export(twoClasses("ClassComposition", "*", "*"));
        assertThat(result.content()).doesNotContain("CREATE TABLE \"cliente_reserva\"");
        assertThat(result.warnings()).anyMatch(value -> value.contains("composición muchos-a-muchos"));
    }

    @Test void infersOneToOneOwnershipAndUniqueConstraint() {
        ExportResult result = sql.export(twoClasses("ClassBidirectional", "1", "1"));
        assertThat(result.content()).contains("UNIQUE");
        assertThat(result.warnings()).anyMatch(item -> item.contains("1:1"));
    }

    @Test void compositionAddsCascadeWhenDependentIsClear() {
        assertThat(sql.export(twoClasses("ClassComposition", "1", "*")).content()).contains("ON DELETE CASCADE");
    }

    @Test void inheritanceUsesJoinedForeignKey() {
        assertThat(sql.export(twoClasses("ClassInheritance", null, null)).content())
                .contains("fk_cliente_parent", "REFERENCES \"reserva\"");
    }

    @Test void syntheticKeyAndUnknownTypeProduceWarnings() {
        var item = clazz("1", "Pago Especial", attribute("monto", "Money", "- monto: Money"), null);
        ExportResult result = sql.export(context(List.of(item), List.of()));
        assertThat(result.content()).contains("UUID PRIMARY KEY", "VARCHAR(255)");
        assertThat(result.warnings()).hasSizeGreaterThanOrEqualTo(2);
    }

    private AiDiagramContext twoClasses(String type, String sourceCardinality, String targetCardinality) {
        var source = clazz("a", "Cliente", attribute("id", "Long", "- id: Long"), null);
        var target = clazz("b", "Reserva", attribute("id", "Long", "- id: Long"), null);
        var relation = new AiRelationshipContext("r", "a", "Cliente", "b", "Reserva", type,
                sourceCardinality, targetCardinality, "realiza");
        return context(List.of(source, target), List.of(relation));
    }

    private AiDiagramContext context(List<AiClassContext> classes, List<AiRelationshipContext> relationships) {
        return new AiDiagramContext("diagram", "", "ClassDiagram", classes, relationships);
    }

    private AiClassContext clazz(String id, String name, AiAttributeContext attribute, AiMethodContext method) {
        return new AiClassContext(id, name, attribute == null ? List.of() : List.of(attribute),
                method == null ? List.of() : List.of(method));
    }

    private AiAttributeContext attribute(String name, String type, String display) {
        return new AiAttributeContext(name, name, type, display);
    }
}
