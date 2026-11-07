package com.example.controltower.application.service;

import com.example.controltower.application.port.in.ProposalResult;
import com.example.controltower.application.port.in.ProposalResult.Proposed;
import com.example.controltower.application.port.in.ProposalResult.Rejected;
import com.example.controltower.application.port.in.ProposeCommand;
import com.example.controltower.application.port.out.Conveyors;
import com.example.controltower.application.port.out.MaintenanceDesk;
import com.example.controltower.application.port.out.PendingCommands;
import com.example.controltower.application.port.out.RobotFleet;
import com.example.controltower.application.port.out.SafetySignals;
import com.example.controltower.application.port.out.Sorters;
import com.example.controltower.application.port.out.UnitOfWork;
import com.example.controltower.domain.Actor;
import com.example.controltower.domain.CommandSpec;
import com.example.controltower.domain.CommandStatus;
import com.example.controltower.domain.Decision;
import com.example.controltower.domain.DenialCode;
import com.example.controltower.domain.LineTopology;
import com.example.controltower.domain.PendingCommand;
import com.example.controltower.domain.Role;
import com.example.controltower.domain.SafetyPolicy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 도구 호출로 일어나는 쓰기는 대기 카드 저장뿐이다. 이 클래스는 전송 메서드와 MaintenanceDesk.open을 부르지 않는다.
 */
public final class ProposeCommandService implements ProposeCommand {

    private final PendingCommands pending;
    private final UnitOfWork unitOfWork;
    private final CommandEvaluator evaluator;
    private final LineTopology topology;
    private final Duration ttl;

    public ProposeCommandService(
            Conveyors conveyors,
            Sorters sorters,
            RobotFleet fleet,
            SafetySignals safety,
            PendingCommands pending,
            MaintenanceDesk desk,
            UnitOfWork unitOfWork,
            SafetyPolicy policy,
            LineTopology topology,
            Clock clock,
            Duration ttl) {
        this.pending = pending;
        this.unitOfWork = unitOfWork;
        this.evaluator = new CommandEvaluator(conveyors, sorters, fleet, safety, desk, policy, topology, clock);
        this.topology = topology;
        this.ttl = ttl;
    }

    @Override
    public ProposalResult propose(CommandSpec spec, Actor actor) {
        Objects.requireNonNull(spec);
        if (!canPropose(actor)) {
            return new Rejected(DenialCode.NOT_PERMITTED);
        }
        if (!targetsExist(spec)) {
            return new Rejected(DenialCode.NOT_FOUND);
        }
        CommandEvaluator.Evaluation evaluation = evaluator.evaluate(spec);
        if (evaluation.decision() instanceof Decision.Denied(DenialCode code)) {
            return new Rejected(code);
        }
        return store(spec, actor.accountId(), evaluation.digest(), evaluation.impact(), evaluation.evaluatedAt());
    }

    // 계정 없는 OPERATOR는 카드 주인을 정할 수 없으므로 권한 없음으로 막는다.
    private static boolean canPropose(Actor actor) {
        return actor != null && actor.role() == Role.OPERATOR && actor.accountId() != null && !actor.accountId().isBlank();
    }

    private boolean targetsExist(CommandSpec spec) {
        return switch (spec) {
            case CommandSpec.Restart s -> topology.isConveyor(s.conveyorId());
            case CommandSpec.Divert s -> topology.isChute(s.fromChute()) && topology.isChute(s.toChute());
            case CommandSpec.Reassign s -> topology.isRobot(s.fromRobot()) && topology.isRobot(s.toRobot());
            case CommandSpec.Maintenance s -> topology.contains(s.equipmentId());
        };
    }

    private ProposalResult store(CommandSpec spec, String accountId, String digest, String impact, Instant now) {
        return new Proposed(unitOfWork.inTransaction(() -> {
            Optional<PendingCommand> live = pending.findLive(accountId, spec.kind(), spec.targetId(), now);
            if (live.isPresent()) {
                PendingCommand card = live.get();
                if (card.spec().equals(spec) && card.stateDigest().equals(digest)) {
                    return card;
                }
                pending.transition(card.id(), CommandStatus.PENDING, CommandStatus.SUPERSEDED, now);
            }
            PendingCommand card = new PendingCommand(
                    UUID.randomUUID(), accountId, spec, digest, impact, CommandStatus.PENDING, now, now.plus(ttl));
            pending.save(card);
            return card;
        }));
    }
}
