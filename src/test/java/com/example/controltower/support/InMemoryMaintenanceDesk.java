package com.example.controltower.support;

import com.example.controltower.application.port.out.MaintenanceDesk;
import com.example.controltower.domain.GatewayUnavailableException;
import com.example.controltower.domain.MaintenanceRequest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class InMemoryMaintenanceDesk implements MaintenanceDesk {

    private final Map<UUID, MaintenanceRequest> requests = new LinkedHashMap<>();
    private boolean failing;
    private int reads;
    private int opens;

    public void setFailing(boolean failing) {
        this.failing = failing;
    }

    public int reads() {
        return reads;
    }

    public int opens() {
        return opens;
    }

    @Override
    public boolean hasOpen(String equipmentId) {
        reads++;
        if (failing) {
            throw new GatewayUnavailableException("desk down", null);
        }
        return requests.values().stream()
                .anyMatch(r -> r.equipmentId().equals(equipmentId) && r.closedAt() == null);
    }

    @Override
    public UUID open(String equipmentId, String summary, Instant now) {
        opens++;
        MaintenanceRequest request = new MaintenanceRequest(UUID.randomUUID(), equipmentId, summary, now, null);
        requests.put(request.id(), request);
        return request.id();
    }

    @Override
    public boolean close(UUID id, Instant now) {
        MaintenanceRequest r = requests.get(id);
        if (r == null || r.closedAt() != null) {
            return false;
        }
        requests.put(id, new MaintenanceRequest(r.id(), r.equipmentId(), r.summary(), r.openedAt(), now));
        return true;
    }

    @Override
    public List<MaintenanceRequest> openRequests() {
        reads++;
        return requests.values().stream().filter(r -> r.closedAt() == null).toList();
    }
}
