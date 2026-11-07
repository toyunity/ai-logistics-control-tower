package com.example.controltower.application.port.out;

import com.example.controltower.domain.MaintenanceRequest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface MaintenanceDesk {

    /** @throws com.example.controltower.domain.GatewayUnavailableException 조회할 수 없을 때 */
    boolean hasOpen(String equipmentId);

    /** @throws com.example.controltower.domain.GatewayUnavailableException 그 설비에 이미 열린 요청이 있을 때 */
    UUID open(String equipmentId, String summary, Instant now);

    /** @return 이미 닫혔거나 없으면 false */
    boolean close(UUID id, Instant now);

    List<MaintenanceRequest> openRequests();
}
