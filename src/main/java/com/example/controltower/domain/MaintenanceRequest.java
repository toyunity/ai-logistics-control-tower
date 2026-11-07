package com.example.controltower.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** @param closedAt 열려 있으면 null */
public record MaintenanceRequest(UUID id, String equipmentId, String summary, Instant openedAt, Instant closedAt) {

    public MaintenanceRequest {
        Objects.requireNonNull(id);
        Objects.requireNonNull(equipmentId);
        Objects.requireNonNull(summary);
        Objects.requireNonNull(openedAt);
    }
}
