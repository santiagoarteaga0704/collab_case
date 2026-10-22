package com.collabcase.export;

import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import com.collabcase.ai.context.AiClassContext;
import com.collabcase.ai.context.AiDiagramContext;
import com.collabcase.ai.context.AiRelationshipContext;
import com.collabcase.xmi.RelationshipTypeMapper;
import com.collabcase.xmi.RelationshipTypeMapper.Mapping;

@Component
public class XmiDiagramExporter {
    static final String XMI = "http://schema.omg.org/spec/XMI/2.1";
    static final String UML = "http://schema.omg.org/spec/UML/2.1";
    private final RelationshipTypeMapper relationshipTypes;

    public XmiDiagramExporter(RelationshipTypeMapper relationshipTypes) {
        this.relationshipTypes = relationshipTypes;
    }

    public byte[] export(AiDiagramContext context) { return export(context, Map.of()); }

    public byte[] export(AiDiagramContext context, Map<String, Object> rawModel) {
        try {
            for (AiRelationshipContext relation : context.relationships()) mapping(relation);
            Document d = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
            Element root = d.createElementNS(XMI, "xmi:XMI");
            root.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:xmi", XMI);
            root.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:uml", UML);
            xmi(root, "version", "2.1"); d.appendChild(root);
            Element documentation = add(d, root, XMI, "xmi:Documentation");
            documentation.setAttribute("exporter", "Collab CASE"); documentation.setAttribute("exporterVersion", "1.0");
            String title = blank(context.title()) ? "Collab CASE Model" : context.title();
            String packageId = id("EAPK"), diagramId = id("EAID");
            Element model = add(d, root, UML, "uml:Model"); xmi(model, "type", "uml:Model"); model.setAttribute("name", "EA_Model"); model.setAttribute("visibility", "public");
            Element pack = packaged(d, "uml:Package", packageId, title); model.appendChild(pack);

            Map<String, String> classIds = new LinkedHashMap<>(), relationIds = new LinkedHashMap<>();
            Map<String, AiClassContext> classes = new LinkedHashMap<>();
            for (AiClassContext item : context.classes()) { classIds.put(item.id(), id("EAID")); classes.put(item.id(), item); }
            for (AiRelationshipContext item : context.relationships()) relationIds.put(item.id(), id("EAID"));
            for (AiClassContext item : context.classes()) {
                Element clazz = packaged(d, "uml:Class", classIds.get(item.id()), item.name()); int n = 1;
                for (var attribute : item.attributes()) {
                    Element property = add(d, clazz, null, "ownedAttribute"); property(property, id("EAID"), attribute.name(), visibility(attribute.displayName()), "none");
                    bounds(d, property, "1", "attribute_" + n++); type(d, property, attribute.type());
                }
                for (var method : item.methods()) {
                    Element operation = add(d, clazz, null, "ownedOperation"); xmi(operation, "type", "uml:Operation"); xmi(operation, "id", id("EAID"));
                    operation.setAttribute("name", method.name()); operation.setAttribute("visibility", visibility(method.signature())); parameters(d, operation, method.signature());
                }
                context.relationships().stream().filter(r -> "ClassInheritance".equals(r.relationshipType()) && item.id().equals(r.sourceElementId()))
                        .filter(r -> classIds.containsKey(r.targetElementId())).forEach(r -> { Element g = add(d, clazz, null, "generalization"); xmi(g, "type", "uml:Generalization"); xmi(g, "id", relationIds.get(r.id())); g.setAttribute("general", classIds.get(r.targetElementId())); });
                pack.appendChild(clazz);
            }
            for (AiRelationshipContext relation : context.relationships()) {
                if ("ClassInheritance".equals(relation.relationshipType()) || !classIds.containsKey(relation.sourceElementId()) || !classIds.containsKey(relation.targetElementId())) continue;
                Mapping mapping = mapping(relation);
                Element relationship = packaged(d, mapping.xmiType(), relationIds.get(relation.id()), relation.label());
                if (mapping.supportsMultiplicity()) {
                    associationEnd(d, relationship, id("EAID_dst"), relationIds.get(relation.id()), classIds.get(relation.targetElementId()), relation.targetCardinality(), "none");
                    associationEnd(d, relationship, id("EAID_src"), relationIds.get(relation.id()), classIds.get(relation.sourceElementId()), relation.sourceCardinality(), mapping.aggregation());
                } else {
                    relationship.setAttribute("client", classIds.get(relation.sourceElementId()));
                    relationship.setAttribute("supplier", classIds.get(relation.targetElementId()));
                }
                pack.appendChild(relationship);
            }
            extension(d, root, packageId, diagramId, title, context, classes, classIds, relationIds, rawModel);
            TransformerFactory tf = TransformerFactory.newInstance(); tf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true); var transformer = tf.newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "yes"); transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8"); ByteArrayOutputStream out = new ByteArrayOutputStream();
            transformer.transform(new DOMSource(d), new StreamResult(out)); return out.toByteArray();
        } catch (IllegalArgumentException e) { throw e;
        } catch (Exception e) { throw new IllegalStateException("No se pudo generar XMI", e); }
    }

    private void extension(Document d, Element root, String packageId, String diagramId, String title, AiDiagramContext context,
            Map<String, AiClassContext> classes, Map<String, String> classIds, Map<String, String> relationIds, Map<String, Object> rawModel) {
        Element extension = add(d, root, XMI, "xmi:Extension"); extension.setAttribute("extender", "Enterprise Architect"); extension.setAttribute("extenderID", "6.5");
        Element elements = add(d, extension, null, "elements"); eaElement(d, elements, packageId, "uml:Package", title, packageId, 1);
        int local = 2; for (AiClassContext item : context.classes()) eaElement(d, elements, classIds.get(item.id()), "uml:Class", item.name(), packageId, local++);
        Element connectors = add(d, extension, null, "connectors"); int connectorLocal = 1;
        for (AiRelationshipContext relation : context.relationships()) {
            if (!classIds.containsKey(relation.sourceElementId()) || !classIds.containsKey(relation.targetElementId())) continue;
            Mapping mapping = mapping(relation);
            Element connector = add(d, connectors, null, "connector"); xmi(connector, "idref", relationIds.get(relation.id()));
            connectorEnd(d, connector, "source", classIds.get(relation.sourceElementId()), classes.get(relation.sourceElementId()).name(),
                    mapping.supportsMultiplicity() ? relation.sourceCardinality() : null, "none", false);
            connectorEnd(d, connector, "target", classIds.get(relation.targetElementId()), classes.get(relation.targetElementId()).name(),
                    mapping.supportsMultiplicity() ? relation.targetCardinality() : null, mapping.aggregation(), !"ClassBidirectional".equals(mapping.apollonType()));
            Element cm = add(d, connector, null, "model"); cm.setAttribute("ea_localid", String.valueOf(connectorLocal++));
            Element cp = add(d, connector, null, "properties"); cp.setAttribute("ea_type", mapping.eaType()); cp.setAttribute("direction", mapping.eaDirection()); if (!mapping.eaSubtype().isBlank()) cp.setAttribute("subtype", mapping.eaSubtype());
            Element labels = add(d, connector, null, "labels"); if (mapping.supportsMultiplicity()) { if (!blank(relation.sourceCardinality())) labels.setAttribute("lb", display(relation.sourceCardinality())); if (!blank(relation.targetCardinality())) labels.setAttribute("rb", display(relation.targetCardinality())); } add(d, connector, null, "extendedProperties");
        }
        add(d, extension, null, "profiles"); Element diagrams = add(d, extension, null, "diagrams"); Element diagram = add(d, diagrams, null, "diagram"); xmi(diagram, "id", diagramId);
        Element dm = add(d, diagram, null, "model"); dm.setAttribute("package", packageId); dm.setAttribute("owner", packageId); dm.setAttribute("localID", "1");
        Element dp = add(d, diagram, null, "properties"); dp.setAttribute("name", title); dp.setAttribute("type", "Logical");
        Element project = add(d, diagram, null, "project"); String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")); project.setAttribute("author", "Collab CASE"); project.setAttribute("version", "1.0"); project.setAttribute("created", now); project.setAttribute("modified", now);
        Element style1 = add(d, diagram, null, "style1"); style1.setAttribute("value", "ShowPrivate=1;ShowProtected=1;ShowPublic=1;HideRelationships=0;ConnectorNotation=UML 2.1;");
        Element style2 = add(d, diagram, null, "style2"); style2.setAttribute("value", "TConnectorNotation=UML 2.1;ShowOpRetType=1;"); add(d, diagram, null, "extendedProperties"); add(d, diagram, null, "xrefs");
        Element visuals = add(d, diagram, null, "elements"); Map<String, Map<String, Object>> nodes = rawNodes(rawModel); int seq = 1;
        for (AiClassContext item : context.classes()) {
            Map<String, Object> node = nodes.get(item.id()), position = nested(node, "position"); int x = number(position.get("x"), 60 + (seq - 1) % 4 * 240), y = number(position.get("y"), 60 + (seq - 1) / 4 * 200);
            int width = number(node == null ? null : node.get("width"), 180), height = number(node == null ? null : node.get("height"), 70 + (item.attributes().size() + item.methods().size()) * 25);
            Element visual = add(d, visuals, null, "element"); visual.setAttribute("geometry", "Left=" + x + ";Top=" + y + ";Right=" + (x + width) + ";Bottom=" + (y + height) + ";"); visual.setAttribute("subject", classIds.get(item.id())); visual.setAttribute("seqno", String.valueOf(seq++)); visual.setAttribute("style", "DUID=" + shortId() + ";");
        }
        for (AiRelationshipContext relation : context.relationships()) { Element visual = add(d, visuals, null, "element"); visual.setAttribute("geometry", "SX=0;SY=0;EX=0;EY=0;EDGE=2;$LLB=;LLT=;LMT=;LMB=;LRT=;LRB=;IRHS=;ILHS=;Path=;"); visual.setAttribute("subject", relationIds.get(relation.id())); visual.setAttribute("style", "Mode=3;Color=-1;LWidth=0;Hidden=0;"); }
    }

    private void eaElement(Document d, Element parent, String id, String type, String name, String packageId, int local) { Element e = add(d, parent, null, "element"); xmi(e, "idref", id); xmi(e, "type", type); e.setAttribute("name", name); e.setAttribute("scope", "public"); Element m = add(d, e, null, "model"); m.setAttribute("package", packageId); m.setAttribute("ea_localid", String.valueOf(local)); m.setAttribute("ea_eleType", type.endsWith("Package") ? "package" : "element"); Element p = add(d, e, null, "properties"); p.setAttribute("sType", type.substring(type.indexOf(':') + 1)); p.setAttribute("scope", "public"); add(d, e, null, "extendedProperties"); }
    private void connectorEnd(Document d, Element parent, String tag, String ref, String name, String multiplicity, String aggregation, boolean navigable) { Element e = add(d, parent, null, tag); xmi(e, "idref", ref); Element m = add(d, e, null, "model"); m.setAttribute("type", "Class"); m.setAttribute("name", name); Element role = add(d, e, null, "role"); role.setAttribute("visibility", "Public"); role.setAttribute("targetScope", "instance"); Element type = add(d, e, null, "type"); if (!blank(multiplicity)) type.setAttribute("multiplicity", display(multiplicity)); type.setAttribute("aggregation", aggregation); type.setAttribute("containment", "Unspecified"); Element modifiers = add(d, e, null, "modifiers"); modifiers.setAttribute("isNavigable", String.valueOf(navigable)); }
    private void associationEnd(Document d, Element association, String endId, String associationId, String classId, String cardinality, String aggregation) { Element member = add(d, association, null, "memberEnd"); xmi(member, "idref", endId); Element end = add(d, association, null, "ownedEnd"); property(end, endId, null, "public", aggregation); end.setAttribute("association", associationId); Element type = add(d, end, null, "type"); xmi(type, "idref", classId); bounds(d, end, cardinality, endId); }
    private void property(Element e, String id, String name, String visibility, String aggregation) { xmi(e, "type", "uml:Property"); xmi(e, "id", id); if (!blank(name)) e.setAttribute("name", name); e.setAttribute("visibility", visibility); e.setAttribute("isStatic", "false"); e.setAttribute("isReadOnly", "false"); e.setAttribute("isDerived", "false"); e.setAttribute("isOrdered", "false"); e.setAttribute("isUnique", "true"); e.setAttribute("aggregation", aggregation); }
    private void bounds(Document d, Element parent, String cardinality, String prefix) { String[] b = bounds(cardinality); Element lower = add(d, parent, null, "lowerValue"); xmi(lower, "type", "uml:LiteralInteger"); xmi(lower, "id", prefix + "_lower"); lower.setAttribute("value", b[0]); Element upper = add(d, parent, null, "upperValue"); xmi(upper, "type", "-1".equals(b[1]) ? "uml:LiteralUnlimitedNatural" : "uml:LiteralInteger"); xmi(upper, "id", prefix + "_upper"); upper.setAttribute("value", b[1]); }
    private void type(Document d, Element parent, String name) { if (blank(name)) return; Element type = add(d, parent, null, "type"); xmi(type, "type", "uml:PrimitiveType"); type.setAttribute("href", "http://www.omg.org/spec/UML/20131001/PrimitiveTypes.xmi#" + name.trim()); }
    private void parameters(Document d, Element operation, String signature) { if (signature == null) return; int open = signature.indexOf('('), close = signature.lastIndexOf(')'); if (open >= 0 && close > open) for (String raw : signature.substring(open + 1, close).split(",")) { if (raw.isBlank()) continue; String[] parts = raw.trim().split("\\s*:\\s*", 2); Element p = add(d, operation, null, "ownedParameter"); xmi(p, "type", "uml:Parameter"); xmi(p, "id", id("EAID")); p.setAttribute("name", parts[0]); if (parts.length == 2) type(d, p, parts[1]); } int colon = close < 0 ? -1 : signature.indexOf(':', close); if (colon >= 0) { Element p = add(d, operation, null, "ownedParameter"); xmi(p, "type", "uml:Parameter"); xmi(p, "id", id("EAID")); p.setAttribute("direction", "return"); type(d, p, signature.substring(colon + 1).trim()); } }
    @SuppressWarnings("unchecked") private Map<String, Map<String, Object>> rawNodes(Map<String, Object> model) { Map<String, Map<String, Object>> result = new LinkedHashMap<>(); Object value = model == null ? null : model.get("nodes"); if (value instanceof List<?> list) for (Object raw : list) if (raw instanceof Map<?, ?> map) { Map<String, Object> node = (Map<String, Object>) map; if (node.get("id") != null) result.put(node.get("id").toString(), node); } return result; }
    @SuppressWarnings("unchecked") private Map<String, Object> nested(Map<String, Object> map, String key) { Object value = map == null ? null : map.get(key); return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of(); }
    private Element packaged(Document d, String type, String id, String name) { Element e = d.createElement("packagedElement"); xmi(e, "type", type); xmi(e, "id", id); if (!blank(name)) e.setAttribute("name", name); e.setAttribute("visibility", "public"); return e; }
    private Element add(Document d, Element parent, String namespace, String name) { Element e = namespace == null ? d.createElement(name) : d.createElementNS(namespace, name); parent.appendChild(e); return e; }
    private void xmi(Element e, String name, String value) { e.setAttributeNS(XMI, "xmi:" + name, value); }
    private String id(String prefix) { return prefix + "_" + UUID.randomUUID().toString().replace('-', '_').toUpperCase(); }
    private String shortId() { return UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(); }
    private int number(Object value, int fallback) { return value instanceof Number n ? Math.max(1, (int) Math.round(n.doubleValue())) : fallback; }
    private String visibility(String value) { if (blank(value)) return "public"; return switch (value.trim().charAt(0)) { case '-' -> "private"; case '#' -> "protected"; case '~' -> "package"; default -> "public"; }; }
    private Mapping mapping(AiRelationshipContext relation) { return relationshipTypes.fromApollon(relation.relationshipType()).orElseThrow(() -> new IllegalArgumentException("Tipo de relacion Apollon no soportado para XMI: " + relation.relationshipType())); }
    private String display(String value) { return blank(value) || "*".equals(value.trim()) ? (blank(value) ? "1" : "0..*") : value.trim(); }
    private String[] bounds(String value) { String clean = display(value); if (!clean.contains("..")) return new String[]{clean, clean}; String[] values = clean.split("\\.\\.", 2); return new String[]{values[0], "*".equals(values[1]) ? "-1" : values[1]}; }
    private boolean blank(String value) { return value == null || value.isBlank(); }
}
