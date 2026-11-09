package com.example.controltower.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.controltower.application.port.in.MaintenanceAdmin;
import com.example.controltower.application.service.MaintenanceAdminService;
import com.example.controltower.support.InMemoryMaintenanceDesk;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.reactive.server.WebTestClient;

@WebFluxTest({MaintenanceController.class, SessionController.class})
@Import(MaintenanceControllerTest.Wiring.class)
class MaintenanceControllerTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class Wiring {

        @Bean
        InMemoryMaintenanceDesk desk() {
            return new InMemoryMaintenanceDesk();
        }

        @Bean
        MaintenanceAdmin maintenanceAdmin(InMemoryMaintenanceDesk desk) {
            return new MaintenanceAdminService(desk, Clock.systemUTC());
        }
    }

    @Autowired WebTestClient client;
    @Autowired InMemoryMaintenanceDesk desk;

    private SessionCookie session;

    @BeforeEach
    void newSession() {
        session = new SessionCookie(client);
    }

    private void login(String accountId) {
        session.loginOk(accountId);
    }

    private WebTestClient.ResponseSpec close(UUID id) {
        return session.with(client.post().uri("/api/maintenance/{id}/close", id)).exchange();
    }

    private boolean isOpen(UUID id) {
        return desk.openRequests().stream().anyMatch(r -> r.id().equals(id));
    }

    @Test
    void operatorCloses_200() {
        UUID id = desk.open("CV-" + UUID.randomUUID(), "벨트 점검", Instant.now());
        login("operator");

        close(id).expectStatus().isOk().expectBody().jsonPath("$.message").isNotEmpty();

        assertThat(isOpen(id)).isFalse();
    }

    @Test
    void viewer_403() {
        UUID id = desk.open("CV-" + UUID.randomUUID(), "벨트 점검", Instant.now());
        login("viewer");

        close(id).expectStatus().isForbidden();

        assertThat(isOpen(id)).isTrue();
    }

    @Test
    void anonymous_403() {
        UUID id = desk.open("CV-" + UUID.randomUUID(), "벨트 점검", Instant.now());

        close(id).expectStatus().isForbidden();

        assertThat(isOpen(id)).isTrue();
    }

    @Test
    void unknownOrClosed_404() {
        login("operator");

        close(UUID.randomUUID()).expectStatus().isNotFound();
    }
}
