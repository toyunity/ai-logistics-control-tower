package com.example.controltower.simulator.web;

import com.example.controltower.simulator.PlantSimulator;
import com.example.controltower.simulator.SimSnapshot;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "controltower.simulator.enabled", havingValue = "true")
@RequestMapping("/sim/vendor-a/plc/{id}")
public class VendorAController {

    private static final int OP_START = 1;

    private final PlantSimulator simulator;

    public VendorAController(PlantSimulator simulator) {
        this.simulator = simulator;
    }

    record Registers(List<Integer> registers) {
        static Registers from(SimSnapshot.Plc plc) {
            return new Registers(List.of(plc.run(), plc.alarm(), plc.zoneCode()));
        }
    }

    record Command(@NotNull UUID cmdId, @NotNull Integer op) {}

    @GetMapping
    ResponseEntity<Registers> read(@PathVariable String id) {
        return ResponseEntity.of(find(id).map(Registers::from));
    }

    @PostMapping("/command")
    ResponseEntity<Void> command(@PathVariable String id, @Valid @RequestBody Command command) {
        if (command.op() != OP_START) {
            return ResponseEntity.badRequest().build();
        }
        if (find(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.status(simulator.restart(command.cmdId(), id) ? HttpStatus.OK : HttpStatus.CONFLICT)
                .build();
    }

    private Optional<SimSnapshot.Plc> find(String id) {
        return simulator.snapshot().conveyors().stream().filter(c -> c.id().equals(id)).findFirst();
    }
}
