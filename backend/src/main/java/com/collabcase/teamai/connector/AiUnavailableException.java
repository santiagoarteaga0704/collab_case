package com.collabcase.teamai.connector;

public class AiUnavailableException extends RuntimeException {

    public enum Reason { OFFLINE, BUSY }

    private final Reason reason;

    public AiUnavailableException(Reason reason) {
        super(reason == Reason.OFFLINE ? "IA desconectada" : "La IA está ocupada, prueba en un momento");
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
