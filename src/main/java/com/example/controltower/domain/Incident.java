package com.example.controltower.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** @param closedAt 열려 있으면 null */
public record Incident(UUID id, String equipmentId, String kind, Severity severity, Instant openedAt, Instant closedAt) {

    public Incident {
        Objects.requireNonNull(id);
        Objects.requireNonNull(equipmentId);
        Objects.requireNonNull(kind);
        Objects.requireNonNull(severity);
        Objects.requireNonNull(openedAt);
    }
}
