package com.collabcase.sequence;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.collabcase.sequence.dto.SequenceDiagramResponse;
import com.collabcase.sequence.dto.SequenceGenerateRequest;
import com.collabcase.sequence.dto.SequenceUpdateRequest;
import com.collabcase.user.User;

import jakarta.validation.Valid;

@RestController
public class SequenceDiagramController {
    private final SequenceDiagramService service;
    public SequenceDiagramController(SequenceDiagramService service) { this.service = service; }

    @PostMapping("/api/diagrams/{diagramId}/sequence-diagrams")
    public SequenceDiagramResponse generate(@PathVariable UUID diagramId,
            @Valid @RequestBody SequenceGenerateRequest request, @AuthenticationPrincipal User user) {
        return service.generate(diagramId, request, user.getId());
    }
    @GetMapping("/api/diagrams/{diagramId}/sequence-diagrams")
    public List<SequenceDiagramResponse> all(@PathVariable UUID diagramId, @AuthenticationPrincipal User user) {
        return service.findAll(diagramId, user.getId());
    }
    @GetMapping("/api/sequence-diagrams/{id}")
    public SequenceDiagramResponse one(@PathVariable UUID id, @AuthenticationPrincipal User user) {
        return service.findById(id, user.getId());
    }
    @PatchMapping("/api/sequence-diagrams/{id}")
    public SequenceDiagramResponse update(@PathVariable UUID id, @Valid @RequestBody SequenceUpdateRequest request,
            @AuthenticationPrincipal User user) { return service.update(id, request, user.getId()); }
    @PostMapping("/api/sequence-diagrams/{id}/regenerate")
    public SequenceDiagramResponse regenerate(@PathVariable UUID id, @AuthenticationPrincipal User user) {
        return service.regenerate(id, user.getId());
    }
    @DeleteMapping("/api/sequence-diagrams/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id, @AuthenticationPrincipal User user) {
        service.delete(id, user.getId()); return ResponseEntity.noContent().build();
    }
    @GetMapping("/api/sequence-diagrams/{id}/plantuml")
    public ResponseEntity<byte[]> plantUml(@PathVariable UUID id, @AuthenticationPrincipal User user) {
        SequenceDiagramResponse item = service.findById(id, user.getId());
        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(item.name() + ".puml", StandardCharsets.UTF_8).build().toString())
                .body(item.plantUml().getBytes(StandardCharsets.UTF_8));
    }
}
