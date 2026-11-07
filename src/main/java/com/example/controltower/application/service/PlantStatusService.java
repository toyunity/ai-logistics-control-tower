package com.example.controltower.application.service;

import com.example.controltower.application.port.in.EquipmentView;
import com.example.controltower.application.port.in.PlantSnapshot;
import com.example.controltower.application.port.in.PlantStatus;
import com.example.controltower.application.port.out.Conveyors;
import com.example.controltower.application.port.out.EventLog;
import com.example.controltower.application.port.out.Incidents;
import com.example.controltower.application.port.out.MaintenanceDesk;
import com.example.controltower.application.port.out.RobotFleet;
import com.example.controltower.application.port.out.Sorters;
import com.example.controltower.domain.CascadeAnalyzer;
import com.example.controltower.domain.ChuteStatus;
import com.example.controltower.domain.ConveyorStatus;
import com.example.controltower.domain.EquipmentEvent;
import com.example.controltower.domain.LineTopology;
import com.example.controltower.domain.RobotStatus;
import com.example.controltower.domain.RootCauseCandidate;
import com.example.controltower.domain.RunState;
import com.example.controltower.domain.SorterStatus;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public final class PlantStatusService implements PlantStatus {

    private static final Logger log = System.getLogger(PlantStatusService.class.getName());

    private static final String SCANNER = "SC-1";
    private static final Duration DIAGNOSE_WINDOW = Duration.ofMinutes(30);
    private static final int MIN_TIMELINE_MINUTES = 1;
    private static final int MAX_TIMELINE_MINUTES = 120;

    private final Conveyors conveyors;
    private final Sorters sorters;
    private final RobotFleet fleet;
    private final EventLog events;
    private final Incidents incidents;
    private final MaintenanceDesk desk;
    private final LineTopology topology;
    private final Clock clock;

    public PlantStatusService(
            Conveyors conveyors,
            Sorters sorters,
            RobotFleet fleet,
            EventLog events,
            Incidents incidents,
            MaintenanceDesk desk,
            LineTopology topology,
            Clock clock) {
        this.conveyors = conveyors;
        this.sorters = sorters;
        this.fleet = fleet;
        this.events = events;
        this.incidents = incidents;
        this.desk = desk;
        this.topology = topology;
        this.clock = clock;
    }

    /** 스캐너는 이벤트로만 상태를 알린다. 모르는 코드와 이력 없음은 UNKNOWN이다. */
    static RunState scannerState(Optional<EquipmentEvent> latest) {
        return latest.map(e -> switch (e.code()) {
                    case "READ_DEGRADED" -> RunState.DEGRADED;
                    case "READ_NORMAL" -> RunState.RUNNING;
                    default -> RunState.UNKNOWN;
                })
                .orElse(RunState.UNKNOWN);
    }

    @Override
    public PlantSnapshot snapshot() {
        Map<String, EquipmentView> views = new HashMap<>();
        views.putAll(readable(this::conveyorViews));
        views.putAll(readable(this::sorterViews));
        views.putAll(readable(this::scannerViews));
        views.putAll(readable(this::robotViews));
        return new PlantSnapshot(
                topology.all().stream().map(id -> viewOrUnknown(views, id)).toList(),
                incidents.open(),
                desk.openRequests(),
                clock.instant());
    }

    @Override
    public Optional<EquipmentView> equipment(String id) {
        if (!topology.contains(id)) {
            return Optional.empty();
        }
        Supplier<Map<String, EquipmentView>> family;
        if (topology.isConveyor(id)) {
            family = this::conveyorViews;
        } else if (topology.isRobot(id)) {
            family = this::robotViews;
        } else if (SCANNER.equals(id)) {
            family = this::scannerViews;
        } else {
            family = this::sorterViews;
        }
        return Optional.of(viewOrUnknown(readable(family), id));
    }

    @Override
    public List<EquipmentEvent> timeline(String id, int minutes) {
        if (!topology.contains(id)) {
            return List.of();
        }
        Instant now = clock.instant();
        int window = Math.clamp(minutes, MIN_TIMELINE_MINUTES, MAX_TIMELINE_MINUTES);
        return events.between(id, now.minus(Duration.ofMinutes(window)), now);
    }

    @Override
    public Optional<RootCauseCandidate> diagnose(String id) {
        // 하류 설비 이벤트도 근거이므로 해당 설비만 읽으면 원인을 찾을 수 없다.
        return CascadeAnalyzer.analyze(id, events.since(clock.instant().minus(DIAGNOSE_WINDOW)));
    }

    /** 설비군을 호출 하나씩 격리한다. 2초마다 불리므로 장애 중 기록은 감지 쪽 경고에 맡기고 DEBUG로만 남긴다. */
    private Map<String, EquipmentView> readable(Supplier<Map<String, EquipmentView>> family) {
        try {
            return family.get();
        } catch (RuntimeException e) {
            log.log(Level.DEBUG, "plant status family unreadable: " + e.getClass().getName());
            return Map.of();
        }
    }

    private EquipmentView viewOrUnknown(Map<String, EquipmentView> views, String id) {
        EquipmentView view = views.get(id);
        return view != null ? view : new EquipmentView(id, kindOf(id), RunState.UNKNOWN, Map.of());
    }

    private String kindOf(String id) {
        if (topology.isConveyor(id)) {
            return "CONVEYOR";
        }
        if (topology.isChute(id)) {
            return "CHUTE";
        }
        if (topology.isRobot(id)) {
            return "ROBOT";
        }
        return SCANNER.equals(id) ? "SCANNER" : "SORTER";
    }

    private Map<String, EquipmentView> conveyorViews() {
        return conveyors.all().stream().collect(Collectors.toMap(ConveyorStatus::id, this::view));
    }

    private EquipmentView view(ConveyorStatus c) {
        return new EquipmentView(c.id(), kindOf(c.id()), c.state(), Map.of("fault", c.fault().name()));
    }

    private Map<String, EquipmentView> sorterViews() {
        SorterStatus s = sorters.get();
        Map<String, EquipmentView> views =
                s.chutes().stream().collect(Collectors.toMap(ChuteStatus::id, this::view, (a, b) -> b, HashMap::new));
        // 슈트 그룹과 우회 대상은 제조사 문자열이다. 라인에 있는 슈트 ID만 통과시킨다.
        Map<String, String> diversions = s.diversions().entrySet().stream()
                .filter(e -> topology.isChute(e.getKey()) && topology.isChute(e.getValue()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        views.put(
                s.id(),
                new EquipmentView(
                        s.id(), kindOf(s.id()), s.state(), Map.of("blocked", s.blocked(), "diversions", diversions)));
        return views;
    }

    private EquipmentView view(ChuteStatus c) {
        return new EquipmentView(
                c.id(),
                kindOf(c.id()),
                c.state(),
                Map.of(
                        "fillPercent", c.fillPercent(),
                        "ratePerMin", c.ratePerMin(),
                        "capacityPerMin", c.capacityPerMin(),
                        "full", c.full()));
    }

    private Map<String, EquipmentView> scannerViews() {
        Optional<EquipmentEvent> latest = events.latest(SCANNER);
        RunState state = scannerState(latest);
        Integer failRate = latest.map(EquipmentEvent::value).orElse(null);
        Map<String, Object> facts = state == RunState.UNKNOWN || failRate == null
                ? Map.of()
                : Map.of("failRatePercent", Math.clamp(failRate, 0, 100));
        return Map.of(SCANNER, new EquipmentView(SCANNER, kindOf(SCANNER), state, facts));
    }

    private Map<String, EquipmentView> robotViews() {
        return fleet.all().stream().collect(Collectors.toMap(RobotStatus::id, this::view));
    }

    private EquipmentView view(RobotStatus r) {
        return new EquipmentView(
                r.id(),
                kindOf(r.id()),
                r.state(),
                Map.of(
                        "batteryPercent", r.batteryPercent(),
                        "queuedTasks", r.queuedTasks(),
                        "queueCapacity", r.queueCapacity()));
    }
}
