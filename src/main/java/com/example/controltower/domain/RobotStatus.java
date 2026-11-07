package com.example.controltower.domain;

import java.util.Objects;

public record RobotStatus(String id, RunState state, int batteryPercent, int queuedTasks, int queueCapacity) {

    public RobotStatus {
        Objects.requireNonNull(state);
    }
}
