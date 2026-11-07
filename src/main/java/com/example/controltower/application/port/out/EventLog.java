package com.example.controltower.application.port.out;

import com.example.controltower.domain.EquipmentEvent;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 시각은 모두 도착 순서가 아니라 occurredAt 기준이다. 늦게 도착한 옛 이벤트가 현재 상태를 덮으면 안 된다. */
public interface EventLog {

    /** @return 처음 보는 eventId면 true, 중복이면 false */
    boolean append(EquipmentEvent event);

    /** @return occurredAt 오름차순 */
    List<EquipmentEvent> between(String equipmentId, Instant from, Instant to);

    /** @return 모든 설비의 이벤트. occurredAt 오름차순 */
    List<EquipmentEvent> since(Instant from);

    Optional<EquipmentEvent> latest(String equipmentId);
}
