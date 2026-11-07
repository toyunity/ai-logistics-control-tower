package com.example.controltower.domain;

import java.time.Instant;
import java.util.Objects;

public record SafetyStatus(String zone, boolean emergencyStop, boolean workerPresent, Instant measuredAt) {

    public SafetyStatus {
        Objects.requireNonNull(zone);
        Objects.requireNonNull(measuredAt);
    }
}
