package com.example.controltower.adapter.in.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.controltower.application.port.in.EquipmentView;
import com.example.controltower.application.port.in.PlantSnapshot;
import com.example.controltower.application.port.in.PlantStatus;
import com.example.controltower.application.port.in.ProposalResult;
import com.example.controltower.application.port.in.ProposeCommand;
import com.example.controltower.application.port.in.TurnScope;
import com.example.controltower.domain.Actor;
import com.example.controltower.domain.CommandSpec;
import com.example.controltower.domain.CommandStatus;
import com.example.controltower.domain.DenialCode;
import com.example.controltower.domain.EquipmentEvent;
import com.example.controltower.domain.MaintenanceRequest;
import com.example.controltower.domain.PendingCommand;
import com.example.controltower.domain.Role;
import com.example.controltower.domain.RootCauseCandidate;
import com.example.controltower.domain.RunState;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.execution.ToolExecutionException;

class PlantToolsTest {

    private static final Actor OPERATOR = new Actor("operator", Role.OPERATOR);
    private static final Instant NOW = Instant.parse("2026-11-08T00:00:00Z");
    private static final String MARKER = "FREE-TEXT-MARKER";

    private final PlantStatus status = mock(PlantStatus.class);
    private final List<CommandSpec> proposedSpecs = new ArrayList<>();
    private Function<CommandSpec, ProposalResult> proposeAnswer = spec -> new ProposalResult.Proposed(card(spec));
    private final ProposeCommand propose = (spec, actor) -> {
        proposedSpecs.add(spec);
        return proposeAnswer.apply(spec);
    };
    private final PlantTools tools = new PlantTools(status, propose);
    private final TurnScope scope = new TurnScope(OPERATOR, 100);
    private final ToolContext ctx = new ToolContext(Map.of(TurnScope.KEY, scope));

    private static PendingCommand card(CommandSpec spec) {
        return new PendingCommand(UUID.randomUUID(), "operator", spec, "digest", "영향 범위", CommandStatus.PENDING,
                NOW, NOW.plusSeconds(120));
    }

    private String callJson(String name, String args) {
        return callJson(name, args, ctx);
    }

    private String callJson(String name, String args, ToolContext context) {
        ToolCallback callback = Arrays.stream(tools.callbacks())
                .filter(c -> c.getToolDefinition().name().equals(name))
                .findFirst()
                .orElseThrow();
        return callback.call(args, context);
    }

    @Test
    void badIds_notFoundNoException() {
        when(status.equipment(anyString())).thenReturn(Optional.empty());
        when(status.timeline(anyString(), anyInt())).thenReturn(List.of());
        when(status.diagnose(anyString())).thenReturn(Optional.empty());

        for (String bad : Arrays.asList("cv3", "CV-99", "", "  ", null)) {
            assertThat(List.of(
                            tools.getEquipment(bad, ctx),
                            tools.getTimeline(bad, 30, ctx),
                            tools.diagnose(bad, ctx),
                            tools.proposeRestart(bad, ctx),
                            tools.proposeDivert(bad, "CH-04", ctx),
                            tools.proposeDivert("CH-03", bad, ctx),
                            tools.proposeReassign(bad, "AMR-02", ctx),
                            tools.proposeReassign("AMR-01", bad, ctx),
                            tools.proposeMaintenance(bad, "점검", ctx)))
                    .as(String.valueOf(bad))
                    .allMatch(ToolResults.NotFound.class::isInstance);
        }
        // 종류가 다른 설비도 없는 설비로 본다.
        assertThat(tools.proposeRestart("CH-03", ctx)).isInstanceOf(ToolResults.NotFound.class);
        assertThat(tools.proposeDivert("CV-3", "CH-04", ctx)).isInstanceOf(ToolResults.NotFound.class);
        assertThat(tools.proposeReassign("CH-03", "AMR-02", ctx)).isInstanceOf(ToolResults.NotFound.class);
        assertThat(proposedSpecs).isEmpty();
        assertThat(scope.proposed()).isEmpty();
    }

