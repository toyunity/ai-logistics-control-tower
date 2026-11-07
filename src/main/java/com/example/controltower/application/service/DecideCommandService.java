package com.example.controltower.application.service;

import com.example.controltower.application.port.in.CommandResult;
import com.example.controltower.application.port.in.CommandResult.Outcome;
import com.example.controltower.application.port.in.DecideCommand;
import com.example.controltower.application.port.out.CommandAudit;
import com.example.controltower.application.port.out.Conveyors;
import com.example.controltower.application.port.out.MaintenanceDesk;
import com.example.controltower.application.port.out.PendingCommands;
import com.example.controltower.application.port.out.RobotFleet;
import com.example.controltower.application.port.out.SafetySignals;
import com.example.controltower.application.port.out.Sorters;
import com.example.controltower.application.port.out.UnitOfWork;
import com.example.controltower.application.service.CommandEvaluator.Evaluation;
import com.example.controltower.domain.Actor;
import com.example.controltower.domain.CommandOutcomeUnknownException;
import com.example.controltower.domain.CommandSpec;
import com.example.controltower.domain.CommandStatus;
import com.example.controltower.domain.Decision;
import com.example.controltower.domain.DenialCode;
import com.example.controltower.domain.GatewayUnavailableException;
import com.example.controltower.domain.LineTopology;
import com.example.controltower.domain.PendingCommand;
import com.example.controltower.domain.Role;
import com.example.controltower.domain.RunState;
import com.example.controltower.domain.SafetyPolicy;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 설비로 명령을 보내는 유일한 경로다. 사람이 버튼을 눌러야 들어오며 AI를 거치지 않는다.
 * 재시도는 어디에도 없다. 도달 여부를 모르는 명령을 다시 보내면 설비가 두 번 움직일 수 있다.
 */
public final class DecideCommandService implements DecideCommand {

    private static final Logger log = System.getLogger(DecideCommandService.class.getName());

    private static final String DONE = "명령을 처리했습니다.";
    private static final String REJECTED = "명령을 거절했습니다.";
    private static final String NOT_FOUND = "명령을 찾을 수 없습니다.";
    private static final String EXPIRED = "유효 시간이 지난 명령입니다. 다시 제안받아 주세요.";
    private static final String ALREADY_DECIDED = "이미 처리되었거나 지금 실행할 수 없는 명령입니다.";
    private static final String STATE_CHANGED = "제안 후 설비 상태가 바뀌어 보내지 않았습니다. 상태를 다시 확인해 주세요.";
    private static final String RECHECK_FAILED = "설비 상태를 다시 확인하지 못해 명령을 보내지 않았습니다.";
    private static final String SEND_FAILED = "설비에 명령을 보내지 못했습니다.";
    private static final String CHECK_ON_SITE = "명령 반영 여부를 확인하지 못했습니다. 다시 보내지 말고 현장에서 설비 상태를 확인해 주세요.";

    private final Conveyors conveyors;
    private final Sorters sorters;
    private final RobotFleet fleet;
    private final PendingCommands pending;
    private final MaintenanceDesk desk;
    private final CommandAudit audit;
    private final UnitOfWork unitOfWork;
    private final CommandEvaluator evaluator;
    private final Clock clock;

    public DecideCommandService(
            Conveyors conveyors,
            Sorters sorters,
            RobotFleet fleet,
            SafetySignals safety,
            PendingCommands pending,
            MaintenanceDesk desk,
            CommandAudit audit,
            UnitOfWork unitOfWork,
            SafetyPolicy policy,
            LineTopology topology,
            Clock clock) {
        this.conveyors = conveyors;
        this.sorters = sorters;
        this.fleet = fleet;
        this.pending = pending;
        this.desk = desk;
        this.audit = audit;
        this.unitOfWork = unitOfWork;
        this.evaluator = new CommandEvaluator(conveyors, sorters, fleet, safety, desk, policy, topology, clock);
        this.clock = clock;
    }

