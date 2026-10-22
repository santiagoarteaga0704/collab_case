package com.collabcase.codegen;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import com.collabcase.ai.context.AiClassContext;
import com.collabcase.ai.context.AiDiagramContext;
import com.collabcase.ai.context.AiRelationshipContext;
import com.collabcase.codegen.GenerationModel.GenerationAttribute;
import com.collabcase.codegen.GenerationModel.GenerationEntity;
import com.collabcase.codegen.GenerationModel.GenerationMethod;
import com.collabcase.codegen.GenerationModel.GenerationParameter;
import com.collabcase.codegen.GenerationModel.GenerationRelationship;
import com.collabcase.codegen.GenerationModel.IdGenerationStrategy;
import com.collabcase.uml.MultiplicityParser;

@Component
public class GenerationModelBuilder {
    private final JavaNames names; private final JavaTypeMapper types; private final IdGenerationPolicy idPolicy; private final MultiplicityParser multiplicities;
    @Autowired
    public GenerationModelBuilder(JavaNames names, JavaTypeMapper types, IdGenerationPolicy idPolicy, MultiplicityParser multiplicities) { this.names=names; this.types=types; this.idPolicy=idPolicy; this.multiplicities=multiplicities; }
    public GenerationModelBuilder(JavaNames names, JavaTypeMapper types) { this(names,types,new IdGenerationPolicy(),new MultiplicityParser()); }

