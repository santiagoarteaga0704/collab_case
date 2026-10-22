package com.collabcase.agent;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import com.collabcase.agent.dto.AgentDtos.*;
import com.collabcase.project.*;
import com.collabcase.project.ProjectAccessService.*;
import tools.jackson.databind.json.JsonMapper;

class CollabCaseAgentTests {
 private final UUID projectId=UUID.randomUUID(),userId=UUID.randomUUID();
 private Context context(){return new Context("/projects/x",projectId,"Biblioteca",null,"main",null,null,"OWNER",null);}
 private CollabCaseAgentService service(String answer,EffectiveRole role){AgentProvider provider=mock(AgentProvider.class);when(provider.generate(any(),any(),any())).thenReturn(answer);ProjectAccessService access=mock(ProjectAccessService.class);when(access.requireRead(projectId,userId)).thenReturn(new Access(new Project(),role));return new CollabCaseAgentService(provider,new AgentActionRegistry(),access,JsonMapper.builder().build());}
 @Test void guideResponseIsAccepted(){var response=service("{\"type\":\"GUIDE\",\"message\":\"Pulsa Nuevo diagrama\",\"actions\":[]}",EffectiveRole.OWNER).chat(new ChatRequest("¿Cómo creo un diagrama?",context(),List.of()),userId);assertThat(response.type()).isEqualTo("GUIDE");assertThat(response.actions()).isEmpty();}
 @Test void createDiagramActionIsValidated(){var response=service("{\"type\":\"ACTION\",\"message\":\"Lo crearé\",\"actions\":[{\"name\":\"CREATE_DIAGRAM\",\"arguments\":{\"name\":\"Biblioteca\"},\"confirmationRequired\":false}]}",EffectiveRole.EDITOR).chat(new ChatRequest("Crea Biblioteca",context(),List.of()),userId);assertThat(response.actions()).extracting(Action::name).containsExactly("CREATE_DIAGRAM");}
 @Test void viewerCannotCreate(){assertThatThrownBy(()->service("{\"type\":\"ACTION\",\"message\":\"\",\"actions\":[{\"name\":\"CREATE_DIAGRAM\",\"arguments\":{},\"confirmationRequired\":false}]}",EffectiveRole.VIEWER).chat(new ChatRequest("crea",context(),List.of()),userId)).isInstanceOf(ResponseStatusException.class).satisfies(e->assertThat(((ResponseStatusException)e).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));}
 @Test void unknownAndDestructiveActionsAreRejected(){for(String action:List.of("INVENTED_ACTION","DELETE_PROJECT"))assertThatThrownBy(()->service("{\"type\":\"ACTION\",\"message\":\"\",\"actions\":[{\"name\":\""+action+"\",\"arguments\":{},\"confirmationRequired\":false}]}",EffectiveRole.OWNER).chat(new ChatRequest("hazlo",context(),List.of()),userId)).isInstanceOf(ResponseStatusException.class);}
 @Test void addClassIsARegisteredAction(){var response=service("{\"type\":\"ACTION\",\"message\":\"\",\"actions\":[{\"name\":\"ADD_CLASS\",\"arguments\":{\"name\":\"Cliente\"},\"confirmationRequired\":false}]}",EffectiveRole.EDITOR).chat(new ChatRequest("Agrega Cliente",context(),List.of()),userId);assertThat(response.actions().getFirst().name()).isEqualTo("ADD_CLASS");}
}
