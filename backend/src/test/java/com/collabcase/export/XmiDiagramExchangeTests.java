package com.collabcase.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.collabcase.ai.context.AiAttributeContext;
import com.collabcase.ai.context.AiClassContext;
import com.collabcase.ai.context.AiDiagramContext;
import com.collabcase.ai.context.AiMethodContext;
import com.collabcase.ai.context.AiRelationshipContext;
import com.collabcase.importer.XmiDiagramImporter;
import com.collabcase.importer.XmiImportException;
import com.collabcase.xmi.RelationshipTypeMapper;

class XmiDiagramExchangeTests {
    private final RelationshipTypeMapper relationshipTypes = new RelationshipTypeMapper();
    private final XmiDiagramExporter exporter = new XmiDiagramExporter(relationshipTypes);
    private final XmiDiagramImporter importer = new XmiDiagramImporter(relationshipTypes);

    @Test void roundTripPreservesClassAttributeMethodAndSpecialCharacters() {
        AiClassContext client = new AiClassContext("client-id", "Cliente & Ñ",
                List.of(new AiAttributeContext("a", "nombre", "String", "- nombre: String")),
                List.of(new AiMethodContext("m", "registrar", "Boolean", "+ registrar(codigo: Long): Boolean")));
        var imported = importer.parse(exporter.export(context(List.of(client), List.of())));
        assertThat(imported.classes()).isEqualTo(1);
        assertThat(imported.attributes()).isEqualTo(1);
        assertThat(imported.methods()).isEqualTo(1);
        assertThat(imported.model().toString()).contains("Cliente & Ñ", "nombre", "registrar");
    }

    @Test void roundTripPreservesOneToManyAssociation() {
        var imported = importer.parse(exporter.export(twoClasses("ClassBidirectional", "1", "0..*")));
        assertThat(imported.relationships()).isEqualTo(1);
        assertThat(imported.model().toString()).contains("sourceMultiplicity=1", "targetMultiplicity=0..*");
    }

    @Test void roundTripPreservesManyToManyAndOneToOne() {
        assertThat(importer.parse(exporter.export(twoClasses("ClassBidirectional", "*", "*"))).model().toString())
                .contains("sourceMultiplicity=0..*", "targetMultiplicity=0..*");
        assertThat(importer.parse(exporter.export(twoClasses("ClassBidirectional", "1", "1"))).model().toString())
                .contains("sourceMultiplicity=1", "targetMultiplicity=1");
    }

    @Test void roundTripPreservesGeneralization() {
        assertThat(importer.parse(exporter.export(twoClasses("ClassInheritance", null, null))).model().toString())
                .contains("ClassInheritance");
    }

    @Test void roundTripPreservesAggregationAndComposition() {
        assertThat(importer.parse(exporter.export(twoClasses("ClassAggregation", "1", "*"))).model().toString())
                .contains("ClassAggregation");
        assertThat(importer.parse(exporter.export(twoClasses("ClassComposition", "1", "*"))).model().toString())
                .contains("ClassComposition");
    }

    @Test void unknownPrimitiveTypeIsNotDiscarded() {
        var item = new AiClassContext("a", "Pago",
                List.of(new AiAttributeContext("x", "monto", "Money", "- monto: Money")), List.of());
        assertThat(importer.parse(exporter.export(context(List.of(item), List.of()))).model().toString()).contains("Money");
    }

    @Test void ignoresEnterpriseArchitectExtensionAndUnsupportedElementWithWarnings() {
        String xml = header() + "<uml:Model xmi:id=\"m\" name=\"EA\"><packagedElement xmi:type=\"uml:Class\" xmi:id=\"c\" name=\"Cliente\"/>"
                + "<packagedElement xmi:type=\"uml:Enumeration\" xmi:id=\"e\" name=\"Estado\"/></uml:Model>"
                + "<xmi:Extension extender=\"Enterprise Architect\"><ea:tag xmlns:ea=\"urn:ea\"/></xmi:Extension></xmi:XMI>";
        var result = importer.parse(xml.getBytes(StandardCharsets.UTF_8));
        assertThat(result.warnings()).anyMatch(item -> item.contains("Enumeration"));
        assertThat(result.warnings()).anyMatch(item -> item.contains("geometria"));
    }

    @Test void exportContainsOneEaPackageOneLogicalDiagramAndNoPackagedPrimitiveTypes() {
        String xml = new String(exporter.export(twoClasses("ClassBidirectional", "1", "0..*")), StandardCharsets.UTF_8);
        assertThat(xml).contains("xmi:Documentation", "xmi:Extension", "<connectors>", "<diagrams>",
                "type=\"Logical\"", "geometry=\"Left=", "<memberEnd", "<ownedEnd");
        assertThat(count(xml, "<packagedElement")).isEqualTo(4); // un paquete, dos clases y una asociacion
        assertThat(count(xml, "<diagram ")).isEqualTo(1);
        assertThat(xml).doesNotContain("<packagedElement xmi:type=\"uml:PrimitiveType\"",
                "<packagedElement xmi:type=\"uml:Enumeration\"");
    }

