package com.example.controltower.domain;

import java.time.Duration;

public record SafetyLimits(Duration signalMaxAge, int minBatteryPercent) {}
