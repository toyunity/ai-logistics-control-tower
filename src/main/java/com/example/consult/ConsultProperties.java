package com.example.consult;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("consult")
public record ConsultProperties(
        String chatModel, String rewriteModel, int maxTokens, int topK, double similarityThreshold) {}
