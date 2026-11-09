package com.example.controltower.adapter.in.web;

import com.example.controltower.application.port.in.AssistantChat;
import com.example.controltower.application.port.in.ChatEvent;
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
import org.springframework.web.server.WebSession;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

@RestController
public class ChatController {

    private final AssistantChat assistantChat;

    public ChatController(AssistantChat assistantChat) {
        this.assistantChat = assistantChat;
    }

    record ChatRequest(UUID conversationId, @NotBlank @Size(max = 2000) String message) {}

    @PostMapping(path = "/api/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    ResponseEntity<Flux<ServerSentEvent<Object>>> stream(
            @Valid @RequestBody ChatRequest request, WebSession session) {
        UUID id = request.conversationId() != null ? request.conversationId() : UUID.randomUUID();
        // 스트림 안에서 메모리(JDBC), 검색, 도구의 설비 호출(RestClient)이 블로킹으로 돈다. 구독과 요청(request(n))을
        // boundedElastic으로 옮겨 Netty 이벤트 루프에서 시작되지 않게 한다. 이미 다른 스레드에서 오는 신호는 그 스레드에 남는다.
        Flux<ChatEvent> events = Flux.defer(() -> assistantChat.stream(id, request.message(), SessionActor.of(session)))
                .subscribeOn(Schedulers.boundedElastic());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("X-Accel-Buffering", "no")
                .body(events.map(ChatController::toSse));
    }

    private static ServerSentEvent<Object> toSse(ChatEvent event) {
        String name = switch (event) {
            case ChatEvent.Meta m -> "meta";
            case ChatEvent.Sources s -> "sources";
            case ChatEvent.Delta d -> "delta";
            case ChatEvent.Action a -> "action";
            case ChatEvent.Done d -> "done";
            case ChatEvent.Failed f -> "error";
        };
        return ServerSentEvent.<Object>builder(event).event(name).build();
    }
}
