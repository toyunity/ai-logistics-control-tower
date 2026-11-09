package com.example.controltower.adapter.out.equipment;

import static com.example.controltower.adapter.out.equipment.EquipmentStub.assertPlainGatewayFailure;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.controltower.adapter.out.equipment.EquipmentHttp.Group;
import com.example.controltower.adapter.out.equipment.EquipmentStub.Reply;
import com.example.controltower.domain.CommandOutcomeUnknownException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreaker.State;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.env.MockEnvironment;

/** 회로는 설비군마다 하나다. 열린 회로는 요청을 내보내지 않고, 재시도를 만들지 않는다. */
@ExtendWith(OutputCaptureExtension.class)
class EquipmentCircuitBreakerTest {

    private static final String PATH = "/device/{id}";
    private static final String ID = "SECRET-ID";
    private static final String DEVICE = "/device/" + ID;
    private static final String OTHER = "/other/" + ID;
    private static final Object BODY = Map.of("cmdId", "c-1");
    private static final int WINDOW = 2;

    record Wire(int value) {}

    private final EquipmentStub stub = new EquipmentStub();
    private final CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
    private final TestObservationRegistry observations = TestObservationRegistry.create();
    // 열린 상태는 테스트 동안 풀리지 않을 만큼 길게 둔다. 반열림은 상태를 직접 바꿔 본다.
    private final EquipmentHttp http = new EquipmentHttp(
            new MockEnvironment().withProperty("controltower.equipment.base-url", stub.baseUrl()),
            EquipmentStub.CONNECT_TIMEOUT,
            EquipmentStub.READ_TIMEOUT,
            registry,
            EquipmentHttp.breakerConfig(WINDOW, 50, Duration.ofMinutes(10), 1),
            observations);
    private final AtomicInteger reads = new AtomicInteger();

    @AfterEach
    void stop() {
        stub.close();
    }

    private CircuitBreaker breaker(Group group) {
        return registry.circuitBreaker(group.breaker);
    }

    private Integer read(Group group, String path) {
        return http.read(group, path, Wire.class, Wire::value, ID);
    }

    private Integer read() {
        return read(Group.SORTER, PATH);
    }

    private void send() {
        http.send(Group.SORTER, PATH, BODY, ID);
    }

    private void countedReads(String path, int status, String body) {
        stub.on("GET", path, b -> {
            reads.incrementAndGet();
            return new Reply(status, body);
        });
    }

    private void failReadsUntilOpen() {
        stub.reply("GET", DEVICE, 503, "SECRET-BODY");
        for (int i = 0; i < WINDOW; i++) {
            assertPlainGatewayFailure(this::read);
        }
    }

    @Test
    void groupHasOneBreaker_readFailuresOpenIt_forSendsToo_otherGroupsUntouched() {
        stub.reply("POST", DEVICE, 200, "");
        stub.reply("GET", OTHER, 200, "{\"value\":7}");

        failReadsUntilOpen();

        assertThat(breaker(Group.SORTER).getState()).isEqualTo(State.OPEN);
        assertPlainGatewayFailure(this::send);
        assertThat(stub.sent()).isEmpty();
        assertThat(read(Group.CONVEYORS, "/other/{id}")).isEqualTo(7);
        assertThat(breaker(Group.CONVEYORS).getState()).isEqualTo(State.CLOSED);
    }

    // 결과마다 태그는 설비군, 동작, 결과값 세 개뿐이다. 경로의 설비 ID나 응답 본문은 관측에 들어가지 않는다.
    @Test
    void eachCallIsObservedWithEnumTagsOnly() {
        stub.reply("POST", DEVICE, 503, "SECRET-BODY");
        stub.reply("GET", OTHER, 200, "{\"value\":7}");
        read(Group.CONVEYORS, "/other/{id}");
        assertThatThrownBy(this::send).isInstanceOf(CommandOutcomeUnknownException.class);
        breaker(Group.SORTER).transitionToOpenState();
        assertPlainGatewayFailure(this::send);

        TestObservationRegistryAssert.assertThat(observations)
                .hasNumberOfObservationsWithNameEqualTo(EquipmentHttp.CALL, 3)
                .forAllObservationsWithNameEqualTo(EquipmentHttp.CALL, o -> o
                        .hasOnlyKeys("group", "operation", "outcome")
                        .doesNotHaveError())
                .hasAnObservation(o -> o.hasLowCardinalityKeyValue("group", "conveyors")
                        .hasLowCardinalityKeyValue("operation", "read")
                        .hasLowCardinalityKeyValue("outcome", "OK"))
                .hasAnObservation(o -> o.hasLowCardinalityKeyValue("group", "sorter")
                        .hasLowCardinalityKeyValue("operation", "send")
                        .hasLowCardinalityKeyValue("outcome", "OUTCOME_UNKNOWN"))
                .hasAnObservation(o -> o.hasLowCardinalityKeyValue("operation", "send")
                        .hasLowCardinalityKeyValue("outcome", "CIRCUIT_OPEN"));
    }

    @Test
    void openBreaker_read_failsPlainWithoutARequest() {
        countedReads(DEVICE, 200, "{\"value\":7}");
        breaker(Group.SORTER).transitionToOpenState();

        assertPlainGatewayFailure(this::read);

        assertThat(reads).hasValue(0);
    }

