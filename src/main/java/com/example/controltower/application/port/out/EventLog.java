package com.example.controltower.application.port.out;

import com.example.controltower.domain.EquipmentEvent;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 순서는 모두 도착 순서가 아니라 occurredAt, 같으면 eventId 기준이다.
 * 늦게 도착한 옛 이벤트가 현재 상태를 덮으면 안 되고, 같은 시각의 결과는 저장소가 달라도 같아야 한다.
 */
public interface EventLog {

    /** @return 처음 보는 eventId면 true, 중복이면 false */
    boolean append(EquipmentEvent event);

    /** @return 위 순서의 오름차순 */
    List<EquipmentEvent> between(String equipmentId, Instant from, Instant to);

    /** @return 모든 설비의 이벤트. 위 순서의 오름차순 */
    List<EquipmentEvent> since(Instant from);

    /** @return 위 순서로 가장 뒤의 이벤트 */
    Optional<EquipmentEvent> latest(String equipmentId);
}
