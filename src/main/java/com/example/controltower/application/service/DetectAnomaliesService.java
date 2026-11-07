package com.example.controltower.application.service;

import com.example.controltower.application.port.in.DetectAnomalies;
import com.example.controltower.application.port.out.Conveyors;
import com.example.controltower.application.port.out.EventLog;
import com.example.controltower.application.port.out.Incidents;
import com.example.controltower.application.port.out.RobotFleet;
import com.example.controltower.application.port.out.Sorters;
import com.example.controltower.domain.ChuteStatus;
import com.example.controltower.domain.ConveyorStatus;
import com.example.controltower.domain.Incident;
import com.example.controltower.domain.RobotStatus;
import com.example.controltower.domain.RunState;
import com.example.controltower.domain.Severity;
import com.example.controltower.domain.SeverityPolicy;
import com.example.controltower.domain.SorterStatus;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 읽기에 실패한 설비군과 상태를 모르는 설비의 장애 건은 열지도 닫지도 않는다.
 * 모르는 것을 정상으로 치면 진행 중인 장애가 화면에서 사라진다.
 */
public final class DetectAnomaliesService implements DetectAnomalies {

    private static final Logger log = System.getLogger(DetectAnomaliesService.class.getName());

    private static final String CONVEYOR_STOPPED = "CONVEYOR_STOPPED";
    private static final String CHUTE_FULL = "CHUTE_FULL";
    private static final String SCANNER_DEGRADED = "SCANNER_DEGRADED";
    private static final String ROBOT_BATTERY = "ROBOT_BATTERY";
    private static final String SCANNER = "SC-1";
    private static final String FLEET = "AMR-FLEET";
    private static final int LOW_BATTERY_PERCENT = 30;
    private static final int LOW_BATTERY_ROBOTS = 2;
    private static final Set<String> STOP_CODES = Set.of("STOPPED", "FAULT");

    private final Conveyors conveyors;
    private final Sorters sorters;
    private final RobotFleet fleet;
    private final EventLog events;
    private final Incidents incidents;
    private final Clock clock;
    private final LocalTime cutoff;

    public DetectAnomaliesService(
            Conveyors conveyors,
            Sorters sorters,
            RobotFleet fleet,
            EventLog events,
            Incidents incidents,
            Clock clock,
            LocalTime cutoff) {
        this.conveyors = conveyors;
        this.sorters = sorters;
        this.fleet = fleet;
        this.events = events;
        this.incidents = incidents;
        this.clock = clock;
        this.cutoff = cutoff;
    }

    /** @param active 조건에 해당하는 설비와 등급 @param cleared 정상임을 확인한 설비. 어느 쪽에도 없으면 모르는 설비다. */
    private record Reading(Map<String, Severity> active, Set<String> cleared) {}

    private record Cycle(Instant now, Duration untilCutoff, long ratePerMin) {}

    /**
     * 스케줄러와 이벤트 수신이 동시에 부른다. 같은 건을 두 번 열지 않도록 주기를 직렬화한다.
     * 인스턴스 하나 안에서만 유효하고, 여러 대로 늘리면 저장소의 부분 유일 인덱스가 막는다.
     */
    @Override
    public synchronized void detect() {
        Instant now = clock.instant();
        List<Incident> open = incidents.open();
        Optional<SorterStatus> sorter = readSorter();
        Cycle cycle = new Cycle(
                now,
                Duration.between(now, LocalDate.now(clock).atTime(cutoff).atZone(clock.getZone()).toInstant()),
                sorter.stream().flatMap(s -> s.chutes().stream()).mapToLong(ChuteStatus::ratePerMin).sum());

        reconcile(CONVEYOR_STOPPED, () -> conveyorReading(cycle), open, now);
        sorter.ifPresent(s -> reconcile(CHUTE_FULL, () -> chuteReading(s, cycle), open, now));
        reconcile(SCANNER_DEGRADED, () -> scannerReading(cycle), open, now);
        reconcile(ROBOT_BATTERY, () -> robotReading(cycle), open, now);
    }

    private Optional<SorterStatus> readSorter() {
        try {
            return Optional.of(sorters.get());
        } catch (RuntimeException e) {
            logSkipped(CHUTE_FULL, e);
            return Optional.empty();
        }
    }

