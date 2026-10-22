package com.collabcase.project.version;
import java.net.URI;import java.util.*;import org.springframework.http.*;import org.springframework.security.core.annotation.AuthenticationPrincipal;import org.springframework.web.bind.annotation.*;import com.collabcase.diagram.dto.DiagramSummaryResponse;import com.collabcase.project.version.dto.*;import com.collabcase.user.User;import jakarta.validation.Valid;
@RestController @RequestMapping("/api/projects/{projectId}/versions")
public class ProjectVersionController{private final ProjectVersionService service;public ProjectVersionController(ProjectVersionService service){this.service=service;}
 @GetMapping public List<ProjectVersionResponse> list(@PathVariable UUID projectId,@AuthenticationPrincipal User user){return service.list(projectId,user.getId());}
 @PostMapping public ResponseEntity<ProjectVersionResponse> create(@PathVariable UUID projectId,@Valid @RequestBody ProjectVersionRequest request,@AuthenticationPrincipal User user){var result=service.create(projectId,request,user);return ResponseEntity.created(URI.create("/api/projects/"+projectId+"/versions/"+result.id())).body(result);}
 @PatchMapping("/{versionId}") public ProjectVersionResponse rename(@PathVariable UUID projectId,@PathVariable UUID versionId,@Valid @RequestBody ProjectVersionRenameRequest request,@AuthenticationPrincipal User user){return service.rename(projectId,versionId,request,user.getId());}
 @DeleteMapping("/{versionId}") @ResponseStatus(HttpStatus.NO_CONTENT) public void delete(@PathVariable UUID projectId,@PathVariable UUID versionId,@AuthenticationPrincipal User user){service.delete(projectId,versionId,user.getId());}
 @GetMapping("/{versionId}/diagrams") public List<DiagramSummaryResponse> diagrams(@PathVariable UUID projectId,@PathVariable UUID versionId,@AuthenticationPrincipal User user){return service.diagrams(projectId,versionId,user.getId());}
}
