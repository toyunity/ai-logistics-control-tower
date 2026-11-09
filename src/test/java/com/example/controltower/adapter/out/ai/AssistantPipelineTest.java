package com.example.controltower.adapter.out.ai;

import static com.example.controltower.support.FakeChatModel.finish;
import static com.example.controltower.support.FakeChatModel.noGeneration;
import static com.example.controltower.support.FakeChatModel.nullText;
import static com.example.controltower.support.FakeChatModel.text;
import static com.example.controltower.support.FakeChatModel.toolCall;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.example.controltower.adapter.in.tool.PlantTools;
import com.example.controltower.application.port.in.AssistantChat;
import com.example.controltower.application.port.in.ChatEvent.Action;
import com.example.controltower.application.port.in.ChatEvent;
import com.example.controltower.application.port.in.ChatEvent.Delta;
import com.example.controltower.application.port.in.ChatEvent.Done;
import com.example.controltower.application.port.in.ChatEvent.Failed;
import com.example.controltower.application.port.in.ChatEvent.Meta;
import com.example.controltower.application.port.in.ChatEvent.Source;
import com.example.controltower.application.port.in.ChatEvent.Sources;
import com.example.controltower.application.port.in.PlantStatus;
import com.example.controltower.application.port.in.ProposalResult;
import com.example.controltower.application.port.out.ControlAssistant;
import com.example.controltower.application.service.AssistantChatService;
import com.example.controltower.config.ControlTowerProperties;
import com.example.controltower.domain.Actor;
import com.example.controltower.domain.CommandSpec;
import com.example.controltower.domain.CommandStatus;
import com.example.controltower.domain.PendingCommand;
import com.example.controltower.domain.Role;
import com.example.controltower.support.FakeChatModel;
import com.example.controltower.support.FakeEmbeddingModel;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import reactor.core.publisher.Flux;

class AssistantPipelineTest {

    private static final Document ALARM_205 = Document.builder()
            .id("cv-alarm-205")
            .text("제목: 알람 205 기계 고장\n내용: 알람 205는 정비 요청")
            .metadata(Map.of("title", "알람 205 기계 고장"))
            .build();

    private static final Actor OPERATOR = new Actor("operator", Role.OPERATOR);
    private static final Instant NOW = Instant.parse("2026-11-08T00:00:00Z");

    private final UUID id = UUID.randomUUID();
    private final Map<CommandSpec, PendingCommand> cards = new ConcurrentHashMap<>();
    private final FakeChatModel model = new FakeChatModel();
    private final InMemoryChatMemoryRepository repository = new InMemoryChatMemoryRepository();
    private final AssistantChat service = service(knowledgeStore());

    private VectorStore knowledgeStore() {
        SimpleVectorStore store = SimpleVectorStore.builder(new FakeEmbeddingModel()).build();
        store.add(List.of(ALARM_205));
        return store;
    }

    private AssistantChat service(VectorStore store) {
        return service(store, Duration.ofSeconds(30));
    }

    private AssistantChat service(VectorStore store, Duration idleTimeout) {
        var props = new ControlTowerProperties("chat-model", "rewrite-model", 1000, 3, 0.5, idleTimeout, "test-index");
        ChatClient client = new AssistantConfig().assistantChatClient(
                // 운영의 tool-calling.advisor-order와 같은 값
                ChatClient.builder(model, ObservationRegistry.NOOP, null, null,
                        ToolCallingAdvisor.builder().advisorOrder(20)),
                ChatClient.builder(model),
                MessageWindowChatMemory.builder().chatMemoryRepository(repository).build(),
                store,
                props,
                MethodToolCallbackProvider.builder()
                        .toolObjects(new PlantTools(mock(PlantStatus.class), this::propose))
                        .build());
        return new AssistantChatService(new SpringAiAssistant(client), idleTimeout);
    }

    // 같은 인자는 같은 카드, 다른 인자는 새 카드를 돌려준다. 서비스의 재사용·무효화 규칙을 흉내 낸다.
    private ProposalResult propose(CommandSpec spec, Actor actor) {
        PendingCommand card = cards.computeIfAbsent(spec, s -> new PendingCommand(UUID.randomUUID(), actor.accountId(),
                s, "digest", "영향 " + s.targetId(), CommandStatus.PENDING, NOW, NOW.plusSeconds(120)));
        return new ProposalResult.Proposed(card);
    }

    private List<ChatEvent> run(String message) {
        return service.stream(id, message, Actor.ANONYMOUS).collectList().block();
    }

    private List<ChatEvent> runAsOperator(String message) {
        return service.stream(id, message, OPERATOR).collectList().block();
    }

