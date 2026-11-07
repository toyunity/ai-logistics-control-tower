package com.example.controltower.domain;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

public final class CascadeAnalyzer {

    private static final Set<String> ABNORMAL_CODES =
            Set.of("CHUTE_FULL", "LANE_BLOCKED", "STOPPED", "FAULT", "READ_DEGRADED", "BATTERY_LOW");

    private CascadeAnalyzer() {}

    /**
     * 이벤트에 레인 연결 정보가 없어, 범위 안에서 더 이르고 복구되지 않은 다른 슈트의 이벤트가 지목될 수 있다.
     * 그래서 결과는 후보일 뿐이다.
     *
     * @param events 분석할 시간 범위로 호출자가 이미 거른 이벤트
     */
    public static Optional<RootCauseCandidate> analyze(String id, List<EquipmentEvent> events) {
        LineTopology topology = LineTopology.DEFAULT;
        if (!topology.contains(id)) {
            return Optional.empty();
        }
        List<String> path =
                Stream.concat(Stream.of(id), topology.downstreamOf(id).stream()).toList();
        List<EquipmentEvent> abnormal = events.stream()
                .filter(e -> path.contains(e.equipmentId()) && ABNORMAL_CODES.contains(e.code()))
                .toList();
        Comparator<EquipmentEvent> byTime = Comparator.comparing(EquipmentEvent::occurredAt);
        Optional<EquipmentEvent> anchor = abnormal.stream()
                .filter(e -> e.equipmentId().equals(id))
                .max(byTime)
                .or(() -> abnormal.stream().max(byTime));
        if (anchor.isEmpty()) {
            return Optional.empty();
        }
        Instant anchorAt = anchor.get().occurredAt();
        List<EquipmentEvent> evidence = abnormal.stream()
                .filter(e -> !e.occurredAt().isAfter(anchorAt) && !recoveredBy(e, anchorAt, events))
                // 고장은 상류로 번지므로 같은 시각이면 더 하류 설비를 먼저 둔다.
                .sorted(byTime.thenComparingInt(e -> -path.indexOf(e.equipmentId())))
                .toList();
        return Optional.of(new RootCauseCandidate(evidence.getFirst().equipmentId(), evidence));
    }

    // 기준 시각 뒤의 복구는 세지 않는다. 슈트가 컨베이어 정지 뒤에 비워져도 원인이었던 사실은 그대로다.
    private static boolean recoveredBy(EquipmentEvent abnormal, Instant anchorAt, List<EquipmentEvent> events) {
        return events.stream()
                .anyMatch(e -> e.equipmentId().equals(abnormal.equipmentId())
                        && !ABNORMAL_CODES.contains(e.code())
                        && e.occurredAt().isAfter(abnormal.occurredAt())
                        && !e.occurredAt().isAfter(anchorAt));
    }
}
