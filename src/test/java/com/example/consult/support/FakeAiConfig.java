package com.example.consult.support;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.test.context.TestConfiguration;

@TestConfiguration(proxyBeanMethods = false)
public class FakeAiConfig {

    @Bean
    @Primary
    FakeChatModel fakeChatModel() {
        return new FakeChatModel();
    }

    @Bean
    @Primary
    FakeEmbeddingModel fakeEmbeddingModel() {
        return new FakeEmbeddingModel();
    }
}
