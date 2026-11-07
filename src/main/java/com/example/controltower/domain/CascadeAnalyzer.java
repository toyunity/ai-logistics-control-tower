package com.example.controltower.domain;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

public final class CascadeAnalyzer {

    private static final Set<String> ABNORMAL_CODES =
            Set.of("CHUTE_FULL", "LANE_BLOCKED", "STOPPED", "FAULT", "READ_DEGRADED", "BATTERY_LOW");

    private CascadeAnalyzer() {}

    /** @param events 분석할 시간 범위로 호출자가 이미 거른 이벤트 */
    public static Optional<RootCauseCandidate> analyze(String id, List<EquipmentEvent> events) {
        LineTopology topology = LineTopology.DEFAULT;
        if (!topology.contains(id)) {
            return Optional.empty();
        }
        List<String> path =
                Stream.concat(Stream.of(id), topology.downstreamOf(id).stream()).toList();
        List<EquipmentEvent> evidence = events.stream()
                .filter(e -> path.contains(e.equipmentId()) && ABNORMAL_CODES.contains(e.code()))
                // 고장은 상류로 번지므로 같은 시각이면 더 하류 설비를 먼저 둔다.
                .sorted(Comparator.comparing(EquipmentEvent::occurredAt)
                        .thenComparingInt(e -> -path.indexOf(e.equipmentId())))
                .toList();
        return evidence.isEmpty()
                ? Optional.empty()
                : Optional.of(new RootCauseCandidate(evidence.getFirst().equipmentId(), evidence));
    }
}
