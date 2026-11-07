package com.example.controltower.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.controltower.application.port.out.MaintenanceDesk;
import com.example.controltower.domain.GatewayUnavailableException;
import com.example.controltower.domain.MaintenanceRequest;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

abstract class MaintenanceDeskContract {

    private static final Instant T0 = Instant.parse("2026-11-07T00:00:00Z");

    abstract MaintenanceDesk subject();

    @Test
    void open_isListedAndHeldAgainstTheEquipment() {
        UUID id = subject().open("CV-1", "벨트 소음", T0);

        assertThat(subject().hasOpen("CV-1")).isTrue();
        assertThat(subject().hasOpen("CV-2")).isFalse();
        assertThat(subject().openRequests()).containsExactly(new MaintenanceRequest(id, "CV-1", "벨트 소음", T0, null));
    }

    @Test
    void open_secondForTheSameEquipmentIsAGatewayFailure_theFirstStays() {
        UUID first = subject().open("CV-1", "벨트 소음", T0);

        assertThatThrownBy(() -> subject().open("CV-1", "다른 요청", T0))
                .isExactlyInstanceOf(GatewayUnavailableException.class);

        assertThat(subject().openRequests()).extracting(MaintenanceRequest::id).containsExactly(first);
    }

    @Test
    void close_onlyTheFirstCloseSucceeds() {
        UUID id = subject().open("CV-1", "벨트 소음", T0);

        assertThat(subject().close(id, T0.plusSeconds(1))).isTrue();
        assertThat(subject().close(id, T0.plusSeconds(2))).isFalse();
        assertThat(subject().close(UUID.randomUUID(), T0)).isFalse();

        assertThat(subject().hasOpen("CV-1")).isFalse();
        assertThat(subject().openRequests()).isEmpty();
    }

    @Test
    void open_afterCloseIsAllowed() {
        subject().close(subject().open("CV-1", "벨트 소음", T0), T0.plusSeconds(1));

        subject().open("CV-1", "재발", T0.plusSeconds(2));

        assertThat(subject().hasOpen("CV-1")).isTrue();
    }
}
