package com.collabcase.codegen;

import org.springframework.stereotype.Component;
import com.collabcase.uml.MultiplicityParser;

@Component
public class RelationshipCodeMapper {
    private final MultiplicityParser multiplicities;
    public RelationshipCodeMapper() { this(new MultiplicityParser()); }
    public RelationshipCodeMapper(MultiplicityParser multiplicities) { this.multiplicities = multiplicities; }
    public JpaMapping fromSource(String sourceCardinality,String targetCardinality){return mapping(sourceCardinality,targetCardinality);}
    public JpaMapping fromTarget(String sourceCardinality,String targetCardinality){return mapping(targetCardinality,sourceCardinality);}
    private JpaMapping mapping(String local,String remote){boolean localMany=multiplicities.isMany(local),remoteMany=multiplicities.isMany(remote);if(localMany&&remoteMany)return new JpaMapping("ManyToMany",true);if(remoteMany)return new JpaMapping("OneToMany",true);if(localMany)return new JpaMapping("ManyToOne",false);return new JpaMapping("OneToOne",false);}
    public record JpaMapping(String annotation,boolean collection){}
}