    @Override
    public CommandResult confirm(UUID id, Actor actor) {
        Optional<PendingCommand> found = pending.find(id);
        if (found.isEmpty()) {
            return new CommandResult(Outcome.NOT_FOUND, null, NOT_FOUND);
        }
        PendingCommand card = found.get();
        if (!mayDecide(card, actor)) {
            return forbidden();
        }
        // 트랜잭션을 기다리는 동안 만료될 수 있으므로 시각은 안에서 읽는다.
        boolean claimed = unitOfWork.inTransaction(
                () -> pending.transition(id, CommandStatus.PENDING, CommandStatus.EXECUTING, clock.instant()));
        return claimed ? execute(card) : conflict(card);
    }

    @Override
    public CommandResult reject(UUID id, Actor actor) {
        Optional<PendingCommand> found = pending.find(id);
        if (found.isEmpty()) {
            return new CommandResult(Outcome.NOT_FOUND, null, NOT_FOUND);
        }
        PendingCommand card = found.get();
        if (!mayDecide(card, actor)) {
            return forbidden();
        }
        boolean rejected = unitOfWork.inTransaction(() -> {
            Instant now = clock.instant();
            boolean moved = pending.transition(id, CommandStatus.PENDING, CommandStatus.REJECTED, now);
            if (moved) {
                audit.record(id, card.accountId(), card.spec(), CommandStatus.REJECTED, now);
            }
            return moved;
        });
        return rejected ? new CommandResult(Outcome.OK, CommandStatus.REJECTED, REJECTED) : conflict(card);
    }

    // 제안 뒤 강등된 계정을 막으려고 카드 주인이어도 역할을 다시 본다.
    private static boolean mayDecide(PendingCommand card, Actor actor) {
        return actor != null && actor.role() == Role.OPERATOR && card.accountId().equals(actor.accountId());
    }

    private static CommandResult forbidden() {
        return new CommandResult(Outcome.FORBIDDEN, null, DenialCode.NOT_PERMITTED.message());
    }

    private CommandResult conflict(PendingCommand card) {
        Instant now = clock.instant();
        PendingCommand current = pending.find(card.id()).orElse(card);
        // 조건부 갱신은 만료된 행을 바꾸지 않으므로 같은 대상의 새 제안이 EXPIRED로 닫기 전까지 PENDING으로 남아 있다.
        if (current.status() == CommandStatus.EXPIRED
                || (current.status() == CommandStatus.PENDING && !current.expiresAt().isAfter(now))) {
            return new CommandResult(Outcome.CONFLICT, CommandStatus.EXPIRED, EXPIRED);
        }
        return new CommandResult(Outcome.CONFLICT, current.status(), ALREADY_DECIDED);
    }

    private CommandResult execute(PendingCommand card) {
        Evaluation evaluation;
        try {
            evaluation = evaluator.evaluate(card.spec());
        } catch (RuntimeException e) {
            log.log(Level.ERROR, "unexpected failure during recheck: " + card.id(), e);
            return refuse(card, RECHECK_FAILED);
        }
        if (evaluation.decision() instanceof Decision.Denied(DenialCode code)) {
            return refuse(card, code.message());
        }
        // 규칙이 허용한 뒤에만 비교한다. 거절 사유가 "상태 변경"보다 구체적이다.
        if (!evaluation.digest().equals(card.stateDigest())) {
            return refuse(card, STATE_CHANGED);
        }
        return sendAndVerify(card, evaluation);
    }

