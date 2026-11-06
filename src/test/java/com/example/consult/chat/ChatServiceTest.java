package com.example.consult.chat;

import static com.example.consult.support.FakeChatModel.finish;
import static com.example.consult.support.FakeChatModel.noGeneration;
import static com.example.consult.support.FakeChatModel.nullText;
import static com.example.consult.support.FakeChatModel.text;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.consult.ConsultProperties;
import com.example.consult.chat.ChatEvent.Delta;
import com.example.consult.chat.ChatEvent.Done;
import com.example.consult.chat.ChatEvent.Failed;
import com.example.consult.chat.ChatEvent.Meta;
import com.example.consult.chat.ChatEvent.Source;
import com.example.consult.chat.ChatEvent.Sources;
import com.example.consult.support.FakeChatModel;
import com.example.consult.support.FakeEmbeddingModel;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import reactor.core.publisher.Flux;

class ChatServiceTest {

    private static final Document REFUND = Document.builder()
            .id("refund-policy")
            .text("Q: 환불 되나요\nA: 환불은 7일 이내")
            .metadata(Map.of("question", "환불 되나요"))
            .build();

    private final UUID id = UUID.randomUUID();
    private final FakeChatModel model = new FakeChatModel();
    private final InMemoryChatMemoryRepository repository = new InMemoryChatMemoryRepository();
    private final ChatService service = service(faqStore());

    private VectorStore faqStore() {
        SimpleVectorStore store = SimpleVectorStore.builder(new FakeEmbeddingModel()).build();
        store.add(List.of(REFUND));
        return store;
    }

    private ChatService service(VectorStore store) {
        return service(store, Duration.ofSeconds(30));
    }

    private ChatService service(VectorStore store, Duration idleTimeout) {
        var props = new ConsultProperties("chat-model", "rewrite-model", 1000, 3, 0.5, idleTimeout, "test-index");
        ChatClient client = new RagConfig().consultChatClient(
                ChatClient.builder(model),
                ChatClient.builder(model),
                MessageWindowChatMemory.builder().chatMemoryRepository(repository).build(),
                store,
                props);
        return new ChatService(client, props);
    }

    private List<ChatEvent> run(String message) {
        return service.stream(id, message).collectList().block();
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

        List<ChatEvent> events = run("환불 되나요");

        assertThat(events).hasSize(6);
        assertThat(events.get(0)).isEqualTo(new Meta(id));
        Source source = ((Sources) events.get(1)).items().get(0);
        assertThat(source.id()).isEqualTo("refund-policy");
        assertThat(source.question()).isEqualTo("환불 되나요");
        assertThat(source.score()).isGreaterThanOrEqualTo(0.5);
        assertThat(events.subList(2, 6)).containsExactly(
                new Delta("안녕"), new Delta(" "), new Delta("하세요"), new Done());
    }

    @Test
    void storesRawQuestionNotAugmentedPrompt() {
        enqueue(text("7일 "), text("이내입니다"), finish("end_turn"));

        run("환불 되나요");

        assertThat(stored(MessageType.USER)).containsExactly("환불 되나요");
        assertThat(stored(MessageType.ASSISTANT)).containsExactly("7일 이내입니다");
        assertThat(lastPromptUserText()).contains("환불은 7일 이내").contains("환불 되나요");
    }

    @Test
    void keepsQuestionWhenNoFaqMatches() {
        enqueue(text("안녕하세요"), finish("end_turn"));

        List<ChatEvent> events = run("안녕하세요");

        assertThat(events.get(1)).isEqualTo(new Sources(List.of()));
        assertThat(lastPromptUserText()).contains("안녕하세요").contains("FAQ 근거 없음");
    }

    @Test
    void rewritesOnlyWhenPriorTurnExists() {
        enqueue(text("네"), finish("end_turn"));
        run("환불 되나요");
        assertThat(model.callCount()).isZero();

        enqueue(text("3일"), finish("end_turn"));
        model.enqueueCall("환불 처리 기간");
        List<ChatEvent> second = run("그건 얼마나 걸려요");

        assertThat(model.callCount()).isEqualTo(1);
        // The fake embedding only matches text containing "환불", so a hit proves the rewritten query was searched.
        assertThat(sourceIds(second)).contains("refund-policy");
        assertThat(stored(MessageType.USER)).containsExactly("환불 되나요", "그건 얼마나 걸려요");
        List<Prompt> prompts = model.prompts(); // turn 1 stream, rewrite call, turn 2 stream
        assertThat(prompts.get(1).getOptions().getModel()).isEqualTo("rewrite-model");
        assertThat(prompts.get(1).getOptions().getMaxTokens()).isEqualTo(1000);
        assertThat(prompts.get(2).getOptions().getModel()).isEqualTo("chat-model");
    }

