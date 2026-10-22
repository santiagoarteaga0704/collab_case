package com.collabcase.sequence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.collabcase.ai.*;
import com.collabcase.ai.context.AiDiagramContext;
import com.collabcase.diagram.*;
import com.collabcase.project.*;
import com.collabcase.project.ProjectAccessService.Access;
import com.collabcase.project.ProjectAccessService.EffectiveRole;
import com.collabcase.sequence.dto.SequenceGenerateRequest;

import tools.jackson.databind.ObjectMapper;

class SequenceDiagramTests {
    @Test void structuredJsonIsValidatedPersistedAndRenderedLocally() {
        var sequences = org.mockito.Mockito.mock(GeneratedSequenceDiagramRepository.class);
        var diagrams = org.mockito.Mockito.mock(DiagramRepository.class);
        var access = org.mockito.Mockito.mock(ProjectAccessService.class);
        var provider = org.mockito.Mockito.mock(AiProvider.class);
        Project project = new Project(); ReflectionTestUtils.setField(project, "id", UUID.randomUUID());
        Diagram diagram = diagram(project);
        when(diagrams.findById(diagram.getId())).thenReturn(Optional.of(diagram));
        when(access.requireDiagramEditor(project.getId(), UUID.randomUUID())).thenReturn(new Access(project, EffectiveRole.OWNER));
        when(provider.generate(any())).thenReturn(new AiProviderResponse("""
                ```json
                {"participants":[{"id":"actor","name":"Usuario","type":"ACTOR"},{"id":"cliente","name":"Cliente","type":"CLASS"},{"id":"reserva","name":"Reserva","type":"CLASS"}],
                 "messages":[{"order":1,"from":"actor","to":"cliente","label":"reservar()","kind":"CALL"},{"order":2,"from":"cliente","to":"reserva","label":"crear()","kind":"CALL"}]}
                ```
                """, "mock"));
        when(sequences.saveAndFlush(any())).thenAnswer(invocation -> { GeneratedSequenceDiagram item=invocation.getArgument(0);ReflectionTestUtils.setField(item,"id",UUID.randomUUID());ReflectionTestUtils.setField(item,"createdAt",java.time.Instant.now());ReflectionTestUtils.setField(item,"updatedAt",java.time.Instant.now());return item; });
        var service = new SequenceDiagramService(sequences, diagrams, access, new AiDiagramContextMapper(), provider,
                new SequenceDiagramValidator(), new SequencePlantUmlGenerator(), new ObjectMapper());
        UUID userId = UUID.randomUUID(); when(access.requireDiagramEditor(project.getId(), userId)).thenReturn(new Access(project, EffectiveRole.OWNER));
        var response = service.generate(diagram.getId(), new SequenceGenerateRequest("Reserva", "Cliente realiza una reserva", null, null), userId);
        assertThat(response.plantUml()).contains("@startuml", "actor \"Usuario\"", "reservar()", "@enduml");
        assertThat(response.svg()).contains("<svg"); assertThat(response.id()).isNotNull();
    }

    @Test void rejectsClassNotPresentInSourceModel() {
        var context = new AiDiagramContextMapper().map(diagram(new Project()).getModel());
        var definition = new com.collabcase.sequence.dto.SequenceDefinition(
                List.of(new com.collabcase.sequence.dto.SequenceDefinition.Participant("x","Inventada",com.collabcase.sequence.dto.SequenceDefinition.ParticipantType.CLASS)), List.of());
        assertThatThrownBy(() -> new SequenceDiagramValidator().validate(definition, context, "escenario"))
                .hasMessageContaining("Inventada");
    }

    @Test void sequenceAdviceReturnsUsefulProviderAndParsingErrors() {
        var advice = new SequenceExceptionHandler();
        assertThat(advice.providerUnavailable(new AiProviderException("Proveedor no disponible", new RuntimeException())).getStatusCode().value()).isEqualTo(503);
        assertThat(advice.invalidAiResponse(new AiResponseException("JSON inválido")).getStatusCode().value()).isEqualTo(422);
    }

    private Diagram diagram(Project project) {
        Diagram diagram = new Diagram(); ReflectionTestUtils.setField(diagram,"id",UUID.randomUUID()); diagram.setProject(project); diagram.setName("Clases"); diagram.setType("ClassDiagram");
        diagram.setModel(Map.of("id","d","title","Clases","type","ClassDiagram","nodes",List.of(
                Map.of("id","c","type","class","data",Map.of("name","Cliente","attributes",List.of(),"methods",List.of(Map.of("id","m1","name","+ reservar()")))),
                Map.of("id","r","type","class","data",Map.of("name","Reserva","attributes",List.of(),"methods",List.of(Map.of("id","m2","name","+ crear()"))))),"edges",List.of())); return diagram;
    }
}
