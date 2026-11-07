package com.example.controltower.support;

import com.example.controltower.application.port.out.SafetySignals;
import com.example.controltower.domain.GatewayUnavailableException;
import com.example.controltower.domain.SafetyStatus;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;

/** 따로 설정하지 않은 구역은 지금 측정한 정상 신호를 돌려준다. */
public class InMemorySafetySignals implements SafetySignals {

    private final Clock clock;
    private final Map<String, SafetyStatus> signals = new HashMap<>();
    private boolean failing;
    private int reads;

    public InMemorySafetySignals(Clock clock) {
        this.clock = clock;
    }

    public void set(SafetyStatus status) {
        signals.put(status.zone(), status);
    }

    public void setFailing(boolean failing) {
        this.failing = failing;
    }

    public int reads() {
        return reads;
    }

    @Override
    public SafetyStatus read(String zone) {
        reads++;
        if (failing) {
            throw new GatewayUnavailableException("safety gateway down", null);
        }
        SafetyStatus status = signals.get(zone);
        return status != null ? status : new SafetyStatus(zone, false, false, clock.instant());
    }
}
