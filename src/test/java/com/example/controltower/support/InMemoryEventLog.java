package com.example.controltower.support;

import com.example.controltower.application.port.out.EventLog;
import com.example.controltower.domain.EquipmentEvent;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 조회 구간은 양 끝을 포함한다. */
public class InMemoryEventLog implements EventLog {

    private static final Comparator<EquipmentEvent> BY_TIME =
            Comparator.comparing(EquipmentEvent::occurredAt).thenComparing(EquipmentEvent::eventId);

    private final Map<String, EquipmentEvent> events = new LinkedHashMap<>();
    private boolean failing;

    public void setFailing(boolean failing) {
        this.failing = failing;
    }

    @Override
    public boolean append(EquipmentEvent event) {
        failIfDown();
        return events.putIfAbsent(event.eventId(), event) == null;
    }

    @Override
    public List<EquipmentEvent> between(String equipmentId, Instant from, Instant to) {
        return since(from).stream()
                .filter(e -> e.equipmentId().equals(equipmentId) && !e.occurredAt().isAfter(to))
                .toList();
    }

    @Override
    public List<EquipmentEvent> since(Instant from) {
        failIfDown();
        return events.values().stream()
                .filter(e -> !e.occurredAt().isBefore(from))
                .sorted(BY_TIME)
                .toList();
    }

    @Override
    public Optional<EquipmentEvent> latest(String equipmentId) {
        failIfDown();
        return events.values().stream()
                .filter(e -> e.equipmentId().equals(equipmentId))
                .max(BY_TIME);
    }

    private void failIfDown() {
        if (failing) {
            throw new IllegalStateException("event log down");
        }
    }
}
