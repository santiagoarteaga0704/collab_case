package com.collabcase.importer;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;
import com.collabcase.xmi.RelationshipTypeMapper;

@Component
public class XmiDiagramImporter {
    private static final int WIDTH = 180;
    private static final int BASE_HEIGHT = 50;
    private final RelationshipTypeMapper relationshipTypes;

    public XmiDiagramImporter(RelationshipTypeMapper relationshipTypes) {
        this.relationshipTypes = relationshipTypes;
    }

    public ImportedDiagram parse(byte[] bytes) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setXIncludeAware(false); factory.setExpandEntityReferences(false);
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler() {
                @Override public void error(SAXParseException exception) throws SAXParseException { throw exception; }
                @Override public void fatalError(SAXParseException exception) throws SAXParseException { throw exception; }
            });
            Document document = builder.parse(new ByteArrayInputStream(bytes));
            Map<String, String> typeNames = new HashMap<>();
            Map<String, Element> classElements = new LinkedHashMap<>();
            List<Element> relationships = new ArrayList<>();
            List<String> warnings = new ArrayList<>();
            var packaged = document.getElementsByTagNameNS("*", "packagedElement");
            for (int i = 0; i < packaged.getLength(); i++) {
                Element element = (Element) packaged.item(i); String type = xmi(element, "type"); String id = xmi(element, "id");
                if (type.endsWith("PrimitiveType")) typeNames.put(id, element.getAttribute("name"));
                else if (type.endsWith("Class")) classElements.put(id, element);
                else if (type.endsWith("Association") || type.endsWith("Dependency") || type.endsWith("Realization")) relationships.add(element);
                else if (!type.isBlank() && !type.endsWith("Package") && !type.endsWith("Model"))
                    warnings.add("Se ignoro elemento UML no soportado: " + type + ".");
            }
            EaDiagram eaDiagram = eaDiagram(document);
            if (!eaDiagram.subjects().isEmpty()) {
                classElements.entrySet().removeIf(entry -> !eaDiagram.subjects().contains(entry.getKey()));
                relationships.removeIf(element -> !eaDiagram.subjects().contains(xmi(element, "id")));
            }
            if (classElements.isEmpty()) throw new XmiImportException(HttpStatus.UNPROCESSABLE_ENTITY, "El XMI no contiene clases UML compatibles.");
            Map<String, String> nodeIds = new LinkedHashMap<>();
            classElements.keySet().forEach(id -> nodeIds.put(id, UUID.randomUUID().toString()));
            List<Map<String, Object>> nodes = new ArrayList<>(); int attributes = 0, methods = 0, index = 0;
            for (var entry : classElements.entrySet()) {
                Element element = entry.getValue(); List<Map<String, Object>> attributeList = new ArrayList<>();
                List<Map<String, Object>> methodList = new ArrayList<>();
                for (Element child : children(element)) {
                    if ("ownedAttribute".equals(local(child)) && blank(child.getAttribute("association"))) {
                        attributeList.add(Map.of("id", UUID.randomUUID().toString(), "name", member(child, typeNames))); attributes++;
                    } else if ("ownedOperation".equals(local(child))) {
                        methodList.add(Map.of("id", UUID.randomUUID().toString(), "name", operation(child, typeNames))); methods++;
                    }
                }
                Geometry geometry = eaDiagram.geometry().get(entry.getKey());
                int width = geometry == null ? WIDTH : geometry.width();
                int height = geometry == null ? BASE_HEIGHT + (attributeList.size() + methodList.size()) * 30 : geometry.height();
                Map<String, Object> node = new LinkedHashMap<>(); node.put("id", nodeIds.get(entry.getKey())); node.put("type", "class");
                node.put("position", geometry == null ? Map.of("x", 60 + index % 4 * 240, "y", 60 + index / 4 * 200) : Map.of("x", geometry.x(), "y", geometry.y()));
                node.put("width", width); node.put("height", height); node.put("measured", Map.of("width", width, "height", height));
                node.put("data", Map.of("name", defaultName(element.getAttribute("name")), "attributes", attributeList, "methods", methodList));
                nodes.add(node); index++;
            }
            List<Map<String, Object>> edges = new ArrayList<>();
            for (var entry : classElements.entrySet()) for (Element child : children(entry.getValue())) {
                if (!"generalization".equals(local(child))) continue; String parent = child.getAttribute("general");
                if (nodeIds.containsKey(parent)) edges.add(edge(nodeIds.get(entry.getKey()), nodeIds.get(parent), "ClassInheritance", null, null, null));
            }
            Map<String, EaConnector> connectors = eaConnectors(document);
            Map<String, Element> associationProperties = associationProperties(document);
            for (Element relationship : relationships) {
                String xmiType = xmi(relationship, "type");
                EaConnector connector = connectors.get(xmi(relationship, "id"));
                if (!xmiType.endsWith("Association")) {
                    var mapping = relationshipTypes.fromXmi(xmiType, connector == null ? "" : connector.eaType(),
                            connector == null ? "" : connector.eaSubtype(), connector == null ? "" : connector.direction(), "none");
                    if (mapping.isEmpty()) { warnings.add("El tipo de relacion " + xmiType + " no esta soportado por Collab CASE."); continue; }
                    String sourceType = relationship.getAttribute("client"), targetType = relationship.getAttribute("supplier");
                    if (connector != null) { sourceType = connector.source(); targetType = connector.target(); }
                    String source = nodeIds.get(sourceType), target = nodeIds.get(targetType);
                    if (source == null || target == null) { warnings.add("Se ignoro " + xmiType + " porque sus extremos no se pudieron resolver."); continue; }
                    edges.add(edge(source, target, mapping.get().apollonType(), null, null, relationship.getAttribute("name")));
                    continue;
                }
                Element association = relationship;
                List<Element> ends = associationEnds(association, associationProperties);
                if (ends.size() < 2) { warnings.add("Se ignoro una asociacion sin dos extremos memberEnd compatibles."); continue; }
                Element first = ends.get(0), second = ends.get(1);
                String firstType = referencedType(first), secondType = referencedType(second);
                String sourceType = connector == null ? secondType : connector.source();
                String targetType = connector == null ? firstType : connector.target();
                String source = nodeIds.get(sourceType), target = nodeIds.get(targetType);
                if (source == null || target == null) { warnings.add("Se ignoro una asociacion con clases no resueltas."); continue; }
                String aggregation = connector == null ? associationAggregation(ends) : connector.aggregation();
                var mapping = relationshipTypes.fromXmi(xmiType, connector == null ? "Association" : connector.eaType(),
                        connector == null ? "" : connector.eaSubtype(), connector == null ? "" : connector.direction(), aggregation);
                if (mapping.isEmpty()) { warnings.add("El tipo de relacion " + (connector == null ? xmiType : connector.eaType()) + " no esta soportado por Collab CASE."); continue; }
                String sourceMultiplicity = connector == null ? multiplicity(second) : connector.sourceMultiplicity();
                String targetMultiplicity = connector == null ? multiplicity(first) : connector.targetMultiplicity();
                edges.add(edge(source, target, mapping.get().apollonType(), sourceMultiplicity, targetMultiplicity, association.getAttribute("name")));
            }
            if (eaDiagram.geometry().isEmpty()) warnings.add("El XMI no incluye geometria compatible; se genero una cuadricula automatica.");
            String title = blank(eaDiagram.name()) ? modelName(document) : eaDiagram.name();
            Map<String, Object> model = new LinkedHashMap<>(); model.put("id", UUID.randomUUID().toString()); model.put("version", "4.2.0");
            model.put("title", title); model.put("type", "ClassDiagram"); model.put("nodes", nodes); model.put("edges", edges); model.put("assessments", List.of());
            return new ImportedDiagram(title, model, nodes.size(), attributes, methods, edges.size(), List.copyOf(warnings));
        } catch (XmiImportException exception) { throw exception;
        } catch (IllegalArgumentException exception) { throw exception;
        } catch (Exception exception) { throw new XmiImportException(HttpStatus.BAD_REQUEST, "El archivo no es un XMI/XML valido y seguro.", exception); }
    }

    private Map<String, Object> edge(String source, String target, String type, String sourceMultiplicity, String targetMultiplicity, String label) {
        Map<String, Object> data = new LinkedHashMap<>(); data.put("points", List.of());
        if (!blank(sourceMultiplicity)) data.put("sourceMultiplicity", sourceMultiplicity); if (!blank(targetMultiplicity)) data.put("targetMultiplicity", targetMultiplicity); if (!blank(label)) data.put("label", label);
        Map<String, Object> edge = new LinkedHashMap<>(); edge.put("id", UUID.randomUUID().toString()); edge.put("source", source); edge.put("target", target); edge.put("type", type);
        edge.put("sourceHandle", "right"); edge.put("targetHandle", "left"); edge.put("data", data); return edge;
    }
    private String member(Element e, Map<String, String> types) { String prefix = visibility(e.getAttribute("visibility")); String type = resolveType(e, types); return prefix + " " + defaultName(e.getAttribute("name")) + (blank(type) ? "" : ": " + type); }
    private String operation(Element e, Map<String, String> types) { List<String> parameters = new ArrayList<>(); String result = null;
        for (Element p : children(e)) if ("ownedParameter".equals(local(p))) { String type = resolveType(p, types); if ("return".equals(p.getAttribute("direction"))) result = type;
            else parameters.add(defaultName(p.getAttribute("name")) + (blank(type) ? "" : ": " + type)); }
        return visibility(e.getAttribute("visibility")) + " " + defaultName(e.getAttribute("name")) + "(" + String.join(", ", parameters) + ")" + (blank(result) ? "" : ": " + result); }
    private String resolveType(Element e, Map<String, String> types) { String reference = e.getAttribute("type"); if (types.containsKey(reference)) return types.get(reference);
        for (Element child : children(e)) if ("type".equals(local(child))) { String href = child.getAttribute("href"); int hash = href.lastIndexOf('#'); if (hash >= 0) return href.substring(hash + 1); String idref = xmi(child, "idref"); if (types.containsKey(idref)) return types.get(idref); return child.getAttribute("name"); } return blank(reference) ? null : reference; }
    private String multiplicity(Element end) { String lower = "0", upper = "1"; for (Element child : children(end)) { if ("lowerValue".equals(local(child))) lower = value(child, "0"); if ("upperValue".equals(local(child))) upper = value(child, "1"); }
        if ("-1".equals(upper) || "*".equals(upper)) upper = "*"; return lower.equals(upper) ? lower : lower + ".." + upper; }
    private String value(Element e, String fallback) { String value = e.getAttribute("value"); return blank(value) ? fallback : value; }
    private String referencedType(Element end) { if (!blank(end.getAttribute("type"))) return end.getAttribute("type"); for (Element child : children(end)) if ("type".equals(local(child))) return xmi(child, "idref"); return ""; }
    private String associationAggregation(List<Element> ends) { for (Element end : ends) if (!blank(end.getAttribute("aggregation")) && !"none".equals(end.getAttribute("aggregation"))) return end.getAttribute("aggregation"); return "none"; }
    private Map<String, Element> associationProperties(Document document) { Map<String, Element> result = new HashMap<>(); for (String tag : List.of("ownedAttribute", "ownedEnd")) { var nodes = document.getElementsByTagNameNS("*", tag); for (int i = 0; i < nodes.getLength(); i++) { Element element = (Element) nodes.item(i); if (!blank(element.getAttribute("association"))) result.put(xmi(element, "id"), element); } } return result; }
    private List<Element> associationEnds(Element association, Map<String, Element> properties) { List<Element> result = new ArrayList<>(); for (Element child : children(association)) if ("memberEnd".equals(local(child))) { Element property = properties.get(xmi(child, "idref")); if (property != null) result.add(property); } if (result.size() < 2) for (Element child : children(association)) if ("ownedEnd".equals(local(child)) && !result.contains(child)) result.add(child); return result; }
    private EaDiagram eaDiagram(Document document) {
        var diagrams = document.getElementsByTagNameNS("*", "diagram");
        for (int i = 0; i < diagrams.getLength(); i++) {
            Element diagram = (Element) diagrams.item(i); String name = ""; Element visualElements = null;
            for (Element child : children(diagram)) { if ("properties".equals(local(child)) && (blank(child.getAttribute("type")) || "Logical".equals(child.getAttribute("type")))) name = child.getAttribute("name"); if ("elements".equals(local(child))) visualElements = child; }
            if (visualElements == null) continue;
            java.util.Set<String> subjects = new java.util.LinkedHashSet<>(); Map<String, Geometry> geometry = new HashMap<>();
            for (Element visual : children(visualElements)) { String subject = visual.getAttribute("subject"); subjects.add(subject); Geometry parsed = geometry(visual.getAttribute("geometry")); if (parsed != null) geometry.put(subject, parsed); }
            return new EaDiagram(name, subjects, geometry);
        }
        return new EaDiagram("", java.util.Set.of(), Map.of());
    }
    private Geometry geometry(String value) { Map<String, Integer> values = new HashMap<>(); for (String part : value.split(";")) { String[] pair = part.split("=", 2); if (pair.length == 2) try { values.put(pair[0], Integer.parseInt(pair[1])); } catch (NumberFormatException ignored) {} } if (!values.keySet().containsAll(List.of("Left", "Top", "Right", "Bottom"))) return null; return new Geometry(values.get("Left"), values.get("Top"), Math.max(1, values.get("Right") - values.get("Left")), Math.max(1, values.get("Bottom") - values.get("Top"))); }
    private Map<String, EaConnector> eaConnectors(Document document) { Map<String, EaConnector> result = new HashMap<>(); var connectors = document.getElementsByTagNameNS("*", "connector"); for (int i = 0; i < connectors.getLength(); i++) { Element connector = (Element) connectors.item(i); String source = "", target = "", sourceMultiplicity = null, targetMultiplicity = null, aggregation = "none", eaType = "", eaSubtype = "", direction = ""; for (Element end : children(connector)) { if ("properties".equals(local(end))) { eaType = end.getAttribute("ea_type"); eaSubtype = end.getAttribute("subtype"); direction = end.getAttribute("direction"); continue; } if (!"source".equals(local(end)) && !"target".equals(local(end))) continue; String ref = xmi(end, "idref"), multiplicity = null, endAggregation = "none"; for (Element detail : children(end)) if ("type".equals(local(detail))) { multiplicity = normalizeMultiplicity(detail.getAttribute("multiplicity")); endAggregation = detail.getAttribute("aggregation"); } if (!blank(endAggregation) && !"none".equals(endAggregation)) aggregation = endAggregation; if ("source".equals(local(end))) { source = ref; sourceMultiplicity = multiplicity; } else { target = ref; targetMultiplicity = multiplicity; } } result.put(xmi(connector, "idref"), new EaConnector(source, target, sourceMultiplicity, targetMultiplicity, aggregation, eaType, eaSubtype, direction)); } return result; }
    private String normalizeMultiplicity(String value) { return blank(value) ? value : value.replace("...", ".."); }
    private String modelName(Document d) { var models = d.getElementsByTagNameNS("*", "Model"); if (models.getLength() == 0) return "Diagrama importado"; return defaultName(((Element) models.item(0)).getAttribute("name")); }
    private String xmi(Element e, String name) { String value = e.getAttributeNS("http://schema.omg.org/spec/XMI/2.1", name); if (blank(value)) value = e.getAttributeNS("http://www.omg.org/spec/XMI/20110701", name); if (blank(value)) value = e.getAttribute("xmi:" + name); return value; }
    private List<Element> children(Element parent) { List<Element> result = new ArrayList<>(); for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) if (node instanceof Element e) result.add(e); return result; }
    private String local(Element e) { return e.getLocalName() == null ? e.getTagName().replaceFirst("^.*:", "") : e.getLocalName(); }
    private String visibility(String value) { return switch (value) { case "private" -> "-"; case "protected" -> "#"; case "package" -> "~"; default -> "+"; }; }
    private String defaultName(String value) { return blank(value) ? "Unnamed" : value.trim(); }
    private boolean blank(String value) { return value == null || value.isBlank(); }
    private record Geometry(int x, int y, int width, int height) {}
    private record EaDiagram(String name, java.util.Set<String> subjects, Map<String, Geometry> geometry) {}
    private record EaConnector(String source, String target, String sourceMultiplicity, String targetMultiplicity,
            String aggregation, String eaType, String eaSubtype, String direction) {}
}
