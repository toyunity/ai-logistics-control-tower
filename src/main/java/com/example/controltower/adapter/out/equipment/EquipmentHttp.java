package com.example.controltower.adapter.out.equipment;

import com.example.controltower.domain.CommandOutcomeUnknownException;
import com.example.controltower.domain.GatewayUnavailableException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * 어댑터 공통 HTTP. 설비군마다 회로 하나를 두고, 재시도하지 않는다. 명령을 두 번 보낼지는 호출자가 결과를 보고 정한다.
 * 스프링 예외의 메시지에는 응답 본문과 URL이 들어가므로 원인으로 잇지 않는다.
 */
@Component
class EquipmentHttp {

    private static final Logger log = LoggerFactory.getLogger(EquipmentHttp.class);

    /** 회로 단위. 게이트웨이가 죽으면 읽기와 명령 전송이 함께 죽으므로 둘이 한 회로를 쓴다. */
    enum Group {
        CONVEYORS("conveyors"),
        SORTER("sorter"),
        ROBOTS("robots"),
        SAFETY_PLC("safety-plc");

        final String breaker;

        Group(String breaker) {
            this.breaker = breaker;
        }
    }

    // 빠진 값이나 어긋난 타입이 0으로 읽히면 안전 판단이 정상으로 기운다. 제조사가 필드를 더하는 것은 허용한다.
    // 한계: 실수 필드는 문자열 "NaN"과 "Infinity"를 받으므로 범위는 어댑터가 따로 검사한다.
    private static final JsonMapper STRICT = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .build();

    // 관측 태그는 설비군, read/send, 아래 결과값뿐이다. 경로, 설비 ID, 예외는 넣지 않는다(예외 문구에 URL과 응답 본문이 든다).
    static final String CALL = "controltower.equipment.call";
    private static final String OK = "OK";
    private static final String UNAVAILABLE = "UNAVAILABLE";
    private static final String OUTCOME_UNKNOWN = "OUTCOME_UNKNOWN";
    private static final String CIRCUIT_OPEN = "CIRCUIT_OPEN";

    private final Environment env;
    private final ObservationRegistry observations;
    private final RestClient client;
    private final Map<Group, CircuitBreaker> breakers = new EnumMap<>(Group.class);

    @Autowired
    EquipmentHttp(
            Environment env,
            @Value("${controltower.equipment.connect-timeout:2s}") Duration connectTimeout,
            @Value("${controltower.equipment.read-timeout:3s}") Duration readTimeout,
            CircuitBreakerRegistry registry,
            @Value("${controltower.equipment.circuit-breaker.sliding-window-size}") int window,
            @Value("${controltower.equipment.circuit-breaker.failure-rate-threshold}") float failureRate,
            @Value("${controltower.equipment.circuit-breaker.wait-in-open-state}") Duration openWait,
            @Value("${controltower.equipment.circuit-breaker.half-open-calls}") int halfOpenCalls,
            ObjectProvider<ObservationRegistry> observations) {
        // 웹 슬라이스 테스트에는 레지스트리가 없다.
        this(env, connectTimeout, readTimeout, registry, breakerConfig(window, failureRate, openWait, halfOpenCalls),
                observations.getIfAvailable(() -> ObservationRegistry.NOOP));
    }

    EquipmentHttp(
            Environment env,
            Duration connectTimeout,
            Duration readTimeout,
            CircuitBreakerRegistry registry,
            CircuitBreakerConfig breakerConfig,
            ObservationRegistry observations) {
        this.env = env;
        this.observations = observations;
        for (Group group : Group.values()) {
            // 같은 이름의 회로가 레지스트리에 이미 있으면 설정을 무시하고 기존 회로를 돌려준다. 이 빈은 하나만 만든다는 전제다.
            CircuitBreaker breaker = registry.circuitBreaker(group.breaker, breakerConfig);
            // 이벤트 문자열에는 예외 문구가 들어갈 수 있어 이름과 상태만 남긴다.
            breaker.getEventPublisher().onStateTransition(e -> log.warn(
                    "equipment circuit {} -> {}", e.getCircuitBreakerName(), e.getStateTransition().getToState()));
            breakers.put(group, breaker);
        }
        SimpleClientHttpRequestFactory timeouts = new SimpleClientHttpRequestFactory();
        timeouts.setConnectTimeout(connectTimeout);
        timeouts.setReadTimeout(readTimeout);
        this.client = RestClient.builder().requestFactory(timeouts).build();
    }

