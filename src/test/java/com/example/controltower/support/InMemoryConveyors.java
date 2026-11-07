package com.example.controltower.support;

import com.example.controltower.application.port.out.Conveyors;
import com.example.controltower.domain.ConveyorStatus;
import com.example.controltower.domain.FaultClass;
import com.example.controltower.domain.GatewayUnavailableException;
import com.example.controltower.domain.RunState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class InMemoryConveyors implements Conveyors {

    private final Map<String, ConveyorStatus> conveyors = new LinkedHashMap<>();
    private final List<UUID> commandIds = new ArrayList<>();
    private boolean failing;
    private Runnable afterSend = () -> {};
    private int reads;

    public InMemoryConveyors(ConveyorStatus... initial) {
        for (ConveyorStatus c : initial) {
            set(c);
        }
    }

    public void set(ConveyorStatus status) {
        conveyors.put(status.id(), status);
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
    public ConveyorStatus get(String id) {
        reads++;
        failIfDown();
        ConveyorStatus status = conveyors.get(id);
        if (status == null) {
            throw new GatewayUnavailableException("no such conveyor", null);
        }
        return status;
    }

    @Override
    public List<ConveyorStatus> all() {
        reads++;
        failIfDown();
        return List.copyOf(conveyors.values());
    }

    @Override
    public void restart(UUID commandId, String id) {
        failIfDown();
        commandIds.add(commandId);
        ConveyorStatus c = conveyors.get(id);
        set(new ConveyorStatus(c.id(), c.zone(), RunState.RUNNING, FaultClass.NONE));
        afterSend.run();
    }

    private void failIfDown() {
        if (failing) {
            throw new GatewayUnavailableException("conveyor gateway down", null);
        }
    }
}
