package com.example.controltower.support;

import com.example.controltower.application.port.out.Incidents;
import com.example.controltower.domain.Incident;
import com.example.controltower.domain.Severity;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 실제 저장소의 부분 유일 인덱스처럼, 같은 설비·종류의 두 번째 열린 건을 거부한다. */
public class InMemoryIncidents implements Incidents {

    private final Map<UUID, Incident> incidents = new LinkedHashMap<>();

    public List<Incident> all() {
        return List.copyOf(incidents.values());
    }

    @Override
    public List<Incident> open() {
        return incidents.values().stream().filter(i -> i.closedAt() == null).toList();
    }

    @Override
    public void open(Incident incident) {
        boolean duplicate = open().stream()
                .anyMatch(i -> i.equipmentId().equals(incident.equipmentId()) && i.kind().equals(incident.kind()));
        if (duplicate) {
            throw new IllegalStateException("already open");
        }
        incidents.put(incident.id(), incident);
    }

    @Override
    public void close(UUID id, Instant at) {
        Incident i = incidents.get(id);
        incidents.put(id, new Incident(i.id(), i.equipmentId(), i.kind(), i.severity(), i.openedAt(), at));
    }

    @Override
    public void regrade(UUID id, Severity severity) {
        Incident i = incidents.get(id);
        incidents.put(id, new Incident(i.id(), i.equipmentId(), i.kind(), severity, i.openedAt(), i.closedAt()));
    }
}
