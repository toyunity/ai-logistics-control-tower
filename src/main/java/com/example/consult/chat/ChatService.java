package com.example.consult.chat;

import com.example.consult.chat.ChatEvent.Delta;
import com.example.consult.chat.ChatEvent.Done;
import com.example.consult.chat.ChatEvent.Failed;
import com.example.consult.chat.ChatEvent.Meta;
import com.example.consult.chat.ChatEvent.Source;
import com.example.consult.chat.ChatEvent.Sources;
import com.example.consult.ConsultProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final ChatClient chatClient;
    private final ConsultProperties props;

    public ChatService(ChatClient chatClient, ConsultProperties props) {
        this.chatClient = chatClient;
        this.props = props;
    }

    // 클라이언트가 끊어도 이 Flux만 취소되고 업스트림 HTTP 호출은 계속된다. 버려진 답변도 max-tokens나 타임아웃까지 생성된다.
    // Spring AI가 취소를 전파하거나 SDK 스트리밍을 직접 호출해 응답을 닫아야 해결된다.
    public Flux<ChatEvent> stream(UUID id, String message) {
        return Flux.defer(() -> {
            var sourcesSent = new AtomicBoolean();
            Flux<ChatEvent> body = chatClient.prompt()
                    .user(u -> u.text(message))
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, id.toString()))
                    .stream()
                    .chatClientResponse()
                    .timeout(props.streamIdleTimeout()) // 청크 단위라 침묵만 제한하고 답변 길이는 제한하지 않는다
                    .concatMapIterable(r -> toEvents(r, sourcesSent));
            return Flux.<ChatEvent>just(new Meta(id))
                    .concatWith(body)
                    .concatWith(Flux.just(new Done())) // onErrorResume이 이보다 뒤에 있어야 실패 시 Done이 나가지 않는다
                    .onErrorResume(this::toFailed);
        });
    }

    @SuppressWarnings("unchecked")
    private List<ChatEvent> toEvents(ChatClientResponse r, AtomicBoolean sourcesSent) {
        List<ChatEvent> events = new ArrayList<>();
        if (sourcesSent.compareAndSet(false, true)) {
            var docs = (List<Document>) r.context().get(RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT);
            events.add(new Sources(docs.stream()
                    .map(d -> new Source(d.getId(), (String) d.getMetadata().get("question"), d.getScore()))
                    .toList()));
        }
        if (r.chatResponse() == null || r.chatResponse().getResult() == null) {
            return events;
        }
        var result = r.chatResponse().getResult();
        String text = result.getOutput().getText();
        if (text != null && !text.isEmpty()) { // hasText로 거르면 공백 청크가 빠져 띄어쓰기가 사라진다
            events.add(new Delta(text));
        }
        if ("refusal".equalsIgnoreCase(result.getMetadata().getFinishReason())) {
            throw new RefusedException();
        }
        return events;
    }

    private Flux<ChatEvent> toFailed(Throwable e) {
        if (e instanceof RefusedException) {
            return Flux.just(new Failed("REFUSED", "이 질문에는 답변드리기 어렵습니다. 상담원 연결을 이용해 주세요."));
        }
        log.error("chat stream failed", e);
        return Flux.just(new Failed("UPSTREAM", "일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요."));
    }

    private static final class RefusedException extends RuntimeException {}
}
