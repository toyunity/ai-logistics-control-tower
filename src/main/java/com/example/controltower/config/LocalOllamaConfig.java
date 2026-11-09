package com.example.controltower.config;

import org.springframework.ai.model.ollama.autoconfigure.OllamaConnectionDetails;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 자동 구성의 WebClient는 Reactor Netty를 써서 Ollama 청크가 서버와 같은 이벤트 루프에서 나온다. 그러면 청크 뒤에 붙는 일이
 * 모두 그 루프에서 돈다. Ollama만 JDK HTTP 클라이언트로 받는다(전환 전 동작). 다른 WebClient는 건드리지 않는다.
 */
@Configuration(proxyBeanMethods = false)
@Profile("local")
class LocalOllamaConfig {

    // 자동 구성(OllamaApiAutoConfiguration)과 같고 WebClient 커넥터만 다르다. 이 빈이 있으면 자동 구성은 물러난다.
    @Bean
    OllamaApi ollamaApi(OllamaConnectionDetails connection, ObjectProvider<RestClient.Builder> restClientBuilder) {
        return OllamaApi.builder()
                .baseUrl(connection.getBaseUrl())
                .restClientBuilder(restClientBuilder.getIfAvailable(RestClient::builder))
                .webClientBuilder(WebClient.builder().clientConnector(new JdkClientHttpConnector()))
                .responseErrorHandler(RetryUtils.DEFAULT_RESPONSE_ERROR_HANDLER)
                .build();
    }
}
