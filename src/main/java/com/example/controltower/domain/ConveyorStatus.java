package com.example.controltower.domain;

public record ConveyorStatus(String id, String zone, RunState state, FaultClass fault) {}
