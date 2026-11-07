package com.example.controltower.adapter.out.ai;

import com.example.controltower.application.port.in.ChatEvent.Source;
import com.example.controltower.application.port.in.TurnScope;
import com.example.controltower.application.port.out.AssistantChunk;
import com.example.controltower.application.port.out.ControlAssistant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

@Component
public class SpringAiAssistant implements ControlAssistant {

    private final ChatClient chatClient;

    public SpringAiAssistant(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    // 클라이언트가 끊어도 이 Flux만 취소되고 업스트림 HTTP 호출은 계속된다. 버려진 답변도 max-tokens나 타임아웃까지 생성된다.
    // Spring AI가 취소를 전파하거나 SDK 스트리밍을 직접 호출해 응답을 닫아야 해결된다.
    @Override
    public Flux<AssistantChunk> stream(UUID conversationId, String message, TurnScope scope) {
        return Flux.defer(() -> {
            var retrievedSent = new AtomicBoolean();
            return chatClient.prompt()
                    .user(u -> u.text(message))
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId.toString()))
                    .stream()
                    .chatClientResponse()
                    .concatMapIterable(r -> toChunks(r, retrievedSent));
        });
    }

    @SuppressWarnings("unchecked")
    private static List<AssistantChunk> toChunks(ChatClientResponse r, AtomicBoolean retrievedSent) {
        List<AssistantChunk> chunks = new ArrayList<>();
        if (retrievedSent.compareAndSet(false, true)) {
            var docs = (List<Document>) r.context().get(RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT);
            chunks.add(new AssistantChunk.Retrieved(docs.stream()
                    .map(d -> new Source(d.getId(), (String) d.getMetadata().get("question"), d.getScore()))
                    .toList()));
        }
        if (r.chatResponse() == null || r.chatResponse().getResult() == null) {
            return chunks;
        }
        var result = r.chatResponse().getResult();
        if ("refusal".equalsIgnoreCase(result.getMetadata().getFinishReason())) {
            return List.of(new AssistantChunk.Refused()); // 거절 응답의 근거와 본문은 내보내지 않는다
        }
        String text = result.getOutput().getText();
        if (text != null && !text.isEmpty()) { // hasText로 거르면 공백 청크가 빠져 띄어쓰기가 사라진다
            chunks.add(new AssistantChunk.Text(text));
        }
        return chunks;
    }
}
