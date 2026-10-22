package com.collabcase.codegen;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.collabcase.codegen.GenerationModel.GenerationEntity;
import com.collabcase.uml.MultiplicityParser;

final class MobileProjectFiles {
    private final MultiplicityParser multiplicities = new MultiplicityParser();
    void add(Map<String, byte[]> files, GenerationModel model, String root, String javaRoot) {
        put(files, javaRoot + "mobile/MobileChangeLog.java", changeLog(model));
        put(files, javaRoot + "mobile/MobileChangeLogRepository.java", changeLogRepository(model));
        put(files, javaRoot + "mobile/ProcessedMobileMutation.java", processedMutation(model));
        put(files, javaRoot + "mobile/ProcessedMobileMutationRepository.java", processedMutationRepository(model));
        put(files, javaRoot + "mobile/MobileDtos.java", dtos(model));
        put(files, javaRoot + "mobile/MobileSyncLock.java", syncLock(model));
        put(files, javaRoot + "mobile/MobileSyncLockRepository.java", syncLockRepository(model));
        put(files, javaRoot + "mobile/MobileChangeLogService.java", changeLogService(model));
        put(files, javaRoot + "mobile/MobileEntitySyncAdapter.java", adapterInterface(model));
        for (GenerationEntity entity : model.entities()) {
            put(files, javaRoot + "mobile/" + entity.javaName() + "MobileSyncAdapter.java", adapter(model, entity));
        }
        put(files, javaRoot + "mobile/MobileEntitySyncRegistry.java", registry(model));
        put(files, javaRoot + "mobile/MobileSyncService.java", syncService(model));
        put(files, javaRoot + "mobile/MobileController.java", controller(model));
    }

    private String changeLog(GenerationModel m) { return """
            package %s.mobile;
            import jakarta.persistence.*;import java.time.Instant;import java.util.UUID;
            @Entity @Table(name="mobile_change_log") public class MobileChangeLog {
              @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
              @Column(nullable=false) private String entityType;
              @Column(nullable=false) private UUID syncId;
              @Column(nullable=false) private Long recordVersion;
              @Column(nullable=false) private String operation;
              @Column(columnDefinition="text") private String payload;
              @Column(nullable=false,updatable=false) private Instant createdAt;
              protected MobileChangeLog(){}
              public MobileChangeLog(String entityType,UUID syncId,Long recordVersion,String operation,String payload){this.entityType=entityType;this.syncId=syncId;this.recordVersion=recordVersion;this.operation=operation;this.payload=payload;this.createdAt=Instant.now();}
              public Long getId(){return id;}public String getEntityType(){return entityType;}public UUID getSyncId(){return syncId;}public Long getRecordVersion(){return recordVersion;}public String getOperation(){return operation;}public String getPayload(){return payload;}public Instant getCreatedAt(){return createdAt;}
            }
            """.formatted(m.packageName()); }

    private String changeLogRepository(GenerationModel m) { return """
            package %s.mobile;
            import java.util.List;import org.springframework.data.jpa.repository.JpaRepository;
            public interface MobileChangeLogRepository extends JpaRepository<MobileChangeLog,Long>{List<MobileChangeLog> findByIdGreaterThanOrderByIdAsc(Long after);}
            """.formatted(m.packageName()); }

    private String processedMutation(GenerationModel m) { return """
            package %s.mobile;
            import jakarta.persistence.*;import java.time.Instant;import java.util.UUID;
            @Entity @Table(name="processed_mobile_mutation") public class ProcessedMobileMutation{
              @Id private UUID mutationId;@Column(nullable=false,updatable=false) private Instant processedAt;
              protected ProcessedMobileMutation(){}public ProcessedMobileMutation(UUID id){mutationId=id;processedAt=Instant.now();}
            }
            """.formatted(m.packageName()); }

    private String processedMutationRepository(GenerationModel m) { return """
            package %s.mobile;
            import java.util.UUID;import org.springframework.data.jpa.repository.JpaRepository;
            public interface ProcessedMobileMutationRepository extends JpaRepository<ProcessedMobileMutation,UUID>{}
            """.formatted(m.packageName()); }

