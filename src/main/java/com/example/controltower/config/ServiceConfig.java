package com.example.controltower.config;

import com.example.controltower.application.port.in.AssistantChat;
import com.example.controltower.application.port.out.ControlAssistant;
import com.example.controltower.application.service.AssistantChatService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ServiceConfig {

    @Bean
    AssistantChat assistantChat(ControlAssistant assistant, ControlTowerProperties props) {
        return new AssistantChatService(assistant, props.streamIdleTimeout());
    }
}
