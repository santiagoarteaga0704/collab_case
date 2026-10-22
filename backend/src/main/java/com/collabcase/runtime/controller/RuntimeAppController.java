package com.collabcase.runtime.controller;

import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import com.collabcase.runtime.app.RuntimeAppService;
import com.collabcase.runtime.dto.RuntimeDtos.*;
import com.collabcase.user.User;

@RestController
public class RuntimeAppController {
    private final RuntimeAppService service;public RuntimeAppController(RuntimeAppService service){this.service=service;}
    @PostMapping("/api/diagrams/{diagramId}/runtime/register") @ResponseStatus(HttpStatus.CREATED)
    public Registration register(@PathVariable UUID diagramId,@AuthenticationPrincipal User user){return service.register(diagramId,user.getId());}
    @GetMapping("/api/projects/{projectId}/runtime/apps") public List<Summary> list(@PathVariable UUID projectId,@AuthenticationPrincipal User user){return service.list(projectId,user.getId());}
    @GetMapping("/api/runtime/apps/{appId}") public Detail detail(@PathVariable UUID appId,@AuthenticationPrincipal User user){return service.detail(appId,user.getId());}
    @DeleteMapping("/api/runtime/apps/{appId}") @ResponseStatus(HttpStatus.NO_CONTENT) public void disable(@PathVariable UUID appId,@AuthenticationPrincipal User user){service.disable(appId,user.getId());}
}
