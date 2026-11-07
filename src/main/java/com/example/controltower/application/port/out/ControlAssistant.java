package com.example.controltower.application.port.out;

import com.example.controltower.application.port.in.TurnScope;
import java.util.UUID;
import reactor.core.publisher.Flux;

public interface ControlAssistant {
    Flux<AssistantChunk> stream(UUID conversationId, String message, TurnScope scope);
}
