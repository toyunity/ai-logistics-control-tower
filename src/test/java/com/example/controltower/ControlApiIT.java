package com.example.controltower;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.controltower.application.port.in.ProposalResult;
import com.example.controltower.application.port.in.ProposeCommand;
import com.example.controltower.application.port.out.Conveyors;
import com.example.controltower.application.port.out.PendingCommands;
import com.example.controltower.application.port.out.Sorters;
import com.example.controltower.domain.Actor;
import com.example.controltower.domain.CommandSpec;
import com.example.controltower.domain.CommandStatus;
import com.example.controltower.domain.DenialCode;
import com.example.controltower.domain.PendingCommand;
import com.example.controltower.domain.Role;
import com.example.controltower.domain.RunState;
import com.example.controltower.simulator.PlantSimulator;
import com.example.controltower.simulator.SimSnapshot;
import com.example.controltower.support.FakeAiConfig;
import com.example.controltower.support.FakeChatModel;
import com.example.controltower.support.TestInfraConfig;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 시뮬레이터, PostgreSQL, 실제 HTTP로 세션부터 설비 반영까지 본다. 시뮬레이터 틱이 돌아야 안전 신호가 3초 한도 안에 있다.
 * 카드는 대부분 ProposeCommand로 만들고, 대화부터 확정까지의 경로는 가짜 모델이 도구를 부르는 시험 하나로 본다.
 */
@SpringBootTest(
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {"controltower.simulator.enabled=true", "controltower.detection.enabled=true"})
@Import({TestInfraConfig.class, FakeAiConfig.class})
class ControlApiIT {

    private static final Actor OPERATOR = new Actor("operator", Role.OPERATOR);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort int port;
    @Autowired PlantSimulator sim;
    @Autowired ProposeCommand propose;
    @Autowired PendingCommands pending;
    @Autowired Sorters sorters;
    @Autowired Conveyors conveyors;
    @Autowired FakeChatModel model;

    private final HttpClient http = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();

    @BeforeEach
    @AfterEach
    void restore() {
        sim.reset();
    }

