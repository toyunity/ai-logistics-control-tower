package com.example.controltower.adapter.out.equipment;

import com.example.controltower.domain.CommandOutcomeUnknownException;
import com.example.controltower.domain.GatewayUnavailableException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * 어댑터 공통 HTTP. 재시도하지 않는다. 명령을 두 번 보낼지는 호출자가 결과를 보고 정한다.
 * 스프링 예외의 메시지에는 응답 본문과 URL이 들어가므로 원인으로 잇지 않는다.
 */
@Component
class EquipmentHttp {

    // 빠진 값이나 어긋난 타입이 0으로 읽히면 안전 판단이 정상으로 기운다. 제조사가 필드를 더하는 것은 허용한다.
    private static final JsonMapper STRICT = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .build();

    private final Environment env;
    private final RestClient client;

    EquipmentHttp(
            Environment env,
            @Value("${controltower.equipment.connect-timeout:2s}") Duration connectTimeout,
            @Value("${controltower.equipment.read-timeout:3s}") Duration readTimeout) {
        this.env = env;
        SimpleClientHttpRequestFactory timeouts = new SimpleClientHttpRequestFactory();
        timeouts.setConnectTimeout(connectTimeout);
        timeouts.setReadTimeout(readTimeout);
        this.client = RestClient.builder().requestFactory(timeouts).build();
    }

    /** 번역까지 여기서 감싸므로 도메인 레코드의 검증 실패도 게이트웨이 장애가 된다. 값은 URI 템플릿으로 넣어 경로를 벗어나지 못한다. */
    <T, R> R read(String path, Class<T> wire, Function<T, R> translate, Object... uriVariables) {
        try {
            String json = client.get().uri(baseUrl() + path, uriVariables).retrieve().body(String.class);
            return translate.apply(STRICT.readValue(json, wire));
        } catch (RuntimeException e) {
            throw unavailable();
        }
    }

    void send(String path, Object body, Object... uriVariables) {
        String url = baseUrl() + path;
        boolean succeeded;
        try {
            succeeded = client.post()
                    .uri(url, uriVariables)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity()
                    .getStatusCode()
                    .is2xxSuccessful();
        } catch (ResourceAccessException e) {
            if (neverReached(e.getCause())) {
                throw unavailable();
            }
            throw new CommandOutcomeUnknownException("equipment command outcome unknown", null);
        } catch (RuntimeException e) {
            throw unavailable();
        }
        // 3xx는 RestClient가 오류로 치지 않는다.
        if (!succeeded) {
            throw unavailable();
        }
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
                        && cause.getMessage().startsWith("Connect"));
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

    private static GatewayUnavailableException unavailable() {
        return new GatewayUnavailableException("equipment gateway unavailable", null);
    }
}
