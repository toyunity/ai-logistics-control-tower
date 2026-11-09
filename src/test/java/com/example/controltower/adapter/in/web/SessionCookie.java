package com.example.controltower.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.web.reactive.server.WebTestClient.RequestHeadersSpec;

/** WebTestClient에는 쿠키 저장소가 없어 SESSION 쿠키를 직접 들고 다닌다. 로그인하면 ID가 바뀌므로 매번 다시 받는다. */
final class SessionCookie {

    static final String NAME = "SESSION";

    private final WebTestClient client;
    private String value;

    SessionCookie(WebTestClient client) {
        this.client = client;
    }

    String value() {
        return value;
    }

    void use(String value) {
        this.value = value;
    }

    @SuppressWarnings("unchecked") // cookie()는 자기 자신을 돌려준다
    <S extends RequestHeadersSpec<?>> S with(S spec) {
        return value == null ? spec : (S) spec.cookie(NAME, value);
    }

    EntityExchangeResult<byte[]> login(String accountId) {
        EntityExchangeResult<byte[]> result = with(client.post().uri("/api/session"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"accountId\":\"" + accountId + "\"}")
                .exchange()
                .expectBody()
                .returnResult();
        ResponseCookie issued = result.getResponseCookies().getFirst(NAME);
        if (issued != null) {
            value = issued.getValue();
        }
        return result;
    }

    void loginOk(String accountId) {
        assertThat(login(accountId).getStatus().value()).isEqualTo(200);
    }
}
