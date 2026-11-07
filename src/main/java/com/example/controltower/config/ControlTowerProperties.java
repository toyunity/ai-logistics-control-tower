package com.example.controltower.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("controltower")
public record ControlTowerProperties(
        String chatModel,
        String rewriteModel,
        int maxTokens,
        int topK,
        double similarityThreshold,
        Duration streamIdleTimeout,
        String embeddingIndex) {}