    @Test
    void badIdsThroughToolCallback_notFoundJson() {
        for (String args : List.of("{}", "{\"conveyorId\":null}", "{\"conveyorId\":\"\"}", "{\"conveyorId\":\"cv3\"}")) {
            assertThat(callJson("proposeRestart", args)).as(args).contains("\"status\":\"NOT_FOUND\"");
        }
        assertThat(callJson("proposeMaintenance", "{\"equipmentId\":\"CV-3\"}")).contains("\"status\":\"OK\"");
        // 문자열이 아닌 값은 Spring AI가 변환하다 ToolExecutionException을 낸다. 고정 결과로 바꾸는 일은 ServiceConfig가 한다.
        assertThatThrownBy(() -> callJson("proposeRestart", "{\"conveyorId\":{\"x\":1}}"))
                .isInstanceOf(ToolExecutionException.class);
        assertThat(proposedSpecs).containsExactly(new CommandSpec.Maintenance("CV-3", ""));
    }

    @Test
    void toolBudget_ninthCallLimited_conversionFailuresCountToo() {
        when(status.equipment("CV-3")).thenReturn(Optional.of(new EquipmentView("CV-3", "CONVEYOR", RunState.RUNNING, Map.of())));
        var limited = new ToolContext(Map.of(TurnScope.KEY, new TurnScope(OPERATOR, 8)));

        assertThatThrownBy(() -> callJson("getTimeline", "{\"equipmentId\":\"CV-3\",\"minutes\":\"abc\"}", limited))
                .isInstanceOf(ToolExecutionException.class);
        for (int i = 0; i < 7; i++) {
            assertThat(callJson("getEquipment", "{\"id\":\"CV-3\"}", limited)).contains("\"status\":\"OK\"");
        }

        assertThat(callJson("proposeRestart", "{\"conveyorId\":\"CV-3\"}", limited)).contains("\"status\":\"LIMIT_EXCEEDED\"");
        assertThat(proposedSpecs).isEmpty();
    }

    @Test
    void deviceFreeTextNotInToolResult() {
        var event = new EquipmentEvent("evt-" + MARKER, "CV-3", "CONVEYOR_STOPPED", 101, NOW);
        when(status.timeline("CV-3", 30)).thenReturn(List.of(event));
        when(status.diagnose("CV-3")).thenReturn(Optional.of(new RootCauseCandidate("CH-03", List.of(event))));

        String timeline = callJson("getTimeline", "{\"equipmentId\":\"CV-3\",\"minutes\":30}");
        String diagnosis = callJson("diagnose", "{\"equipmentId\":\"CV-3\"}");

        assertThat(timeline).contains("CONVEYOR_STOPPED").contains("101").doesNotContain(MARKER).doesNotContain("eventId");
        assertThat(diagnosis).contains("CH-03").contains("CONVEYOR_STOPPED").doesNotContain(MARKER);
    }

    @Test
    void timelineMinutesOptional() {
        when(status.timeline("CV-3", 30)).thenReturn(List.of());

        assertThat(callJson("getTimeline", "{\"equipmentId\":\"CV-3\"}")).contains("\"status\":\"OK\"");
    }

    @Test
    void modelSummaryNotReturned() {
        String proposal = callJson("proposeMaintenance", "{\"equipmentId\":\"CV-3\",\"summary\":\"" + MARKER + "\"}");

        assertThat(proposal).contains("\"status\":\"OK\"").contains("MAINTENANCE").doesNotContain(MARKER);
        assertThat(proposedSpecs).containsExactly(new CommandSpec.Maintenance("CV-3", MARKER));

        when(status.snapshot()).thenReturn(new PlantSnapshot(List.of(), List.of(),
                List.of(new MaintenanceRequest(UUID.randomUUID(), "CV-3", MARKER, NOW, null)), NOW));
        assertThat(callJson("getPlantStatus", "{}")).contains("CV-3").doesNotContain(MARKER);
    }