    private String dtos(GenerationModel m) { return """
            package %s.mobile;
            import java.util.*;import tools.jackson.databind.JsonNode;
            public final class MobileDtos{private MobileDtos(){}
              public record Mutation(UUID mutationId,String entityType,UUID syncId,long baseVersion,String operation,JsonNode data){}
              public record SyncRequest(String deviceId,long lastChangeId,List<Mutation> mutations){}
              public record Accepted(UUID mutationId,String entityType,UUID syncId,long version){}
              public record Conflict(UUID mutationId,String entityType,UUID syncId,long serverVersion,JsonNode serverData,String message){}
              public record Change(long changeId,String entityType,UUID syncId,long version,String operation,JsonNode data){}
              public record ChangesResponse(List<Change> changes,long nextCursor){}
              public record SyncResponse(List<Accepted> accepted,List<Conflict> conflicts,List<Change> changes,long nextCursor){}
              public record SyncRecord(UUID syncId,long version,JsonNode data){}
            }
            """.formatted(m.packageName()); }

    // Single-row lock taken at the start of every write transaction. Change-log ids are
    // allocated and committed in order, so a client cursor never skips a change that was
    // still uncommitted when it read (the gap problem of IDENTITY ids under concurrency).
    private String syncLock(GenerationModel m) { return """
            package %s.mobile;
            import jakarta.persistence.*;
            @Entity @Table(name="mobile_sync_lock") public class MobileSyncLock{
              @Id private Long id;
              protected MobileSyncLock(){}public MobileSyncLock(Long id){this.id=id;}
            }
            """.formatted(m.packageName()); }

    private String syncLockRepository(GenerationModel m) { return """
            package %s.mobile;
            import java.util.Optional;import jakarta.persistence.LockModeType;import org.springframework.data.jpa.repository.*;
            public interface MobileSyncLockRepository extends JpaRepository<MobileSyncLock,Long>{
              @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select l from MobileSyncLock l where l.id = 1") Optional<MobileSyncLock> lockRow();
            }
            """.formatted(m.packageName()); }

    private String changeLogService(GenerationModel m) { return """
            package %s.mobile;
            import java.util.*;import org.springframework.boot.context.event.ApplicationReadyEvent;import org.springframework.context.event.EventListener;import org.springframework.stereotype.Service;import org.springframework.transaction.annotation.Transactional;import tools.jackson.databind.*;import static %s.mobile.MobileDtos.*;
            @Service public class MobileChangeLogService{
              private final MobileChangeLogRepository repository;private final MobileSyncLockRepository locks;private final ObjectMapper json;
              public MobileChangeLogService(MobileChangeLogRepository repository,MobileSyncLockRepository locks,ObjectMapper json){this.repository=repository;this.locks=locks;this.json=json;}
              @EventListener(ApplicationReadyEvent.class) public void createLockRow(){if(!locks.existsById(1L))locks.saveAndFlush(new MobileSyncLock(1L));}
              public void beginWrite(){if(locks.lockRow().isEmpty()){locks.saveAndFlush(new MobileSyncLock(1L));locks.lockRow();}}
              public void upsert(String type,UUID syncId,Long version,Object payload){save(type,syncId,version,"UPSERT",payload);}
              public void delete(String type,UUID syncId,Long version){save(type,syncId,version,"DELETE",null);}
              private void save(String type,UUID syncId,Long version,String operation,Object payload){try{repository.save(new MobileChangeLog(type,syncId,version,operation,payload==null?null:json.writeValueAsString(payload)));}catch(Exception e){throw new IllegalStateException("No se pudo registrar el cambio móvil",e);}}
              @Transactional(readOnly=true) public ChangesResponse after(long cursor){var values=repository.findByIdGreaterThanOrderByIdAsc(cursor).stream().map(this::change).toList();long next=values.isEmpty()?cursor:values.getLast().changeId();return new ChangesResponse(values,next);}
              private Change change(MobileChangeLog value){try{return new Change(value.getId(),value.getEntityType(),value.getSyncId(),value.getRecordVersion(),value.getOperation(),value.getPayload()==null?null:json.readTree(value.getPayload()));}catch(Exception e){throw new IllegalStateException(e);}}
            }
            """.formatted(m.packageName(), m.packageName()); }

    private String adapterInterface(GenerationModel m) { return """
            package %s.mobile;
            import java.util.*;import tools.jackson.databind.JsonNode;import static %s.mobile.MobileDtos.*;
            public interface MobileEntitySyncAdapter{
              String entityType();int priority();Optional<SyncRecord> find(UUID syncId);SyncRecord create(UUID syncId,JsonNode data);SyncRecord update(UUID syncId,long baseVersion,JsonNode data);SyncRecord delete(UUID syncId,long baseVersion);
            }
            """.formatted(m.packageName(), m.packageName()); }

