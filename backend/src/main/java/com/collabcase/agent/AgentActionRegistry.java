package com.collabcase.agent;import java.util.*;import org.springframework.http.*;import org.springframework.stereotype.Component;import org.springframework.web.server.ResponseStatusException;import com.collabcase.agent.dto.AgentDtos.*;
@Component public class AgentActionRegistry{
 private static final Set<String> ALLOWED=Set.of("NAVIGATE","CREATE_PROJECT","RENAME_PROJECT","CREATE_VERSION","SWITCH_VERSION","CREATE_DIAGRAM","RENAME_DIAGRAM","ADD_CLASS","ADD_ATTRIBUTE","ADD_METHOD","ADD_RELATIONSHIP","SET_MULTIPLICITY","GENERATE_SEQUENCE","EXPORT_JSON","EXPORT_SQL","EXPORT_PLANTUML","GENERATE_BACKEND","OPEN_AI_PANEL");
 private static final Set<String> WRITES=Set.of("CREATE_PROJECT","RENAME_PROJECT","CREATE_VERSION","CREATE_DIAGRAM","RENAME_DIAGRAM","ADD_CLASS","ADD_ATTRIBUTE","ADD_METHOD","ADD_RELATIONSHIP","SET_MULTIPLICITY","GENERATE_SEQUENCE","GENERATE_BACKEND");
 private static final Set<String> CONFIRM=Set.of("GENERATE_BACKEND");
 public List<Action> validate(List<Action> actions,String role){if(actions==null)return List.of();return actions.stream().map(a->{if(!ALLOWED.contains(a.name()))throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Acción del agente no permitida: "+a.name());if("VIEWER".equals(role)&&WRITES.contains(a.name()))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"El rol VIEWER solo puede recibir ayuda y acciones de lectura");return new Action(a.name(),a.arguments()==null?Map.of():a.arguments(),CONFIRM.contains(a.name()));}).toList();}
 public Set<String> names(){return ALLOWED;}
}