    @Test void importsGoldenEaDiagramMembershipAssociationAndGeometry() throws Exception {
        byte[] fixture = Files.readAllBytes(Path.of("src/test/resources/ea/ea-reference-class-diagram.xmi.xml"));
        var imported = importer.parse(fixture);
        assertThat(imported.title()).isEqualTo("Prueba1");
        assertThat(imported.classes()).isEqualTo(2);
        assertThat(imported.attributes()).isEqualTo(4);
        assertThat(imported.relationships()).isEqualTo(1);
        assertThat(imported.model().toString()).contains("Cliente", "Reserva", "sourceMultiplicity=1", "targetMultiplicity=0..*");
        @SuppressWarnings("unchecked")
        var nodes = (List<Map<String, Object>>) imported.model().get("nodes");
        assertThat(nodes).extracting(node -> node.get("position")).contains(Map.of("x", 157, "y", 76), Map.of("x", 464, "y", 94));
    }

    @Test void roundTripPreservesExplicitApollonGeometry() {
        Map<String, Object> raw = Map.of("nodes", List.of(
                Map.of("id", "a", "position", Map.of("x", 125, "y", 210), "width", 240, "height", 135),
                Map.of("id", "b", "position", Map.of("x", 510, "y", 80), "width", 190, "height", 90)));
        var imported = importer.parse(exporter.export(twoClasses("ClassBidirectional", "1", "*"), raw));
        @SuppressWarnings("unchecked")
        var nodes = (List<Map<String, Object>>) imported.model().get("nodes");
        assertThat(nodes).extracting(node -> node.get("position")).containsExactly(Map.of("x", 125, "y", 210), Map.of("x", 510, "y", 80));
        assertThat(nodes).extracting(node -> node.get("width")).containsExactly(240, 190);
    }

    @Test void conceptualEaModelHasThreeClassesTwoBinaryAssociationsAndOneDiagram() {
        var cliente = new AiClassContext("cliente", "Cliente", List.of(), List.of());
        var reserva = new AiClassContext("reserva", "Reserva", List.of(), List.of());
        var pago = new AiClassContext("pago", "Pago", List.of(), List.of());
        var relations = List.of(
                new AiRelationshipContext("r1", "cliente", "Cliente", "reserva", "Reserva", "ClassBidirectional", "1", "0..*", "realiza"),
                new AiRelationshipContext("r2", "reserva", "Reserva", "pago", "Pago", "ClassComposition", "1", "1", "paga"));
        byte[] xmi = exporter.export(context(List.of(cliente, reserva, pago), relations));
        String xml = new String(xmi, StandardCharsets.UTF_8);
        var imported = importer.parse(xmi);
        assertThat(imported.classes()).isEqualTo(3);
        assertThat(imported.relationships()).isEqualTo(2);
        assertThat(imported.model().toString()).contains("Cliente", "Reserva", "Pago", "ClassComposition", "targetMultiplicity=0..*");
        assertThat(count(xml, "<diagram ")).isEqualTo(1);
        assertThat(xml).doesNotContain("<packagedElement xmi:type=\"uml:PrimitiveType\"", "uml:Enumeration");
    }

    @Test void roundTripClassAggregationPreservesTypeDirectionAndCardinalities() { assertRoundTrip("ClassAggregation", "1", "0..*"); }
    @Test void roundTripClassBidirectionalPreservesTypeDirectionAndCardinalities() { assertRoundTrip("ClassBidirectional", "0..1", "1..*"); }
    @Test void roundTripClassCompositionPreservesTypeDirectionAndCardinalities() { assertRoundTrip("ClassComposition", "1", "1..*"); }
    @Test void roundTripClassUnidirectionalPreservesTypeDirectionAndCardinalities() { assertRoundTrip("ClassUnidirectional", "1", "*"); }
    @Test void roundTripClassDependencyPreservesTypeAndDirectionWithoutCardinalities() { assertRoundTrip("ClassDependency", null, null); }
    @Test void roundTripClassInheritancePreservesTypeAndDirectionWithoutCardinalities() { assertRoundTrip("ClassInheritance", null, null); }
    @Test void roundTripClassRealizationPreservesTypeAndDirectionWithoutCardinalities() { assertRoundTrip("ClassRealization", null, null); }

