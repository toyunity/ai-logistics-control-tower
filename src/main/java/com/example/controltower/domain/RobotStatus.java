package com.example.controltower.domain;

public record RobotStatus(String id, RunState state, int batteryPercent, int queuedTasks, int queueCapacity) {}
