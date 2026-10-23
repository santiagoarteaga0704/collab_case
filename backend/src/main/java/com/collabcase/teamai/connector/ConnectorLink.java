package com.collabcase.teamai.connector;

import java.util.Map;

/** Canal hacia el conector local de la IA (normalmente una sesión WebSocket). */
public interface ConnectorLink {
    void send(Map<String, Object> payload);
}
