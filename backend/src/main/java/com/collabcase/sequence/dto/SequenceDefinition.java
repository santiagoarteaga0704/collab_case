package com.collabcase.sequence.dto;
import java.util.List;
public record SequenceDefinition(List<Participant> participants,List<Message> messages){
 public record Participant(String id,String name,ParticipantType type){}
 public record Message(Integer order,String from,String to,String label,MessageKind kind){}
 public enum ParticipantType{ACTOR,CLASS} public enum MessageKind{CALL,RETURN,CREATE}
}