    private void enqueue(ChatResponse... chunks) {
        model.enqueueStream(Flux.just(chunks));
    }

    private String lastPromptUserText() {
        List<Prompt> prompts = model.prompts();
        return prompts.get(prompts.size() - 1).getUserMessage().getText();
    }

    private List<String> stored(MessageType type) {
        return repository.findByConversationId(id.toString()).stream()
                .filter(m -> m.getMessageType() == type)
                .map(Message::getText)
                .toList();
    }

    private static List<String> sourceIds(List<ChatEvent> events) {
        return events.stream()
                .filter(Sources.class::isInstance)
                .map(Sources.class::cast)
                .flatMap(s -> s.items().stream())
                .map(Source::id)
                .toList();
    }

    @Test
    void emitsEventsInContractOrder() {
        enqueue(text("안녕"), noGeneration(), nullText(), text(""), text(" "), text("하세요"), finish("end_turn"));

        List<ChatEvent> events = run("알람 205 뭐예요");

        assertThat(events).hasSize(6);
        assertThat(events.get(0)).isEqualTo(new Meta(id));
        Source source = ((Sources) events.get(1)).items().get(0);
        assertThat(source.id()).isEqualTo("cv-alarm-205");
        assertThat(source.score()).isGreaterThanOrEqualTo(0.5);
        assertThat(events.subList(2, 6)).containsExactly(
                new Delta("안녕"), new Delta(" "), new Delta("하세요"), new Done());
    }

    @Test
    void storesRawQuestionNotAugmentedPrompt() {
        enqueue(text("정비 "), text("요청하세요"), finish("end_turn"));

        run("알람 205 뭐예요");

        assertThat(stored(MessageType.USER)).containsExactly("알람 205 뭐예요");
        assertThat(stored(MessageType.ASSISTANT)).containsExactly("정비 요청하세요");
        assertThat(lastPromptUserText()).contains("알람 205는 정비 요청").contains("알람 205 뭐예요");
    }

    @Test
    void sourcesExposeTitle() {
        enqueue(text("네"), finish("end_turn"));

        List<ChatEvent> events = run("알람 205 뭐예요");

        assertThat(((Sources) events.get(1)).items()).extracting(Source::title).containsExactly("알람 205 기계 고장");
    }

    @Test
    void systemPromptAllowsToolsWithoutDocuments() {
        enqueue(text("안녕하세요"), finish("end_turn"));

        List<ChatEvent> events = run("안녕하세요");

        assertThat(events.get(1)).isEqualTo(new Sources(List.of()));
        Prompt sent = model.prompts().getLast();
        assertThat(sent.getUserMessage().getText()).contains("안녕하세요", "도구").doesNotContain("FAQ");
        assertThat(sent.getSystemMessage().getText())
                .contains("도구 결과", "지식 문서", "이전 대화", "제안만", "카드에서 확정", "실행했다고 말하지", "후보")
                .doesNotContain("FAQ", "상담원");
        // 권한 없음이나 거절에도 확정을 안내하면 사용자가 없는 카드를 찾는다.
        assertThat(sent.getSystemMessage().getText())
                .contains("status가 OK일 때만", "NOT_PERMITTED", "DENIED", "UNAVAILABLE", "카드가 없으니");
    }

    @Test
    void rewritesOnlyWhenPriorTurnExists() {
        enqueue(text("네"), finish("end_turn"));
        run("알람 205 뭐예요");
        assertThat(model.callCount()).isZero();

        enqueue(text("3일"), finish("end_turn"));
        model.enqueueCall("알람 205 조치");
        List<ChatEvent> second = run("그건 얼마나 걸려요");

        assertThat(model.callCount()).isEqualTo(1);
        // 가짜 임베딩은 "205"가 든 문장만 찾으므로, 걸렸다면 재작성된 질문으로 검색한 것이다.
        assertThat(sourceIds(second)).contains("cv-alarm-205");
        assertThat(stored(MessageType.USER)).containsExactly("알람 205 뭐예요", "그건 얼마나 걸려요");
        List<Prompt> prompts = model.prompts();
        assertThat(prompts.get(1).getOptions().getModel()).isEqualTo("rewrite-model");
        assertThat(prompts.get(1).getOptions().getMaxTokens()).isEqualTo(1000);
        assertThat(prompts.get(2).getOptions().getModel()).isEqualTo("chat-model");
    }

    @Test
    void rewriteFailureFallsBackToOriginalQuery() {
        enqueue(text("네"), finish("end_turn"));
        run("알람 205 뭐예요");

        enqueue(text("3일"), finish("end_turn"));
        model.failNextCall();
        List<ChatEvent> second = run("그건 얼마나 걸려요");

        assertThat(second).last().isEqualTo(new Done());
        assertThat(model.callCount()).isEqualTo(1);
        assertThat(lastPromptUserText()).contains("그건 얼마나 걸려요");
    }

