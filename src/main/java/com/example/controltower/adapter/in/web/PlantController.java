package com.example.controltower.adapter.in.web;

import com.example.controltower.application.port.in.PlantSnapshot;
import com.example.controltower.application.port.in.PlantStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PlantController {

    private final PlantStatus plant;

    public PlantController(PlantStatus plant) {
        this.plant = plant;
    }

    @GetMapping("/api/plant/status")
    PlantSnapshot status() {
        return plant.snapshot();
    }
}
