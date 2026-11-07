package com.example.controltower.adapter.out.equipment;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.controltower.domain.GatewayUnavailableException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.json.JsonMapper;

/** 제조사 서버를 흉내 내는 JDK HttpServer. 응답을 붙잡는 동안 다른 요청이 막히지 않게 요청마다 스레드를 쓴다. */
final class EquipmentStub implements AutoCloseable {

    static final Duration CONNECT_TIMEOUT = Duration.ofMillis(500);
    static final Duration READ_TIMEOUT = Duration.ofMillis(300);
    private static final Reply NOT_FOUND = new Reply(404, "");
    private static final Reply UNAVAILABLE = new Reply(503, "");
    private static final Reply HANG = new Reply(0, "");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    record Reply(int status, String body) {
        static Reply ok(String body) {
            return new Reply(200, body);
        }
    }

    private final HttpServer server;
    private final Map<String, Function<String, Reply>> routes = new ConcurrentHashMap<>();
    private final List<String> sent = new CopyOnWriteArrayList<>();
    private final CountDownLatch closing = new CountDownLatch(1);
    private volatile boolean down;
    private volatile boolean losingSendResponses;

    EquipmentStub() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", this::handle);
        server.start();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String method = exchange.getRequestMethod();
            if (method.equals("POST")) {
                sent.add(body);
            }
            Reply reply = down
                    ? UNAVAILABLE
                    : routes.getOrDefault(method + " " + exchange.getRequestURI().getRawPath(), b -> NOT_FOUND)
                            .apply(body);
            if (reply == HANG || (losingSendResponses && method.equals("POST"))) {
                closing.await(10, TimeUnit.SECONDS);
                return;
            }
            byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(reply.status(), bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    void on(String method, String path, Function<String, Reply> route) {
        routes.put(method + " " + path, route);
    }

    void reply(String method, String path, int status, String body) {
        on(method, path, b -> new Reply(status, body));
    }

    void hang(String method, String path) {
        on(method, path, b -> HANG);
    }

    void goDown() {
        down = true;
    }

    /** 요청은 적용하되 응답을 돌려주지 않는다. */
    void loseSendResponses() {
        losingSendResponses = true;
    }

    List<String> sent() {
        return sent;
    }

    List<UUID> sentIds(String field) {
        return sent.stream().map(body -> UUID.fromString(field(body, field))).toList();
    }

    static String field(String body, String field) {
        return JSON.readTree(body).get(field).asString();
    }

    static int intField(String body, String field) {
        return JSON.readTree(body).get(field).asInt();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    EquipmentHttp http() {
        return new EquipmentHttp(
                new MockEnvironment().withProperty("controltower.equipment.base-url", baseUrl()),
                CONNECT_TIMEOUT,
                READ_TIMEOUT);
    }

    /** CommandOutcomeUnknownException도 하위 타입이라 isInstanceOf로는 구분되지 않는다. */
    static void assertPlainGatewayFailure(ThrowingCallable call) {
        assertThatThrownBy(call).isExactlyInstanceOf(GatewayUnavailableException.class);
    }

    @Override
    public void close() {
        closing.countDown();
        server.stop(0);
    }
}