    private CommandResult sendAndVerify(PendingCommand card, Evaluation evaluation) {
        try {
            send(card);
        } catch (CommandOutcomeUnknownException e) {
            return unconfirmed(card);
        } catch (GatewayUnavailableException e) {
            return afterSend(card, CommandStatus.FAILED, Outcome.CONFLICT, SEND_FAILED);
        } catch (RuntimeException e) {
            log.log(Level.ERROR, "unexpected failure during send: " + card.id(), e);
            return unconfirmed(card);
        }
        boolean applied;
        try {
            applied = isApplied(card.spec(), evaluation);
        } catch (GatewayUnavailableException e) {
            return unconfirmed(card);
        } catch (RuntimeException e) {
            log.log(Level.ERROR, "unexpected failure during read-back: " + card.id(), e);
            return unconfirmed(card);
        }
        // 미반영을 FAILED로 적지 않는다. 늦게 반영될 수 있는데 실패로 보이면 다시 보내게 된다.
        return applied ? afterSend(card, CommandStatus.SUCCEEDED, Outcome.OK, DONE) : unconfirmed(card);
    }

    private void send(PendingCommand card) {
        switch (card.spec()) {
            case CommandSpec.Restart s -> conveyors.restart(card.id(), s.conveyorId());
            case CommandSpec.Divert s -> sorters.divert(card.id(), s.fromChute(), s.toChute());
            case CommandSpec.Reassign s -> fleet.reassign(card.id(), s.fromRobot(), s.toRobot());
            case CommandSpec.Maintenance s -> desk.open(s.equipmentId(), s.summary(), clock.instant());
        }
    }

    private boolean isApplied(CommandSpec spec, Evaluation evaluation) {
        return switch (spec) {
            case CommandSpec.Restart s -> conveyors.get(s.conveyorId()).state() == RunState.RUNNING;
            case CommandSpec.Divert s -> s.toChute().equals(sorters.get().diversions().get(s.fromChute()));
            // 한계: 로봇이 작업을 끝내 큐가 줄어도 반영으로 본다. 설비가 명령 ID 처리 결과를 주면 그것으로 바꾼다.
            case CommandSpec.Reassign s -> CommandEvaluator.robot(fleet.all(), s.fromRobot())
                    .map(r -> r.queuedTasks() == 0 || r.queuedTasks() < evaluation.sourceQueuedTasks())
                    .orElse(false);
            // 정비 요청은 설비를 움직이지 않고 등록 자체가 결과다.
            case CommandSpec.Maintenance s -> true;
        };
    }

    private CommandResult unconfirmed(PendingCommand card) {
        return afterSend(card, CommandStatus.UNCONFIRMED, Outcome.OK, CHECK_ON_SITE);
    }

    private CommandResult refuse(PendingCommand card, String message) {
        record(card, CommandStatus.FAILED);
        return new CommandResult(Outcome.CONFLICT, CommandStatus.FAILED, message);
    }

    // 기록을 못 하면 설비가 움직였을 수 있는데 결과가 남지 않으므로 어떤 결과든 현장 확인으로 안내한다.
    private CommandResult afterSend(PendingCommand card, CommandStatus status, Outcome outcome, String message) {
        return record(card, status)
                ? new CommandResult(outcome, status, message)
                : new CommandResult(Outcome.OK, CommandStatus.UNCONFIRMED, CHECK_ON_SITE);
    }

    // 실패하면 행은 EXECUTING으로 남고 기동 시 정리가 UNCONFIRMED로 닫는다. 예외 내용에 SQL 값이 섞일 수 있어 로그에 넣지 않는다.
    private boolean record(PendingCommand card, CommandStatus status) {
        try {
            unitOfWork.inTransaction(() -> {
                Instant now = clock.instant();
                // 다른 인스턴스의 기동 정리가 먼저 UNCONFIRMED로 닫았어도 실제 결과는 감사에 남긴다.
                pending.transition(card.id(), CommandStatus.EXECUTING, status, now);
                audit.record(card.id(), card.accountId(), card.spec(), status, now);
                return null;
            });
            return true;
        } catch (RuntimeException e) {
            log.log(Level.ERROR, "failed to record command result: " + card.id() + " " + status);
            return false;
        }
    }
}
