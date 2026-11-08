package com.example.controltower.adapter.out.ai;

import static com.example.controltower.support.FakeChatModel.finish;
import static com.example.controltower.support.FakeChatModel.text;
import static com.example.controltower.support.FakeChatModel.toolCall;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.controltower.application.port.in.ChatEvent;
import com.example.controltower.application.port.in.ChatEvent.Delta;
import com.example.controltower.application.port.in.ChatEvent.Done;
import com.example.controltower.application.port.in.TurnScope;
import com.example.controltower.application.service.AssistantChatService;
import com.example.controltower.config.ControlTowerProperties;
import com.example.controltower.domain.Actor;
import com.example.controltower.support.FakeAiConfig;
import com.example.controltower.support.FakeChatModel;
import com.example.controltower.support.FakeEmbeddingModel;
import com.example.controltower.support.TestInfraConfig;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

// 운영과 같은 JDBC 메모리에 사용자가 본 문장이 그대로 저장되는지 본다. 도구 호출 청크는 메모리 advisor까지 오지 않는다.
@SpringBootTest
@Import({TestInfraConfig.class, FakeAiConfig.class})
@Timeout(30)
@ExtendWith(OutputCaptureExtension.class)
class ToolLoopTest {

    @Autowired FakeChatModel model;
    @Autowired FakeEmbeddingModel embedding;
    @Autowired ChatMemory memory;
    @Autowired VectorStore store;
    @Autowired ControlTowerProperties props;
    // 자동 구성 빌더를 써야 운영 설정(tool-calling.advisor-order)이 적용된 경로를 확인한다.
    @Autowired ChatClient.Builder main;

    private final PingTool ping = new PingTool();
    private UUID id;
    private ChatClient client;

    @BeforeEach
    void setUp() {
        model.reset();
        id = UUID.randomUUID();
        client = new AssistantConfig().assistantChatClient(
                main,
                ChatClient.builder(model),
                memory,
                store,
                props,
                MethodToolCallbackProvider.builder().toolObjects(ping, new CountTool()).build());
    }

    // 실제 SDK처럼 논블로킹 스레드에서 내보낸다. 도구 스레드 단언은 이것 없이도 통과하므로 이 설정을 보호하지는 않는다.
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
    void userMessageSavedOnce_fullVisibleAnswer() {
        enqueueToolLoop();

        assertThat(run("알람 205 뭐예요")).last().isEqualTo(new Done());

        assertThat(ping.calls).hasValue(1);
        List<Message> saved = memory.get(id.toString());
        assertThat(saved).extracting(Message::getMessageType)
                .containsExactly(MessageType.USER, MessageType.ASSISTANT);
        assertThat(saved.get(0).getText()).isEqualTo("알람 205 뭐예요");
        // 메모리는 도구 앞뒤 스트림을 합쳐 저장하므로 도구 앞 문장도 답변에 포함된다.
        assertThat(saved.get(1).getText()).isEqualTo("확인합니다 끝");
        assertThat(((AssistantMessage) saved.get(1)).hasToolCalls()).isFalse();
        List<Message> second = model.prompts().get(1).getInstructions();
        assertThat(second.getLast()).isInstanceOfSatisfying(ToolResponseMessage.class, m ->
                assertThat(m.getResponses()).singleElement().satisfies(r -> {
                    assertThat(r.id()).isEqualTo("call-1");
                    assertThat(r.responseData()).contains("pong");
                }));
    }

    @Test
    void retrievalRunsOnce() {
        enqueueToolLoop();
        int embeddedBefore = embedding.embeddedTextCount();

        List<ChatEvent> events = run("알람 205 뭐예요");

        assertThat(ping.calls).hasValue(1);
        // Sources는 어댑터가 한 번만 내보내므로 검색 횟수는 임베딩 호출로 센다.
        assertThat(embedding.embeddedTextCount() - embeddedBefore).isEqualTo(1);
        assertThat(events).last().isEqualTo(new Done());
    }

    @Test
    void toolContextReachesToolOnBlockingCapableThread() {
        enqueueToolLoop();
        var scope = new TurnScope(Actor.ANONYMOUS, 8);

        new SpringAiAssistant(client).stream(id, "알람 205 뭐예요", scope).blockLast();

        assertThat(ping.calls).hasValue(1);
        assertThat(ping.scope).hasValue(scope);
        assertThat(ping.thread.get()).startsWith("boundedElastic");
    }

    @Test
    void textBeforeAndAfterToolStayInOrder() {
        enqueueToolLoop();

        List<ChatEvent> events = run("알람 205 뭐예요");

        assertThat(events).filteredOn(Delta.class::isInstance)
                .containsExactly(new Delta("확인합니다 "), new Delta("끝"));
    }

    @Test
    void followUpRewriteRunsOnce() {
        model.enqueueStream(Flux.just(text("네"), finish("end_turn")));
        run("알람 205 뭐예요");

        enqueueToolLoop();
        model.enqueueCall("알람 205 조치");
        List<ChatEvent> second = run("그건 얼마나 걸려요");

        assertThat(second).last().isEqualTo(new Done());
        assertThat(ping.calls).hasValue(1);
        assertThat(model.callCount()).isEqualTo(1);
    }

    // 인자 변환 실패는 도구 메서드에 오기 전에 나므로 운영 조립(자동 구성된 ToolCallingManager)으로 확인한다.
    @Test
    void malformedToolArgument_fixedResultNoEchoNoLog(CapturedOutput output) {
        String marker = "MALFORMED-ARG-MARKER";
        model.enqueueStream(Flux.just(toolCall("call-1", "count", "{\"n\":\"" + marker + "\"}")));
        model.enqueueStream(Flux.just(text("끝"), finish("end_turn")));

        List<ChatEvent> events = run("세어 줘");

        assertThat(events).last().isEqualTo(new Done());
        ToolResponseMessage response = (ToolResponseMessage) model.prompts().get(1).getInstructions().getLast();
        assertThat(response.getResponses()).singleElement().satisfies(r -> {
            assertThat(r.responseData()).contains("INVALID_ARGUMENT").doesNotContain(marker);
        });
        assertThat(output.getOut() + output.getErr()).doesNotContain(marker);
    }

    static class CountTool {

        @Tool(description = "개수 확인")
        String count(Integer n, ToolContext ctx) {
            return "ok";
        }
    }

    static class PingTool {

        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<Object> scope = new AtomicReference<>();
        final AtomicReference<String> thread = new AtomicReference<>();

        @Tool(description = "연결 확인")
        String ping(ToolContext ctx) {
            calls.incrementAndGet();
            scope.set(ctx.getContext().get(TurnScope.KEY));
            thread.set(Thread.currentThread().getName());
            return "pong";
        }
    }
}
