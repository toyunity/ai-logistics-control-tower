package com.example.controltower.domain;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

public final class LineTopology {

    public static final LineTopology DEFAULT = new LineTopology();

    private static final List<String> LINE = List.of("CV-1", "CV-2", "CV-3", "SC-1", "SR-1");
    private static final List<String> CHUTES = List.of("CH-01", "CH-02", "CH-03", "CH-04", "CH-05", "CH-06");
    private static final List<String> ROBOTS = List.of("AMR-01", "AMR-02", "AMR-03", "AMR-04");

    private LineTopology() {}

    public List<String> all() {
        return Stream.of(LINE, CHUTES, ROBOTS).flatMap(List::stream).toList();
    }

    public boolean contains(String id) {
        return id != null && (LINE.contains(id) || CHUTES.contains(id) || ROBOTS.contains(id));
    }

    public boolean isConveyor(String id) {
        return contains(id) && id.startsWith("CV-");
    }

    public boolean isChute(String id) {
        return contains(id) && id.startsWith("CH-");
    }

    public boolean isRobot(String id) {
        return contains(id) && id.startsWith("AMR-");
    }

    public Optional<String> zoneOf(String id) {
        if (!contains(id)) {
            return Optional.empty();
        }
        return Optional.of(id.startsWith("CV-") ? "Z1" : id.startsWith("AMR-") ? "Z3" : "Z2");
    }

    public List<String> upstreamOf(String id) {
        if (!contains(id)) {
            return List.of();
        }
        if (ROBOTS.contains(id)) {
            return CHUTES;
        }
        return CHUTES.contains(id) ? LINE.reversed() : LINE.subList(0, LINE.indexOf(id)).reversed();
    }

    /**
     * 슈트까지가 물류 흐름이고 로봇은 슈트에서 회수만 하므로 의도적으로 전이되지 않는다.
     * 로봇은 슈트의 하류지만 슈트보다 위 설비의 하류는 아니다.
     */
    public List<String> downstreamOf(String id) {
        if (!contains(id) || ROBOTS.contains(id)) {
            return List.of();
        }
        if (CHUTES.contains(id)) {
            return ROBOTS;
        }
        return Stream.concat(LINE.subList(LINE.indexOf(id) + 1, LINE.size()).stream(), CHUTES.stream())
                .toList();
    }
}
