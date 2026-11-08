package com.example.controltower.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** 실제 DecideCommandService를 메모리 대역 위에 올려 세션 신원부터 응답 코드까지 함께 본다. */
@WebMvcTest({CommandController.class, SessionController.class})
@Import(CommandControllerTest.Wiring.class)
@ExtendWith(OutputCaptureExtension.class)
class CommandControllerTest {

    static class FailingPending extends InMemoryPendingCommands {
        volatile boolean failing;

        @Override
        public synchronized Optional<PendingCommand> find(UUID id) {
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

    @Autowired MockMvc mvc;
    @Autowired FailingPending pending;

    private final MockHttpSession session = new MockHttpSession();

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

    private void login(String accountId) throws Exception {
        mvc.perform(post("/api/session")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"" + accountId + "\"}"))
                .andExpect(status().isOk());
    }

    private ResultActions decide(UUID id, String action) throws Exception {
        return mvc.perform(post("/api/commands/{id}/{action}", id, action).session(session));
    }

    private CommandStatus statusOf(UUID id) {
        return pending.find(id).orElseThrow().status();
    }

    @Test
    void viewerConfirm_403() throws Exception {
        UUID id = liveCard("operator");
        login("viewer");

        decide(id, "confirm")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").doesNotExist())
                .andExpect(jsonPath("$.message").value(DenialCode.NOT_PERMITTED.message()));

        assertThat(statusOf(id)).isEqualTo(CommandStatus.PENDING);
    }

    @Test
    void otherOperatorSession_403() throws Exception {
        UUID id = liveCard("operator-b");
        login("operator");

        decide(id, "confirm").andExpect(status().isForbidden());
        decide(id, "reject").andExpect(status().isForbidden());

        assertThat(statusOf(id)).isEqualTo(CommandStatus.PENDING);
    }

    @Test
    void sessionGone_403() throws Exception {
        UUID id = liveCard("operator");
        login("operator");
        mvc.perform(delete("/api/session").session(session)).andExpect(status().isNoContent());

        decide(id, "confirm").andExpect(status().isForbidden());

        assertThat(statusOf(id)).isEqualTo(CommandStatus.PENDING);
    }

    @Test
    void ownerReject_200WithStatus() throws Exception {
        UUID id = liveCard("operator");
        login("operator");

        decide(id, "reject")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.message").isString());
    }

    @Test
    void unknownCard_404WithoutStatus() throws Exception {
        login("operator");

        decide(UUID.randomUUID(), "confirm")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").doesNotExist())
                .andExpect(jsonPath("$.message").isString());
    }

    @Test
    void expiredCard_409() throws Exception {
        UUID id = card("operator", Instant.now().minusSeconds(1));
        login("operator");

        decide(id, "confirm").andExpect(status().isConflict()).andExpect(jsonPath("$.status").value("EXPIRED"));
    }

    @Test
    void storeFailure_500WithFixedBody(CapturedOutput output) throws Exception {
        login("operator");
        pending.failing = true;

        String body = decide(UUID.randomUUID(), "confirm")
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").isString())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).doesNotContain("SECRET-SQL-VALUE");
        assertThat(output.getOut() + output.getErr()).doesNotContain("SECRET-SQL-VALUE");
    }

    @Test
    void malformedId_400() throws Exception {
        mvc.perform(post("/api/commands/not-a-uuid/confirm")).andExpect(status().isBadRequest());
    }

    @Test
    void unknownAccount_400AndNotLogged(CapturedOutput output) throws Exception {
        mvc.perform(post("/api/session")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"ACCOUNT-MARKER\"}"))
                .andExpect(status().isBadRequest());

        assertThat(session.getAttributeNames().hasMoreElements()).isFalse();
        assertThat(output.getOut() + output.getErr()).doesNotContain("ACCOUNT-MARKER");
    }

    @Test
    void login_returnsRole() throws Exception {
        mvc.perform(post("/api/session").contentType(MediaType.APPLICATION_JSON).content("{\"accountId\":\"operator\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value("operator"))
                .andExpect(jsonPath("$.role").value("OPERATOR"));
    }
}