    private String adapter(GenerationModel m, GenerationEntity e) {
        int priority = dependencyPriority(m, e);
        String n = e.javaName();
        List<MobileRelation> relations = mobileRelations(m, e);
        String relationFields = relations.stream().map(r -> "private final " + r.target() + "Repository " + r.repositoryField() + ";").collect(Collectors.joining());
        String relationParameters = relations.stream().map(r -> "," + r.target() + "Repository " + r.repositoryField()).collect(Collectors.joining());
        String relationAssignments = relations.stream().map(r -> "this." + r.repositoryField() + "=" + r.repositoryField() + ";").collect(Collectors.joining());
        String relationApply = relations.stream().map(this::relationApply).collect(Collectors.joining());
        return """
                package %s.mobile;
                import java.util.*;import org.springframework.stereotype.Component;import tools.jackson.databind.*;
                import %s.entity.*;import %s.repository.*;import %s.dto.%sRequest;import %s.service.%sService;import static %s.mobile.MobileDtos.*;
                @Component public class %sMobileSyncAdapter implements MobileEntitySyncAdapter{
                  private final %sRepository repository;private final %sService service;private final ObjectMapper json;%s
                  public %sMobileSyncAdapter(%sRepository repository,%sService service,ObjectMapper json%s){this.repository=repository;this.service=service;this.json=json;%s}
                  public String entityType(){return "%s";}public int priority(){return %d;}
                  public Optional<SyncRecord> find(UUID syncId){return repository.findBySyncId(syncId).map(this::record);}
                  public SyncRecord create(UUID syncId,JsonNode data){if(repository.findBySyncId(syncId).isPresent())return repository.findBySyncId(syncId).map(this::record).orElseThrow();%s entity=new %s();entity.setSyncId(syncId);apply(entity,data);return record(repository.saveAndFlush(entity));}
                  public SyncRecord update(UUID syncId,long baseVersion,JsonNode data){%s entity=repository.findBySyncId(syncId).orElseThrow(()->new IllegalArgumentException("Registro no encontrado"));check(entity.getVersion(),baseVersion);apply(entity,data);return record(repository.saveAndFlush(entity));}
                  public SyncRecord delete(UUID syncId,long baseVersion){%s entity=repository.findBySyncId(syncId).orElseThrow(()->new IllegalArgumentException("Registro no encontrado"));check(entity.getVersion(),baseVersion);SyncRecord old=record(entity);repository.delete(entity);repository.flush();return old;}
                  private void apply(%s entity,JsonNode data){try{service.applyMobile(entity,json.treeToValue(data,%sRequest.class));%s}catch(Exception ex){throw new IllegalArgumentException("Datos o referencias inválidos para %s",ex);}}
                  private SyncRecord record(%s entity){return new SyncRecord(entity.getSyncId(),entity.getVersion(),json.valueToTree(service.response(entity)));}
                  private void check(Long current,long base){if(current==null||current!=base)throw new IllegalStateException("VERSION_CONFLICT");}
                }
                """.formatted(m.packageName(),m.packageName(),m.packageName(),m.packageName(),n,m.packageName(),n,m.packageName(),n,n,n,relationFields,n,n,n,relationParameters,relationAssignments,n,priority,n,n,n,n,n,n,relationApply,n,n);
    }

    private String registry(GenerationModel m) {
        return """
                package %s.mobile;
                import java.util.*;import org.springframework.stereotype.Component;
                @Component public class MobileEntitySyncRegistry{
                  private final Map<String,MobileEntitySyncAdapter> adapters;
                  public MobileEntitySyncRegistry(List<MobileEntitySyncAdapter> values){Map<String,MobileEntitySyncAdapter> map=new HashMap<>();for(var value:values)map.put(value.entityType(),value);adapters=Map.copyOf(map);}
                  public MobileEntitySyncAdapter require(String type){var value=adapters.get(type);if(value==null)throw new IllegalArgumentException("Tipo de entidad móvil desconocido: "+type);return value;}
                }
                """.formatted(m.packageName());
    }

