package com.collabcase;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class BackendApplicationTests {
	@Value("${local.server.port}") int port;

	@Test
	void contextLoads() {
	}

	@Test
	void patchPreflightIsAllowedForProjectsAndDiagrams() throws Exception {
		assertPatchPreflight("/api/projects/" + UUID.randomUUID());
		assertPatchPreflight("/api/diagrams/" + UUID.randomUUID());
	}

	private void assertPatchPreflight(String path) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
				.method("OPTIONS", HttpRequest.BodyPublishers.noBody())
				.header("Origin", "http://localhost:5173")
				.header("Access-Control-Request-Method", "PATCH")
				.header("Access-Control-Request-Headers", "authorization,content-type")
				.build();
		HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).contains("http://localhost:5173");
		assertThat(response.headers().firstValue("Access-Control-Allow-Methods").orElse(""))
				.contains("PATCH");
	}

}
