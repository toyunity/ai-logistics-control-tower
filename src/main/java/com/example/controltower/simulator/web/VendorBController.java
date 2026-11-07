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

/** 분류기 제조사 형식. 분류기가 하나뿐이라 그 외 ID는 경로가 없어 404가 된다. */
@RestController
@ConditionalOnProperty(name = "controltower.simulator.enabled", havingValue = "true")
@RequestMapping("/sim/vendor-b/sorters/SR-1")
public class VendorBController {

    private final PlantSimulator simulator;

    public VendorBController(PlantSimulator simulator) {
        this.simulator = simulator;
    }

    record Sorter(String opStatus, List<SimSnapshot.Lane> lanes, List<SimSnapshot.Reroute> reroutes) {
        static Sorter from(SimSnapshot snapshot) {
            return new Sorter(snapshot.opStatus(), snapshot.lanes(), snapshot.reroutes());
        }
    }

    record RerouteRequest(@NotNull UUID txn, @NotNull Integer src, @NotNull Integer dst) {}

    @GetMapping
    Sorter read() {
        return Sorter.from(simulator.snapshot());
    }

    @PostMapping("/reroutes")
    ResponseEntity<Void> reroute(@Valid @RequestBody RerouteRequest request) {
        boolean applied = simulator.divert(request.txn(), request.src(), request.dst());
        return ResponseEntity.status(applied ? HttpStatus.OK : HttpStatus.CONFLICT).build();
    }
}
