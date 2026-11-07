package com.example.controltower.application.port.out;

import com.example.controltower.domain.SorterStatus;
import java.util.UUID;

/** 조회 실패와 타임아웃은 GatewayUnavailableException, 전송 타임아웃은 CommandOutcomeUnknownException이다. */
public interface Sorters {

    SorterStatus get();

    void divert(UUID commandId, String fromChute, String toChute);
}
