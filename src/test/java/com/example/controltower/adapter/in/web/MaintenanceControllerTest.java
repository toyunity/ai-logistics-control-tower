package com.example.controltower.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.controltower.application.port.in.MaintenanceAdmin;
import com.example.controltower.application.service.MaintenanceAdminService;
import com.example.controltower.support.InMemoryMaintenanceDesk;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@WebMvcTest({MaintenanceController.class, SessionController.class})
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

    @Autowired MockMvc mvc;
    @Autowired InMemoryMaintenanceDesk desk;

    private final MockHttpSession session = new MockHttpSession();

    private void login(String accountId) throws Exception {
        mvc.perform(post("/api/session")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"" + accountId + "\"}"))
                .andExpect(status().isOk());
    }

    private ResultActions close(UUID id) throws Exception {
        return mvc.perform(post("/api/maintenance/{id}/close", id).session(session));
    }

    private boolean isOpen(UUID id) {
        return desk.openRequests().stream().anyMatch(r -> r.id().equals(id));
    }

    @Test
    void operatorCloses_200() throws Exception {
        UUID id = desk.open("CV-" + UUID.randomUUID(), "벨트 점검", Instant.now());
        login("operator");

        close(id).andExpect(status().isOk()).andExpect(jsonPath("$.message").isString());

        assertThat(isOpen(id)).isFalse();
    }

    @Test
    void viewer_403() throws Exception {
        UUID id = desk.open("CV-" + UUID.randomUUID(), "벨트 점검", Instant.now());
        login("viewer");

        close(id).andExpect(status().isForbidden());

        assertThat(isOpen(id)).isTrue();
    }

    @Test
    void anonymous_403() throws Exception {
        UUID id = desk.open("CV-" + UUID.randomUUID(), "벨트 점검", Instant.now());

        close(id).andExpect(status().isForbidden());

        assertThat(isOpen(id)).isTrue();
    }

    @Test
    void unknownOrClosed_404() throws Exception {
        login("operator");

        close(UUID.randomUUID()).andExpect(status().isNotFound());
    }
}
