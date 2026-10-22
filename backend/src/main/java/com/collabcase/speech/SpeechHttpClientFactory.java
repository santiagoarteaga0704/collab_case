package com.collabcase.speech;

import java.time.Duration;
import java.net.http.HttpClient;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

final class SpeechHttpClientFactory {
    private SpeechHttpClientFactory() {
    }

    static RestClient create(String baseUrl, long timeoutSeconds) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory();
        requestFactory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
        return RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
    }

    static RestClient createHttp1(String baseUrl, long timeoutSeconds) {
        HttpClient httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
        return RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
    }
}
