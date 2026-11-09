package com.example.controltower.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "controltower.mcp.enabled", havingValue = "true")
public class McpConfig {}
