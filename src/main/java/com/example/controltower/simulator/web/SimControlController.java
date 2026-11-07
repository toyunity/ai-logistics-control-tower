package com.example.controltower.simulator.web;

import com.example.controltower.simulator.PlantSimulator;
import com.example.controltower.simulator.Scenario;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "controltower.simulator.enabled", havingValue = "true")
public class SimControlController {

    private final PlantSimulator simulator;

    public SimControlController(PlantSimulator simulator) {
        this.simulator = simulator;
    }

    @PostMapping("/sim/scenarios/{name}")
    ResponseEntity<Void> start(@PathVariable String name) {
        if (Scenario.byName(name).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.status(simulator.start(name) ? HttpStatus.OK : HttpStatus.CONFLICT).build();
    }

    @PostMapping("/sim/reset")
    void reset() {
        simulator.reset();
    }
}
