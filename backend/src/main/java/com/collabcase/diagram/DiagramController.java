package com.collabcase.diagram;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import com.collabcase.diagram.dto.DiagramRequest;
import com.collabcase.diagram.dto.DiagramResponse;
import com.collabcase.diagram.dto.DiagramRenameRequest;

import jakarta.validation.Valid;
import com.collabcase.user.User;

@RestController
@RequestMapping("/api/diagrams")
public class DiagramController {

    private final DiagramService diagramService;

    public DiagramController(DiagramService diagramService) {
        this.diagramService = diagramService;
    }

    @GetMapping("/{id}")
    public DiagramResponse findById(@PathVariable UUID id, @AuthenticationPrincipal User user) {
        return diagramService.findById(id, user.getId());
    }

    @PutMapping("/{id}")
    public DiagramResponse update(
            @PathVariable UUID id,
            @Valid @RequestBody DiagramRequest request,
            @AuthenticationPrincipal User user) {
        return diagramService.update(id, request, user.getId());
    }

    @PatchMapping("/{id}") public DiagramResponse rename(@PathVariable UUID id,@Valid @RequestBody DiagramRenameRequest request,@AuthenticationPrincipal User user){return diagramService.rename(id,request,user.getId());}
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void delete(@PathVariable UUID id,@AuthenticationPrincipal User user){diagramService.delete(id,user.getId());}
}
