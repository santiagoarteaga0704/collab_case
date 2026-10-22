package com.collabcase.agent.dto;import java.util.*;import jakarta.validation.Valid;import jakarta.validation.constraints.*;
public final class AgentDtos{private AgentDtos(){}
 public record Context(String route,UUID projectId,String projectName,UUID versionId,String versionName,UUID diagramId,String diagramName,String role,String selectedElement){}
 public record ChatRequest(@NotBlank @Size(max=2000)String message,@Valid Context context,List<Map<String,String>> history){}
 public record Action(@NotBlank String name,Map<String,Object> arguments,boolean confirmationRequired){}
 public record ChatResponse(String type,String message,List<Action> actions){}
 public record Status(String provider,boolean available){}
}
