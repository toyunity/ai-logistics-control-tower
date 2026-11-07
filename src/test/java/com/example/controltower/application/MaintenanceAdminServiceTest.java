package com.example.controltower.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.controltower.application.service.MaintenanceAdminService;
import com.example.controltower.domain.Actor;
import com.example.controltower.domain.Role;
import com.example.controltower.support.InMemoryMaintenanceDesk;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MaintenanceAdminServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-07T00:00:00Z");

    private final InMemoryMaintenanceDesk desk = new InMemoryMaintenanceDesk();
    private final MaintenanceAdminService service =
            new MaintenanceAdminService(desk, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void maintenanceClose_operatorOnly() {
        UUID id = desk.open("SR-1", "벨트 소음 점검", NOW);

        assertThat(service.close(id, new Actor("viewer-1", Role.VIEWER))).isFalse();
        assertThat(service.close(id, Actor.ANONYMOUS)).isFalse();
        assertThat(service.close(id, null)).isFalse();
        assertThat(desk.openRequests()).hasSize(1);

        assertThat(service.close(id, new Actor("op-1", Role.OPERATOR))).isTrue();
        assertThat(desk.openRequests()).isEmpty();
    }

    @Test
    void closingUnknownRequest_isFalse() {
        assertThat(service.close(UUID.randomUUID(), new Actor("op-1", Role.OPERATOR))).isFalse();
    }
}
