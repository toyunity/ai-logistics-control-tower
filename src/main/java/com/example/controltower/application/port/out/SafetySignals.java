package com.example.controltower.application.port.out;

import com.example.controltower.domain.SafetyStatus;

/** 조회 실패와 타임아웃은 GatewayUnavailableException이다. */
public interface SafetySignals {

    SafetyStatus read(String zone);
}
