package com.example.controltower.support;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;

public class FakeChatModel implements ChatModel {

    private final Deque<Flux<ChatResponse>> streams = new ArrayDeque<>();
    private final Deque<Object> calls = new ArrayDeque<>(); // String 또는 RuntimeException
    private final List<Prompt> prompts = new ArrayList<>();
    private int callCount;

    public synchronized void enqueueStream(Flux<ChatResponse> response) {
        streams.add(response);
    }

    public synchronized void enqueueCall(String text) {
        calls.add(text);
    }

    public synchronized void failNextCall() {
        calls.add(new IllegalStateException("fake call failure"));
    }

    public synchronized List<Prompt> prompts() {
        return List.copyOf(prompts);
    }

    public synchronized int callCount() {
        return callCount;
    }

    public synchronized void reset() {
        streams.clear();
        calls.clear();
        prompts.clear();
        callCount = 0;
    }

    // ChatClient는 이 옵션을 기반으로 요청 옵션을 만든다. 도구용 옵션이 아니면 도구와 ToolCallingAdvisor가 모두 무시된다.
    @Override
    public ChatOptions getOptions() {
        return ToolCallingChatOptions.builder().build();
    }

    @Override
    public synchronized ChatResponse call(Prompt prompt) {
        prompts.add(prompt);
        callCount++;
        Object next = calls.poll();
        if (next instanceof RuntimeException e) {
            throw e;
        }
        String text = next != null ? (String) next : lastUserText(prompt);
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    @Override
    public synchronized Flux<ChatResponse> stream(Prompt prompt) {
        prompts.add(prompt);
        Flux<ChatResponse> next = streams.poll();
        return next != null ? next : Flux.error(new IllegalStateException("no stream enqueued"));
    }

    private static String lastUserText(Prompt prompt) {
        String text = "";
        for (var m : prompt.getInstructions()) {
            if (m.getMessageType() == MessageType.USER) {
                text = m.getText();
            }
        }
        return text;
    }

    public static ChatResponse text(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    public static ChatResponse finish(String reason) {
        return new ChatResponse(List.of(new Generation(
                new AssistantMessage(""),
                ChatGenerationMetadata.builder().finishReason(reason).build())));
    }

    public static ChatResponse toolCall(String id, String name, String argsJson) {
        var call = new AssistantMessage.ToolCall(id, "function", name, argsJson);
        return new ChatResponse(List.of(new Generation(
                AssistantMessage.builder().content("").toolCalls(List.of(call)).build())));
    }

    public static ChatResponse noGeneration() {
        return new ChatResponse(List.of());
    }

    // thinking 청크. assistant 메시지의 text가 null이다.
    public static ChatResponse nullText() {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content(null).build())));
    }
}
