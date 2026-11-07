package com.example.controltower.domain;

import java.util.Objects;

public record ChuteStatus(
        String id, String group, RunState state, int fillPercent, int ratePerMin, int capacityPerMin, boolean full) {

    public ChuteStatus {
        Objects.requireNonNull(state);
        if (ratePerMin < 0 || capacityPerMin < 0) {
            throw new IllegalArgumentException("rate and capacity must not be negative");
        }
    }
}
