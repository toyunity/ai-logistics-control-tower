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
        commandIds.add(commandId);
        Map<String, String> diversions = new HashMap<>(status.diversions());
        diversions.put(fromChute, toChute);
        status = new SorterStatus(status.id(), status.state(), status.blocked(), status.chutes(), diversions);
    }

    private void failIfDown() {
        if (failing) {
            throw new GatewayUnavailableException("sorter gateway down", null);
        }
    }
}
