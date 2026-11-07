package com.example.controltower.domain;

public record ChuteStatus(
        String id, String group, RunState state, int fillPercent, int ratePerMin, int capacityPerMin, boolean full) {}
