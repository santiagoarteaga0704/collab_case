package com.collabcase.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SpaForwardControllerTests {

    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new SpaForwardController()).build();

    @Test
    void deepLinkForwardsToIndex() throws Exception {
        mockMvc.perform(get("/projects/abc")).andExpect(forwardedUrl("/index.html"));
        mockMvc.perform(get("/projects/abc/versions/v1/diagrams/d1")).andExpect(forwardedUrl("/index.html"));
        mockMvc.perform(get("/login")).andExpect(forwardedUrl("/index.html"));
        mockMvc.perform(get("/register")).andExpect(forwardedUrl("/index.html"));
    }

    @Test
    void apiIsNotForwarded() throws Exception {
        mockMvc.perform(get("/api/projects")).andExpect(status().isNotFound());
        mockMvc.perform(get("/ws/chat/1")).andExpect(status().isNotFound());
        mockMvc.perform(get("/assets/app.js")).andExpect(status().isNotFound());
    }
}