    @Test
    void rewriteFailureFallsBackToOriginalQuery() {
        enqueue(text("네"), finish("end_turn"));
        run("환불 되나요");

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

        List<ChatEvent> events = run("환불 되나요");

        assertThat(events).last().isEqualTo(new Done());
        assertThat(stored(MessageType.USER)).containsExactly("환불 되나요");
        assertThat(stored(MessageType.ASSISTANT)).isEmpty();
    }

    @Test
    void doesNotStoreWhitespaceOnlyAssistantMessage() {
        enqueue(text(" "), text("\n"), finish("end_turn"));

        List<ChatEvent> events = run("환불 되나요");

        assertThat(events).last().isEqualTo(new Done());
        assertThat(stored(MessageType.ASSISTANT)).isEmpty();
    }

    @Test
    void upstreamFailureEmitsFailedNotDone() {
        model.enqueueStream(Flux.concat(Flux.just(text("안녕")), Flux.error(new RuntimeException("secret"))));

        List<ChatEvent> events = run("환불 되나요");

        assertThat(events).doesNotContain(new Done()).contains(new Delta("안녕"));
        Failed failed = (Failed) events.get(events.size() - 1);
        assertThat(failed.code()).isEqualTo("UPSTREAM");
        assertThat(failed.message()).doesNotContain("secret");
    }

    @Test
    void stalledStreamEndsWithFailed() {
        model.enqueueStream(Flux.never());

        List<ChatEvent> events = service(faqStore(), Duration.ofMillis(200))
                .stream(id, "환불 되나요")
                .collectList()
                .block(Duration.ofSeconds(5));

        assertThat(events).hasSize(2);
        assertThat(events.get(0)).isEqualTo(new Meta(id));
        assertThat(((Failed) events.get(1)).code()).isEqualTo("UPSTREAM");
    }

    @Test
    void slowButSteadyStreamIsNotCut() {
        // Spacing is a tenth of the timeout, and the 750ms total is longer than the timeout.
        model.enqueueStream(Flux.range(1, 15).delayElements(Duration.ofMillis(50)).map(i -> text("조각" + i)));

        List<ChatEvent> events = service(faqStore(), Duration.ofMillis(500))
                .stream(id, "환불 되나요")
                .collectList()
                .block(Duration.ofSeconds(5));

        assertThat(events).last().isEqualTo(new Done());
        assertThat(events).filteredOn(Delta.class::isInstance).hasSize(15);
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

        List<ChatEvent> events = service(broken).stream(id, "환불 되나요").collectList().block();

        assertThat(events).hasSize(2);
        assertThat(events.get(0)).isEqualTo(new Meta(id));
        Failed failed = (Failed) events.get(1);
        assertThat(failed.code()).isEqualTo("UPSTREAM");
        assertThat(failed.message()).doesNotContain("secret");
    }

    @Test
    void refusalEmitsFailed() {
        enqueue(finish("refusal"));

        List<ChatEvent> events = run("환불 되나요");

        assertThat(events).doesNotContain(new Done());
        assertThat(((Failed) events.get(events.size() - 1)).code()).isEqualTo("REFUSED");
    }

    @Test
    void bracesInMessagePassThrough() {
        String message = "환불 {플랜} <b> {";
        enqueue(text("네"), finish("end_turn"));

        List<ChatEvent> events = run(message);

        assertThat(events).last().isEqualTo(new Done());
        assertThat(lastPromptUserText()).contains(message);
    }

    @Test
    void nextTurnWorksAfterCancelledStream() {
        enqueue(text("첫"), text("답변"), finish("end_turn"));
        service.stream(id, "환불 되나요").take(2).collectList().block();

        enqueue(text("네"), finish("end_turn"));
        model.enqueueCall("환불 처리 기간");
        List<ChatEvent> second = run("그건 얼마나 걸려요");

        assertThat(second).last().isEqualTo(new Done());
        // Exact message match: the FAQ text in the augmented prompt also contains this question.
        List<Prompt> prompts = model.prompts();
        assertThat(prompts.get(prompts.size() - 1).getInstructions())
                .filteredOn(m -> m.getMessageType() == MessageType.USER)
                .extracting(Message::getText)
                .contains("환불 되나요");
    }
}
