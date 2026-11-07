package com.example.controltower.adapter.out.ai;

import static com.example.controltower.support.FakeChatModel.finish;
import static com.example.controltower.support.FakeChatModel.text;
import static com.example.controltower.support.FakeChatModel.toolCall;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.controltower.application.port.in.ChatEvent;
import com.example.controltower.application.port.in.ChatEvent.Delta;
import com.example.controltower.application.port.in.ChatEvent.Done;
import com.example.controltower.application.port.in.ChatEvent.Sources;
import com.example.controltower.application.service.AssistantChatService;
import com.example.controltower.config.ControlTowerProperties;
import com.example.controltower.domain.Actor;
import com.example.controltower.support.FakeAiConfig;
import com.example.controltower.support.FakeChatModel;
import com.example.controltower.support.FakeEmbeddingModel;
import com.example.controltower.support.TestInfraConfig;
import io.micrometer.observation.ObservationRegistry;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

// 저장소가 도구 메시지를 거르는지가 확인 대상이라 인메모리가 아닌 실제 JDBC 메모리를 쓴다.
@SpringBootTest
@Import({TestInfraConfig.class, FakeAiConfig.class})
@Timeout(30)
class ToolLoopTest {

    private static final int TOOL_ADVISOR_ORDER = 20;

    @Autowired FakeChatModel model;
    @Autowired FakeEmbeddingModel embedding;
    @Autowired ChatMemory memory;
    @Autowired VectorStore store;
    @Autowired ControlTowerProperties props;

    private final PingTool ping = new PingTool();
    private UUID id;
    private ChatClient client;

    @BeforeEach
    void setUp() {
        model.reset();
        id = UUID.randomUUID();
        client = new AssistantConfig().assistantChatClient(
                ChatClient.builder(
                                model,
                                ObservationRegistry.NOOP,
                                null,
                                null,
                                ToolCallingAdvisor.builder().advisorOrder(TOOL_ADVISOR_ORDER))
                        .defaultTools(ping)
                        // ToolContext를 받는 도구는 컨텍스트가 비어 있으면 호출이 거부된다.
                        .defaultToolContext(Map.of("who", "default")),
                ChatClient.builder(model),
                memory,
                store,
                props);
    }

    // 실제 스트림처럼 논블로킹 스레드에서 내보내야 도구 실행 스레드 단언이 의미를 가진다.
    private void enqueueToolLoop() {
        model.enqueueStream(Flux.just(text("확인합니다 "), toolCall("call-1", "ping", "{}"))
                .publishOn(Schedulers.parallel()));
        model.enqueueStream(Flux.just(text("끝"), finish("end_turn")).publishOn(Schedulers.parallel()));
    }

    private List<ChatEvent> run(String message) {
        return new AssistantChatService(new SpringAiAssistant(client), props.streamIdleTimeout())
                .stream(id, message, Actor.ANONYMOUS)
                .collectList()
                .block();
    }

    @Test
    void userMessageSavedOnce_finalAnswerOnly() {
        enqueueToolLoop();

        assertThat(run("환불 되나요")).last().isEqualTo(new Done());

        assertThat(ping.calls).hasValue(1);
        List<Message> saved = memory.get(id.toString());
        assertThat(saved).extracting(Message::getMessageType)
                .containsExactly(MessageType.USER, MessageType.ASSISTANT);
        assertThat(saved.get(0).getText()).isEqualTo("환불 되나요");
        // 메모리는 도구 앞뒤 스트림을 합쳐 저장하므로 도구 앞 문장도 답변에 포함된다.
        assertThat(saved.get(1).getText()).isEqualTo("확인합니다 끝");
        assertThat(((AssistantMessage) saved.get(1)).hasToolCalls()).isFalse();
    }

    @Test
    void retrievalRunsOnce() {
        enqueueToolLoop();
        int embeddedBefore = embedding.embeddedTextCount();

        List<ChatEvent> events = run("환불 되나요");

        assertThat(ping.calls).hasValue(1);
        assertThat(embedding.embeddedTextCount() - embeddedBefore).isEqualTo(1);
        assertThat(events).filteredOn(Sources.class::isInstance).hasSize(1);
    }

    @Test
    void toolContextReachesToolOnBlockingCapableThread() {
        enqueueToolLoop();

        // SpringAiAssistant는 아직 요청별 컨텍스트를 넘기지 않으므로 요청 단위 경로를 직접 확인한다.
        client.prompt()
                .user("환불 되나요")
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, id.toString()))
                .toolContext(Map.of("who", "request"))
                .stream()
                .chatClientResponse()
                .blockLast();

        assertThat(ping.calls).hasValue(1);
        assertThat(ping.who).hasValue("request");
        assertThat(ping.thread.get()).startsWith("boundedElastic");
    }

    @Test
    void textBeforeAndAfterToolStayInOrder() {
        enqueueToolLoop();

        List<ChatEvent> events = run("환불 되나요");

        assertThat(events).filteredOn(Delta.class::isInstance)
                .containsExactly(new Delta("확인합니다 "), new Delta("끝"));
    }

    @Test
    void followUpRewriteRunsOnce() {
        model.enqueueStream(Flux.just(text("네"), finish("end_turn")));
        run("환불 되나요");

        enqueueToolLoop();
        model.enqueueCall("환불 처리 기간");
        List<ChatEvent> second = run("그건 얼마나 걸려요");

        assertThat(second).last().isEqualTo(new Done());
        assertThat(ping.calls).hasValue(1);
        assertThat(model.callCount()).isEqualTo(1);
    }

    static class PingTool {

        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<String> who = new AtomicReference<>();
        final AtomicReference<String> thread = new AtomicReference<>();

        @Tool(description = "연결 확인")
        String ping(ToolContext ctx) {
            calls.incrementAndGet();
            who.set((String) ctx.getContext().get("who"));
            thread.set(Thread.currentThread().getName());
            return "pong";
        }
    }
}
