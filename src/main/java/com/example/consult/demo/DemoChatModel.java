package com.example.consult.demo;

import java.time.Duration;
import java.util.List;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

@Component
@Profile("demo")
class DemoChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(DemoChatModel.class);

    private static final String PREFIX = "[데모 응답 · 실제 AI 아님] ";
    private static final String NO_FAQ_MARKER = "FAQ 근거 없음";
    private static final String GUIDANCE = "등록된 FAQ에서 관련 내용을 찾지 못했습니다. 상담원 연결을 이용해 주세요.";
    private static final Pattern FIRST_ANSWER = Pattern.compile("^A: (.*)$", Pattern.MULTILINE);
    private static final Pattern CHUNK = Pattern.compile(".{1,3}", Pattern.DOTALL);
    private static final Duration CHUNK_DELAY = Duration.ofMillis(30);

    DemoChatModel() {
        log.info("Demo mode on: answers are FAQ text from a stub, not AI. No external AI API is called.");
    }

    // 후속 질문 재작성 전용. 재작성 없이 프롬프트를 그대로 돌려주므로 대화 이력 전체가 검색어가 되고,
    // 대화가 길어지면 앞선 주제에 끌려 검색 결과가 틀릴 수 있다. 실제 모델에서는 생기지 않는 한계다.
    @Override
    public ChatResponse call(Prompt prompt) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(prompt.getUserMessage().getText()))));
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        String answer = PREFIX + answer(prompt.getUserMessage().getText());
        List<String> chunks = CHUNK.matcher(answer).results().map(MatchResult::group).toList();
        var finish = ChatGenerationMetadata.builder().finishReason("end_turn").build();
        return Flux.fromIterable(chunks)
                .delayElements(CHUNK_DELAY)
                .map(chunk -> new ChatResponse(List.of(new Generation(new AssistantMessage(chunk)))))
                .concatWith(Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage(""), finish)))));
    }

    // 근거 없음 프롬프트에는 사용자 입력이 그대로 들어가므로, 입력의 <faq>에 속지 않게 이쪽을 먼저 본다.
    private static String answer(String userText) {
        if (userText.startsWith(NO_FAQ_MARKER) || !userText.contains("<faq>")) {
            return GUIDANCE;
        }
        var answer = FIRST_ANSWER.matcher(userText);
        return answer.find() ? answer.group(1) : GUIDANCE;
    }
}