    @Test
    void doesNotStoreEmptyAssistantMessage() {
        enqueue(finish("end_turn"));

        List<ChatEvent> events = run("알람 205 뭐예요");

        assertThat(events).last().isEqualTo(new Done());
        assertThat(stored(MessageType.USER)).containsExactly("알람 205 뭐예요");
        assertThat(stored(MessageType.ASSISTANT)).isEmpty();
    }

    @Test
    void doesNotStoreWhitespaceOnlyAssistantMessage() {
        enqueue(text(" "), text("\n"), finish("end_turn"));

        List<ChatEvent> events = run("알람 205 뭐예요");

        assertThat(events).last().isEqualTo(new Done());
        assertThat(stored(MessageType.ASSISTANT)).isEmpty();
    }

    @Test
    void upstreamFailureEmitsFailedNotDone() {
        model.enqueueStream(Flux.concat(Flux.just(text("안녕")), Flux.error(new RuntimeException("secret"))));

        List<ChatEvent> events = run("알람 205 뭐예요");

        assertThat(events).doesNotContain(new Done()).contains(new Delta("안녕"));
        Failed failed = (Failed) events.get(events.size() - 1);
        assertThat(failed.code()).isEqualTo("UPSTREAM");
        assertThat(failed.message()).doesNotContain("secret");
    }

    @Test
    void stalledStreamEndsWithFailed() {
        model.enqueueStream(Flux.never());

        List<ChatEvent> events = service(knowledgeStore(), Duration.ofMillis(200))
                .stream(id, "알람 205 뭐예요", Actor.ANONYMOUS)
                .collectList()
                .block(Duration.ofSeconds(5));

        assertThat(events).hasSize(2);
        assertThat(events.get(0)).isEqualTo(new Meta(id));
        assertThat(((Failed) events.get(1)).code()).isEqualTo("UPSTREAM");
    }

    @Test
    void slowButSteadyStreamIsNotCut() {
        model.enqueueStream(Flux.range(1, 15).delayElements(Duration.ofMillis(50)).map(i -> text("조각" + i)));

        List<ChatEvent> events = service(knowledgeStore(), Duration.ofMillis(500))
                .stream(id, "알람 205 뭐예요", Actor.ANONYMOUS)
                .collectList()
                .block(Duration.ofSeconds(5));

        assertThat(events).last().isEqualTo(new Done());
        assertThat(events).filteredOn(Delta.class::isInstance).hasSize(15);
    }

    @Test
    void emptyChunksKeepIdleTimerRunning() {
        // 간격은 타임아웃보다 짧고 합계는 타임아웃의 2배다. 빈 청크가 타이머를 초기화하지 않으면 끊긴다.
        ChatResponse[] empties = {noGeneration(), nullText(), text("")};
        model.enqueueStream(Flux.range(0, 20)
                .delayElements(Duration.ofMillis(50))
                .map(i -> empties[i % empties.length])
                .concatWith(Flux.just(text("끝"), finish("end_turn"))));

        List<ChatEvent> events = service(knowledgeStore(), Duration.ofMillis(500))
                .stream(id, "알람 205 뭐예요", Actor.ANONYMOUS)
                .collectList()
                .block(Duration.ofSeconds(5));

        assertThat(events).last().isEqualTo(new Done());
        assertThat(events).filteredOn(Delta.class::isInstance).containsExactly(new Delta("끝"));
    }

    @Test
    void retrievalFailureEmitsFailed() {
        VectorStore broken = new VectorStore() {
            @Override
            public void add(List<Document> documents) {}

            @Override
            public void delete(List<String> idList) {}

            @Override
            public void delete(Filter.Expression filterExpression) {}

            @Override
            public List<Document> similaritySearch(SearchRequest request) {
                throw new RuntimeException("secret");
            }
        };

        List<ChatEvent> events = service(broken).stream(id, "알람 205 뭐예요", Actor.ANONYMOUS).collectList().block();

        assertThat(events).hasSize(2);
        assertThat(events.get(0)).isEqualTo(new Meta(id));
        Failed failed = (Failed) events.get(1);
        assertThat(failed.code()).isEqualTo("UPSTREAM");
        assertThat(failed.message()).doesNotContain("secret");
    }