    private String syncService(GenerationModel m) { return """
            package %s.mobile;
            import java.util.*;import org.springframework.stereotype.Service;import org.springframework.transaction.PlatformTransactionManager;import org.springframework.transaction.TransactionDefinition;import org.springframework.transaction.support.TransactionTemplate;import static %s.mobile.MobileDtos.*;
            // Each mutation runs in its own transaction: a database error in one mutation (for example a
            // foreign key violation when deleting a parent) becomes a conflict for that mutation only,
            // instead of rolling back the whole batch and blocking the offline queue forever.
            @Service public class MobileSyncService{
              private final MobileEntitySyncRegistry registry;private final ProcessedMobileMutationRepository processed;private final MobileChangeLogService changes;private final TransactionTemplate transaction;
              public MobileSyncService(MobileEntitySyncRegistry registry,ProcessedMobileMutationRepository processed,MobileChangeLogService changes,PlatformTransactionManager transactions){this.registry=registry;this.processed=processed;this.changes=changes;this.transaction=new TransactionTemplate(transactions);this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);}
              public SyncResponse sync(SyncRequest request){List<Accepted> accepted=new ArrayList<>();List<Conflict> conflicts=new ArrayList<>();List<Mutation> mutations=new ArrayList<>(request.mutations()==null?List.of():request.mutations());mutations.sort(Comparator.comparingInt(this::priority));for(var mutation:mutations)process(mutation,accepted,conflicts);var remote=changes.after(request.lastChangeId());return new SyncResponse(accepted,conflicts,remote.changes(),remote.nextCursor());}
              private int priority(Mutation m){try{return registry.require(m.entityType()).priority();}catch(RuntimeException ex){return Integer.MAX_VALUE;}}
              private void process(Mutation m,List<Accepted> accepted,List<Conflict> conflicts){MobileEntitySyncAdapter adapter;try{adapter=registry.require(m.entityType());}catch(RuntimeException ex){conflicts.add(new Conflict(m.mutationId(),m.entityType(),m.syncId(),0,null,ex.getMessage()));return;}if(processed.existsById(m.mutationId())){adapter.find(m.syncId()).ifPresent(r->accepted.add(new Accepted(m.mutationId(),m.entityType(),m.syncId(),r.version())));return;}try{SyncRecord result=transaction.execute(status->apply(adapter,m));accepted.add(new Accepted(m.mutationId(),m.entityType(),m.syncId(),result.version()));}catch(Exception ex){var server=adapter.find(m.syncId()).orElse(null);conflicts.add(new Conflict(m.mutationId(),m.entityType(),m.syncId(),server==null?0:server.version(),server==null?null:server.data(),ex.getMessage()));}}
              private SyncRecord apply(MobileEntitySyncAdapter adapter,Mutation m){changes.beginWrite();SyncRecord result;if("DELETE".equals(m.operation())){result=adapter.delete(m.syncId(),m.baseVersion());changes.delete(m.entityType(),m.syncId(),result.version());}else if(m.baseVersion()==0&&!adapter.find(m.syncId()).isPresent()){result=adapter.create(m.syncId(),m.data());changes.upsert(m.entityType(),m.syncId(),result.version(),result.data());}else{result=adapter.update(m.syncId(),m.baseVersion(),m.data());changes.upsert(m.entityType(),m.syncId(),result.version(),result.data());}processed.save(new ProcessedMobileMutation(m.mutationId()));return result;}
            }
            """.formatted(m.packageName(),m.packageName()); }

    private String controller(GenerationModel m) { return """
            package %s.mobile;
            import java.io.*;import org.springframework.beans.factory.annotation.Value;import org.springframework.http.*;import org.springframework.web.bind.annotation.*;import tools.jackson.databind.ObjectMapper;import static %s.mobile.MobileDtos.*;
            @RestController @RequestMapping("/api/mobile") public class MobileController{
              private final MobileSyncService sync;private final MobileChangeLogService changes;private final ObjectMapper json;private final String token;
              public MobileController(MobileSyncService sync,MobileChangeLogService changes,ObjectMapper json,@Value("${mobile.api-token:}")String token){this.sync=sync;this.changes=changes;this.json=json;this.token=token;}
              @GetMapping("/definition") public Object definition(@RequestHeader(value="X-Mobile-Token",required=false)String supplied)throws IOException{authorize(supplied);try(InputStream in=getClass().getResourceAsStream("/app-definition.json")){return json.readValue(in,Object.class);}}
              @GetMapping("/changes") public ChangesResponse changes(@RequestParam(defaultValue="0")long after,@RequestHeader(value="X-Mobile-Token",required=false)String supplied){authorize(supplied);return changes.after(after);}
              @PostMapping("/sync") public SyncResponse sync(@RequestBody SyncRequest request,@RequestHeader(value="X-Mobile-Token",required=false)String supplied){authorize(supplied);return sync.sync(request);}
              private void authorize(String supplied){if(!token.isBlank()&&!token.equals(supplied))throw new org.springframework.web.server.ResponseStatusException(HttpStatus.UNAUTHORIZED,"X-Mobile-Token inválido");}
            }
            """.formatted(m.packageName(),m.packageName()); }

    private int dependencyPriority(GenerationModel m, GenerationEntity e) {
        return dependencyPriority(m, e, new java.util.HashSet<>());
    }

