package com.example.controltower.simulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.controltower.support.FakeAiConfig;
import com.example.controltower.support.TestInfraConfig;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/** 실제 서버 포트 해석, 스케줄러, 직렬화까지 한 번에 확인한다. 업무 쪽 수신부와 겹치지 않게 필터에서 가로챈다. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "controltower.simulator.enabled=true")
@Import({TestInfraConfig.class, FakeAiConfig.class, SimulatorPushIT.Capture.class})
class SimulatorPushIT {

    private static final Queue<String> PUSHED = new ConcurrentLinkedQueue<>();

    @TestConfiguration(proxyBeanMethods = false)
    static class Capture {
        @Bean
        Filter eventCapture() {
            return (request, response, chain) -> {
                if ("/api/events".equals(((HttpServletRequest) request).getRequestURI())) {
                    PUSHED.add(new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
                    return;
                }
                chain.doFilter(request, response);
            };
        }
    }

    @Autowired PlantSimulator sim;

    @Test
    void tickerPushesToItsOwnServerPort() {
        await().atMost(Duration.ofSeconds(30)).until(() -> !PUSHED.isEmpty());

        assertThat(PUSHED.peek()).contains("\"equipmentId\":\"SC-1\"").contains("\"code\":\"READ_NORMAL\"");

        sim.start("robot-battery");
        await().atMost(Duration.ofSeconds(30))
                .until(() -> PUSHED.stream().anyMatch(body -> body.contains("BATTERY_LOW")));
    }
}
