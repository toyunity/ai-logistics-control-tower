package com.example.consult.chat;

import com.example.consult.chat.ChatEvent.Delta;
import com.example.consult.chat.ChatEvent.Done;
import com.example.consult.chat.ChatEvent.Failed;
import com.example.consult.chat.ChatEvent.Meta;
import com.example.consult.chat.ChatEvent.Source;
import com.example.consult.chat.ChatEvent.Sources;
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

    public ChatService(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    public Flux<ChatEvent> stream(UUID id, String message) {
        return Flux.defer(() -> {
            var sourcesSent = new AtomicBoolean();
            Flux<ChatEvent> body = chatClient.prompt()
                    .user(u -> u.text(message))
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, id.toString()))
                    .stream()
                    .chatClientResponse()
                    .concatMapIterable(r -> toEvents(r, sourcesSent));
            return Flux.<ChatEvent>just(new Meta(id))
                    .concatWith(body)
                    .concatWith(Flux.just(new Done())) // onErrorResume sits after this so failures skip Done
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
        if (text != null && !text.isEmpty()) { // not hasText: whitespace chunks carry the spacing
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
