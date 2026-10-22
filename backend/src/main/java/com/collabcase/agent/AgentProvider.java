package com.collabcase.agent;
public interface AgentProvider{String generate(String system,String user,String schema);String name();boolean available();}
