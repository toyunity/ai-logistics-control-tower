package com.example.controltower.simulator;

import java.time.Instant;

public record SimEvent(String eventId, String equipmentId, String code, Integer value, Instant occurredAt) {}
