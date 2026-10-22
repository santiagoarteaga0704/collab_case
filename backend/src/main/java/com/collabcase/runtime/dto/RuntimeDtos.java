package com.collabcase.runtime.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class RuntimeDtos {
    private RuntimeDtos(){}
    public record Registration(UUID appId,String name,int schemaVersion,String appToken,Map<String,Object> definition){}
    public record Summary(UUID id,String name,int schemaVersion,boolean active,Instant createdAt){}
    public record Detail(UUID id,UUID sourceDiagramId,UUID sourceProjectId,String name,int schemaVersion,boolean active,Instant createdAt,Instant updatedAt,Map<String,Object> definition){}
    public record CreateRecord(UUID recordId,Map<String,Object> data){}
    public record UpdateRecord(long baseVersion,Map<String,Object> data){}
    public record RecordResponse(UUID recordId,String entityType,long version,long syncVersion,Map<String,Object> data,boolean deleted,Instant createdAt,Instant updatedAt){}
    public record Changes(List<RecordResponse> changes,long nextCursor){}
    public record SyncMutation(UUID mutationId,String entityType,UUID recordId,long baseVersion,String operation,Map<String,Object> data){}
    public record SyncRequest(String deviceId,long lastSyncVersion,List<SyncMutation> mutations){}
    public record Accepted(UUID mutationId,UUID recordId,long version,long syncVersion,boolean duplicate){}
    public record Conflict(UUID mutationId,UUID recordId,long serverVersion,Map<String,Object> serverData,boolean deleted){}
    public record Rejected(UUID mutationId,UUID recordId,String message){}
    public record SyncResponse(List<Accepted> accepted,List<Conflict> conflicts,List<RecordResponse> changes,long nextCursor,List<Rejected> rejected){}
}
