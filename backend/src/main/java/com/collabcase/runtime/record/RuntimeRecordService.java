package com.collabcase.runtime.record;

import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import com.collabcase.runtime.app.*;
import com.collabcase.runtime.dto.RuntimeDtos.*;
import com.collabcase.runtime.sync.RuntimeVersionService;

@Service
public class RuntimeRecordService {
    private final RuntimeRecordRepository records;private final RuntimeAppService apps;private final RuntimeDefinitionValidator validator;private final RuntimeVersionService versions;
    public RuntimeRecordService(RuntimeRecordRepository records,RuntimeAppService apps,RuntimeDefinitionValidator validator,RuntimeVersionService versions){this.records=records;this.apps=apps;this.validator=validator;this.versions=versions;}
    @Transactional(readOnly=true) public List<RecordResponse> list(UUID appId,String token,String entityType){RuntimeApp app=apps.authenticate(appId,token);validator.validate(app,entityType,Map.of());return records.findByAppIdAndEntityTypeAndDeletedFalseOrderByUpdatedAtDesc(appId,entityType).stream().map(this::response).toList();}
    @Transactional(readOnly=true) public RecordResponse get(UUID appId,String token,String entityType,UUID recordId){RuntimeApp app=apps.authenticate(appId,token);validator.validate(app,entityType,Map.of());return response(find(appId,entityType,recordId));}
    @Transactional public RecordResponse create(UUID appId,String token,String entityType,CreateRecord request){RuntimeApp app=apps.authenticate(appId,token);Map<String,Object> data=data(request.data());validator.validate(app,entityType,data);UUID recordId=request.recordId()==null?UUID.randomUUID():request.recordId();if(records.findByAppIdAndEntityTypeAndRecordId(appId,entityType,recordId).isPresent())throw new ResponseStatusException(HttpStatus.CONFLICT,"recordId already exists");RuntimeRecord record=new RuntimeRecord();record.setApp(app);record.setEntityType(entityType);record.setRecordId(recordId);record.setData(data);record.setVersion(1);record.setSyncVersion(versions.next());return response(records.saveAndFlush(record));}
    @Transactional public RecordResponse update(UUID appId,String token,String entityType,UUID recordId,UpdateRecord request){RuntimeApp app=apps.authenticate(appId,token);Map<String,Object> data=data(request.data());validator.validate(app,entityType,data);RuntimeRecord record=locked(appId,entityType,recordId);if(record.getVersion()!=request.baseVersion())throw conflict(record);record.setData(data);record.setDeleted(false);record.setVersion(record.getVersion()+1);record.setSyncVersion(versions.next());return response(records.saveAndFlush(record));}
    @Transactional public RecordResponse delete(UUID appId,String token,String entityType,UUID recordId){apps.authenticate(appId,token);RuntimeRecord record=locked(appId,entityType,recordId);record.setDeleted(true);record.setVersion(record.getVersion()+1);record.setSyncVersion(versions.next());return response(records.saveAndFlush(record));}
    @Transactional(readOnly=true) public Changes changes(UUID appId,String token,long after){apps.authenticate(appId,token);List<RecordResponse> list=records.findByAppIdAndSyncVersionGreaterThanOrderBySyncVersionAsc(appId,after).stream().map(this::response).toList();long cursor=list.isEmpty()?after:list.getLast().syncVersion();return new Changes(list,cursor);}
    RuntimeRecord locked(UUID appId,String type,UUID id){return records.findLockedByAppIdAndEntityTypeAndRecordId(appId,type,id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Runtime record not found"));}
    RuntimeRecord find(UUID appId,String type,UUID id){return records.findByAppIdAndEntityTypeAndRecordId(appId,type,id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Runtime record not found"));}
    RecordResponse response(RuntimeRecord r){return new RecordResponse(r.getRecordId(),r.getEntityType(),r.getVersion(),r.getSyncVersion(),r.getData(),r.isDeleted(),r.getCreatedAt(),r.getUpdatedAt());}
    static ResponseStatusException conflict(RuntimeRecord r){return new ResponseStatusException(HttpStatus.CONFLICT,"Version conflict; server version is "+r.getVersion());}
    private Map<String,Object> data(Map<String,Object> value){return value==null?Map.of():new LinkedHashMap<>(value);}
}