    private HttpResponse<String> post(String path, String json) throws Exception {
        return http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .build(),
                BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(), BodyHandlers.ofString());
    }

    private void loginOperator() throws Exception {
        HttpResponse<String> response = post("/api/session", "{\"accountId\":\"operator\"}");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().allValues("Set-Cookie")).anyMatch(c -> c.contains("SameSite=Lax"));
    }

    private HttpResponse<String> confirm(UUID id) throws Exception {
        return post("/api/commands/" + id + "/confirm", "");
    }

    private static JsonNode json(HttpResponse<String> response) {
        return JSON.readTree(response.body());
    }

    private void startChuteJamUntilBlocked() {
        assertThat(sim.start("chute-jam")).isTrue();
        await().atMost(Duration.ofSeconds(30)).until(() -> sorters.get().blocked());
    }

    private PendingCommand proposedDivert() {
        ProposalResult result = propose.propose(new CommandSpec.Divert("CH-03", "CH-04"), OPERATOR);
        assertThat(result).isInstanceOf(ProposalResult.Proposed.class);
        return ((ProposalResult.Proposed) result).command();
    }

    private boolean hasIncident(String equipmentId, String kind) throws Exception {
        JsonNode status = json(get("/api/plant/status"));
        return StreamSupport.stream(status.get("incidents").spliterator(), false)
                .anyMatch(i -> i.get("equipmentId").asString().equals(equipmentId)
                        && i.get("kind").asString().equals(kind));
    }

    // WebFlux의 시작 페이지 처리로 루트가 정적 화면을 돌려주는지 본다.
    @Test
    void rootServesTheStaticUi() throws Exception {
        HttpResponse<String> response = get("/");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("<title>설비 관제 어시스턴트</title>");
    }

    @Test
    void chatToolCardThenHttpConfirm_simulatorChanges() throws Exception {
        loginOperator();
        startChuteJamUntilBlocked();
        model.reset();
        model.enqueueStream(Flux.just(FakeChatModel.text("우회를 제안합니다 "),
                FakeChatModel.toolCall("c1", "proposeDivert", "{\"fromChute\":\"CH-03\",\"toChute\":\"CH-04\"}")));
        model.enqueueStream(Flux.just(FakeChatModel.text("아래 카드에서 확정해 주세요"), FakeChatModel.finish("end_turn")));

        String sse = post("/api/chat/stream", "{\"message\":\"CH-03을 CH-04로 우회해\"}").body();

        List<String> lines = sse.lines().toList();
        int action = lines.indexOf("event:action");
        assertThat(action).isPositive();
        assertThat(lines.subList(action, lines.size())).contains("event:done");
        JsonNode card = JSON.readTree(lines.get(action + 1).substring("data:".length()));
        assertThat(card.get("kind").asString()).isEqualTo("DIVERT");
        assertThat(card.get("targetId").asString()).isEqualTo("CH-03");
        assertThat(sim.snapshot().reroutes()).doesNotContain(new SimSnapshot.Reroute(3, 4));

        HttpResponse<String> response = confirm(UUID.fromString(card.get("commandId").asString()));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json(response).get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(sim.snapshot().reroutes()).contains(new SimSnapshot.Reroute(3, 4));
    }

    @Test
    void operatorConfirmDivert_simulatorChanges() throws Exception {
        loginOperator();
        startChuteJamUntilBlocked();
        PendingCommand card = proposedDivert();

        HttpResponse<String> response = confirm(card.id());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json(response).get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(sim.snapshot().reroutes()).contains(new SimSnapshot.Reroute(3, 4));
        assertThat(confirm(card.id()).statusCode()).isEqualTo(409);
    }

    // 차단 중에는 제안이 거절돼 카드가 생기지 않는다. 확정 경로가 스스로 다시 막는지 보려고 카드를 직접 넣는다.
    @Test
    void restartBeforeDivert_409() throws Exception {
        loginOperator();
        startChuteJamUntilBlocked();
        await().atMost(Duration.ofSeconds(30)).until(() -> conveyors.get("CV-3").state() == RunState.STOPPED);
        assertThat(propose.propose(new CommandSpec.Restart("CV-3"), OPERATOR))
                .isEqualTo(new ProposalResult.Rejected(DenialCode.SORTER_BLOCKED));
        Instant now = Instant.now();
        UUID id = UUID.randomUUID();
        pending.save(new PendingCommand(id, "operator", new CommandSpec.Restart("CV-3"), "digest", "impact",
                CommandStatus.PENDING, now, now.plus(Duration.ofMinutes(2))));

        HttpResponse<String> response = confirm(id);

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(json(response).get("message").asString()).isEqualTo(DenialCode.SORTER_BLOCKED.message());
        assertThat(conveyors.get("CV-3").state()).isEqualTo(RunState.STOPPED);
    }

    @Test
    void staleCardAfterSimReset_409() throws Exception {
        loginOperator();
        startChuteJamUntilBlocked();
        PendingCommand card = proposedDivert();
        assertThat(post("/sim/reset", "").statusCode()).isEqualTo(200);

        HttpResponse<String> response = confirm(card.id());

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(json(response).get("status").asString()).isEqualTo("FAILED");
        assertThat(sim.snapshot().reroutes()).isEmpty();
    }

    // 조치 순서대로 우회를 확정한 뒤 만든 재가동 카드도 리셋 뒤에는 보내지 않는다.
    @Test
    void staleRestartCardAfterSimReset_409() throws Exception {
        loginOperator();
        startChuteJamUntilBlocked();
        await().atMost(Duration.ofSeconds(30)).until(() -> conveyors.get("CV-3").state() == RunState.STOPPED);
        assertThat(confirm(proposedDivert().id()).statusCode()).isEqualTo(200);
        ProposalResult restart = propose.propose(new CommandSpec.Restart("CV-3"), OPERATOR);
        assertThat(restart).isInstanceOf(ProposalResult.Proposed.class);
        UUID id = ((ProposalResult.Proposed) restart).command().id();
        assertThat(post("/sim/reset", "").statusCode()).isEqualTo(200);

        HttpResponse<String> response = confirm(id);

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(json(response).get("status").asString()).isEqualTo("FAILED");
        assertThat(pending.find(id).orElseThrow().status()).isEqualTo(CommandStatus.FAILED);
    }

    // 스캐너 상태는 이벤트로만 오므로 시뮬레이터 전송, 수신 검증, 감지가 모두 돌아야 통과한다.
    @Test
    void scannerScenario_incidentAppearsThenClosesAfterReset() throws Exception {
        assertThat(post("/sim/scenarios/scanner-degraded", "").statusCode()).isEqualTo(200);
        await().atMost(Duration.ofSeconds(30)).until(() -> hasIncident("SC-1", "SCANNER_DEGRADED"));

        assertThat(post("/sim/reset", "").statusCode()).isEqualTo(200);

        await().atMost(Duration.ofSeconds(30)).until(() -> !hasIncident("SC-1", "SCANNER_DEGRADED"));
    }
}
