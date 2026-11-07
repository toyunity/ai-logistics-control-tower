package com.example.controltower.application.service;

import com.example.controltower.application.port.out.Conveyors;
import com.example.controltower.application.port.out.MaintenanceDesk;
import com.example.controltower.application.port.out.RobotFleet;
import com.example.controltower.application.port.out.SafetySignals;
import com.example.controltower.application.port.out.Sorters;
import com.example.controltower.domain.ChuteStatus;
import com.example.controltower.domain.CommandSpec;
import com.example.controltower.domain.ConveyorStatus;
import com.example.controltower.domain.Decision;
import com.example.controltower.domain.DenialCode;
import com.example.controltower.domain.GatewayUnavailableException;
import com.example.controltower.domain.LineTopology;
import com.example.controltower.domain.RobotStatus;
import com.example.controltower.domain.SafetyPolicy;
import com.example.controltower.domain.SafetyStatus;
import com.example.controltower.domain.SorterStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 제안과 확정이 같은 방식으로 설비를 새로 읽어 규칙을 평가하게 한다. 조회 실패는 거절로 돌려준다.
 * 영향 문구에는 서버가 읽은 값과 토폴로지로 검증된 ID만 넣는다. 모델이 쓴 요약은 넣지 않는다.
 */
final class CommandEvaluator {

    /** sourceQueuedTasks는 재배정이 반영됐는지 볼 기준값이고 재배정 외에는 0이다. 거절이면 digest와 impact는 비어 있다. */
    record Evaluation(Decision decision, String digest, String impact, Instant evaluatedAt, int sourceQueuedTasks) {}

    private final Conveyors conveyors;
    private final Sorters sorters;
    private final RobotFleet fleet;
    private final SafetySignals safety;
    private final MaintenanceDesk desk;
    private final SafetyPolicy policy;
    private final LineTopology topology;
    private final Clock clock;

    CommandEvaluator(
            Conveyors conveyors,
            Sorters sorters,
            RobotFleet fleet,
            SafetySignals safety,
            MaintenanceDesk desk,
            SafetyPolicy policy,
            LineTopology topology,
            Clock clock) {
        this.conveyors = conveyors;
        this.sorters = sorters;
        this.fleet = fleet;
        this.safety = safety;
        this.desk = desk;
        this.policy = policy;
        this.topology = topology;
        this.clock = clock;
    }

    /** spec의 대상은 토폴로지에 있어야 한다. */
    Evaluation evaluate(CommandSpec spec) {
        return switch (spec) {
            case CommandSpec.Restart restart -> restart(restart);
            case CommandSpec.Divert divert -> divert(divert);
            case CommandSpec.Reassign reassign -> reassign(reassign);
            case CommandSpec.Maintenance maintenance -> maintenance(maintenance);
        };
    }

    private Evaluation restart(CommandSpec.Restart spec) {
        String zone = topology.zoneOf(spec.conveyorId()).orElseThrow();
        ConveyorStatus conveyor = null;
        SorterStatus sorter = null;
        try {
            conveyor = conveyors.get(spec.conveyorId());
            sorter = sorters.get();
        } catch (GatewayUnavailableException e) {
            // 거절 사유를 정하려면 안전 신호도 읽어야 하므로 아래에서 판단한다.
        }
        // 설비 조회가 느려도 신호의 신선도를 쓰지 않도록 안전 신호를 마지막에 읽는다.
        Optional<SafetyStatus> signal = readSafety(zone);
        if (conveyor == null || sorter == null) {
            // 둘 다 실패하면 규칙과 같이 안전 사유를 먼저 보인다.
            return denied(signal.isEmpty() ? DenialCode.SAFETY_SIGNAL_UNKNOWN : DenialCode.STATE_UNKNOWN);
        }
        // 조회가 늦어진 만큼 신호 나이에 반영되도록 조회 뒤의 시각으로 평가한다.
        Instant now = clock.instant();
        Decision decision = policy.restart(conveyor, sorter, signal, now);
        if (decision instanceof Decision.Denied) {
            return new Evaluation(decision, "", "", now, 0);
        }
        String impact = spec.conveyorId() + " 재가동, 구역 " + conveyor.zone();
        return new Evaluation(decision, StateDigest.of(spec, conveyor, sorter), impact, now, 0);
    }

    private Optional<SafetyStatus> readSafety(String zone) {
        try {
            return Optional.of(safety.read(zone));
        } catch (GatewayUnavailableException e) {
            return Optional.empty();
        }
    }

    private Evaluation divert(CommandSpec.Divert spec) {
        SorterStatus sorter;
        try {
            sorter = sorters.get();
        } catch (GatewayUnavailableException e) {
            return denied(DenialCode.STATE_UNKNOWN);
        }
        Decision decision = policy.divert(sorter, spec.fromChute(), spec.toChute());
        if (decision instanceof Decision.Denied) {
            return new Evaluation(decision, "", "", clock.instant(), 0);
        }
        ChuteStatus from = StateDigest.chute(sorter, spec.fromChute());
        ChuteStatus to = StateDigest.chute(sorter, spec.toChute());
        String impact = "%s 물량 %d개/분을 %s로 보냄, %s 예상 부하 %d/%d".formatted(
                from.id(), from.ratePerMin(), to.id(), to.id(), from.ratePerMin() + to.ratePerMin(), to.capacityPerMin());
        return new Evaluation(decision, StateDigest.of(spec, sorter), impact, clock.instant(), 0);
    }

    private Evaluation reassign(CommandSpec.Reassign spec) {
        List<RobotStatus> robots;
        try {
            robots = fleet.all();
        } catch (GatewayUnavailableException e) {
            return denied(DenialCode.STATE_UNKNOWN);
        }
        Optional<RobotStatus> source = robot(robots, spec.fromRobot());
        Optional<RobotStatus> target = robot(robots, spec.toRobot());
        if (source.isEmpty() || target.isEmpty()) {
            // 토폴로지에는 있는데 관제 시스템이 보고하지 않았다. 모르면 막는다.
            return denied(DenialCode.STATE_UNKNOWN);
        }
        RobotStatus from = source.get();
        RobotStatus to = target.get();
        Decision decision = policy.reassign(from, to);
        if (decision instanceof Decision.Denied) {
            return new Evaluation(decision, "", "", clock.instant(), 0);
        }
        String impact = "%s 작업 %d건을 %s로 재배정, %s 현재 큐 %d/%d".formatted(
                from.id(), from.queuedTasks(), to.id(), to.id(), to.queuedTasks(), to.queueCapacity());
        return new Evaluation(decision, StateDigest.of(spec, from, to), impact, clock.instant(), from.queuedTasks());
    }

    static Optional<RobotStatus> robot(List<RobotStatus> robots, String id) {
        return robots.stream().filter(r -> r.id().equals(id)).findFirst();
    }

    private Evaluation maintenance(CommandSpec.Maintenance spec) {
        boolean open;
        try {
            open = desk.hasOpen(spec.equipmentId());
        } catch (GatewayUnavailableException e) {
            return denied(DenialCode.STATE_UNKNOWN);
        }
        if (open) {
            return denied(DenialCode.MAINTENANCE_ALREADY_OPEN);
        }
        return new Evaluation(
                new Decision.Allowed(), StateDigest.of(spec), "설비를 움직이지 않는 정비 요청 등록", clock.instant(), 0);
    }

    private Evaluation denied(DenialCode code) {
        return new Evaluation(new Decision.Denied(code), "", "", clock.instant(), 0);
    }
}
