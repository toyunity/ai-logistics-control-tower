package com.example.controltower.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.controltower.application.port.in.AssistantChat;
import com.example.controltower.application.port.in.ChatEvent;
import com.example.controltower.application.port.in.ChatEvent.Action;
import com.example.controltower.application.port.in.ChatEvent.Delta;
import com.example.controltower.application.port.in.ChatEvent.Done;
import com.example.controltower.application.port.in.ChatEvent.Failed;
import com.example.controltower.application.port.in.ChatEvent.Meta;
import com.example.controltower.application.port.in.ChatEvent.Source;
import com.example.controltower.application.port.in.ChatEvent.Sources;
import com.example.controltower.domain.Actor;
import com.example.controltower.domain.Role;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;

@WebFluxTest({ChatController.class, SessionController.class})
@ExtendWith(OutputCaptureExtension.class)
class ChatControllerTest {

    private static final UUID ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired WebTestClient client;
    @MockitoBean AssistantChat service;

    @Test
    void writesEventNamesAndPayloads() {
        when(service.stream(eq(ID), eq("hi"), eq(Actor.ANONYMOUS))).thenReturn(Flux.just(
                new Meta(ID),
                new Sources(List.of(new Source("a", "q", 0.9))),
                new Delta(" "),
                new Done(),
                new Failed("UPSTREAM", "m")));

        var result = streamOk("{\"conversationId\":\"" + ID + "\",\"message\":\"hi\"}");

        assertThat(body(result))
                .contains("event:meta\ndata:{\"conversationId\":\"" + ID + "\"}")
                .contains("event:sources\ndata:{\"items\":[{\"id\":\"a\",\"title\":\"q\",\"score\":0.9}]}")
                .contains("event:delta\ndata:{\"text\":\" \"}")
                .contains("event:done\ndata:{}")
                .contains("event:error\ndata:{\"code\":\"UPSTREAM\",\"message\":\"m\"}");
    }

    @Test
    void writesActionEvent() {
        UUID commandId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        when(service.stream(any(), any(), any())).thenReturn(Flux.just(
                new Action(commandId, "RESTART", "CV-3", "CV-3 재가동", "CV-3 구간 재가동", Instant.parse("2026-11-08T00:02:00Z")),
                new Done()));

        var result = streamOk("{\"message\":\"hi\"}");

        assertThat(body(result))
                .contains("event:action\ndata:{\"commandId\":\"" + commandId + "\",\"kind\":\"RESTART\",\"targetId\":\"CV-3\","
                        + "\"summary\":\"CV-3 재가동\",\"impact\":\"CV-3 구간 재가동\",\"expiresAt\":\"2026-11-08T00:02:00Z\"}");
    }

    @Test
    void setsNoBufferingHeaders() {
        when(service.stream(any(), any(), any())).thenReturn(Flux.just(new Done()));

        var headers = streamOk("{\"message\":\"hi\"}").getResponseHeaders();

        assertThat(headers.getFirst("X-Accel-Buffering")).isEqualTo("no");
        assertThat(headers.getCacheControl()).isEqualTo("no-store");
    }

    @Test
    void usesGivenConversationId() {
        when(service.stream(any(), any(), any())).thenReturn(Flux.just(new Done()));

        streamOk("{\"conversationId\":\"" + ID + "\",\"message\":\"hi\"}");

        verify(service).stream(ID, "hi", Actor.ANONYMOUS);
    }

    @Test
    void generatesConversationIdWhenMissing() {
        when(service.stream(any(), any(), any())).thenReturn(Flux.just(new Done()));

        streamOk("{\"message\":\"hi\"}");

        verify(service).stream(any(UUID.class), eq("hi"), eq(Actor.ANONYMOUS)); // any(UUID.class)는 null에 매치되지 않는다
    }

    @Test
    void chatPassesSessionActor() {
        when(service.stream(any(), any(), any())).thenReturn(Flux.just(new Done()));
        SessionCookie session = new SessionCookie(client);
        session.loginOk("operator");

        session.with(client.post().uri("/api/chat/stream"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"message\":\"hi\"}")
                .exchange()
                .expectStatus().isOk()
                .expectBody().returnResult();

        verify(service).stream(any(UUID.class), eq("hi"), eq(new Actor("operator", Role.OPERATOR)));
    }

    @Test
    void chatAnonymousStillWorks() {
        when(service.stream(any(), any(), any())).thenReturn(Flux.just(new Done()));

        var result = streamOk("{\"message\":\"hi\"}");

        verify(service).stream(any(UUID.class), eq("hi"), eq(Actor.ANONYMOUS));
        assertThat(body(result)).contains("event:done");
        assertThat(result.getResponseHeaders().get(HttpHeaders.SET_COOKIE)).isNull(); // 비로그인 대화가 세션을 만들지 않는다
    }

    // 스트림 안에서 메모리 저장(JDBC)과 도구의 설비 호출이 돈다. 구독이 이벤트 루프에서 일어나면 그것들도 거기서 돈다.
    @Test
    void streamIsSubscribedOffTheRequestThread() {
        AtomicReference<String> thread = new AtomicReference<>();
        when(service.stream(any(), any(), any())).thenAnswer(inv -> {
            thread.set(Thread.currentThread().getName());
            return Flux.just(new Done());
        });

        streamOk("{\"message\":\"hi\"}");

        assertThat(thread.get()).startsWith("boundedElastic-");
    }

    @Test
    void exactly2000CharsIsAccepted() {
        when(service.stream(any(), any(), any())).thenReturn(Flux.just(new Done()));
        String message = "가".repeat(2000);

        streamOk("{\"message\":\"" + message + "\"}");

        verify(service).stream(any(UUID.class), eq(message), eq(Actor.ANONYMOUS));
    }

    @Test
    void blankMessageReturns400() {
        assertRejected("{\"message\":\"   \"}");
    }

    @Test
    void tooLongMessageReturns400() {
        assertRejected("{\"message\":\"" + "a".repeat(2001) + "\"}");
    }

    @Test
    void invalidUuidReturns400() {
        assertRejected("{\"conversationId\":\"not-a-uuid\",\"message\":\"hi\"}");
    }

    @Test
    void malformedJsonReturns400() {
        assertRejected("{\"message\":");
    }

    @Test
    void rejectedInputIsNotLogged(CapturedOutput output) {
        String marker = "REJECTED-INPUT-MARKER";

        assertRejected("{\"message\":\"" + marker + "a".repeat(2001) + "\"}");
        assertRejected("{\"conversationId\":\"" + marker + "\",\"message\":\"hi\"}");
        assertRejected("{\"message\":\"" + marker + "\"");

        assertThat(output.getOut() + output.getErr()).doesNotContain(marker);
    }

    // String으로 받으면 SSE 디코더가 event: 줄을 걷어 내므로 바이트로 받는다.
    private EntityExchangeResult<byte[]> streamOk(String body) {
        return send(body).expectStatus().isOk().expectBody().returnResult();
    }

    private static String body(EntityExchangeResult<byte[]> result) {
        return new String(result.getResponseBody(), StandardCharsets.UTF_8);
    }

    private void assertRejected(String body) {
        send(body).expectStatus().isBadRequest();
        verifyNoInteractions(service);
    }

    private WebTestClient.ResponseSpec send(String body) {
        return client.post().uri("/api/chat/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body.getBytes(StandardCharsets.UTF_8))
                .exchange();
    }
}
