package com.example.controltower.simulator;

import java.util.Arrays;
import java.util.Optional;

public enum Scenario {
    CHUTE_JAM("chute-jam"),
    ROBOT_BATTERY("robot-battery"),
    SCANNER_DEGRADED("scanner-degraded"),
    WORKER_IN_ZONE("worker-in-zone"),
    SAFETY_OFFLINE("safety-offline");

    private final String label;

    Scenario(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static Optional<Scenario> byName(String name) {
        return Arrays.stream(values()).filter(s -> s.label.equals(name)).findFirst();
    }
}
