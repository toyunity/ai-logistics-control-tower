package com.example.controltower.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.controltower.application.port.in.CommandResult;
import com.example.controltower.application.port.in.CommandResult.Outcome;
import com.example.controltower.application.port.in.ProposalResult;
import com.example.controltower.application.port.out.Sorters;
import com.example.controltower.application.port.out.UnitOfWork;
import com.example.controltower.application.service.DecideCommandService;
import com.example.controltower.application.service.ProposeCommandService;
import com.example.controltower.domain.Actor;
import com.example.controltower.domain.ChuteStatus;
import com.example.controltower.domain.CommandOutcomeUnknownException;
import com.example.controltower.domain.CommandSpec;
import com.example.controltower.domain.CommandSpec.Divert;
import com.example.controltower.domain.CommandSpec.Maintenance;
import com.example.controltower.domain.CommandSpec.Reassign;
import com.example.controltower.domain.CommandSpec.Restart;
import com.example.controltower.domain.CommandStatus;
import com.example.controltower.domain.ConveyorStatus;
import com.example.controltower.domain.DenialCode;
import com.example.controltower.domain.FaultClass;
import com.example.controltower.domain.GatewayUnavailableException;
import com.example.controltower.domain.LineTopology;
import com.example.controltower.domain.PendingCommand;
import com.example.controltower.domain.RobotStatus;
import com.example.controltower.domain.Role;
import com.example.controltower.domain.RunState;
import com.example.controltower.domain.SafetyLimits;
import com.example.controltower.domain.SafetyPolicy;
import com.example.controltower.domain.SafetyStatus;
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
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class DecideCommandServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-07T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Duration TTL = Duration.ofMinutes(2);
    private static final Actor OPERATOR = new Actor("op-1", Role.OPERATOR);
    private static final SafetyPolicy POLICY = new SafetyPolicy(new SafetyLimits(Duration.ofSeconds(3), 30));

    private static final Restart RESTART = new Restart("CV-3");
    private static final Divert DIVERT = new Divert("CH-03", "CH-04");
    private static final Reassign REASSIGN = new Reassign("AMR-01", "AMR-02");
    private static final Maintenance MAINTENANCE = new Maintenance("SR-1", "벨트 소음 점검");

    private static final ConveyorStatus STOPPED = new ConveyorStatus("CV-3", "Z1", RunState.STOPPED, FaultClass.NONE);
    private static final SorterStatus SORTER = sorter(60, 20, Map.of());
    private static final RobotStatus SOURCE = robot("AMR-01", RunState.RUNNING, 5);
    private static final RobotStatus TARGET = robot("AMR-02", RunState.RUNNING, 2);

    private final InMemoryConveyors conveyors = new InMemoryConveyors(STOPPED);
    private final InMemorySorters sorters = new InMemorySorters(SORTER);
    private final InMemoryRobotFleet fleet = new InMemoryRobotFleet(SOURCE, TARGET);
    private final InMemorySafetySignals safety = new InMemorySafetySignals(CLOCK);
    private final InMemoryPendingCommands pending = new InMemoryPendingCommands();
    private final InMemoryMaintenanceDesk desk = new InMemoryMaintenanceDesk();
    private final InMemoryCommandAudit audit = new InMemoryCommandAudit();
    private final InMemoryUnitOfWork unitOfWork = new InMemoryUnitOfWork();
    private final DecideCommandService service = service(CLOCK, unitOfWork, sorters);

    private DecideCommandService service(Clock clock, UnitOfWork uow, Sorters sorterPort) {
        return new DecideCommandService(
                conveyors, sorterPort, fleet, safety, pending, desk, audit, uow, POLICY, LineTopology.DEFAULT, clock);
    }

    /** to-rate는 CH-04의 분당 처리량이다. 용량 40에서 CH-03 물량 20을 더해 넘는지 가른다. */
    private static SorterStatus sorter(int sourceFill, int toRate, Map<String, String> diversions) {
        return new SorterStatus(
                "SR-1",
                RunState.RUNNING,
                false,
                List.of(
                        new ChuteStatus("CH-03", "A", RunState.RUNNING, sourceFill, 20, 40, false),
                        new ChuteStatus("CH-04", "A", RunState.RUNNING, 30, toRate, 40, false)),
                diversions);
    }

    private static RobotStatus robot(String id, RunState state, int queued) {
        return new RobotStatus(id, state, 80, queued, 10);
    }

    private PendingCommand card(CommandSpec spec) {
        ProposeCommandService propose = new ProposeCommandService(
                conveyors, sorters, fleet, safety, pending, desk, unitOfWork, POLICY, LineTopology.DEFAULT, CLOCK, TTL);
        return ((ProposalResult.Proposed) propose.propose(spec, OPERATOR)).command();
    }

    private static Stream<CommandSpec> allKinds() {
        return Stream.of(RESTART, DIVERT, REASSIGN, MAINTENANCE);
    }

    private static Stream<CommandSpec> equipmentKinds() {
        return Stream.of(RESTART, DIVERT, REASSIGN);
    }

    private int sends() {
        return conveyors.commandIds().size() + sorters.commandIds().size() + fleet.commandIds().size() + desk.opens();
    }

    private int equipmentReads() {
        return conveyors.reads() + sorters.reads() + fleet.reads() + safety.reads() + desk.reads();
    }

    private void afterAnySend(Runnable hook) {
        conveyors.afterSend(hook);
        sorters.afterSend(hook);
        fleet.afterSend(hook);
        desk.afterSend(hook);
    }

    private CommandStatus rowStatus(PendingCommand card) {
        return pending.find(card.id()).orElseThrow().status();
    }

    private void assertTerminal(PendingCommand card, CommandResult result, Outcome outcome, CommandStatus status) {
        assertThat(result.outcome()).isEqualTo(outcome);
        assertThat(result.status()).isEqualTo(status);
        assertThat(rowStatus(card)).isEqualTo(status);
        assertThat(audit.entries())
                .containsExactly(new InMemoryCommandAudit.Entry(card.id(), "op-1", card.spec(), status, NOW));
    }

    private void assertRefusedUntouched(PendingCommand card, CommandResult result, Outcome outcome) {
        assertThat(result.outcome()).isEqualTo(outcome);
        // 남의 카드 상태를 알려 주지 않는다.
        assertThat(result.status()).isNull();
        assertThat(rowStatus(card)).isEqualTo(CommandStatus.PENDING);
        assertThat(audit.entries()).isEmpty();
        assertThat(sends()).isZero();
    }

    @Test
    void unknownId_notFound() {
        UUID id = UUID.randomUUID();

        assertThat(service.confirm(id, OPERATOR).outcome()).isEqualTo(Outcome.NOT_FOUND);
        assertThat(service.reject(id, OPERATOR).outcome()).isEqualTo(Outcome.NOT_FOUND);
        assertThat(audit.entries()).isEmpty();
        assertThat(equipmentReads()).isZero();
    }

    @Test
    void otherAccount_forbidden() {
        PendingCommand card = card(RESTART);
        int readsAfterProposal = equipmentReads();

        assertRefusedUntouched(card, service.confirm(card.id(), new Actor("op-2", Role.OPERATOR)), Outcome.FORBIDDEN);
        assertRefusedUntouched(card, service.confirm(card.id(), Actor.ANONYMOUS), Outcome.FORBIDDEN);
        assertRefusedUntouched(card, service.confirm(card.id(), null), Outcome.FORBIDDEN);
        assertThat(equipmentReads()).isEqualTo(readsAfterProposal);
    }

    @Test
    void viewer_forbidden() {
        PendingCommand card = card(RESTART);

        assertRefusedUntouched(card, service.confirm(card.id(), new Actor("op-1", Role.VIEWER)), Outcome.FORBIDDEN);
    }

    @Test
    void expired_conflict() {
        PendingCommand card = card(RESTART);
        int readsAfterProposal = equipmentReads();

        CommandResult result = service(Clock.offset(CLOCK, TTL), unitOfWork, sorters).confirm(card.id(), OPERATOR);

        assertThat(result.outcome()).isEqualTo(Outcome.CONFLICT);
        assertThat(result.status()).isEqualTo(CommandStatus.EXPIRED);
        assertThat(sends()).isZero();
        assertThat(audit.entries()).isEmpty();
        assertThat(equipmentReads()).isEqualTo(readsAfterProposal);
    }

    @Test
    void secondConfirm_conflict_oneSend() {
        PendingCommand card = card(RESTART);

        CommandResult first = service.confirm(card.id(), OPERATOR);
        CommandResult second = service.confirm(card.id(), OPERATOR);

        assertThat(first.status()).isEqualTo(CommandStatus.SUCCEEDED);
        assertThat(second.outcome()).isEqualTo(Outcome.CONFLICT);
        assertThat(second.status()).isEqualTo(CommandStatus.SUCCEEDED);
        assertThat(sends()).isEqualTo(1);
        assertThat(audit.entries()).hasSize(1);
    }

    @Test
    void confirmWhileAnotherIsExecuting_conflict_oneSend() {
        PendingCommand card = card(RESTART);
        CommandResult[] nested = new CommandResult[1];
        afterAnySend(() -> nested[0] = service.confirm(card.id(), OPERATOR));

        service.confirm(card.id(), OPERATOR);

        assertThat(nested[0].outcome()).isEqualTo(Outcome.CONFLICT);
        assertThat(nested[0].status()).isEqualTo(CommandStatus.EXECUTING);
        assertThat(sends()).isEqualTo(1);
    }

    @Test
    void safetyNowFails_conflict_noSend() {
        PendingCommand card = card(RESTART);
        safety.set(new SafetyStatus("Z1", false, true, NOW));

        CommandResult result = service.confirm(card.id(), OPERATOR);

        assertTerminal(card, result, Outcome.CONFLICT, CommandStatus.FAILED);
        assertThat(result.message()).isEqualTo(DenialCode.WORKER_IN_ZONE.message());
        assertThat(sends()).isZero();
    }

    @Test
    void digestChanged_conflict_noSend() {
        PendingCommand card = card(RESTART);
        conveyors.set(new ConveyorStatus("CV-3", "Z1", RunState.FAULT, FaultClass.NONE));

        CommandResult result = service.confirm(card.id(), OPERATOR);

        assertTerminal(card, result, Outcome.CONFLICT, CommandStatus.FAILED);
        assertThat(sends()).isZero();
    }

    @Test
    void rulesAllowButNewDiversion_conflict_noSend() {
        PendingCommand card = card(DIVERT);
        sorters.set(sorter(60, 20, Map.of("CH-07", "CH-08")));

        CommandResult result = service.confirm(card.id(), OPERATOR);

        assertTerminal(card, result, Outcome.CONFLICT, CommandStatus.FAILED);
        assertThat(result.message()).contains("다시 확인");
        assertThat(sends()).isZero();
    }

    @Test
    void rulesAllowButSourceRobotStateChanged_conflict_noSend() {
        PendingCommand card = card(REASSIGN);
        fleet.set(robot("AMR-01", RunState.DEGRADED, 5));

        CommandResult result = service.confirm(card.id(), OPERATOR);

        assertTerminal(card, result, Outcome.CONFLICT, CommandStatus.FAILED);
        assertThat(result.message()).contains("다시 확인");
        assertThat(sends()).isZero();
    }

    @Test
    void numbersChanged_stillSends() {
        PendingCommand card = card(DIVERT);
        sorters.set(sorter(95, 20, Map.of()));

        assertTerminal(card, service.confirm(card.id(), OPERATOR), Outcome.OK, CommandStatus.SUCCEEDED);
        assertThat(sends()).isEqualTo(1);
    }

    @Test
    void numberCrossesLimit_conflict() {
        PendingCommand card = card(DIVERT);
        sorters.set(sorter(60, 21, Map.of()));

        CommandResult result = service.confirm(card.id(), OPERATOR);

        assertTerminal(card, result, Outcome.CONFLICT, CommandStatus.FAILED);
        assertThat(result.message()).isEqualTo(DenialCode.OVER_CAPACITY.message());
        assertThat(sends()).isZero();
    }

    @Test
    void oldCardAfterRestart_conflict() {
        // 제안 뒤 시뮬레이터가 초기값(가동 중)으로 돌아갔다.
        PendingCommand restart = card(RESTART);
        conveyors.set(new ConveyorStatus("CV-3", "Z1", RunState.RUNNING, FaultClass.NONE));
        assertTerminal(restart, service.confirm(restart.id(), OPERATOR), Outcome.CONFLICT, CommandStatus.FAILED);
        assertThat(sends()).isZero();
    }

    @Test
    void oldDivertCardAfterReset_conflict() {
        // 규칙은 통과하므로 상태 비교만이 막는다.
        sorters.set(sorter(60, 20, Map.of("CH-07", "CH-08")));
        PendingCommand divert = card(DIVERT);
        sorters.set(SORTER);

        CommandResult result = service.confirm(divert.id(), OPERATOR);

        assertTerminal(divert, result, Outcome.CONFLICT, CommandStatus.FAILED);
        assertThat(result.message()).contains("다시 확인");
        assertThat(sends()).isZero();
    }

    @ParameterizedTest
    @MethodSource("allKinds")
    void gatewayDownDuringRecheck_failedNoSend(CommandSpec spec) {
        PendingCommand card = card(spec);
        conveyors.setFailing(true);
        sorters.setFailing(true);
        fleet.setFailing(true);
        safety.setFailing(true);
        desk.setFailing(true);

        assertTerminal(card, service.confirm(card.id(), OPERATOR), Outcome.CONFLICT, CommandStatus.FAILED);
        assertThat(sends()).isZero();
    }

    @Test
    void unexpectedExceptionDuringRecheck_failedNoSend() {
        PendingCommand card = card(DIVERT);
        Sorters broken = new Sorters() {
            @Override
            public SorterStatus get() {
                throw new IllegalStateException("bug");
            }

            @Override
            public void divert(UUID commandId, String fromChute, String toChute) {
                sorters.divert(commandId, fromChute, toChute);
            }
        };

        CommandResult result = service(CLOCK, unitOfWork, broken).confirm(card.id(), OPERATOR);

        assertTerminal(card, result, Outcome.CONFLICT, CommandStatus.FAILED);
        assertThat(sends()).isZero();
    }

    @ParameterizedTest
    @MethodSource("allKinds")
    void success_recordsAudit(CommandSpec spec) {
        PendingCommand card = card(spec);

        assertTerminal(card, service.confirm(card.id(), OPERATOR), Outcome.OK, CommandStatus.SUCCEEDED);
        assertThat(sends()).isEqualTo(1);
    }

    @ParameterizedTest
    @MethodSource("equipmentKinds")
    void sendTimeout_unconfirmed_noRetry(CommandSpec spec) {
        PendingCommand card = card(spec);
        afterAnySend(() -> {
            throw new CommandOutcomeUnknownException("timeout", null);
        });

        CommandResult result = service.confirm(card.id(), OPERATOR);

        assertTerminal(card, result, Outcome.OK, CommandStatus.UNCONFIRMED);
        assertThat(sends()).isEqualTo(1);
        assertThat(result.message()).contains("현장");
    }

    @ParameterizedTest
    @MethodSource("allKinds")
    void sendFails_failed(CommandSpec spec) {
        PendingCommand card = card(spec);
        int[] readsAtSend = new int[1];
        afterAnySend(() -> {
            readsAtSend[0] = equipmentReads();
            throw new GatewayUnavailableException("refused", null);
        });

        assertTerminal(card, service.confirm(card.id(), OPERATOR), Outcome.CONFLICT, CommandStatus.FAILED);
        assertThat(sends()).isEqualTo(1);
        assertThat(equipmentReads()).isEqualTo(readsAtSend[0]);
    }

    @ParameterizedTest
    @MethodSource("allKinds")
    void unexpectedExceptionFromSend_unconfirmed(CommandSpec spec) {
        PendingCommand card = card(spec);
        afterAnySend(() -> {
            throw new IllegalStateException("bug");
        });

        CommandResult result = service.confirm(card.id(), OPERATOR);

        assertTerminal(card, result, Outcome.OK, CommandStatus.UNCONFIRMED);
        assertThat(sends()).isEqualTo(1);
        assertThat(result.message()).contains("현장");
    }

    @ParameterizedTest
    @MethodSource("equipmentKinds")
    void readBackFails_unconfirmed(CommandSpec spec) {
        PendingCommand card = card(spec);
        afterAnySend(() -> {
            conveyors.setFailing(true);
            sorters.setFailing(true);
            fleet.setFailing(true);
        });

        CommandResult result = service.confirm(card.id(), OPERATOR);

        assertTerminal(card, result, Outcome.OK, CommandStatus.UNCONFIRMED);
        assertThat(sends()).isEqualTo(1);
        assertThat(result.message()).contains("현장");
    }

    @Test
    void unexpectedExceptionFromReadBack_unconfirmed() {
        PendingCommand card = card(DIVERT);
        Sorters brokenAfterSend = new Sorters() {
            @Override
            public SorterStatus get() {
                if (!sorters.commandIds().isEmpty()) {
                    throw new IllegalStateException("bug");
                }
                return sorters.get();
            }

            @Override
            public void divert(UUID commandId, String fromChute, String toChute) {
                sorters.divert(commandId, fromChute, toChute);
            }
        };

        CommandResult result = service(CLOCK, unitOfWork, brokenAfterSend).confirm(card.id(), OPERATOR);

        assertTerminal(card, result, Outcome.OK, CommandStatus.UNCONFIRMED);
        assertThat(sends()).isEqualTo(1);
    }

    @Test
    void restartNotYetApplied_unconfirmedNeverFailed() {
        PendingCommand card = card(RESTART);
        conveyors.afterSend(() -> conveyors.set(STOPPED));

        assertTerminal(card, service.confirm(card.id(), OPERATOR), Outcome.OK, CommandStatus.UNCONFIRMED);
        assertThat(sends()).isEqualTo(1);
    }

    @Test
    void divertNotYetApplied_unconfirmedNeverFailed() {
        PendingCommand card = card(DIVERT);
        sorters.afterSend(() -> sorters.set(SORTER));

        assertTerminal(card, service.confirm(card.id(), OPERATOR), Outcome.OK, CommandStatus.UNCONFIRMED);
    }

    @Test
    void divertToOtherChuteIsNotApplied_unconfirmed() {
        PendingCommand card = card(DIVERT);
        sorters.afterSend(() -> sorters.set(sorter(60, 20, Map.of("CH-03", "CH-09"))));

        assertTerminal(card, service.confirm(card.id(), OPERATOR), Outcome.OK, CommandStatus.UNCONFIRMED);
    }

    @Test
    void reassignNotYetApplied_unconfirmedNeverFailed() {
        PendingCommand card = card(REASSIGN);
        fleet.afterSend(() -> {
            fleet.set(SOURCE);
            fleet.set(TARGET);
        });

        assertTerminal(card, service.confirm(card.id(), OPERATOR), Outcome.OK, CommandStatus.UNCONFIRMED);
    }

    @Test
    void reassignPartlyDrained_succeeded() {
        PendingCommand card = card(REASSIGN);
        fleet.afterSend(() -> fleet.set(robot("AMR-01", RunState.RUNNING, 4)));

        assertTerminal(card, service.confirm(card.id(), OPERATOR), Outcome.OK, CommandStatus.SUCCEEDED);
    }

    @Test
    void reassignBaselineIsTheRecheckNotTheProposal() {
        fleet.set(robot("AMR-01", RunState.RUNNING, 3));
        PendingCommand card = card(REASSIGN);
        // 제안 뒤 큐가 늘었다. 전송 뒤 제안 때와 같은 3건이어도 재평가 때의 7건보다 줄었으므로 반영으로 본다.
        fleet.set(robot("AMR-01", RunState.RUNNING, 7));
        fleet.afterSend(() -> fleet.set(robot("AMR-01", RunState.RUNNING, 3)));

        assertTerminal(card, service.confirm(card.id(), OPERATOR), Outcome.OK, CommandStatus.SUCCEEDED);
    }

    @Test
    void commandIdPassedToGateway() {
        PendingCommand restart = card(RESTART);
        PendingCommand divert = card(DIVERT);
        PendingCommand reassign = card(REASSIGN);

        service.confirm(restart.id(), OPERATOR);
        service.confirm(divert.id(), OPERATOR);
        service.confirm(reassign.id(), OPERATOR);

        assertThat(conveyors.commandIds()).containsExactly(restart.id());
        assertThat(sorters.commandIds()).containsExactly(divert.id());
        assertThat(fleet.commandIds()).containsExactly(reassign.id());
    }

    @Test
    void maintenance_opensRequestWithoutSafetyCheck() {
        PendingCommand card = card(MAINTENANCE);
        safety.setFailing(true);
        conveyors.setFailing(true);
        sorters.setFailing(true);
        fleet.setFailing(true);
        int deskReads = desk.reads();

        assertTerminal(card, service.confirm(card.id(), OPERATOR), Outcome.OK, CommandStatus.SUCCEEDED);

        assertThat(safety.reads()).isZero();
        assertThat(desk.reads()).isEqualTo(deskReads + 1);
        assertThat(desk.opens()).isEqualTo(1);
        assertThat(desk.openRequests()).singleElement().satisfies(r -> {
            assertThat(r.equipmentId()).isEqualTo("SR-1");
            assertThat(r.summary()).isEqualTo("벨트 소음 점검");
            assertThat(r.openedAt()).isEqualTo(NOW);
        });
    }

    @Test
    void maintenanceOpenedMeanwhile_conflictNoSecondRequest() {
        PendingCommand card = card(MAINTENANCE);
        desk.open("SR-1", "다른 사람의 요청", NOW);

        CommandResult result = service.confirm(card.id(), OPERATOR);

        assertTerminal(card, result, Outcome.CONFLICT, CommandStatus.FAILED);
        assertThat(result.message()).isEqualTo(DenialCode.MAINTENANCE_ALREADY_OPEN.message());
        assertThat(desk.opens()).isEqualTo(1);
    }

    @ParameterizedTest
    @MethodSource("allKinds")
    void reject_marksRejected_noSend(CommandSpec spec) {
        PendingCommand card = card(spec);
        int readsAfterProposal = equipmentReads();

        assertTerminal(card, service.reject(card.id(), OPERATOR), Outcome.OK, CommandStatus.REJECTED);

        assertThat(sends()).isZero();
        assertThat(equipmentReads()).isEqualTo(readsAfterProposal);
    }

    @Test
    void rejectByOtherAccountOrViewer_forbidden() {
        PendingCommand card = card(RESTART);

        assertRefusedUntouched(card, service.reject(card.id(), new Actor("op-2", Role.OPERATOR)), Outcome.FORBIDDEN);
        assertRefusedUntouched(card, service.reject(card.id(), new Actor("op-1", Role.VIEWER)), Outcome.FORBIDDEN);
    }

    @Test
    void rejectAfterConfirm_conflictKeepsResult() {
        PendingCommand card = card(RESTART);
        service.confirm(card.id(), OPERATOR);

        CommandResult result = service.reject(card.id(), OPERATOR);

        assertThat(result.outcome()).isEqualTo(Outcome.CONFLICT);
        assertThat(result.status()).isEqualTo(CommandStatus.SUCCEEDED);
        assertThat(audit.entries()).hasSize(1);
    }

    @Test
    void confirmAfterReject_conflict_noSend() {
        PendingCommand card = card(RESTART);
        service.reject(card.id(), OPERATOR);

        CommandResult result = service.confirm(card.id(), OPERATOR);

        assertThat(result.outcome()).isEqualTo(Outcome.CONFLICT);
        assertThat(result.status()).isEqualTo(CommandStatus.REJECTED);
        assertThat(sends()).isZero();
    }

    @Test
    void rejectExpired_conflict() {
        PendingCommand card = card(RESTART);

        CommandResult result = service(Clock.offset(CLOCK, TTL), unitOfWork, sorters).reject(card.id(), OPERATOR);

        assertThat(result.outcome()).isEqualTo(Outcome.CONFLICT);
        assertThat(result.status()).isEqualTo(CommandStatus.EXPIRED);
        assertThat(audit.entries()).isEmpty();
    }

    /** 트랜잭션마다 시작과 끝의 조회, 전송 횟수를 남긴다. 안에서 설비를 건드리면 둘이 달라진다. */
    private final class CheckingUnitOfWork implements UnitOfWork {
        final List<CommandStatus> rowAtEntry = new ArrayList<>();
        final List<Integer> sendsAtEntry = new ArrayList<>();
        private final PendingCommand card;

        CheckingUnitOfWork(PendingCommand card) {
            this.card = card;
        }

        @Override
        public <T> T inTransaction(Supplier<T> work) {
            rowAtEntry.add(rowStatus(card));
            sendsAtEntry.add(sends());
            int reads = equipmentReads();
            int sends = sends();
            T result = work.get();
            assertThat(equipmentReads()).isEqualTo(reads);
            assertThat(sends()).isEqualTo(sends);
            return result;
        }
    }

    @ParameterizedTest
    @MethodSource("allKinds")
    void claimAndRecordAreSeparateTransactionsAroundTheSend(CommandSpec spec) {
        PendingCommand card = card(spec);
        CheckingUnitOfWork checking = new CheckingUnitOfWork(card);

        service(CLOCK, checking, sorters).confirm(card.id(), OPERATOR);

        assertThat(checking.rowAtEntry).containsExactly(CommandStatus.PENDING, CommandStatus.EXECUTING);
        assertThat(checking.sendsAtEntry).containsExactly(0, 1);
    }

    @Test
    void refusedAfterClaimStillRecordsInItsOwnTransaction() {
        PendingCommand card = card(RESTART);
        safety.set(new SafetyStatus("Z1", false, true, NOW));
        CheckingUnitOfWork checking = new CheckingUnitOfWork(card);

        service(CLOCK, checking, sorters).confirm(card.id(), OPERATOR);

        assertThat(checking.rowAtEntry).containsExactly(CommandStatus.PENDING, CommandStatus.EXECUTING);
        assertThat(checking.sendsAtEntry).containsExactly(0, 0);
    }

    @Test
    void rowIsExecutingDuringTheSend() {
        PendingCommand card = card(RESTART);
        List<CommandStatus> duringSend = new ArrayList<>();
        afterAnySend(() -> duringSend.add(rowStatus(card)));

        service.confirm(card.id(), OPERATOR);

        assertThat(duringSend).containsExactly(CommandStatus.EXECUTING);
    }

    @Test
    void rejectIsOneTransaction() {
        PendingCommand card = card(RESTART);
        CheckingUnitOfWork checking = new CheckingUnitOfWork(card);

        service(CLOCK, checking, sorters).reject(card.id(), OPERATOR);

        assertThat(checking.rowAtEntry).containsExactly(CommandStatus.PENDING);
        assertThat(rowStatus(card)).isEqualTo(CommandStatus.REJECTED);
        assertThat(audit.entries()).hasSize(1);
    }
}
