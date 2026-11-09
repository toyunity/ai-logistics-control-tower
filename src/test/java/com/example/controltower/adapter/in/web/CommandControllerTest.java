package com.example.controltower.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.controltower.application.port.in.DecideCommand;
import com.example.controltower.application.service.DecideCommandService;
import com.example.controltower.domain.CommandSpec;
import com.example.controltower.domain.CommandStatus;
import com.example.controltower.domain.DenialCode;
import com.example.controltower.domain.LineTopology;
import com.example.controltower.domain.PendingCommand;
import com.example.controltower.domain.RunState;
import com.example.controltower.domain.SafetyLimits;
import com.example.controltower.domain.SafetyPolicy;
import com.example.controltower.domain.SorterStatus;
import com.example.controltower.support.InMemoryCommandAudit;
import com.example.controltower.support.InMemoryConveyors;
import com.example.controltower.support.InMemoryMaintenanceDesk;
import com.example.controltower.support.InMemoryPendingCommands;
import com.example.controltower.support.InMemoryRobotFleet;
import com.example.controltower.support.InMemorySafetySignals;
import com.example.controltower.support.InMemorySorters;
import com.example.controltower.support.InMemoryUnitOfWork;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/** 실제 DecideCommandService를 메모리 대역 위에 올려 세션 신원부터 응답 코드까지 함께 본다. */
@WebFluxTest({CommandController.class, SessionController.class})
@Import(CommandControllerTest.Wiring.class)
@ExtendWith(OutputCaptureExtension.class)
class CommandControllerTest {

    static class FailingPending extends InMemoryPendingCommands {
        volatile boolean failing;
        volatile String findThread;

