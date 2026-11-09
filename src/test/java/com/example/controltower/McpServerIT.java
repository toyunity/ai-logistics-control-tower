package com.example.controltower;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.controltower.support.FakeAiConfig;
import com.example.controltower.support.TestInfraConfig;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

/** 실제 HTTP 위에서 MCP SDK 클라이언트로 붙는다. 조회 도구 네 개만 보이고, 결과는 채팅 도구와 같은 고정 형식이다. */
@SpringBootTest(
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
            "controltower.mcp.enabled=true",
            "controltower.mcp.token=" + McpServerIT.TOKEN,
            "controltower.simulator.enabled=true"
        })
@Import({TestInfraConfig.class, FakeAiConfig.class})
@ExtendWith(OutputCaptureExtension.class)
class McpServerIT {

    static final String TOKEN = "mcp-test-token-7f3a";
    private static final String MARKER = "MCP-ARG-MARKER";

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
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp"))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"));
            if (header != null) {
                request.header("Authorization", header);
            }
            HttpResponse<String> response = http.send(request.build(), BodyHandlers.ofString());

            assertThat(response.statusCode()).as(String.valueOf(header)).isEqualTo(401);
            assertThat(response.body()).isEqualTo("{\"status\":\"UNAUTHORIZED\",\"message\":\"MCP 토큰이 필요합니다.\"}");
        }
        assertThat(output.getAll()).doesNotContain(MARKER).doesNotContain(TOKEN);
    }

    // 경로 앞부분만 같은 다른 주소는 MCP가 아니다. 인증 필터가 끼어들지 않는다.
    @Test
    void lookalikePathIsNotGuarded() throws Exception {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcpx")).build(), BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(404);
    }
}
