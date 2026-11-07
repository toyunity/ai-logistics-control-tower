package com.example.controltower.application.port.in;

import com.example.controltower.domain.EquipmentEvent;
import com.example.controltower.domain.RootCauseCandidate;
import java.util.List;
import java.util.Optional;

public interface PlantStatus {

    PlantSnapshot snapshot();

    Optional<EquipmentView> equipment(String id);

    List<EquipmentEvent> timeline(String id, int minutes);

    Optional<RootCauseCandidate> diagnose(String id);
}
