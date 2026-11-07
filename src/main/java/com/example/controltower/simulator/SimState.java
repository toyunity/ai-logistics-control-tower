package com.example.controltower.simulator;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 초기 상태는 생성자가 정한다. 리셋은 이 객체를 새로 만드는 것으로 끝난다. */
final class SimState {

    static final int FULL = 100;
    static final int NORMAL_READ_FAIL_PERCENT = 1;
    static final int MAX_JOBS = 4;

    private static final int INITIAL_FILL = 30;
    private static final int LANE_CAP = 40;

    static final class Conveyor {
        int run = 1;
        int alarm = 0;
    }

    static final class Lane {
        final int no;
        final String group;
        final int plannedRate;
        int fill = INITIAL_FILL;

        Lane(int no, String group, int plannedRate) {
            this.no = no;
            this.group = group;
            this.plannedRate = plannedRate;
        }

        String id() {
            return "CH-0" + no;
        }

        boolean full() {
            return fill >= FULL;
        }
    }

    static final class Robot {
        final String name;
        String mode = "AUTO";
        int soc;
        int jobs = 2;

        Robot(String name, int soc) {
            this.name = name;
            this.soc = soc;
        }
    }

    final Map<String, Conveyor> conveyors = new LinkedHashMap<>();
    final List<Lane> lanes = List.of(
            new Lane(1, "A", 10),
            new Lane(2, "A", 10),
            new Lane(3, "A", 20),
            new Lane(4, "A", 20),
            new Lane(5, "B", 10),
            new Lane(6, "B", 10));
    final List<SimSnapshot.Reroute> reroutes = new ArrayList<>();
    final Map<String, Robot> robots = new LinkedHashMap<>();

    /** 차단된 레인 번호. 0이면 차단 없음. */
    int holdLane = 0;

    int fullTicks = 0;
    int holdTicks = 0;
    int backlog = 0;
    int readFailPercent = NORMAL_READ_FAIL_PERCENT;
    int z1Presence = 0;
    boolean safetyOnline = true;

    SimState() {
        for (String id : List.of("CV-1", "CV-2", "CV-3")) {
            conveyors.put(id, new Conveyor());
        }
        robots.put("AMR-01", new Robot("AMR-01", 82));
        robots.put("AMR-02", new Robot("AMR-02", 85));
        robots.put("AMR-03", new Robot("AMR-03", 80));
        robots.put("AMR-04", new Robot("AMR-04", 90));
    }

    Lane lane(int no) {
        return lanes.stream().filter(l -> l.no == no).findFirst().orElse(null);
    }

    // 우회해도 합계가 그대로여야 물량 기반 등급이 흔들리지 않는다. 그래서 원본은 0으로 내린다.
    private int rate(Lane lane) {
        boolean diverted = reroutes.stream().anyMatch(r -> r.src() == lane.no);
        int received = reroutes.stream()
                .filter(r -> r.dst() == lane.no)
                .mapToInt(r -> lane(r.src()).plannedRate)
                .sum();
        return (diverted ? 0 : lane.plannedRate) + received;
    }

    SimSnapshot snapshot(Instant now) {
        return new SimSnapshot(
                conveyors.entrySet().stream()
                        .map(e -> new SimSnapshot.Plc(e.getKey(), e.getValue().run, e.getValue().alarm, 1))
                        .toList(),
                holdLane != 0 ? "HOLD" : "ACTV",
                lanes.stream()
                        .map(l -> new SimSnapshot.Lane(
                                l.no, l.group, l.fill, LANE_CAP, rate(l), l.full() ? "FULL" : "OK"))
                        .toList(),
                List.copyOf(reroutes),
                robots.values().stream()
                        .map(r -> new SimSnapshot.Robot(r.name, r.mode, r.soc, r.jobs, MAX_JOBS))
                        .toList(),
                List.of(
                        new SimSnapshot.Safety("Z1", 0, z1Presence, now),
                        new SimSnapshot.Safety("Z2", 0, 0, now),
                        new SimSnapshot.Safety("Z3", 0, 0, now)),
                backlog,
                safetyOnline);
    }
}
