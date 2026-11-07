package com.example.controltower.application.service;

import com.example.controltower.application.port.in.AssistantChat;
import com.example.controltower.application.port.in.ChatEvent;
import com.example.controltower.application.port.in.ChatEvent.Delta;
import com.example.controltower.application.port.in.ChatEvent.Done;
import com.example.controltower.application.port.in.ChatEvent.Failed;
import com.example.controltower.application.port.in.ChatEvent.Meta;
import com.example.controltower.application.port.in.ChatEvent.Sources;
import com.example.controltower.application.port.in.TurnScope;
import com.example.controltower.application.port.out.AssistantChunk;
import com.example.controltower.application.port.out.ControlAssistant;
import com.example.controltower.domain.Actor;
import java.time.Duration;
import java.util.UUID;
import reactor.core.publisher.Flux;

public class AssistantChatService implements AssistantChat {

    private static final int MAX_TOOL_CALLS = 8;

    // application은 JDK와 Reactor만 보므로 slf4j 대신 System.Logger를 쓴다.
    private static final System.Logger log = System.getLogger(AssistantChatService.class.getName());

    private final ControlAssistant assistant;
    private final Duration idleTimeout;

    public AssistantChatService(ControlAssistant assistant, Duration idleTimeout) {
        this.assistant = assistant;
        this.idleTimeout = idleTimeout;
    }

    @Override
    public Flux<ChatEvent> stream(UUID conversationId, String message, Actor actor) {
        return Flux.defer(() -> {
            Flux<ChatEvent> body = assistant.stream(conversationId, message, new TurnScope(actor, MAX_TOOL_CALLS))
                    .timeout(idleTimeout) // 청크 단위라 침묵만 제한하고 답변 길이는 제한하지 않는다
                    // 빈 청크도 타이머를 초기화해야 하므로 timeout 뒤에서 거른다.
                    // hasText로 거르면 공백 청크가 빠져 띄어쓰기가 사라진다.
                    .filter(c -> !(c instanceof AssistantChunk.Text t && t.text().isEmpty()))
                    .map(AssistantChatService::toEvent);
            return Flux.<ChatEvent>just(new Meta(conversationId))
                    .concatWith(body)
                    .concatWith(Flux.just(new Done())) // onErrorResume이 이보다 뒤에 있어야 실패 시 Done이 나가지 않는다
                    .onErrorResume(AssistantChatService::toFailed);
        });
    }

    private static ChatEvent toEvent(AssistantChunk chunk) {
        return switch (chunk) {
            case AssistantChunk.Retrieved r -> new Sources(r.sources());
            case AssistantChunk.Text t -> new Delta(t.text());
            case AssistantChunk.Refused r -> throw new RefusedException();
        };
    }

    private static Flux<ChatEvent> toFailed(Throwable e) {
        if (e instanceof RefusedException) {
            return Flux.just(new Failed("REFUSED", "이 질문에는 답변드리기 어렵습니다. 상담원 연결을 이용해 주세요."));
        }
        log.log(System.Logger.Level.ERROR, "chat stream failed", e);
        return Flux.just(new Failed("UPSTREAM", "일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요."));
    }

    private static final class RefusedException extends RuntimeException {}
}
