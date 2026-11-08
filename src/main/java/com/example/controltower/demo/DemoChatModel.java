package com.example.controltower.demo;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

@Component
@Profile("demo")
class DemoChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(DemoChatModel.class);

    private static final String NO_DOCUMENTS_MARKER = "관련 지식 문서 없음";
    private static final String GUIDANCE = "등록된 지식 문서에서 관련 내용을 찾지 못했습니다. 설비 상태는 화면의 상태 패널에서 확인해 주세요.";
    private static final Pattern FIRST_BODY = Pattern.compile("^내용: (.*)$", Pattern.MULTILINE);
    // 증강된 사용자 메시지에서 질문만 꺼낸다. 문서 본문에 설비 ID와 "재가동"이 있어 전체를 보면 묻지 않은 도구를 부른다.
    // 문서에는 이 태그가 없으므로 첫 여는 태그부터 마지막 닫는 태그까지가 사용자 입력이다.
    private static final Pattern QUESTION = Pattern.compile("<question>(.*)</question>", Pattern.DOTALL);
    private static final Pattern FOLLOW_UP = Pattern.compile("Follow-up query:\\n(.*)\\n\\nStandalone query:", Pattern.DOTALL);
    private static final Pattern CHUNK = Pattern.compile(".{1,3}", Pattern.DOTALL);
    private static final Duration CHUNK_DELAY = Duration.ofMillis(30);

    DemoChatModel() {
        log.info("Demo mode on: a scripted stub, not AI, calls tools by keyword and answers with fixed text or knowledge"
                + " document text. No external AI API is called.");
    }

    // ChatClient가 이 옵션으로 요청 옵션을 만든다. 도구용 옵션이 아니면 도구 반복이 오류 없이 꺼진다.
    @Override
    public ChatOptions getOptions() {
        return ToolCallingChatOptions.builder().build();
    }

    // 재작성 전용. 스텁은 문맥을 모르니 현재 질문만 돌려준다. 이력까지 넘기면 직전 답변의 문서 원문이 검색을 지배한다.
    @Override
    public ChatResponse call(Prompt prompt) {
        String text = prompt.getUserMessage().getText();
        var followUp = FOLLOW_UP.matcher(text);
        String query = followUp.find() ? followUp.group(1) : text;
        return new ChatResponse(List.of(new Generation(new AssistantMessage(query))));
    }

    // 도구 결과를 먼저 본다. 두 번째 호출에도 같은 사용자 메시지가 있어 의도부터 보면 도구를 한도까지 다시 부른다.
    // 도구 호출 응답에는 글자를 넣지 않는다. 접두어는 최종 답변에 한 번만 붙는다.
    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        if (prompt.getInstructions().getLast() instanceof ToolResponseMessage tool) {
            var response = tool.getResponses().getLast();
            return chunked(DemoReply.render(response.name(), response.responseData()));
        }
        String userText = prompt.getUserMessage().getText();
        Optional<DemoIntent.ToolRequest> intent = DemoIntent.detect(question(userText));
        if (intent.isPresent()) {
            var call = new AssistantMessage.ToolCall(
                    "demo-" + UUID.randomUUID(), "function", intent.get().name(), intent.get().argsJson());
            return Flux.just(new ChatResponse(List.of(new Generation(
                    AssistantMessage.builder().content("").toolCalls(List.of(call)).build()))));
        }
        return chunked(DemoReply.PREFIX + answer(userText));
    }

    private static String question(String userText) {
        var question = QUESTION.matcher(userText);
        return question.find() ? question.group(1) : "";
    }

    private static Flux<ChatResponse> chunked(String answer) {
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
