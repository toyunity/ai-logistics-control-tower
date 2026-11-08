package com.example.controltower.demo;

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
    private static final String NO_DOCUMENTS_MARKER = "관련 지식 문서 없음";
    private static final String GUIDANCE = "등록된 지식 문서에서 관련 내용을 찾지 못했습니다. 설비 상태는 화면의 상태 패널에서 확인해 주세요.";
    private static final Pattern FIRST_BODY = Pattern.compile("^내용: (.*)$", Pattern.MULTILINE);
    private static final Pattern FOLLOW_UP = Pattern.compile("Follow-up query:\\n(.*)\\n\\nStandalone query:", Pattern.DOTALL);
    private static final Pattern CHUNK = Pattern.compile(".{1,3}", Pattern.DOTALL);
    private static final Duration CHUNK_DELAY = Duration.ofMillis(30);

    DemoChatModel() {
        log.info("Demo mode on: answers are knowledge document text from a stub, not AI. No external AI API is called.");
    }

    // 재작성 전용. 스텁은 문맥을 모르니 현재 질문만 돌려준다. 이력까지 넘기면 직전 답변의 문서 원문이 검색을 지배한다.
    @Override
    public ChatResponse call(Prompt prompt) {
        String text = prompt.getUserMessage().getText();
        var followUp = FOLLOW_UP.matcher(text);
        String query = followUp.find() ? followUp.group(1) : text;
        return new ChatResponse(List.of(new Generation(new AssistantMessage(query))));
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

    // 근거 없음 프롬프트에는 사용자 입력이 그대로 들어가므로, 입력의 <documents>에 속지 않게 이쪽을 먼저 본다.
    private static String answer(String userText) {
        if (userText.startsWith(NO_DOCUMENTS_MARKER) || !userText.contains("<documents>")) {
            return GUIDANCE;
        }
        var body = FIRST_BODY.matcher(userText);
        return body.find() ? body.group(1) : GUIDANCE;
    }
}