        @Override
        public synchronized Optional<PendingCommand> find(UUID id) {
            findThread = Thread.currentThread().getName();
            if (failing) {
                throw new IllegalStateException("SECRET-SQL-VALUE");
            }
            return super.find(id);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Wiring {

        @Bean
        FailingPending pending() {
            return new FailingPending();
        }

        @Bean
        DecideCommand decideCommand(FailingPending pending) {
            Clock clock = Clock.systemUTC();
            return new DecideCommandService(
                    new InMemoryConveyors(),
                    new InMemorySorters(new SorterStatus("SR-1", RunState.RUNNING, false, List.of(), Map.of())),
                    new InMemoryRobotFleet(),
                    new InMemorySafetySignals(clock),
                    pending,
                    new InMemoryMaintenanceDesk(),
                    new InMemoryCommandAudit(),
                    new InMemoryUnitOfWork(),
                    new SafetyPolicy(new SafetyLimits(Duration.ofSeconds(3), 30)),
                    LineTopology.DEFAULT,
                    clock);
        }
    }

    @Autowired WebTestClient client;
    @Autowired FailingPending pending;

    private SessionCookie session;

    @BeforeEach
    void newSession() {
        session = new SessionCookie(client);
    }

    @AfterEach
    void restore() {
        pending.failing = false;
    }

    private UUID card(String accountId, Instant expiresAt) {
        UUID id = UUID.randomUUID();
        pending.save(new PendingCommand(
                id,
                accountId,
                new CommandSpec.Restart("CV-" + id), // 컨텍스트를 테스트끼리 공유하므로 대상 키가 겹치지 않게 한다
                "digest",
                "impact",
                CommandStatus.PENDING,
                expiresAt.minus(Duration.ofMinutes(2)),
                expiresAt));
        return id;
    }

    private UUID liveCard(String accountId) {
        return card(accountId, Instant.now().plus(Duration.ofMinutes(2)));
    }

    private WebTestClient.ResponseSpec decide(UUID id, String action) {
        return session.with(client.post().uri("/api/commands/{id}/{action}", id, action)).exchange();
    }

    private CommandStatus statusOf(UUID id) {
        return pending.find(id).orElseThrow().status();
    }

    @Test
    void viewerConfirm_403() {
        UUID id = liveCard("operator");
        session.loginOk("viewer");

        decide(id, "confirm")
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.status").doesNotExist()
                .jsonPath("$.message").isEqualTo(DenialCode.NOT_PERMITTED.message());

        assertThat(statusOf(id)).isEqualTo(CommandStatus.PENDING);
    }

    @Test
    void otherOperatorSession_403() {
        UUID id = liveCard("operator-b");
        session.loginOk("operator");

        decide(id, "confirm").expectStatus().isForbidden();
        decide(id, "reject").expectStatus().isForbidden();

        assertThat(statusOf(id)).isEqualTo(CommandStatus.PENDING);
    }

    @Test
    void sessionGone_403() {
        UUID id = liveCard("operator");
        session.loginOk("operator");
        session.with(client.delete().uri("/api/session")).exchange().expectStatus().isNoContent();

        decide(id, "confirm").expectStatus().isForbidden();

        assertThat(statusOf(id)).isEqualTo(CommandStatus.PENDING);
    }

    // 로그인 전에 받은 세션 ID가 로그인 뒤에도 통하면 세션 고정 공격에 열린다.
    @Test
    void loginRotatesSessionId_oldIdNoLongerConfirms() {
        UUID id = liveCard("operator");
        session.loginOk("operator");
        String before = session.value();

        session.loginOk("operator");

        assertThat(session.value()).isNotNull().isNotEqualTo(before);
        String after = session.value();
        session.use(before);
        decide(id, "confirm").expectStatus().isForbidden();
        assertThat(statusOf(id)).isEqualTo(CommandStatus.PENDING);
        session.use(after);
        decide(id, "reject").expectStatus().isOk();
    }

    @Test
    void sessionCookieIsSameSiteLax() {
        assertThat(session.login("operator").getResponseHeaders().get(HttpHeaders.SET_COOKIE))
                .anyMatch(c -> c.startsWith(SessionCookie.NAME + "=") && c.contains("SameSite=Lax"));
    }

    @Test
    void ownerReject_200WithStatus() {
        UUID id = liveCard("operator");
        session.loginOk("operator");

        decide(id, "reject")
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("REJECTED")
                .jsonPath("$.message").isNotEmpty();
    }

    @Test
    void unknownCard_404WithoutStatus() {
        session.loginOk("operator");

        decide(UUID.randomUUID(), "confirm")
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.status").doesNotExist()
                .jsonPath("$.message").isNotEmpty();
    }

    @Test
    void expiredCard_409() {
        UUID id = card("operator", Instant.now().minusSeconds(1));
        session.loginOk("operator");

        decide(id, "confirm").expectStatus().isEqualTo(409).expectBody().jsonPath("$.status").isEqualTo("EXPIRED");
    }

    // 서비스는 JDBC를 부르므로 이벤트 루프가 아니라 boundedElastic에서 돌아야 한다.
    @Test
    void confirmRunsOffTheRequestThread() {
        session.loginOk("operator");

        decide(UUID.randomUUID(), "confirm").expectStatus().isNotFound();

        assertThat(pending.findThread).startsWith("boundedElastic-");
    }

    @Test
    void storeFailure_500WithFixedBody(CapturedOutput output) {
        session.loginOk("operator");
        pending.failing = true;

        String body = new String(decide(UUID.randomUUID(), "confirm")
                .expectStatus().isEqualTo(500)
                .expectBody()
                .jsonPath("$.message").isNotEmpty()
                .returnResult()
                .getResponseBody(), StandardCharsets.UTF_8);

        assertThat(body).doesNotContain("SECRET-SQL-VALUE");
        assertThat(output.getOut() + output.getErr()).doesNotContain("SECRET-SQL-VALUE");
    }

    @Test
    void malformedId_400(CapturedOutput output) {
        client.post().uri("/api/commands/not-a-uuid-MARKER/confirm").exchange()
                .expectStatus().isBadRequest()
                .expectBody(String.class).value(b -> assertThat(b).doesNotContain("MARKER"));

        assertThat(output.getOut() + output.getErr()).doesNotContain("MARKER");
    }

    @Test
    void unknownAccount_400AndNotLogged(CapturedOutput output) {
        var result = session.login("ACCOUNT-MARKER");

        assertThat(result.getStatus().value()).isEqualTo(400);
        assertThat(result.getResponseHeaders().get(HttpHeaders.SET_COOKIE)).isNull(); // 실패한 로그인은 세션을 만들지 않는다
        assertThat(output.getOut() + output.getErr()).doesNotContain("ACCOUNT-MARKER");
    }

    @Test
    void login_returnsRole() {
        client.post().uri("/api/session")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"accountId\":\"operator\"}")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.accountId").isEqualTo("operator")
                .jsonPath("$.role").isEqualTo("OPERATOR");
    }
}
