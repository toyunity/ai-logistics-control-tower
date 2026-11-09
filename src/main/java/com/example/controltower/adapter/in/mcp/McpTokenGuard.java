package com.example.controltower.adapter.in.mcp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.HandlerFilterFunction;
import org.springframework.web.reactive.function.server.HandlerFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

/**
 * MCP 라우터 자체에 붙이는 Bearer 토큰 검사다. 경로 문자열을 따로 비교하는 WebFilter는 라우터의 PathPattern 매칭(퍼센트 디코딩,
 * ; 매개변수 제거)과 어긋나 /mcp;a=b, /%6Dcp가 토큰 없이 통과했다. 라우터가 고른 요청만 여기로 오므로 그런 틈이 없다.
 * 토큰과 헤더는 어디에도 기록하지 않는다.
 */
public final class McpTokenGuard implements HandlerFilterFunction<ServerResponse, ServerResponse> {

    private static final String UNAUTHORIZED = "{\"status\":\"UNAUTHORIZED\",\"message\":\"MCP 토큰이 필요합니다.\"}";

    private final byte[] expected;

    public McpTokenGuard(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("controltower.mcp.enabled=true requires MCP_TOKEN");
        }
        this.expected = sha256("Bearer " + token);
    }

    @Override
    public Mono<ServerResponse> filter(ServerRequest request, HandlerFunction<ServerResponse> next) {
        String header = request.headers().firstHeader(HttpHeaders.AUTHORIZATION);
        // 해시끼리 비교해 길이도 시간 차로 드러나지 않게 한다.
        if (header != null && MessageDigest.isEqual(sha256(header), expected)) {
            return next.handle(request);
        }
        return ServerResponse.status(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .bodyValue(UNAUTHORIZED);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
