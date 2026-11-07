package com.example.controltower.simulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.controltower.support.FakeAiConfig;
import com.example.controltower.support.TestInfraConfig;
import jakarta.servlet.Filter;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
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
    private static final AtomicReference<Hold> HOLD = new AtomicReference<>();

    private record Hold(CountDownLatch entered, CountDownLatch release) {
        // 읽기 타임아웃이 먼저 끝내면 재시도 사이에 틱이 돌 수 있어, 조금씩 보내 전송 한 건을 끝까지 붙잡는다.
        void block(ServletResponse response) throws IOException {
            entered.countDown();
            ServletOutputStream out = response.getOutputStream();
            try {
                while (!release.await(500, TimeUnit.MILLISECONDS)) {
                    out.write(' ');
                    response.flushBuffer();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Capture {
        @Bean
        Filter eventCapture() {
            return (request, response, chain) -> {
                if ("/api/events".equals(((HttpServletRequest) request).getRequestURI())) {
                    PUSHED.add(new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
                    Hold hold = HOLD.get();
                    if (hold != null) {
                        hold.block(response);
                    }
                    return;
                }
                chain.doFilter(request, response);
            };
        }
    }

    @Autowired PlantSimulator sim;

    @AfterEach
    void restore() {
        Hold hold = HOLD.getAndSet(null);
        if (hold != null) {
            hold.release().countDown();
        }
        sim.reset();
    }

    private Instant lastTick() {
        return sim.snapshot().safety().getFirst().ts();
    }

    @Test
    void tickerPushesToItsOwnServerPort() {
        await().atMost(Duration.ofSeconds(30)).until(() -> PUSHED.stream()
                .anyMatch(body -> body.contains("\"equipmentId\":\"SC-1\"") && body.contains("\"code\":\"READ_NORMAL\"")));

        assertThat(sim.start("robot-battery")).isTrue();
        await().atMost(Duration.ofSeconds(30))
                .until(() -> PUSHED.stream().anyMatch(body -> body.contains("BATTERY_LOW")));
    }

    // 안전 신호 시각이 3초 한도를 넘으면 전송 지연만으로 재가동이 거절된다.
    @Test
    void heldPush_doesNotStopTheTicker() throws InterruptedException {
        await().atMost(Duration.ofSeconds(30)).until(() -> !PUSHED.isEmpty());
        Hold hold = new Hold(new CountDownLatch(1), new CountDownLatch(1));
        HOLD.set(hold);

        assertThat(sim.start("scanner-degraded")).isTrue();
        assertThat(hold.entered().await(30, TimeUnit.SECONDS)).isTrue();

        Instant held = lastTick();
        await().atMost(Duration.ofSeconds(30)).until(() -> lastTick().isAfter(held.plusSeconds(3)));
    }
}