    public GenerationModel build(AiDiagramContext context) {
        String application = context.title() == null || context.title().isBlank() ? "Generated Application" : context.title().trim();
        List<String> warnings = new ArrayList<>(); Set<String> entityNames = new HashSet<>();
        for (AiClassContext item : context.classes()) { String name=names.className(item.name()); if (name!=null) entityNames.add(name); }
        Map<String,String> javaNames = new LinkedHashMap<>(); for (AiClassContext item:context.classes()) javaNames.put(item.id(), names.className(item.name()));
        Map<String,String> parents = new HashMap<>();
        for (AiRelationshipContext r:context.relationships()) if ("ClassInheritance".equals(r.relationshipType())) parents.put(r.sourceElementId(), javaNames.get(r.targetElementId()));
        List<GenerationEntity> entities = new ArrayList<>();
        for (AiClassContext item:context.classes()) {
            String javaName=javaNames.get(item.id()); List<GenerationAttribute> attributes=new ArrayList<>(); GenerationAttribute id=null;
            for (var attribute:item.attributes()) {
                String field=names.fieldName(attribute.name()); var mapped=types.map(attribute.type(),entityNames); if(mapped.warning()!=null) warnings.add(item.name()+"."+attribute.name()+": "+mapped.warning());
                GenerationAttribute generated=new GenerationAttribute(attribute.id(),attribute.name(),field,mapped.javaType()); attributes.add(generated);
                String lower=field==null?"":field.toLowerCase(); if (id==null && ("id".equals(lower)||lower.equals(names.fieldName(item.name())+"Id"))) id=generated;
            }
            boolean synthetic=id==null; if(synthetic){ id=new GenerationAttribute("", "id", "id", "UUID",IdGenerationStrategy.UUID); warnings.add("La clase "+item.name()+" no tenia identificador; se anadio un UUID tecnico al backend generado."); }else{id=new GenerationAttribute(id.sourceId(),id.name(),id.javaName(),id.javaType(),idPolicy.forType(id.javaType()));}
            List<GenerationMethod> methods=new ArrayList<>(); for(var method:item.methods()) methods.add(method(method.signature(),method.name(),entityNames,warnings,item));
            entities.add(new GenerationEntity(item.id(),item.name(),javaName,names.tableName(item.name()),names.path(item.name()),id,synthetic,parents.get(item.id()),List.copyOf(attributes),List.copyOf(methods)));
        }
        List<GenerationRelationship> relationships=new ArrayList<>();
        for(AiRelationshipContext r:context.relationships()) { String source=javaNames.get(r.sourceElementId());String target=javaNames.get(r.targetElementId());boolean manyToMany=multiplicities.isManyToMany(r.sourceCardinality(),r.targetCardinality())&&List.of("ClassBidirectional","ClassUnidirectional").contains(r.relationshipType());String mobileField=target==null?null:Character.toLowerCase(target.charAt(0))+target.substring(1)+(manyToMany?"SyncIds":"SyncId");relationships.add(new GenerationRelationship(r.relationshipType(),r.sourceElementId(),source,source,r.targetElementId(),target,target,r.sourceCardinality(),r.targetCardinality(),direction(r.relationshipType()),mobileField,manyToMany?"MANY_TO_MANY":relationshipType(r),manyToMany)); if(manyToMany) warnings.add("Si la relación muchos-a-muchos necesita atributos propios, modele una clase asociativa."); if("ClassDependency".equals(r.relationshipType())) warnings.add("Dependency "+r.sourceClass()+" -> "+r.targetClass()+" preservada como metadata; no genera relacion JPA."); if("ClassRealization".equals(r.relationshipType())) warnings.add("Realization "+r.sourceClass()+" -> "+r.targetClass()+" preservada como metadata: el UMLModel no identifica el target como interface."); }
        return new GenerationModel(1,context.id(),application,names.artifactId(application),"com.collabcase.generated."+names.packageSegment(application),List.copyOf(entities),List.copyOf(relationships),List.copyOf(warnings));
    }
    private GenerationMethod method(String signature,String fallback,Set<String> entities,List<String>warnings,AiClassContext owner){ String text=signature==null?"":signature.trim(); String visibility=text.startsWith("-")?"private":text.startsWith("#")?"protected":"public"; text=text.replaceFirst("^[+#~-]\\s*",""); int open=text.indexOf('('),close=text.lastIndexOf(')'); String rawName=open<0?fallback:text.substring(0,open).trim(); List<GenerationParameter> parameters=new ArrayList<>(); if(open>=0&&close>open) for(String raw:text.substring(open+1,close).split(",")){if(raw.isBlank())continue;String[]p=raw.trim().split("\\s*:\\s*",2);var mapped=types.map(p.length>1?p[1]:null,entities);if(mapped.warning()!=null)warnings.add(owner.name()+"."+rawName+": "+mapped.warning());parameters.add(new GenerationParameter(p[0],names.fieldName(p[0]),mapped.javaType()));} int colon=close<0?-1:text.indexOf(':',close); String rawReturn=colon<0?null:text.substring(colon+1).trim(); if(rawReturn==null){String getter=rawName.matches("get_.+")?rawName.substring(4):rawName.matches("get[A-Z].+")?Character.toLowerCase(rawName.charAt(3))+rawName.substring(4):null; if(getter!=null){var matches=owner.attributes().stream().filter(a->a.name().equalsIgnoreCase(getter)).toList();if(matches.size()==1){rawReturn=matches.getFirst().type();warnings.add("Se infirio el retorno de "+owner.name()+"."+rawName+" como "+rawReturn+" a partir del atributo "+matches.getFirst().name()+".");}}} if(rawReturn==null)warnings.add("El metodo "+owner.name()+"."+rawName+" no tenia retorno explicito; se genero void."); var result=rawReturn==null?new JavaTypeMapper.Result("void",null):types.map(rawReturn,entities); return new GenerationMethod(rawName,names.fieldName(rawName),visibility,result.javaType(),List.copyOf(parameters)); }
    private String direction(String type){return switch(type){case "ClassBidirectional"->"BIDIRECTIONAL";case "ClassDependency","ClassInheritance","ClassRealization","ClassUnidirectional"->"SOURCE_TO_TARGET";case "ClassAggregation","ClassComposition"->"PART_TO_WHOLE";default->"UNKNOWN";};}
    private String relationshipType(AiRelationshipContext relation){return switch(relation.relationshipType()){case "ClassInheritance"->"INHERITANCE";case "ClassRealization"->"REALIZATION";case "ClassDependency"->"DEPENDENCY";default->"ASSOCIATION";};}
}