    /** 번역까지 여기서 감싸므로 도메인 레코드의 검증 실패도 게이트웨이 장애가 된다. 값은 URI 템플릿으로 넣어 경로를 벗어나지 못한다. */
    <T, R> R read(Group group, String path, Class<T> wire, Function<T, R> translate, Object... uriVariables) {
        Observation call = start(group, "read");
        String outcome = UNAVAILABLE;
        try {
            String url = url(path, uriVariables);
            // 회로는 HTTP 교환만 감싼다. 번역 실패는 기기가 답한 것이라 세지 않는다.
            String json = breakers.get(group)
                    .executeSupplier(() -> client.get().uri(url, uriVariables).retrieve().body(String.class));
            R result = translate.apply(STRICT.readValue(json, wire));
            outcome = OK;
            return result;
        } catch (CallNotPermittedException e) {
            outcome = CIRCUIT_OPEN;
            throw unavailable();
        } catch (RuntimeException e) {
            throw unavailable();
        } finally {
            call.lowCardinalityKeyValue("outcome", outcome).stop();
        }
    }

    void send(Group group, String path, Object body, Object... uriVariables) {
        Observation call = start(group, "send");
        String outcome = UNAVAILABLE;
        try {
            String url = url(path, uriVariables);
            boolean succeeded;
            try {
                succeeded = breakers.get(group).executeSupplier(() -> client.post()
                        .uri(url, uriVariables)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .toBodilessEntity()
                        .getStatusCode()
                        .is2xxSuccessful());
            } catch (CallNotPermittedException e) {
                // 열린 회로에서는 요청이 나가지 않았으므로 결과 미확인이 아니라 보내지 못한 것이다.
                outcome = CIRCUIT_OPEN;
                throw unavailable();
            } catch (ResourceAccessException e) {
                if (neverReached(e.getCause())) {
                    throw unavailable();
                }
                outcome = OUTCOME_UNKNOWN;
                throw outcomeUnknown();
            } catch (HttpServerErrorException e) {
                // 502, 504처럼 게이트웨이나 프록시가 낸 응답일 수 있어 기기가 받았는지 모른다. 4xx는 기기의 거절로 본다.
                outcome = OUTCOME_UNKNOWN;
                throw outcomeUnknown();
            } catch (RuntimeException e) {
                throw unavailable();
            }
            // 3xx는 RestClient가 오류로 치지 않는다.
            if (!succeeded) {
                throw unavailable();
            }
            outcome = OK;
        } finally {
            call.lowCardinalityKeyValue("outcome", outcome).stop();
        }
    }

    private Observation start(Group group, String operation) {
        return Observation.createNotStarted(CALL, observations)
                .lowCardinalityKeyValue("group", group.breaker)
                .lowCardinalityKeyValue("operation", operation)
                .start();
    }

    /**
     * 응답이 없거나 5xx일 때만 실패로 센다. 4xx, 3xx, 형식이 어긋난 본문은 게이트웨이가 답한 것이라 세지 않는다.
     * 최소 호출 수를 윈도 크기와 같게 둬야 기동 직후 몇 건의 실패로 열리지 않는다.
     */
    static CircuitBreakerConfig breakerConfig(int window, float failureRate, Duration openWait, int halfOpenCalls) {
        return CircuitBreakerConfig.custom()
                .slidingWindow(window, window, SlidingWindowType.COUNT_BASED)
                .failureRateThreshold(failureRate)
                .waitDurationInOpenState(openWait)
                .permittedNumberOfCallsInHalfOpenState(halfOpenCalls)
                .recordException(e -> e instanceof ResourceAccessException || e instanceof HttpServerErrorException)
                .build();
    }

    /**
     * 요청이 나가지 않았다고 확신할 때만 true다. 끊김 등 나머지는 기기가 받았을 수 있으므로 결과를 모른다고 본다.
     * 연결과 읽기 타임아웃이 같은 타입이라 JDK 문구로 가른다. 문구가 바뀌면 안전한 쪽(모름)으로 떨어진다.
     */
    static boolean neverReached(Throwable cause) {
        return cause instanceof ConnectException
                || cause instanceof NoRouteToHostException
                || cause instanceof UnknownHostException
                || (cause instanceof SocketTimeoutException
                        && cause.getMessage() != null
                        && cause.getMessage().regionMatches(true, 0, "connect", 0, 7));
    }

    // 인코딩은 점을 건드리지 않아 서버가 점 세그먼트를 접으면 다른 경로에 닿는다. ID는 모델 인자에서 온다.
    private String url(String path, Object[] uriVariables) {
        for (Object variable : uriVariables) {
            if (".".equals(variable) || "..".equals(variable)) {
                throw unavailable();
            }
        }
        return baseUrl() + path;
    }

    // 임의 포트는 서버가 뜬 뒤에야 정해지므로 호출할 때마다 읽는다.
    private String baseUrl() {
        String configured = env.getProperty("controltower.equipment.base-url");
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        String port = env.getProperty("local.server.port");
        if (port == null) {
            throw unavailable();
        }
        return "http://localhost:" + port;
    }

    private static CommandOutcomeUnknownException outcomeUnknown() {
        return new CommandOutcomeUnknownException("equipment command outcome unknown", null);
    }

    private static GatewayUnavailableException unavailable() {
        return new GatewayUnavailableException("equipment gateway unavailable", null);
    }
}
