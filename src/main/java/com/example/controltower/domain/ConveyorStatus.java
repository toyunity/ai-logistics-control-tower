package com.example.controltower.domain;

import java.util.Objects;

public record ConveyorStatus(String id, String zone, RunState state, FaultClass fault) {

    public ConveyorStatus {
        Objects.requireNonNull(state);
        Objects.requireNonNull(fault);
    }
}
