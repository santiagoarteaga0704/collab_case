package com.collabcase.config;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Sirve index.html en las rutas de la SPA para que recargar un enlace profundo no dé 404. */
@Controller
public class SpaForwardController {

    @GetMapping({
            "/login",
            "/register",
            "/projects/{projectId}",
            "/projects/{projectId}/versions/{versionId}",
            "/projects/{projectId}/diagrams/{diagramId}",
            "/projects/{projectId}/versions/{versionId}/diagrams/{diagramId}"
    })
    public String forwardToIndex() {
        return "forward:/index.html";
    }
}