    private int dependencyPriority(GenerationModel model, GenerationEntity entity, java.util.Set<String> visiting) {
        if (!visiting.add(entity.sourceElementId())) return 0;
        int result = 0;
        for (var relation : model.relationships()) {
            String dependencyId = null;
            if (entity.sourceElementId().equals(relation.sourceId()) && ("ClassUnidirectional".equals(relation.originalType()) || "ClassBidirectional".equals(relation.originalType()))) dependencyId = relation.targetId();
            else if (entity.sourceElementId().equals(relation.targetId()) && ("ClassAggregation".equals(relation.originalType()) || "ClassComposition".equals(relation.originalType()) || "ClassBidirectional".equals(relation.originalType()))) dependencyId = relation.sourceId();
            if (dependencyId != null) {
                String id = dependencyId;
                var dependency = model.entities().stream().filter(value -> value.sourceElementId().equals(id)).findFirst();
                if (dependency.isPresent()) result = Math.max(result, 1 + dependencyPriority(model, dependency.get(), new java.util.HashSet<>(visiting)));
            }
        }
        return result;
    }

    private List<MobileRelation> mobileRelations(GenerationModel model, GenerationEntity entity) {
        Map<String, MobileRelation> values = new LinkedHashMap<>();
        for (var relation : model.relationships()) {
            if (List.of("ClassDependency", "ClassInheritance", "ClassRealization").contains(relation.originalType())) continue;
            if (relation.multiple()) {
                GenerationEntity source = entity(model, relation.sourceId());
                GenerationEntity target = entity(model, relation.targetId());
                GenerationEntity owner = "ClassBidirectional".equals(relation.originalType()) && source.tableName().compareTo(target.tableName()) > 0 ? target : source;
                GenerationEntity other = owner == source ? target : source;
                if (entity.sourceElementId().equals(owner.sourceElementId())) addRelation(values, other.javaName(), lower(other.javaName()), true, true);
                continue;
            }
            if (entity.sourceElementId().equals(relation.sourceId()) && ("ClassUnidirectional".equals(relation.originalType()) || "ClassBidirectional".equals(relation.originalType()))) {
                addRelation(values, relation.targetJavaName(), lower(relation.targetJavaName()), multiplicities.isMany(relation.targetCardinality()), false);
            } else if (entity.sourceElementId().equals(relation.targetId()) && ("ClassAggregation".equals(relation.originalType()) || "ClassComposition".equals(relation.originalType()) || "ClassBidirectional".equals(relation.originalType()))) {
                addRelation(values, relation.sourceJavaName(), lower(relation.sourceJavaName()), multiplicities.isMany(relation.sourceCardinality()), false);
            }
        }
        return List.copyOf(values.values());
    }

    private void addRelation(Map<String, MobileRelation> values, String target, String base, boolean collection, boolean set) {
        String field = collection ? base + (set ? "Set" : "List") : base;
        values.put(field, new MobileRelation(target, field, base + "Repository", base + (collection ? "SyncIds" : "SyncId"), collection, set));
    }

    private String relationApply(MobileRelation relation) {
        String jsonField = relation.jsonField();
        String setter = Character.toUpperCase(relation.field().charAt(0)) + relation.field().substring(1);
        if (relation.collection()) { String declaration=relation.set()?"Set<"+relation.target()+"> refs=new LinkedHashSet<>()":"List<"+relation.target()+"> refs=new ArrayList<>()"; return "if(data.has(\"" + jsonField + "\")){"+declaration+";for(JsonNode id:data.get(\"" + jsonField + "\"))refs.add(" + relation.repositoryField() + ".findBySyncId(UUID.fromString(id.asText())).orElseThrow(()->new IllegalArgumentException(\"Referencia no encontrada\")));entity.set" + setter + "(refs);}"; }
        return "if(data.has(\"" + jsonField + "\"))entity.set" + setter + "(" + relation.repositoryField() + ".findBySyncId(UUID.fromString(data.get(\"" + jsonField + "\").asText())).orElseThrow(()->new IllegalArgumentException(\"Referencia no encontrada\")));";
    }

    private GenerationEntity entity(GenerationModel model,String id){return model.entities().stream().filter(value->value.sourceElementId().equals(id)).findFirst().orElseThrow();}
    private String lower(String value) { return Character.toLowerCase(value.charAt(0)) + value.substring(1); }
    private record MobileRelation(String target, String field, String repositoryField, String jsonField, boolean collection, boolean set) {}

    private void put(Map<String, byte[]> files, String path, String content) {
        files.put(path, content.getBytes(StandardCharsets.UTF_8));
    }
}
