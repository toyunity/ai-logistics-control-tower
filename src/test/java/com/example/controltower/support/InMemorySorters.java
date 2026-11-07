package com.example.controltower.support;

import com.example.controltower.application.port.out.Sorters;
import com.example.controltower.domain.GatewayUnavailableException;
import com.example.controltower.domain.SorterStatus;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class InMemorySorters implements Sorters {

    private final List<UUID> commandIds = new ArrayList<>();
    private SorterStatus status;
    private boolean failing;
    private Runnable afterSend = () -> {};
    private int reads;

    public InMemorySorters(SorterStatus initial) {
        this.status = initial;
    }

    public void set(SorterStatus status) {
        this.status = status;
    }

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

    public List<UUID> commandIds() {
        return commandIds;
    }

    @Override
    public SorterStatus get() {
        reads++;
        failIfDown();
        return status;
    }

    @Override
    public void divert(UUID commandId, String fromChute, String toChute) {
        failIfDown();
        if (!isChuteId(fromChute) || !isChuteId(toChute)) {
            throw new GatewayUnavailableException("chute id out of format", null);
        }
        commandIds.add(commandId);
        Map<String, String> diversions = new HashMap<>(status.diversions());
        diversions.put(fromChute, toChute);
        status = new SorterStatus(status.id(), status.state(), status.blocked(), status.chutes(), diversions);
        afterSend.run();
    }

    private static boolean isChuteId(String id) {
        return id != null && id.matches("CH-\\d\\d");
    }

    private void failIfDown() {
        if (failing) {
            throw new GatewayUnavailableException("sorter gateway down", null);
        }
    }
}