    @Test void goldenEaRelationshipsImportsUnambiguousSemanticTypes() throws Exception {
        var imported = importer.parse(Files.readAllBytes(Path.of("src/test/resources/ea/ea-reference-relationships.xmi.xml")));
        String model = imported.model().toString();
        assertThat(model).contains("ClassAggregation", "ClassBidirectional", "ClassComposition",
                "ClassDependency", "ClassInheritance", "ClassRealization");
        assertThat(model).doesNotContain("ClassDependency, sourceMultiplicity", "ClassRealization, sourceMultiplicity");
    }

    @Test void exporterRejectsUnknownRelationshipInsteadOfFallingBackToAssociation() {
        assertThatThrownBy(() -> exporter.export(twoClasses("TemplateBinding", null, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("TemplateBinding");
    }

    @Test void importerWarnsAndDoesNotConvertUnknownRelationshipToAssociation() {
        String xml = header() + "<uml:Model xmi:id=\"m\" name=\"Unsupported\">"
                + "<packagedElement xmi:type=\"uml:Class\" xmi:id=\"a\" name=\"A\"/>"
                + "<packagedElement xmi:type=\"uml:Class\" xmi:id=\"b\" name=\"B\"/>"
                + "<packagedElement xmi:type=\"uml:TemplateBinding\" xmi:id=\"r\"/></uml:Model></xmi:XMI>";
        var imported = importer.parse(xml.getBytes(StandardCharsets.UTF_8));
        assertThat(imported.relationships()).isZero();
        assertThat(imported.warnings()).anyMatch(warning -> warning.contains("TemplateBinding"));
    }

    @Test void rejectsInvalidXml() {
        assertThatThrownBy(() -> importer.parse("<broken>".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(XmiImportException.class);
    }

    @Test void rejectsDoctypeAndExternalEntities() {
        String xml = "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"
                + "<xmi:XMI xmlns:xmi=\"http://schema.omg.org/spec/XMI/2.1\" xmlns:uml=\"http://www.eclipse.org/uml2/5.0.0/UML\">"
                + "<uml:Model xmi:id=\"m\" name=\"&xxe;\"/></xmi:XMI>";
        assertThatThrownBy(() -> importer.parse(xml.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(XmiImportException.class);
    }

    private AiDiagramContext twoClasses(String type, String source, String target) {
        var first = new AiClassContext("a", "Cliente", List.of(), List.of());
        var second = new AiClassContext("b", "Reserva", List.of(), List.of());
        var edge = new AiRelationshipContext("r", "a", "Cliente", "b", "Reserva", type, source, target, "realiza");
        return context(List.of(first, second), List.of(edge));
    }

    private AiDiagramContext context(List<AiClassContext> classes, List<AiRelationshipContext> relationships) {
        return new AiDiagramContext("d", "Sistema de Barbería", "ClassDiagram", classes, relationships);
    }

    @SuppressWarnings("unchecked")
    private void assertRoundTrip(String type, String sourceMultiplicity, String targetMultiplicity) {
        byte[] xmi = exporter.export(twoClasses(type, sourceMultiplicity, targetMultiplicity));
        String xml = new String(xmi, StandardCharsets.UTF_8);
        var imported = importer.parse(xmi);
        var nodes = (List<Map<String, Object>>) imported.model().get("nodes");
        var edges = (List<Map<String, Object>>) imported.model().get("edges");
        assertThat(edges).hasSize(1);
        Map<String, Object> edge = edges.getFirst();
        Map<String, String> names = new java.util.HashMap<>();
        for (Map<String, Object> node : nodes) names.put((String) node.get("id"), (String) ((Map<String, Object>) node.get("data")).get("name"));
        assertThat(edge.get("type")).isEqualTo(type);
        assertThat(names.get(edge.get("source"))).isEqualTo("Cliente");
        assertThat(names.get(edge.get("target"))).isEqualTo("Reserva");
        Map<String, Object> data = (Map<String, Object>) edge.get("data");
        boolean multiplicity = relationshipTypes.fromApollon(type).orElseThrow().supportsMultiplicity();
        if (multiplicity) {
            assertThat(data.get("sourceMultiplicity")).isEqualTo(normalized(sourceMultiplicity));
            assertThat(data.get("targetMultiplicity")).isEqualTo(normalized(targetMultiplicity));
        } else {
            assertThat(data).doesNotContainKeys("sourceMultiplicity", "targetMultiplicity");
            assertThat(xml).doesNotContain("<memberEnd", "<ownedEnd", "multiplicity=");
        }
    }

    private String normalized(String value) { return "*".equals(value) ? "0..*" : value; }

    private String header() {
        return "<?xml version=\"1.0\"?><xmi:XMI xmlns:xmi=\"http://schema.omg.org/spec/XMI/2.1\" "
                + "xmlns:uml=\"http://www.eclipse.org/uml2/5.0.0/UML\" xmi:version=\"2.1\">";
    }

    private int count(String value, String needle) { return (value.length() - value.replace(needle, "").length()) / needle.length(); }
}
