package com.example.controltower;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.controltower.application.port.in.PlantStatus;
import com.example.controltower.support.FakeAiConfig;
import com.example.controltower.support.TestInfraConfig;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import reactor.core.scheduler.Schedulers;

/** 실제 HTTP 위에서 MCP SDK 클라이언트로 붙는다. 조회 도구 네 개만 보이고, 결과는 채팅 도구와 같은 고정 형식이다. */
@SpringBootTest(
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
            "controltower.mcp.enabled=true",
            "controltower.mcp.token=" + McpServerIT.TOKEN,
            "controltower.simulator.enabled=true"
        })
@Import({TestInfraConfig.class, FakeAiConfig.class, McpServerIT.Recording.class})
@ExtendWith(OutputCaptureExtension.class)
class McpServerIT {

    static final String TOKEN = "mcp-test-token-7f3a";
    private static final String MARKER = "MCP-ARG-MARKER";
    private static final String UNAUTHORIZED = "{\"status\":\"UNAUTHORIZED\",\"message\":\"MCP 토큰이 필요합니다.\"}";

    @LocalServerPort int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private McpSyncClient client(String token) {
        var transport = HttpClientStreamableHttpTransport.builder("http://localhost:" + port)
                .endpoint("/mcp")
                .requestBuilder(HttpRequest.newBuilder().header("Authorization", "Bearer " + token))
                .build();
        McpSyncClient client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(30)).build();
        client.initialize();
        return client;
    }

    private HttpRequest.Builder toolsList(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"));
    }

    private static String text(McpSchema.CallToolResult result) {
        return ((McpSchema.TextContent) result.content().getFirst()).text();
    }

    @Test
    void listsExactlyTheFourReadTools() {
        try (McpSyncClient client = client(TOKEN)) {
            assertThat(client.listTools().tools()).extracting(McpSchema.Tool::name)
                    .containsExactlyInAnyOrder("getPlantStatus", "getEquipment", "getTimeline", "diagnose");
        }
    }

    @Test
    void readsReturnTheFixedToolShape(CapturedOutput output) {
        try (McpSyncClient client = client(TOKEN)) {
            String ok = text(client.callTool(new McpSchema.CallToolRequest("getEquipment", Map.of("id", "CV-3"))));
            String missing = text(client.callTool(new McpSchema.CallToolRequest("getEquipment", Map.of("id", MARKER))));
            String timeline = text(client.callTool(new McpSchema.CallToolRequest("getTimeline", Map.of("equipmentId", "CV-3"))));
            String status = text(client.callTool(new McpSchema.CallToolRequest("getPlantStatus", Map.of())));

            assertThat(ok).contains("\"status\":\"OK\"").contains("CV-3");
            assertThat(missing).contains("\"status\":\"NOT_FOUND\"").doesNotContain(MARKER);
            assertThat(timeline).contains("\"status\":\"OK\"").doesNotContain("eventId");
            assertThat(status).contains("\"status\":\"OK\"");
        }
        assertThat(output.getAll()).doesNotContain(MARKER).doesNotContain(TOKEN);
    }

    // 인자 변환 실패는 메서드 본문 전에 터진다. SDK 기본 처리는 예외 문구(인자 값이 든)를 그대로 돌려준다.
    @Test
    void malformedArgumentsGetFixedResultWithoutEcho(CapturedOutput output) {
        try (McpSyncClient client = client(TOKEN)) {
            String nested = text(client.callTool(new McpSchema.CallToolRequest("getEquipment", Map.of("id", Map.of("x", MARKER)))));
            String minutes = text(client.callTool(
                    new McpSchema.CallToolRequest("getTimeline", Map.of("equipmentId", "CV-3", "minutes", MARKER))));

            for (String result : new String[] {nested, minutes}) {
                assertThat(result).contains("\"status\":\"INVALID_ARGUMENT\"")
                        .doesNotContain(MARKER).doesNotContain("Exception").doesNotContain("at com.");
            }
        }
        assertThat(output.getAll()).doesNotContain(MARKER).doesNotContain(TOKEN);
    }

    @Test
    void missingOrWrongToken_401WithFixedBody(CapturedOutput output) throws Exception {
        for (String header : new String[] {null, "Bearer wrong-" + MARKER, TOKEN, "Bearer " + TOKEN + "x"}) {
            HttpRequest.Builder request = toolsList("/mcp");
            if (header != null) {
                request.header("Authorization", header);
            }
            HttpResponse<String> response = http.send(request.build(), BodyHandlers.ofString());

            assertThat(response.statusCode()).as(String.valueOf(header)).isEqualTo(401);
            assertThat(response.body()).isEqualTo(UNAUTHORIZED);
        }
        assertThat(output.getAll()).doesNotContain(MARKER).doesNotContain(TOKEN);
    }

    // 라우터는 PathPattern으로 맞춘다(퍼센트 디코딩, ; 매개변수 제거). 문자열 비교로 막으면 이 주소들이 토큰 없이 통과했다.
    @Test
    void encodedOrMatrixPaths_neverBypassTheToken() throws Exception {
        for (String path : new String[] {"/mcp;a=b", "/%6Dcp", "/%6dcp", "/mcp/", "/MCP", "/mcp%2F"}) {
            HttpResponse<String> response = http.send(toolsList(path).build(), BodyHandlers.ofString());

            assertThat(response.statusCode()).as(path).isIn(401, 404);
            if (response.statusCode() == 401) {
                assertThat(response.body()).as(path).isEqualTo(UNAUTHORIZED);
            }
        }
        for (String path : new String[] {"/mcp;a=b", "/%6Dcp", "/%6dcp"}) {
            assertThat(http.send(toolsList(path).build(), BodyHandlers.ofString()).statusCode()).as(path).isEqualTo(401);
        }
    }

    // 블로킹 조회(JDBC, 설비 HTTP)가 Netty 이벤트 루프에서 돌면 안 된다.
    @Test
    void toolCallsRunOffTheEventLoop() {
        Recording.nonBlockingThread.set(null);
        try (McpSyncClient client = client(TOKEN)) {
            client.callTool(new McpSchema.CallToolRequest("getEquipment", Map.of("id", "CV-3")));
        }
        assertThat(Recording.nonBlockingThread.get()).isFalse();
    }

    // 경로 앞부분만 같은 다른 주소는 MCP가 아니다. 인증 필터가 끼어들지 않는다.
    @Test
    void lookalikePathIsNotGuarded() throws Exception {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcpx")).build(), BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(404);
    }

    /** 실제 PlantStatus를 감싸 조회가 불린 스레드가 논블로킹 스레드였는지 기록한다. */
    @TestConfiguration(proxyBeanMethods = false)
    static class Recording {

        static final AtomicReference<Boolean> nonBlockingThread = new AtomicReference<>();

        @Bean
        @Primary
        PlantStatus recordingPlantStatus(@Qualifier("plantStatus") PlantStatus real) {
            return (PlantStatus) Proxy.newProxyInstance(PlantStatus.class.getClassLoader(), new Class<?>[] {PlantStatus.class},
                    (proxy, method, args) -> {
                        nonBlockingThread.compareAndSet(null, Schedulers.isInNonBlockingThread());
                        try {
                            return method.invoke(real, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        }
    }
}
