package com.collabcase.runtime.controller;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import com.collabcase.runtime.app.RuntimeAppService;
import com.collabcase.runtime.dto.RuntimeDtos.*;
import com.collabcase.runtime.record.RuntimeRecordService;
import com.collabcase.runtime.sync.RuntimeSyncService;

@RestController
@RequestMapping("/api/runtime/apps/{appId}")
public class RuntimeDataController {
    private final RuntimeAppService apps;private final RuntimeRecordService records;private final RuntimeSyncService sync;
    public RuntimeDataController(RuntimeAppService apps,RuntimeRecordService records,RuntimeSyncService sync){this.apps=apps;this.records=records;this.sync=sync;}
    @GetMapping("/definition") public Map<String,Object> definition(@PathVariable UUID appId,@RequestHeader("X-App-Token") String token){return apps.authenticate(appId,token).getDefinition();}
    @GetMapping("/records/{entityType}") public List<RecordResponse> list(@PathVariable UUID appId,@PathVariable String entityType,@RequestHeader("X-App-Token") String token){return records.list(appId,token,entityType);}
    @GetMapping("/records/{entityType}/{recordId}") public RecordResponse get(@PathVariable UUID appId,@PathVariable String entityType,@PathVariable UUID recordId,@RequestHeader("X-App-Token") String token){return records.get(appId,token,entityType,recordId);}
    @PostMapping("/records/{entityType}") @ResponseStatus(HttpStatus.CREATED) public RecordResponse create(@PathVariable UUID appId,@PathVariable String entityType,@RequestHeader("X-App-Token") String token,@RequestBody CreateRecord request){return records.create(appId,token,entityType,request);}
    @PutMapping("/records/{entityType}/{recordId}") public RecordResponse update(@PathVariable UUID appId,@PathVariable String entityType,@PathVariable UUID recordId,@RequestHeader("X-App-Token") String token,@RequestBody UpdateRecord request){return records.update(appId,token,entityType,recordId,request);}
    @DeleteMapping("/records/{entityType}/{recordId}") public RecordResponse delete(@PathVariable UUID appId,@PathVariable String entityType,@PathVariable UUID recordId,@RequestHeader("X-App-Token") String token){return records.delete(appId,token,entityType,recordId);}
    @GetMapping("/changes") public Changes changes(@PathVariable UUID appId,@RequestHeader("X-App-Token") String token,@RequestParam(defaultValue="0") long after){return records.changes(appId,token,after);}
    @PostMapping("/sync") public SyncResponse sync(@PathVariable UUID appId,@RequestHeader("X-App-Token") String token,@RequestBody SyncRequest request){return sync.sync(appId,token,request);}
}
