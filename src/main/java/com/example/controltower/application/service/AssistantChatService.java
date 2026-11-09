package com.example.controltower.application.service;

import com.example.controltower.application.port.in.AssistantChat;
import com.example.controltower.application.port.in.ChatEvent;
import com.example.controltower.application.port.in.ChatEvent.Action;
import com.example.controltower.application.port.in.ChatEvent.Delta;
import com.example.controltower.application.port.in.ChatEvent.Done;
import com.example.controltower.application.port.in.ChatEvent.Failed;
import com.example.controltower.application.port.in.ChatEvent.Meta;
import com.example.controltower.application.port.in.ChatEvent.Sources;
import com.example.controltower.application.port.in.TurnScope;
import com.example.controltower.application.port.out.AssistantChunk;
import com.example.controltower.application.port.out.ControlAssistant;
import com.example.controltower.domain.Actor;
import com.example.controltower.domain.CommandSpec;
import com.example.controltower.domain.PendingCommand;
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
            TurnScope scope = new TurnScope(actor, MAX_TOOL_CALLS);
            // 어댑터가 동기로 던져도 Meta 뒤 Failed로 끝나게 구독 시점까지 미룬다.
            Flux<ChatEvent> body = Flux.defer(() -> assistant.stream(conversationId, message, scope))
                    .timeout(idleTimeout) // 청크 단위라 침묵만 제한하고 답변 길이는 제한하지 않는다
                    // 빈 청크도 타이머를 초기화해야 하므로 timeout 뒤에서 거른다.
                    // hasText로 거르면 공백 청크가 빠져 띄어쓰기가 사라진다.
                    .filter(c -> !(c instanceof AssistantChunk.Text t && t.text().isEmpty()))
                    .map(AssistantChatService::toEvent);
            return Flux.<ChatEvent>just(new Meta(conversationId))
                    .concatWith(body)
                    // 본문이 끝난 뒤에 읽어야 이번 턴 도구가 만든 카드가 모두 들어 있다.
                    .concatWith(Flux.defer(() -> Flux.fromIterable(scope.proposed()).map(AssistantChatService::toAction)))
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

    private static ChatEvent toAction(PendingCommand card) {
        return new Action(card.id(), card.spec().kind().name(), card.spec().targetId(), summary(card.spec()),
                card.impact(), card.expiresAt());
    }

    // 정비 요약만 모델이 쓴 문장이다. 카드에 표시만 하고 도구 결과로는 돌려주지 않는다.
    private static String summary(CommandSpec spec) {
        return switch (spec) {
            case CommandSpec.Restart s -> s.conveyorId() + " 재가동";
            case CommandSpec.Divert s -> s.fromChute() + " 물량을 " + s.toChute() + "로 우회";
            case CommandSpec.Reassign s -> s.fromRobot() + " 작업을 " + s.toRobot() + "로 재배정";
            case CommandSpec.Maintenance s -> s.summary();
        };
    }

    private static Flux<ChatEvent> toFailed(Throwable e) {
        if (e instanceof RefusedException) {
            return Flux.just(new Failed("REFUSED", "이 질문에는 답변드리기 어렵습니다. 상담원 연결을 이용해 주세요."));
        }
        // 예외 문구에 모델이 만든 도구 이름이나 사용자 입력이 들 수 있어 클래스 이름만 남긴다.
        log.log(System.Logger.Level.ERROR, "chat stream failed: " + e.getClass().getName());
        return Flux.just(new Failed("UPSTREAM", "일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요."));
    }

    private static final class RefusedException extends RuntimeException {}
}
