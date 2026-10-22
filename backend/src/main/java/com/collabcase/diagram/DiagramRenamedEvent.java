package com.collabcase.diagram;
import java.util.UUID;
public record DiagramRenamedEvent(UUID diagramId,String name) {}