    @Test
    void proposalResultHidesAccountAndRecordsCard() {
        String json = callJson("proposeRestart", "{\"conveyorId\":\"CV-3\"}");

        assertThat(json).contains("\"status\":\"OK\"").contains("RESTART").contains("영향 범위").doesNotContain("operator");
        assertThat(scope.proposed()).extracting(PendingCommand::spec).containsExactly(new CommandSpec.Restart("CV-3"));
    }

    @Test
    void rejectionsMapToFixedResults() {
        proposeAnswer = spec -> new ProposalResult.Rejected(DenialCode.NOT_PERMITTED);
        assertThat(tools.proposeRestart("CV-3", ctx)).isEqualTo(ToolResults.NOT_PERMITTED);

        proposeAnswer = spec -> new ProposalResult.Rejected(DenialCode.NOT_FOUND);
        assertThat(tools.proposeRestart("CV-3", ctx)).isEqualTo(ToolResults.NOT_FOUND);

        proposeAnswer = spec -> new ProposalResult.Rejected(DenialCode.SORTER_BLOCKED);
        assertThat(tools.proposeRestart("CV-3", ctx))
                .isEqualTo(new ToolResults.Denied("SORTER_BLOCKED", DenialCode.SORTER_BLOCKED.message()));
        assertThat(callJson("proposeRestart", "{\"conveyorId\":\"CV-3\"}"))
                .contains("\"status\":\"DENIED\"").contains("\"code\":\"SORTER_BLOCKED\"");
        assertThat(scope.proposed()).isEmpty();
    }

    @Test
    void failuresBecomeUnavailableWithoutDetail() {
        // 같은 키의 동시 저장(DuplicateKeyException)도 여기로 온다.
        proposeAnswer = spec -> {
            throw new IllegalStateException("secret duplicate key");
        };
        when(status.snapshot()).thenThrow(new IllegalStateException("secret db"));
        when(status.equipment("CV-3")).thenThrow(new IllegalStateException("secret gateway"));

        assertThat(tools.proposeDivert("CH-03", "CH-04", ctx)).isEqualTo(ToolResults.UNAVAILABLE);
        assertThat(tools.getPlantStatus(ctx)).isEqualTo(ToolResults.UNAVAILABLE);
        assertThat(callJson("getEquipment", "{\"id\":\"CV-3\"}"))
                .contains("\"status\":\"UNAVAILABLE\"").doesNotContain("secret");
        assertThat(scope.proposed()).isEmpty();
    }

    // MCP 호출에는 TurnScope가 없다. 조회는 계정이 필요 없으니 그대로 돌고, 제안만 막힌다.
    @Test
    void readsWithoutScope_ok_proposalsStillUnavailable() {
        when(status.equipment("CV-3")).thenReturn(Optional.of(new EquipmentView("CV-3", "CONVEYOR", RunState.RUNNING, Map.of())));
        var noScope = new ToolContext(Map.of("exchange", "x"));

        assertThat(callJson("getEquipment", "{\"id\":\"CV-3\"}", noScope)).contains("\"status\":\"OK\"");
        assertThat(callJson("getEquipment", "{\"id\":\"cv3\"}", noScope)).contains("\"status\":\"NOT_FOUND\"");
        assertThat(callJson("proposeRestart", "{\"conveyorId\":\"CV-3\"}", noScope)).contains("\"status\":\"UNAVAILABLE\"");
        assertThat(proposedSpecs).isEmpty();
    }

    @Test
    void missingScope_unavailable() {
        assertThat(tools.proposeRestart("CV-3", new ToolContext(Map.of("other", "x")))).isEqualTo(ToolResults.UNAVAILABLE);
        assertThat(proposedSpecs).isEmpty();
    }
}
