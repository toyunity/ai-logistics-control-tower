package com.example.controltower.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.controltower.adapter.out.ai.ChatService;
import com.example.controltower.application.port.in.ChatEvent;
import com.example.controltower.application.port.in.ChatEvent.Delta;
import com.example.controltower.application.port.in.ChatEvent.Done;
import com.example.controltower.application.port.in.ChatEvent.Failed;
import com.example.controltower.application.port.in.ChatEvent.Meta;
import com.example.controltower.application.port.in.ChatEvent.Source;
import com.example.controltower.application.port.in.ChatEvent.Sources;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import reactor.core.publisher.Flux;

@WebMvcTest(ChatController.class)
@ExtendWith(OutputCaptureExtension.class)
class ChatControllerTest {

    private static final UUID ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired MockMvc mvc;
    @MockitoBean ChatService service;

    @Test
    void writesEventNamesAndPayloads() throws Exception {
        when(service.stream(eq(ID), eq("hi"))).thenReturn(Flux.just(
                new Meta(ID),
                new Sources(List.of(new Source("a", "q", 0.9))),
                new Delta(" "),
                new Done(),
                new Failed("UPSTREAM", "m")));

        var result = streamOk("{\"conversationId\":\"" + ID + "\",\"message\":\"hi\"}");

        assertThat(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("event:meta\ndata:{\"conversationId\":\"" + ID + "\"}")
                .contains("event:sources\ndata:{\"items\":[{\"id\":\"a\",\"question\":\"q\",\"score\":0.9}]}")
                .contains("event:delta\ndata:{\"text\":\" \"}")
                .contains("event:done\ndata:{}")
                .contains("event:error\ndata:{\"code\":\"UPSTREAM\",\"message\":\"m\"}");
    }

    @Test
    void setsNoBufferingHeaders() throws Exception {
        when(service.stream(any(), any())).thenReturn(Flux.just(new Done()));

        streamOk("{\"message\":\"hi\"}")
                .andExpect(header().string("X-Accel-Buffering", "no"))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void usesGivenConversationId() throws Exception {
        when(service.stream(any(), any())).thenReturn(Flux.just(new Done()));

        streamOk("{\"conversationId\":\"" + ID + "\",\"message\":\"hi\"}");

        verify(service).stream(ID, "hi");
    }

    @Test
    void generatesConversationIdWhenMissing() throws Exception {
        when(service.stream(any(), any())).thenReturn(Flux.just(new Done()));

        streamOk("{\"message\":\"hi\"}");

        verify(service).stream(any(UUID.class), eq("hi")); // any(UUID.class)는 null에 매치되지 않는다
    }

    @Test
    void exactly2000CharsIsAccepted() throws Exception {
        when(service.stream(any(), any())).thenReturn(Flux.just(new Done()));
        String message = "가".repeat(2000);

        streamOk("{\"message\":\"" + message + "\"}");

        verify(service).stream(any(UUID.class), eq(message));
    }

    @Test
    void blankMessageReturns400() throws Exception {
        assertRejected("{\"message\":\"   \"}");
    }

    @Test
    void tooLongMessageReturns400() throws Exception {
        assertRejected("{\"message\":\"" + "a".repeat(2001) + "\"}");
    }

    @Test
    void invalidUuidReturns400() throws Exception {
        assertRejected("{\"conversationId\":\"not-a-uuid\",\"message\":\"hi\"}");
    }

    @Test
    void malformedJsonReturns400() throws Exception {
        assertRejected("{\"message\":");
    }

    @Test
    void rejectedInputIsNotLogged(CapturedOutput output) throws Exception {
        String marker = "REJECTED-INPUT-MARKER";

        assertRejected("{\"message\":\"" + marker + "a".repeat(2001) + "\"}");
        assertRejected("{\"conversationId\":\"" + marker + "\",\"message\":\"hi\"}");
        assertRejected("{\"message\":\"" + marker + "\"");

        assertThat(output.getOut() + output.getErr()).doesNotContain(marker);
    }

    private ResultActions streamOk(String body) throws Exception {
        var started = send(body).andExpect(request().asyncStarted()).andReturn();
        return mvc.perform(asyncDispatch(started)).andExpect(status().isOk());
    }

    private void assertRejected(String body) throws Exception {
        send(body).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    private ResultActions send(String body) throws Exception {
        return mvc.perform(post("/api/chat/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .characterEncoding(StandardCharsets.UTF_8)
                .content(body));
    }
}
