package com.example.controltower.application.port.in;

import com.example.controltower.domain.RunState;
import java.util.Map;
import java.util.Objects;

/** @param facts 코드가 번역한 값만 담는다. AI에게도 전달되므로 설비가 보낸 자유 문자열을 넣지 않는다. */
public record EquipmentView(String id, String kind, RunState state, Map<String, Object> facts) {

    public EquipmentView {
        Objects.requireNonNull(id);
        Objects.requireNonNull(kind);
        Objects.requireNonNull(state);
        facts = Map.copyOf(facts);
    }
}
