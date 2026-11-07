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
import com.example.controltower.domain.ChuteStatus;
import com.example.controltower.domain.CommandSpec;
import com.example.controltower.domain.CommandStatus;
import com.example.controltower.domain.ConveyorStatus;
import com.example.controltower.domain.Decision;
import com.example.controltower.domain.DenialCode;
import com.example.controltower.domain.GatewayUnavailableException;
import com.example.controltower.domain.LineTopology;
import com.example.controltower.domain.PendingCommand;
import com.example.controltower.domain.RobotStatus;
import com.example.controltower.domain.Role;
import com.example.controltower.domain.SafetyPolicy;
import com.example.controltower.domain.SafetyStatus;
import com.example.controltower.domain.SorterStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 도구 호출로 일어나는 쓰기는 대기 카드 저장뿐이다. 이 클래스는 전송 메서드와 MaintenanceDesk.open을 부르지 않는다.
 * 영향 문구에는 서버가 읽은 값과 토폴로지로 검증된 ID만 넣는다. 모델이 쓴 요약은 넣지 않는다.
 */
public final class ProposeCommandService implements ProposeCommand {

    private final Conveyors conveyors;
    private final Sorters sorters;
    private final RobotFleet fleet;
    private final SafetySignals safety;
    private final PendingCommands pending;
    private final MaintenanceDesk desk;
    private final UnitOfWork unitOfWork;
    private final SafetyPolicy policy;
    private final LineTopology topology;
    private final Clock clock;
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
        this.conveyors = conveyors;
        this.sorters = sorters;
        this.fleet = fleet;
        this.safety = safety;
        this.pending = pending;
        this.desk = desk;
        this.unitOfWork = unitOfWork;
        this.policy = policy;
        this.topology = topology;
        this.clock = clock;
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
        return switch (spec) {
            case CommandSpec.Restart restart -> restart(restart, actor.accountId());
            case CommandSpec.Divert divert -> divert(divert, actor.accountId());
            case CommandSpec.Reassign reassign -> reassign(reassign, actor.accountId());
            case CommandSpec.Maintenance maintenance -> maintenance(maintenance, actor.accountId());
        };
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

    private ProposalResult restart(CommandSpec.Restart spec, String accountId) {
        Optional<SafetyStatus> signal = readSafety(topology.zoneOf(spec.conveyorId()).orElseThrow());
        ConveyorStatus conveyor;
        SorterStatus sorter;
        try {
            conveyor = conveyors.get(spec.conveyorId());
            sorter = sorters.get();
        } catch (GatewayUnavailableException e) {
            // 둘 다 실패하면 규칙과 같이 안전 사유를 먼저 보인다.
            return new Rejected(signal.isEmpty() ? DenialCode.SAFETY_SIGNAL_UNKNOWN : DenialCode.STATE_UNKNOWN);
        }
        // 조회가 늦어진 만큼 신호 나이에 반영되도록 조회 뒤의 시각으로 평가한다.
        Instant now = clock.instant();
        if (policy.restart(conveyor, sorter, signal, now) instanceof Decision.Denied(DenialCode code)) {
            return new Rejected(code);
        }
        String impact = spec.conveyorId() + " 재가동, 구역 " + conveyor.zone();
        return store(spec, accountId, StateDigest.of(spec, conveyor, sorter), impact, now);
    }

    private Optional<SafetyStatus> readSafety(String zone) {
        try {
            return Optional.of(safety.read(zone));
        } catch (GatewayUnavailableException e) {
            return Optional.empty();
        }
    }

    private ProposalResult divert(CommandSpec.Divert spec, String accountId) {
        SorterStatus sorter;
        try {
            sorter = sorters.get();
        } catch (GatewayUnavailableException e) {
            return new Rejected(DenialCode.STATE_UNKNOWN);
        }
        if (policy.divert(sorter, spec.fromChute(), spec.toChute()) instanceof Decision.Denied(DenialCode code)) {
            return new Rejected(code);
        }
        ChuteStatus from = StateDigest.chute(sorter, spec.fromChute());
        ChuteStatus to = StateDigest.chute(sorter, spec.toChute());
        String impact = "%s 물량 %d개/분을 %s로 보냄, %s 예상 부하 %d/%d".formatted(
                from.id(), from.ratePerMin(), to.id(), to.id(), from.ratePerMin() + to.ratePerMin(), to.capacityPerMin());
        return store(spec, accountId, StateDigest.of(spec, sorter), impact, clock.instant());
    }

    private ProposalResult reassign(CommandSpec.Reassign spec, String accountId) {
        List<RobotStatus> robots;
        try {
            robots = fleet.all();
        } catch (GatewayUnavailableException e) {
            return new Rejected(DenialCode.STATE_UNKNOWN);
        }
        Optional<RobotStatus> source = robot(robots, spec.fromRobot());
        Optional<RobotStatus> target = robot(robots, spec.toRobot());
        if (source.isEmpty() || target.isEmpty()) {
            // 토폴로지에는 있는데 관제 시스템이 보고하지 않았다. 모르면 막는다.
            return new Rejected(DenialCode.STATE_UNKNOWN);
        }
        RobotStatus from = source.get();
        RobotStatus to = target.get();
        if (policy.reassign(from, to) instanceof Decision.Denied(DenialCode code)) {
            return new Rejected(code);
        }
        String impact = "%s 작업 %d건을 %s로 재배정, %s 현재 큐 %d/%d".formatted(
                from.id(), from.queuedTasks(), to.id(), to.id(), to.queuedTasks(), to.queueCapacity());
        return store(spec, accountId, StateDigest.of(spec, from, to), impact, clock.instant());
    }

    private static Optional<RobotStatus> robot(List<RobotStatus> robots, String id) {
        return robots.stream().filter(r -> r.id().equals(id)).findFirst();
    }

    private ProposalResult maintenance(CommandSpec.Maintenance spec, String accountId) {
        boolean open;
        try {
            open = desk.hasOpen(spec.equipmentId());
        } catch (GatewayUnavailableException e) {
            return new Rejected(DenialCode.STATE_UNKNOWN);
        }
        if (open) {
            return new Rejected(DenialCode.MAINTENANCE_ALREADY_OPEN);
        }
        return store(spec, accountId, StateDigest.of(spec), "설비를 움직이지 않는 정비 요청 등록", clock.instant());
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
