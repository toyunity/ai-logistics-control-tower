package com.example.controltower.simulator.web;

import com.example.controltower.simulator.PlantSimulator;
import com.example.controltower.simulator.SimSnapshot;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "controltower.simulator.enabled", havingValue = "true")
public class SafetyController {

    private final PlantSimulator simulator;

    public SafetyController(PlantSimulator simulator) {
        this.simulator = simulator;
    }

    record Zone(int estop, int presence, long ts) {
        static Zone from(SimSnapshot.Safety safety) {
            return new Zone(safety.estop(), safety.presence(), safety.ts().toEpochMilli());
        }
    }

    @GetMapping("/sim/safety/zones/{zone}")
    ResponseEntity<Zone> zone(@PathVariable String zone) {
        SimSnapshot snapshot = simulator.snapshot();
        if (!snapshot.safetyOnline()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        return ResponseEntity.of(
                snapshot.safety().stream().filter(s -> s.zone().equals(zone)).findFirst().map(Zone::from));
    }
}
