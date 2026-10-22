package com.collabcase.diagram;

import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import com.collabcase.project.*;
import com.collabcase.project.ProjectAccessService.*;
import com.collabcase.project.member.ProjectMemberRepository;
import com.collabcase.project.version.*;
import com.collabcase.sequence.GeneratedSequenceDiagramRepository;

class DeletionServiceTests {
 @Test void deletingDiagramRemovesOnlyItsSequencesAndPublishesRoomCleanup(){UUID projectId=UUID.randomUUID(),diagramId=UUID.randomUUID(),userId=UUID.randomUUID();Project project=new Project();ReflectionTestUtils.setField(project,"id",projectId);Diagram diagram=new Diagram();ReflectionTestUtils.setField(diagram,"id",diagramId);diagram.setProject(project);var diagrams=mock(DiagramRepository.class);var sequences=mock(GeneratedSequenceDiagramRepository.class);var access=mock(ProjectAccessService.class);var events=mock(ApplicationEventPublisher.class);when(diagrams.findById(diagramId)).thenReturn(Optional.of(diagram));when(access.requireDiagramEditor(projectId,userId)).thenReturn(new Access(project,EffectiveRole.EDITOR));new DiagramService(diagrams,access,events,mock(ProjectVersionService.class),sequences).delete(diagramId,userId);verify(sequences).deleteBySourceDiagramId(diagramId);verify(diagrams).delete(diagram);verify(events).publishEvent(any(DiagramDeletedEvent.class));}
 @Test void deletingProjectCascadesApplicationOwnedDependencies(){UUID projectId=UUID.randomUUID(),diagramId=UUID.randomUUID(),userId=UUID.randomUUID();Project project=new Project();ReflectionTestUtils.setField(project,"id",projectId);Diagram diagram=new Diagram();ReflectionTestUtils.setField(diagram,"id",diagramId);diagram.setProject(project);ProjectVersion version=new ProjectVersion();version.setProject(project);version.setName("main");var projects=mock(ProjectRepository.class);var members=mock(ProjectMemberRepository.class);var access=mock(ProjectAccessService.class);var versions=mock(ProjectVersionRepository.class);var diagrams=mock(DiagramRepository.class);var sequences=mock(GeneratedSequenceDiagramRepository.class);var events=mock(ApplicationEventPublisher.class);when(access.requireOwner(projectId,userId)).thenReturn(new Access(project,EffectiveRole.OWNER));when(diagrams.findByProjectIdOrderByUpdatedAtDesc(projectId)).thenReturn(List.of(diagram));when(versions.findByProjectIdOrderByMainVersionDescCreatedAtAsc(projectId)).thenReturn(List.of(version));new ProjectService(projects,members,access,versions,diagrams,sequences,mock(ProjectVersionService.class),events).delete(projectId,userId);verify(sequences).deleteBySourceDiagramId(diagramId);verify(diagrams).deleteAll(List.of(diagram));verify(versions).deleteAll(List.of(version));verify(members).deleteByProjectId(projectId);verify(projects).delete(project);}
}
