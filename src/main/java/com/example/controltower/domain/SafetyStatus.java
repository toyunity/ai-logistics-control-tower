package com.example.controltower.domain;

import java.time.Instant;

public record SafetyStatus(String zone, boolean emergencyStop, boolean workerPresent, Instant measuredAt) {}
