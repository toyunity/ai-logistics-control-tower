package com.example.controltower.application.port.out;

import com.example.controltower.domain.ConveyorStatus;
import java.util.List;
import java.util.UUID;

/** 조회 실패와 타임아웃은 GatewayUnavailableException, 전송 타임아웃은 CommandOutcomeUnknownException이다. */
public interface Conveyors {

    ConveyorStatus get(String id);

    List<ConveyorStatus> all();

    void restart(UUID commandId, String id);
}
