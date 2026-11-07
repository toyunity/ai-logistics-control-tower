package com.example.controltower.simulator.web;

import com.example.controltower.simulator.PlantSimulator;
import com.example.controltower.simulator.SimSnapshot;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "controltower.simulator.enabled", havingValue = "true")
@RequestMapping("/sim/fleet")
public class FleetController {

    private final PlantSimulator simulator;

    public FleetController(PlantSimulator simulator) {
        this.simulator = simulator;
    }

    record Robot(String name, String mode, double soc, int jobs, int maxJobs) {
        static Robot from(SimSnapshot.Robot robot) {
            return new Robot(robot.name(), robot.mode(), robot.soc() / 100.0, robot.jobs(), robot.maxJobs());
        }
    }

    record Reassign(@NotNull UUID cmdId, @NotNull String from, @NotNull String to) {}

    @GetMapping("/robots")
    List<Robot> robots() {
        return simulator.snapshot().robots().stream().map(Robot::from).toList();
    }

    @PostMapping("/reassign")
    ResponseEntity<Void> reassign(@Valid @RequestBody Reassign request) {
        List<SimSnapshot.Robot> robots = simulator.snapshot().robots();
        boolean known = robots.stream().anyMatch(r -> r.name().equals(request.from()))
                && robots.stream().anyMatch(r -> r.name().equals(request.to()));
        if (!known) {
            return ResponseEntity.notFound().build();
        }
        boolean applied = simulator.reassign(request.cmdId(), request.from(), request.to());
        return ResponseEntity.status(applied ? HttpStatus.OK : HttpStatus.CONFLICT).build();
    }
}
