package com.example.controltower.domain;

import java.time.Instant;
import java.util.Objects;

public record EquipmentEvent(String eventId, String equipmentId, String code, Integer value, Instant occurredAt) {

    public EquipmentEvent {
        Objects.requireNonNull(eventId);
        Objects.requireNonNull(equipmentId);
        Objects.requireNonNull(code);
        Objects.requireNonNull(occurredAt);
    }
}