    // 보내지 못한 명령은 FAILED가 된다. 결과 미확인(UNCONFIRMED)으로 떨어지면 안 된다.
    @Test
    void openBreaker_send_isNeverSent_andIsAPlainFailure() {
        stub.reply("POST", DEVICE, 200, "");
        breaker(Group.SORTER).transitionToOpenState();

        assertPlainGatewayFailure(this::send);

        assertThat(stub.sent()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {500, 502, 503, 504})
    void outcomeUnknown_countsAsAFailure_andIsSentOnce(int status) {
        stub.reply("POST", DEVICE, status, "");

        assertThatThrownBy(this::send).isExactlyInstanceOf(CommandOutcomeUnknownException.class);

        assertThat(stub.sent()).hasSize(1);
        assertThat(breaker(Group.SORTER).getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
    }

    // 결과 미확인이 회로를 열어도 같은 명령이 다시 나가는 길은 없다. 다음 전송은 막혀서 아무것도 나가지 않는다.
    @Test
    void outcomeUnknownOpeningTheBreaker_neverCausesASecondSend() {
        stub.hang("POST", DEVICE);
        for (int i = 0; i < WINDOW; i++) {
            assertThatThrownBy(this::send).isExactlyInstanceOf(CommandOutcomeUnknownException.class);
        }
        assertThat(breaker(Group.SORTER).getState()).isEqualTo(State.OPEN);

        assertPlainGatewayFailure(this::send);

        assertThat(stub.sent()).hasSize(WINDOW);
    }

    // 4xx는 기기가 답한 것이다. 거절이 몇 번이든 게이트웨이는 살아 있다.
    @ParameterizedTest
    @ValueSource(ints = {302, 400, 404, 409})
    void deviceRefusals_doNotCount(int status) {
        stub.reply("GET", DEVICE, status, "");
        stub.reply("POST", DEVICE, status, "");

        for (int i = 0; i < WINDOW * 3; i++) {
            assertPlainGatewayFailure(this::read);
            assertPlainGatewayFailure(this::send);
        }

        assertThat(breaker(Group.SORTER).getState()).isEqualTo(State.CLOSED);
        assertThat(breaker(Group.SORTER).getMetrics().getNumberOfFailedCalls()).isZero();
    }

    // 형식이 어긋난 응답은 기기가 답한 것이라 회로를 열어도 빨라지지 않는다. 거절은 기존대로 한다.
    @Test
    void malformedBodies_doNotCount() {
        stub.reply("GET", DEVICE, 200, "not json");

        for (int i = 0; i < WINDOW * 3; i++) {
            assertPlainGatewayFailure(this::read);
        }

        assertThat(breaker(Group.SORTER).getState()).isEqualTo(State.CLOSED);
    }

    @Test
    void timeoutsAndRefusedConnections_count() {
        stub.hang("GET", DEVICE);
        assertPlainGatewayFailure(this::read);
        stub.goDown();
        assertPlainGatewayFailure(this::read);

        assertThat(breaker(Group.SORTER).getState()).isEqualTo(State.OPEN);
    }

    @Test
    void halfOpen_successfulProbe_closesTheBreaker() {
        countedReads(DEVICE, 200, "{\"value\":7}");
        breaker(Group.SORTER).transitionToOpenState();
        breaker(Group.SORTER).transitionToHalfOpenState();

        assertThat(read()).isEqualTo(7);

        assertThat(reads).hasValue(1);
        assertThat(breaker(Group.SORTER).getState()).isEqualTo(State.CLOSED);
    }

    // 반열림의 시험 호출이 다 쓰인 동안 온 확정은 보내지 않는다.
    @Test
    void halfOpen_withProbesUsedUp_doesNotSend() {
        stub.reply("POST", DEVICE, 200, "");
        CircuitBreaker breaker = breaker(Group.SORTER);
        breaker.transitionToOpenState();
        breaker.transitionToHalfOpenState();
        assertThat(breaker.tryAcquirePermission()).isTrue();

        assertPlainGatewayFailure(this::send);

        assertThat(stub.sent()).isEmpty();
    }

    // 열린 상태 대기 설정이 실제로 쓰이는지 본다. 하한만 단언하고 상한은 교착 감지용이다.
    @Test
    void openBreaker_staysShutForTheConfiguredWait_thenProbes() throws InterruptedException {
        Duration wait = Duration.ofMillis(200);
        EquipmentHttp shortWait = new EquipmentHttp(
                new MockEnvironment().withProperty("controltower.equipment.base-url", stub.baseUrl()),
                EquipmentStub.CONNECT_TIMEOUT,
                EquipmentStub.READ_TIMEOUT,
                CircuitBreakerRegistry.ofDefaults(),
                EquipmentHttp.breakerConfig(WINDOW, 50, wait, 1),
                ObservationRegistry.NOOP);
        stub.reply("GET", DEVICE, 503, "");
        for (int i = 0; i < WINDOW; i++) {
            assertPlainGatewayFailure(() -> shortWait.read(Group.SORTER, PATH, Wire.class, Wire::value, ID));
        }
        long opened = System.nanoTime();
        countedReads(DEVICE, 200, "{\"value\":7}");

        Integer value = null;
        while (value == null && System.nanoTime() - opened < Duration.ofSeconds(10).toNanos()) {
            try {
                value = shortWait.read(Group.SORTER, PATH, Wire.class, Wire::value, ID);
            } catch (RuntimeException e) {
                assertThat(reads).hasValue(0);
                Thread.sleep(20);
            }
        }

        assertThat(value).isEqualTo(7);
        assertThat(Duration.ofNanos(System.nanoTime() - opened)).isGreaterThanOrEqualTo(wait);
        assertThat(reads).hasValue(1);
    }

    @Test
    void stateChanges_logGroupAndStateOnly(CapturedOutput output) {
        failReadsUntilOpen();

        assertThat(output).contains("sorter").contains("OPEN");
        assertThat(output).doesNotContain(ID).doesNotContain("SECRET-BODY").doesNotContain("503");
    }
}