    private Reading conveyorReading(Cycle cycle) {
        Reading reading = new Reading(new HashMap<>(), new HashSet<>());
        for (ConveyorStatus c : conveyors.all()) {
            if (isStop(c.state())) {
                reading.active().put(c.id(), grade(c.id(), true, cycle));
            } else if (isKnown(c.state())) {
                reading.cleared().add(c.id());
            }
        }
        return reading;
    }

    private Reading chuteReading(SorterStatus sorter, Cycle cycle) {
        Reading reading = new Reading(new HashMap<>(), new HashSet<>());
        for (ChuteStatus c : sorter.chutes()) {
            if (c.full()) {
                reading.active().put(c.id(), grade(c.id(), isStop(c.state()), cycle));
            } else if (isKnown(c.state())) {
                reading.cleared().add(c.id());
            }
        }
        return reading;
    }

    private Reading scannerReading(Cycle cycle) {
        return switch (PlantStatusService.scannerState(events.latest(SCANNER))) {
            case DEGRADED -> new Reading(Map.of(SCANNER, grade(SCANNER, false, cycle)), Set.of());
            case RUNNING -> new Reading(Map.of(), Set.of(SCANNER));
            default -> new Reading(Map.of(), Set.of());
        };
    }

    private Reading robotReading(Cycle cycle) {
        List<RobotStatus> robots = fleet.all();
        long low = robots.stream()
                .filter(r -> isKnown(r.state()) && r.batteryPercent() < LOW_BATTERY_PERCENT)
                .count();
        if (low >= LOW_BATTERY_ROBOTS) {
            return new Reading(Map.of(FLEET, grade(FLEET, false, cycle)), Set.of());
        }
        boolean allKnown = !robots.isEmpty() && robots.stream().allMatch(r -> isKnown(r.state()));
        return new Reading(Map.of(), allKnown ? Set.of(FLEET) : Set.of());
    }

    private Severity grade(String id, boolean stoppedNow, Cycle cycle) {
        Duration stopped = stoppedNow ? stoppedFor(id, cycle.now()) : Duration.ZERO;
        long parcels = cycle.ratePerMin() * stopped.toMinutes();
        return SeverityPolicy.grade(stopped, (int) Math.min(parcels, Integer.MAX_VALUE), cycle.untilCutoff());
    }

    private Duration stoppedFor(String id, Instant now) {
        return events.latest(id)
                .filter(e -> STOP_CODES.contains(e.code()))
                .map(e -> Duration.between(e.occurredAt(), now))
                // 게이트웨이 시계가 앞서면 음수가 된다. 등급 계산이 거부해 주기 전체가 멈추지 않게 한다.
                .filter(Duration::isPositive)
                .orElse(Duration.ZERO);
    }

    private void reconcile(String kind, Supplier<Reading> read, List<Incident> open, Instant now) {
        try {
            Reading reading = read.get();
            List<Incident> ofKind =
                    open.stream().filter(i -> i.kind().equals(kind)).toList();
            reading.active().forEach((id, severity) -> {
                Optional<Incident> existing = ofKind.stream()
                        .filter(i -> i.equipmentId().equals(id))
                        .findFirst();
                if (existing.isEmpty()) {
                    incidents.open(new Incident(UUID.randomUUID(), id, kind, severity, now, null));
                } else if (existing.get().severity() != severity) {
                    incidents.regrade(existing.get().id(), severity);
                }
            });
            ofKind.stream()
                    .filter(i -> reading.cleared().contains(i.equipmentId()))
                    .forEach(i -> incidents.close(i.id(), now));
        } catch (RuntimeException e) {
            logSkipped(kind, e);
        }
    }

    private static boolean isStop(RunState state) {
        return state == RunState.STOPPED || state == RunState.FAULT;
    }

    private static boolean isKnown(RunState state) {
        return state != RunState.UNKNOWN && state != RunState.OFFLINE;
    }

    private static void logSkipped(String kind, RuntimeException e) {
        // 예외 메시지에 설비 응답이 섞일 수 있어 종류만 남긴다.
        log.log(Level.WARNING, "anomaly detection skipped " + kind + ": " + e.getClass().getName());
    }
}
