package com.example.controltower.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.controltower.application.port.in.PlantStatus;
import com.example.controltower.application.port.out.Conveyors;
import com.example.controltower.application.service.PlantStatusService;
import com.example.controltower.domain.Incident;
import com.example.controltower.domain.LineTopology;
import com.example.controltower.domain.RobotStatus;
import com.example.controltower.domain.RunState;
import com.example.controltower.domain.SorterStatus;
import com.example.controltower.support.InMemoryEventLog;
import com.example.controltower.support.InMemoryIncidents;
import com.example.controltower.support.InMemoryMaintenanceDesk;
import com.example.controltower.support.InMemoryRobotFleet;
import com.example.controltower.support.InMemorySorters;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 컨베이어는 실제 어댑터와 기본 타임아웃으로 로컬 HTTP 서버를 읽는다. 늦은 설비가 상태 화면을 얼마나 붙잡는지는 어댑터 타임아웃이 정한다.
 * 나머지 설비군과 저장소는 메모리 대역이다.
 */
@WebMvcTest(PlantController.class)
@Import(PlantControllerTest.Wiring.class)
@ExtendWith(OutputCaptureExtension.class)
class PlantControllerTest {

    private enum Mode { OK, DEAD, HANG }

    private static volatile Mode mode = Mode.OK;
    private static volatile CountDownLatch release = new CountDownLatch(1);
    private static final HttpServer VENDOR_A = startVendorA();

    private static HttpServer startVendorA() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.createContext("/sim/vendor-a/plc/", exchange -> {
                try (exchange) {
                    switch (mode) {
                        // 바이트를 조금씩 보내면 소켓 읽기 타임아웃을 넘길 수 있어 아무것도 보내지 않고 붙잡는다.
                        case HANG -> release.await(30, TimeUnit.SECONDS);
                        case DEAD -> exchange.sendResponseHeaders(503, -1);
                        case OK -> {
                            byte[] body = "{\"registers\":[1,0,1]}".getBytes(StandardCharsets.UTF_8);
                            exchange.getResponseHeaders().add("Content-Type", "application/json");
                            exchange.sendResponseHeaders(200, body.length);
                            exchange.getResponseBody().write(body);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void vendorA(DynamicPropertyRegistry registry) {
        registry.add("controltower.equipment.base-url", () -> "http://127.0.0.1:" + VENDOR_A.getAddress().getPort());
    }

    static class FailingIncidents extends InMemoryIncidents {
        volatile boolean failing;

        @Override
        public List<Incident> open() {
            if (failing) {
                throw new IllegalStateException("SECRET-SQL-VALUE");
            }
            return super.open();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @ComponentScan(
            basePackages = "com.example.controltower.adapter.out.equipment",
            useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(
                    type = FilterType.REGEX,
                    pattern = ".*\\.equipment\\.(VendorAConveyors|EquipmentHttp)"))
    static class Wiring {

        @Bean
        FailingIncidents incidents() {
            return new FailingIncidents();
        }

        @Bean
        PlantStatus plantStatus(Conveyors conveyors, FailingIncidents incidents) {
            return new PlantStatusService(
                    conveyors,
                    new InMemorySorters(new SorterStatus("SR-1", RunState.RUNNING, false, List.of(), Map.of())),
                    new InMemoryRobotFleet(new RobotStatus("AMR-01", RunState.RUNNING, 80, 1, 5)),
                    new InMemoryEventLog(),
                    incidents,
                    new InMemoryMaintenanceDesk(),
                    LineTopology.DEFAULT,
                    Clock.systemUTC());
        }
    }

    @Autowired MockMvc mvc;
    @Autowired FailingIncidents incidents;

    @Value("${controltower.equipment.read-timeout:3s}")
    Duration readTimeout;

    @AfterEach
    void restore() {
        mode = Mode.OK;
        release.countDown();
        release = new CountDownLatch(1);
        incidents.failing = false;
    }

    @AfterAll
    static void stop() {
        VENDOR_A.stop(0);
    }

    @Test
    void statusReadsRealConveyorAdapter() throws Exception {
        mvc.perform(get("/api/plant/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.equipment[?(@.id=='CV-1')].state").value("RUNNING"))
                .andExpect(jsonPath("$.equipment[?(@.id=='AMR-01')].state").value("RUNNING"))
                .andExpect(jsonPath("$.incidents").isArray())
                .andExpect(jsonPath("$.maintenance").isArray())
                .andExpect(jsonPath("$.at").isString());
    }

    @Test
    void statusWithDeadConveyorAdapter_200AndUnknown() throws Exception {
        mode = Mode.DEAD;

        mvc.perform(get("/api/plant/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.equipment[?(@.id=='CV-1')].state").value("UNKNOWN"))
                .andExpect(jsonPath("$.equipment[?(@.id=='CV-3')].state").value("UNKNOWN"))
                .andExpect(jsonPath("$.equipment[?(@.id=='AMR-01')].state").value("RUNNING"));
    }

    /**
     * 이름은 계획의 목표(3.5초 안)를 따른다. 규칙상 상한은 단언하지 않고, 측정값은 작업 보고에 남긴다.
     * 하한은 서버가 실제로 붙잡았음을 보이고, 타임아웃은 교착 감지용이다.
     */
    @Test
    @Timeout(30)
    void statusUnderSlowAdapter_returnsWithin3500ms() throws Exception {
        mode = Mode.HANG;

        long started = System.nanoTime();
        mvc.perform(get("/api/plant/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.equipment[?(@.id=='CV-1')].state").value("UNKNOWN"))
                .andExpect(jsonPath("$.equipment[?(@.id=='AMR-01')].state").value("RUNNING"));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertThat(elapsed).isGreaterThanOrEqualTo(readTimeout);
    }

    @Test
    void storeFailure_500WithFixedBody(CapturedOutput output) throws Exception {
        incidents.failing = true;

        String body = mvc.perform(get("/api/plant/status"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").isString())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).doesNotContain("SECRET-SQL-VALUE");
        assertThat(output.getOut() + output.getErr()).doesNotContain("SECRET-SQL-VALUE");
    }
}
