package com.example.controltower.adapter.in.web;

import com.example.controltower.application.port.in.AssistantChat;
import com.example.controltower.application.port.in.ChatEvent;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
public class ChatController {

    private final AssistantChat assistantChat;

    public ChatController(AssistantChat assistantChat) {
        this.assistantChat = assistantChat;
    }

    record ChatRequest(UUID conversationId, @NotBlank @Size(max = 2000) String message) {}

    @PostMapping(path = "/api/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    ResponseEntity<Flux<ServerSentEvent<Object>>> stream(
            @Valid @RequestBody ChatRequest request, HttpServletRequest http) {
        UUID id = request.conversationId() != null ? request.conversationId() : UUID.randomUUID();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("X-Accel-Buffering", "no")
                .body(assistantChat.stream(id, request.message(), SessionActor.of(http)).map(ChatController::toSse));
    }

    private static ServerSentEvent<Object> toSse(ChatEvent event) {
        String name = switch (event) {
            case ChatEvent.Meta m -> "meta";
            case ChatEvent.Sources s -> "sources";
            case ChatEvent.Delta d -> "delta";
            case ChatEvent.Action a -> "todo";
            case ChatEvent.Done d -> "done";
            case ChatEvent.Failed f -> "error";
        };
        return ServerSentEvent.<Object>builder(event).event(name).build();
    }
}
