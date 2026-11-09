package com.example.controltower.adapter.in.mcp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** MCP 경로에만 Bearer 토큰을 요구한다. 토큰과 헤더는 어디에도 기록하지 않는다. */
public final class McpTokenFilter implements WebFilter {

    private static final byte[] UNAUTHORIZED =
            "{\"status\":\"UNAUTHORIZED\",\"message\":\"MCP 토큰이 필요합니다.\"}".getBytes(StandardCharsets.UTF_8);

    private final String path;
    private final byte[] expected;

    public McpTokenFilter(String path, String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("controltower.mcp.enabled=true requires MCP_TOKEN");
        }
        this.path = path;
        this.expected = sha256("Bearer " + token);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String requested = exchange.getRequest().getPath().pathWithinApplication().value();
        if (!requested.equals(path) && !requested.startsWith(path + "/")) {
            return chain.filter(exchange);
        }
        String header = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        // 해시끼리 비교해 길이도 시간 차로 드러나지 않게 한다.
        if (header != null && MessageDigest.isEqual(sha256(header), expected)) {
            return chain.filter(exchange);
        }
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        return response.writeWith(Mono.just(response.bufferFactory().wrap(UNAUTHORIZED)));
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
