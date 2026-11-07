package com.example.controltower.application.port.in;

import com.example.controltower.domain.Actor;
import java.util.UUID;
import reactor.core.publisher.Flux;

public interface AssistantChat {
    Flux<ChatEvent> stream(UUID conversationId, String message, Actor actor);
}
