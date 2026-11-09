package com.example.controltower.adapter.out.equipment;

import com.example.controltower.application.port.out.SafetySignals;
import com.example.controltower.domain.SafetyStatus;
import java.time.Instant;
import org.springframework.stereotype.Component;

/** 안전 PLC의 0/1 신호를 번역한다. ts를 그대로 넘겨야 오래된 값 판정이 실제 측정 시각으로 이뤄진다. */
@Component
public class SafetyPlcSignals implements SafetySignals {

    record Zone(int estop, int presence, long ts) {}

    private final EquipmentHttp http;

    SafetyPlcSignals(EquipmentHttp http) {
        this.http = http;
    }

    @Override
    public SafetyStatus read(String zone) {
        return http.read(
                EquipmentHttp.Group.SAFETY_PLC,
                "/sim/safety/zones/{zone}",
                Zone.class,
                wire -> new SafetyStatus(zone, flag(wire.estop()), flag(wire.presence()), Instant.ofEpochMilli(wire.ts())),
                zone);
    }

    private static boolean flag(int signal) {
        return switch (signal) {
            case 0 -> false;
            case 1 -> true;
            default -> throw new IllegalArgumentException("signal must be 0 or 1");
        };
    }
}
