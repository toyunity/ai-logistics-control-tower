package com.example.controltower.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.controltower.application.port.out.EventLog;
import com.example.controltower.domain.EquipmentEvent;
import java.time.Instant;
import org.junit.jupiter.api.Test;

abstract class EventLogContract {

    private static final Instant T0 = Instant.parse("2026-11-07T00:00:00Z");

    abstract EventLog subject();

    private static EquipmentEvent event(String id, String equipment, Instant at) {
        return new EquipmentEvent(id, equipment, "STOPPED", 7, at);
    }

    @Test
    void duplicateEventReturnsFalse_andKeepsTheFirst() {
        EquipmentEvent first = event("e1", "CV-1", T0);

        assertThat(subject().append(first)).isTrue();
        assertThat(subject().append(new EquipmentEvent("e1", "CV-2", "FAULT", 9, T0.plusSeconds(1)))).isFalse();

        assertThat(subject().since(T0)).containsExactly(first);
    }

    @Test
    void valueMayBeAbsent_andZeroIsNotAbsent() {
        EquipmentEvent none = new EquipmentEvent("e1", "CV-1", "STOPPED", null, T0);
        EquipmentEvent zero = new EquipmentEvent("e2", "CV-1", "STOPPED", 0, T0.plusSeconds(1));
        subject().append(none);
        subject().append(zero);

        assertThat(subject().since(T0)).containsExactly(none, zero);
    }

    @Test
    void latest_isByOccurrenceNotArrival() {
        EquipmentEvent newer = event("e1", "CV-1", T0.plusSeconds(10));
        subject().append(newer);
        subject().append(event("e2", "CV-1", T0));

        assertThat(subject().latest("CV-1")).contains(newer);
    }

    @Test
    void latest_tieGoesToTheLastEventIdInCodePointOrder() {
        // 사전식 정렬이면 a가 B 뒤가 아니라 앞에 온다. 대역과 같은 결과가 나오려면 DB도 코드 순서로 정렬해야 한다.
        EquipmentEvent lower = event("a", "CV-1", T0);
        subject().append(lower);
        subject().append(event("B", "CV-1", T0));

        assertThat(subject().latest("CV-1")).contains(lower);
    }

    @Test
    void latest_isPerEquipment_andEmptyWhenNone() {
        EquipmentEvent mine = event("e1", "CV-1", T0);
        subject().append(mine);
        subject().append(event("e2", "CV-2", T0.plusSeconds(1)));

        assertThat(subject().latest("CV-1")).contains(mine);
        assertThat(subject().latest("CV-9")).isEmpty();
    }

    @Test
    void since_ordersByOccurrenceThenEventId_acrossEquipment_fromInclusive() {
        EquipmentEvent early = event("e0", "CV-1", T0.minusSeconds(1));
        EquipmentEvent atFromLower = event("a", "CV-2", T0);
        EquipmentEvent atFromUpper = event("B", "CV-1", T0);
        EquipmentEvent late = event("e9", "CV-2", T0.plusSeconds(1));
        subject().append(late);
        subject().append(atFromLower);
        subject().append(early);
        subject().append(atFromUpper);

        assertThat(subject().since(T0)).containsExactly(atFromUpper, atFromLower, late);
    }

    @Test
    void between_isOneEquipmentBothEndsInclusive_inOrder() {
        EquipmentEvent start = event("e1", "CV-1", T0);
        EquipmentEvent end = event("e2", "CV-1", T0.plusSeconds(10));
        subject().append(end);
        subject().append(start);
        subject().append(event("e3", "CV-1", T0.minusNanos(1000)));
        subject().append(event("e4", "CV-1", T0.plusSeconds(10).plusNanos(1000)));
        subject().append(event("e5", "CV-2", T0.plusSeconds(5)));

        assertThat(subject().between("CV-1", T0, T0.plusSeconds(10))).containsExactly(start, end);
    }
}
