package com.example.controltower.application.port.out;

import com.example.controltower.domain.MaintenanceRequest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface MaintenanceDesk {

    boolean hasOpen(String equipmentId);

    UUID open(String equipmentId, String summary, Instant now);

    boolean close(UUID id, Instant now);

    List<MaintenanceRequest> openRequests();
}
