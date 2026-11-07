package com.example.controltower.domain;

import java.util.List;

/** 확정된 원인이 아니라 이벤트 순서로 추정한 후보다. */
public record RootCauseCandidate(String equipmentId, List<EquipmentEvent> evidence) {

    public RootCauseCandidate {
        evidence = List.copyOf(evidence);
    }
}
