package com.example.controltower.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.junit.jupiter.api.Test;

class DemoChatModelTest {

    private final DemoChatModel model = new DemoChatModel();

    @Test
    void optionsKeepToolLoopOn() {
        assertThat(model.getOptions()).isInstanceOf(ToolCallingChatOptions.class);
    }

    // 문서 본문의 설비 ID와 키워드로 도구를 부르면 안 된다. 질문만 본다.
    @Test
    void documentTextDoesNotTriggerTools() {
        String augmented = "<documents>\n제목: 재가동\n내용: CV-3 재가동은 원인 해소 뒤에 한다.\n</documents>\n\n안내\n\n"
                + "<question>안녕하세요</question>";

        List<ChatResponse> out = model.stream(new Prompt(new UserMessage(augmented))).collectList().block();

        assertThat(out).noneMatch(r -> r.getResult().getOutput().hasToolCalls());
        assertThat(text(out)).isEqualTo(DemoReply.PREFIX + "CV-3 재가동은 원인 해소 뒤에 한다.");
    }

    @Test
    void questionWithIntentEmitsOnlyToolCall() {
        List<ChatResponse> out = model.stream(new Prompt(new UserMessage("관련 지식 문서 없음.\n\n<question>CV-3 재가동해</question>")))
                .collectList().block();

        assertThat(out).singleElement().satisfies(r -> {
            AssistantMessage m = r.getResult().getOutput();
            assertThat(m.getText()).isEmpty();
            assertThat(m.getToolCalls()).singleElement().satisfies(c -> {
                assertThat(c.name()).isEqualTo("proposeRestart");
                assertThat(c.arguments()).isEqualTo("{\"conveyorId\":\"CV-3\"}");
            });
        });
    }

    @Test
    void toolResultIsRenderedEvenThoughQuestionStillHasIntent() {
        var user = new UserMessage("관련 지식 문서 없음.\n\n<question>CV-3 왜 멈췄어</question>");
        var tool = ToolResponseMessage.builder().responses(List.of(new ToolResponseMessage.ToolResponse(
                "demo-1", "diagnose", "{\"status\":\"OK\",\"data\":{\"candidateId\":\"CH-03\",\"evidence\":[]}}"))).build();

        List<ChatResponse> out = model.stream(new Prompt(List.of(user, tool))).collectList().block();

        assertThat(out).noneMatch(r -> r.getResult().getOutput().hasToolCalls());
        assertThat(text(out)).startsWith(DemoReply.PREFIX).contains("CH-03");
    }

    private static String text(List<ChatResponse> out) {
        StringBuilder sb = new StringBuilder();
        out.forEach(r -> sb.append(r.getResult().getOutput().getText()));
        return sb.toString();
    }
}
