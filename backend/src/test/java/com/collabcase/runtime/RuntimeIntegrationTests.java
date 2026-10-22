package com.collabcase.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;
import com.collabcase.runtime.app.*;
import com.collabcase.runtime.dto.RuntimeDtos.*;
import com.collabcase.runtime.record.RuntimeRecordService;
import com.collabcase.runtime.security.RuntimeTokenService;
import com.collabcase.runtime.sync.RuntimeSyncService;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Transactional
class RuntimeIntegrationTests {
    @Autowired RuntimeAppRepository apps;
    @Autowired RuntimeTokenService tokens;
    @Autowired RuntimeRecordService records;
    @Autowired RuntimeSyncService sync;

    @Test void tokenIsHashedAndCannotCrossApplications(){var a=app("Biblioteca");var b=app("Otra");String tokenA=token(a);String tokenB=token(b);records.create(a.getId(),tokenA,"Cliente",new CreateRecord(UUID.randomUUID(),Map.of("nombre","Alex")));assertThat(a.getTokenHash()).doesNotContain(tokenA);assertThatThrownBy(()->records.list(b.getId(),tokenA,"Cliente")).isInstanceOf(ResponseStatusException.class).satisfies(e->assertThat(((ResponseStatusException)e).getStatusCode().value()).isEqualTo(401));assertThat(records.list(b.getId(),tokenB,"Cliente")).isEmpty();}

    @Test void changesAreOrderedAndDeleteProducesTombstone(){var app=app("Biblioteca");String token=token(app);var cliente=records.create(app.getId(),token,"Cliente",new CreateRecord(UUID.randomUUID(),Map.of("nombre","Alex")));var libro=records.create(app.getId(),token,"Libro",new CreateRecord(UUID.randomUUID(),Map.of("titulo","DDD","disponible",true)));Changes first=records.changes(app.getId(),token,0);assertThat(first.changes()).extracting(RecordResponse::recordId).containsExactly(cliente.recordId(),libro.recordId());long cursor=first.nextCursor();var reserva=records.create(app.getId(),token,"Reserva",new CreateRecord(UUID.randomUUID(),Map.of("fecha","2026-09-17","estado","ACTIVA")));assertThat(records.changes(app.getId(),token,cursor).changes()).extracting(RecordResponse::recordId).containsExactly(reserva.recordId());long beforeDelete=records.changes(app.getId(),token,0).nextCursor();records.delete(app.getId(),token,"Libro",libro.recordId());assertThat(records.changes(app.getId(),token,beforeDelete).changes()).singleElement().satisfies(change->assertThat(change.deleted()).isTrue());}

    @Test void syncDetectsConflictsAndMutationIsIdempotent(){var app=app("Biblioteca");String token=token(app);UUID recordId=UUID.randomUUID(),createId=UUID.randomUUID();var create=new SyncMutation(createId,"Cliente",recordId,0,"UPSERT",Map.of("nombre","Alex"));SyncResponse created=sync.sync(app.getId(),token,new SyncRequest("phone",0,List.of(create)));assertThat(created.accepted()).singleElement().satisfies(a->assertThat(a.version()).isEqualTo(1));SyncResponse duplicate=sync.sync(app.getId(),token,new SyncRequest("phone",created.nextCursor(),List.of(create)));assertThat(duplicate.accepted()).singleElement().satisfies(a->assertThat(a.duplicate()).isTrue());var update=new SyncMutation(UUID.randomUUID(),"Cliente",recordId,1,"UPSERT",Map.of("nombre","Bea"));assertThat(sync.sync(app.getId(),token,new SyncRequest("phone",0,List.of(update))).accepted()).singleElement().satisfies(a->assertThat(a.version()).isEqualTo(2));var stale=new SyncMutation(UUID.randomUUID(),"Cliente",recordId,1,"UPSERT",Map.of("nombre","Sobrescrito"));SyncResponse conflict=sync.sync(app.getId(),token,new SyncRequest("phone",0,List.of(stale)));assertThat(conflict.conflicts()).singleElement().satisfies(c->{assertThat(c.serverVersion()).isEqualTo(2);assertThat(c.serverData()).containsEntry("nombre","Bea");});assertThat(records.get(app.getId(),token,"Cliente",recordId).data()).containsEntry("nombre","Bea");}

    private RuntimeApp app(String name){RuntimeApp app=new RuntimeApp();app.setSourceDiagramId(UUID.randomUUID());app.setSourceProjectId(UUID.randomUUID());app.setName(name);app.setSchemaVersion(1);app.setDefinition(Map.of("schemaVersion",1,"entities",List.of(entity("Cliente",Map.of("nombre","String")),entity("Libro",Map.of("titulo","String","disponible","Boolean")),entity("Reserva",Map.of("fecha","LocalDate","estado","String")))));app.setTokenHash("pending");return apps.saveAndFlush(app);}
    private String token(RuntimeApp app){String token=tokens.generate();app.setTokenHash(tokens.hash(token));apps.saveAndFlush(app);return token;}
    private Map<String,Object> entity(String name,Map<String,String> fields){return Map.of("name",name,"javaName",name,"id",Map.of("javaName","id"),"attributes",fields.entrySet().stream().map(e->Map.<String,Object>of("javaName",e.getKey(),"javaType",e.getValue())).toList());}
}
