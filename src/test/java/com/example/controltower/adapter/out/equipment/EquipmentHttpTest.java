package com.example.controltower.adapter.out.equipment;

import static com.example.controltower.adapter.out.equipment.EquipmentStub.assertPlainGatewayFailure;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeout;

import com.example.controltower.domain.CommandOutcomeUnknownException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.ServerSocket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

class EquipmentHttpTest {

    private static final String PATH = "/device/{id}";
    private static final String DEVICE = "/device/1";
    private static final Object BODY = Map.of("cmdId", "c-1");
    private static final EquipmentHttp.Group GROUP = EquipmentHttp.Group.CONVEYORS;

    record Wire(int value) {}

    private final EquipmentStub stub = new EquipmentStub();
    private final EquipmentHttp http = stub.http();

    @AfterEach
    void stop() {
        stub.close();
    }

    private Integer read() {
        return http.read(GROUP, PATH, Wire.class, Wire::value, "1");
    }

    private void send() {
        http.send(GROUP, PATH, BODY, "1");
    }

    private static EquipmentHttp httpAt(String baseUrl) {
        return httpWith(new MockEnvironment().withProperty("controltower.equipment.base-url", baseUrl));
    }

    private static EquipmentHttp httpWith(MockEnvironment env) {
        return new EquipmentHttp(
                env,
                EquipmentStub.CONNECT_TIMEOUT,
                EquipmentStub.READ_TIMEOUT,
                CircuitBreakerRegistry.ofDefaults(),
                EquipmentHttp.breakerConfig(100, 100, Duration.ofMinutes(1), 1));
    }

