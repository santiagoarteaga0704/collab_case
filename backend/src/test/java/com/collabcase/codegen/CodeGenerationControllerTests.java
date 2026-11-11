package com.collabcase.codegen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import com.collabcase.user.User;

class CodeGenerationControllerTests {
    @Test
    void sendsTheZipWithItsExactSizeSoTheBrowserCanShowProgress() throws Exception {
        BackendGenerationService service = mock(BackendGenerationService.class);
        var prepared = new BackendGenerationService.Prepared(Map.of("demo/README.md", "hola".getBytes()),
                List.of(new ZipService.StreamedEntry("demo/demo.apk", () -> new ByteArrayInputStream(new byte[50_000]))), "demo.zip");
        when(service.prepare(any(), any())).thenReturn(prepared);
        var response = new MockHttpServletResponse();

        new CodeGenerationController(service, new ZipService()).generate(UUID.randomUUID(), mock(User.class), response);

        assertThat(response.getContentType()).isEqualTo("application/zip");
        assertThat(response.getHeader("Content-Length")).isEqualTo(String.valueOf(response.getContentAsByteArray().length));
        assertThat(response.getContentAsByteArray().length).isGreaterThan(50_000);
        assertThat(response.getHeader("Content-Disposition")).contains("demo.zip");
    }
}