    @Test
    void refusalEmitsFailed() {
        enqueue(finish("refusal"));

        List<ChatEvent> events = run("알람 205 뭐예요");

        assertThat(events).hasSize(2);
        assertThat(events).doesNotContain(new Done());
        assertThat(((Failed) events.get(events.size() - 1)).code()).isEqualTo("REFUSED");
    }

    @Test
    void bracesInMessagePassThrough() {
        String message = "알람 205 {플랜} <b> {";
        enqueue(text("네"), finish("end_turn"));

        List<ChatEvent> events = run(message);

        assertThat(events).last().isEqualTo(new Done());
        assertThat(lastPromptUserText()).contains(message);
    }

    @Test
    void nextTurnWorksAfterCancelledStream() {
        enqueue(text("첫"), text("답변"), finish("end_turn"));
        service.stream(id, "알람 205 뭐예요", Actor.ANONYMOUS).take(2).collectList().block();

        enqueue(text("네"), finish("end_turn"));
        model.enqueueCall("알람 205 조치");
        List<ChatEvent> second = run("그건 얼마나 걸려요");

        assertThat(second).last().isEqualTo(new Done());
        // 보강된 프롬프트의 문서 본문에도 이 질문이 있어 메시지 전체로 비교한다.
        List<Prompt> prompts = model.prompts();
        assertThat(prompts.get(prompts.size() - 1).getInstructions())
                .filteredOn(m -> m.getMessageType() == MessageType.USER)
                .extracting(Message::getText)
                .contains("알람 205 뭐예요");
    }

    @Test
    void actionEmittedAfterLastDeltaBeforeDone() {
        model.enqueueStream(Flux.just(text("확인합니다 "), toolCall("c1", "proposeRestart", "{\"conveyorId\":\"CV-3\"}")));
        enqueue(text("카드를 확인해 주세요"), finish("end_turn"));

        List<ChatEvent> events = runAsOperator("CV-3 재가동해");

        PendingCommand card = cards.get(new CommandSpec.Restart("CV-3"));
        assertThat(events.subList(events.size() - 4, events.size())).containsExactly(
                new Delta("확인합니다 "),
                new Delta("카드를 확인해 주세요"),
                new Action(card.id(), "RESTART", "CV-3", "CV-3 재가동", "영향 CV-3", card.expiresAt()),
                new Done());
    }

    @Test
    void streamFails_noAction() {
        model.enqueueStream(Flux.just(toolCall("c1", "proposeRestart", "{\"conveyorId\":\"CV-3\"}")));
        model.enqueueStream(Flux.concat(Flux.just(text("카드")), Flux.error(new RuntimeException("secret"))));

        List<ChatEvent> events = runAsOperator("CV-3 재가동해");

        assertThat(cards).hasSize(1);
        assertThat(events).noneMatch(Action.class::isInstance).doesNotContain(new Done());
        assertThat(((Failed) events.getLast()).code()).isEqualTo("UPSTREAM");
    }

    @Test
    void oneCardPerProposal() {
        model.enqueueStream(Flux.just(new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(
                        call("c1", "proposeRestart", "{\"conveyorId\":\"CV-3\"}"),
                        call("c2", "proposeRestart", "{\"conveyorId\":\"CV-3\"}"),
                        call("c3", "proposeDivert", "{\"fromChute\":\"CH-03\",\"toChute\":\"CH-04\"}"),
                        call("c4", "proposeDivert", "{\"fromChute\":\"CH-03\",\"toChute\":\"CH-05\"}")))
                .build())))));
        enqueue(text("끝"), finish("end_turn"));

        List<ChatEvent> events = runAsOperator("정리해 줘");

        // 같은 카드는 한 번만, 같은 대상의 새 카드는 이전 카드를 대신한다.
        assertThat(events).filteredOn(Action.class::isInstance)
                .extracting(e -> ((Action) e).commandId())
                .containsExactly(
                        cards.get(new CommandSpec.Restart("CV-3")).id(),
                        cards.get(new CommandSpec.Divert("CH-03", "CH-05")).id());
    }

    @Test
    void assistantThrowingSynchronously_metaThenFailed() {
        ControlAssistant throwing = (conversationId, message, scope) -> {
            throw new IllegalStateException("secret");
        };

        List<ChatEvent> events = new AssistantChatService(throwing, Duration.ofSeconds(5))
                .stream(id, "hi", Actor.ANONYMOUS)
                .collectList()
                .block();

        assertThat(events).hasSize(2);
        assertThat(events.get(0)).isEqualTo(new Meta(id));
        assertThat(((Failed) events.get(1)).code()).isEqualTo("UPSTREAM");
    }

    private static AssistantMessage.ToolCall call(String id, String name, String args) {
        return new AssistantMessage.ToolCall(id, "function", name, args);
    }
}