    private static String refusingUrl() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return "http://127.0.0.1:" + socket.getLocalPort();
        }
    }

    @Test
    void read_translatesAWellFormedBody_ignoringAddedVendorFields() {
        stub.reply("GET", DEVICE, 200, "{\"value\":7,\"addedLater\":\"x\"}");

        assertThat(read()).isEqualTo(7);
    }

    // 잘못 읽은 값이 0으로 바뀌면 안전 판단이 정상으로 기운다.
    @ParameterizedTest
    @ValueSource(strings = {
        "{}", "{\"value\":null}", "{\"value\":\"7\"}", "{\"value\":7.5}", "{\"value\":true}", "{\"value\":7} trailing",
        "null", "", "not json"
    })
    void read_refusesAnythingLoose(String body) {
        stub.reply("GET", DEVICE, 200, body);

        assertPlainGatewayFailure(this::read);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 404, 409, 500, 503})
    void read_errorStatus_isAGatewayFailure(int status) {
        stub.reply("GET", DEVICE, status, "{\"value\":7}");

        assertPlainGatewayFailure(this::read);
    }

    @Test
    void read_timeoutAndRefusedConnection_areGatewayFailures() throws IOException {
        stub.hang("GET", DEVICE);
        assertPlainGatewayFailure(this::read);

        assertPlainGatewayFailure(() -> httpAt(refusingUrl()).read(GROUP, PATH, Wire.class, Wire::value, "1"));
    }

    @Test
    void read_translatorFailure_isAGatewayFailure() {
        stub.reply("GET", DEVICE, 200, "{\"value\":7}");

        for (RuntimeException e : new RuntimeException[] {
            new NullPointerException(), new IllegalArgumentException(), new IllegalStateException()
        }) {
            Function<Wire, Integer> failing = wire -> {
                throw e;
            };
            assertPlainGatewayFailure(() -> http.read(GROUP, PATH, Wire.class, failing, "1"));
        }
    }

    @Test
    void read_failureCarriesNoResponseBody() {
        stub.reply("GET", DEVICE, 503, "SECRET");
        assertThatThrownBy(this::read).hasNoCause().message().doesNotContain("SECRET");

        stub.reply("GET", DEVICE, 200, "{\"value\":\"SECRET\"}");
        assertThatThrownBy(this::read).hasNoCause().message().doesNotContain("SECRET");
    }

    @Test
    void send_postsJson_anyTwoHundredIsSuccess_bodyNotRead() {
        stub.reply("POST", DEVICE, 202, "not json");

        send();

        assertThat(stub.sent()).hasSize(1);
        assertThat(EquipmentStub.field(stub.sent().getFirst(), "cmdId")).isEqualTo("c-1");
    }

    // 4xx는 기기가 거절했거나 ID를 모른다고 답한 것이라 결과를 안다. 재시도도 없어야 한다.
    @ParameterizedTest
    @ValueSource(ints = {302, 400, 404, 409})
    void send_refusedByTheDevice_isAPlainFailure_sentOnce(int status) {
        stub.reply("POST", DEVICE, status, "SECRET");

        assertPlainGatewayFailure(this::send);

        assertThat(stub.sent()).hasSize(1);
        assertThatThrownBy(this::send).hasNoCause().message().doesNotContain("SECRET");
    }

    // 5xx는 게이트웨이나 프록시가 낸 것일 수 있다(502, 504). 기기가 받았는지 모르므로 다시 보내지 않게 결과를 모른다고 본다.
    @ParameterizedTest
    @ValueSource(ints = {500, 502, 503, 504})
    void send_serverError_isOutcomeUnknown_sentOnce(int status) {
        stub.reply("POST", DEVICE, status, "SECRET");

        assertThatThrownBy(this::send)
                .isExactlyInstanceOf(CommandOutcomeUnknownException.class)
                .hasNoCause()
                .message().doesNotContain("SECRET");

        assertThat(stub.sent()).hasSize(1);
    }

    @Test
    void send_readTimeout_isOutcomeUnknown_sentOnce() {
        stub.hang("POST", DEVICE);

        assertThatThrownBy(this::send)
                .isExactlyInstanceOf(CommandOutcomeUnknownException.class)
                .hasNoCause();

        assertThat(stub.sent()).hasSize(1);
    }

    // HttpURLConnection은 응답 전에 끊긴 POST를 조용히 다시 보낼 수 있다. 재가동이 두 번 나가면 안 된다.
    @Test
    void send_droppedConnection_isOutcomeUnknown_sentOnce() {
        stub.drop("POST", DEVICE);

        assertTimeout(EquipmentStub.READ_TIMEOUT, () -> assertThatThrownBy(this::send)
                .isExactlyInstanceOf(CommandOutcomeUnknownException.class));

        assertThat(stub.sent()).hasSize(1);
    }

    @Test
    void send_refusedConnection_isAPlainFailure() throws IOException {
        EquipmentHttp refused = httpAt(refusingUrl());

        assertPlainGatewayFailure(() -> refused.send(GROUP, PATH, BODY, "1"));
    }

    @Test
    void sendFailureClassification_onlyFailuresBeforeTheRequestLeftArePlain() {
        assertThat(EquipmentHttp.neverReached(new ConnectException("refused"))).isTrue();
        assertThat(EquipmentHttp.neverReached(new NoRouteToHostException())).isTrue();
        assertThat(EquipmentHttp.neverReached(new UnknownHostException())).isTrue();
        assertThat(EquipmentHttp.neverReached(new SocketTimeoutException("Connect timed out"))).isTrue();
        assertThat(EquipmentHttp.neverReached(new SocketTimeoutException("connect timed out"))).isTrue();

        assertThat(EquipmentHttp.neverReached(new SocketTimeoutException("Read timed out"))).isFalse();
        assertThat(EquipmentHttp.neverReached(new SocketTimeoutException())).isFalse();
        assertThat(EquipmentHttp.neverReached(new SocketException("Connection reset"))).isFalse();
    }

    // 값은 인코딩되지만 점 세그먼트는 그대로 나가 서버가 경로를 접을 수 있다. ID는 모델 인자에서 온다.
    @ParameterizedTest
    @ValueSource(strings = {".", ".."})
    void dotSegmentId_isAGatewayFailure_andNothingLeaves(String id) {
        stub.reply("GET", "/device/" + id, 200, "{\"value\":7}");
        stub.reply("POST", "/device/" + id, 200, "");

        assertPlainGatewayFailure(() -> http.read(GROUP, PATH, Wire.class, Wire::value, id));
        assertPlainGatewayFailure(() -> http.send(GROUP, PATH, BODY, id));

        assertThat(stub.sent()).isEmpty();
    }

    @Test
    void blankBaseUrl_usesTheServerPortKnownAtCallTime_noPortIsAGatewayFailure() {
        stub.reply("GET", DEVICE, 200, "{\"value\":7}");
        MockEnvironment env = new MockEnvironment().withProperty("controltower.equipment.base-url", " ");
        EquipmentHttp loopback = httpWith(env);

        assertPlainGatewayFailure(() -> loopback.read(GROUP, PATH, Wire.class, Wire::value, "1"));
        assertPlainGatewayFailure(() -> loopback.send(GROUP, PATH, BODY, "1"));

        env.setProperty("local.server.port", stub.baseUrl().substring(stub.baseUrl().lastIndexOf(':') + 1));

        assertThat(loopback.read(GROUP, PATH, Wire.class, Wire::value, "1")).isEqualTo(7);
    }
}
