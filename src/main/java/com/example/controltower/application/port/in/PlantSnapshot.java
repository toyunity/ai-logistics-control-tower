package com.example.controltower.application.port.in;

import com.example.controltower.domain.Incident;
import com.example.controltower.domain.MaintenanceRequest;
import java.time.Instant;
import java.util.List;

public record PlantSnapshot(
        List<EquipmentView> equipment, List<Incident> incidents, List<MaintenanceRequest> maintenance, Instant at) {

    public PlantSnapshot {
        equipment = List.copyOf(equipment);
        incidents = List.copyOf(incidents);
        maintenance = List.copyOf(maintenance);
    }
}
