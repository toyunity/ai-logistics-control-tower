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
    private Runnable afterSend = () -> {};
    private int reads;
    private int opens;

    public void setFailing(boolean failing) {
        this.failing = failing;
    }

    /** 전송이 기록되고 적용된 뒤에 실행한다. 던지면 응답을 잃은 전송, 상태를 되돌리면 아직 반영되지 않은 전송을 흉내 낸다. */
    public void afterSend(Runnable afterSend) {
        this.afterSend = afterSend;
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
        afterSend.run();
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
