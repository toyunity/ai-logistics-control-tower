package com.example.controltower.simulator;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.client.RestClient;

/** 1초 틱과 이벤트 전송. 스케줄러 스레드 하나만 큐를 만지므로 컨트롤러에서 전송을 시작하면 안 된다. */
public class SimulatorRunner {

    private static final Logger log = System.getLogger(SimulatorRunner.class.getName());

    private final PlantSimulator simulator;
    private final Environment env;
    private final RestClient client;
    private final Deque<SimEvent> queue = new ArrayDeque<>();
    private boolean announced;

    public SimulatorRunner(PlantSimulator simulator, Environment env) {
        this.simulator = simulator;
        this.env = env;
        SimpleClientHttpRequestFactory timeouts = new SimpleClientHttpRequestFactory();
        timeouts.setConnectTimeout(Duration.ofSeconds(2));
        timeouts.setReadTimeout(Duration.ofSeconds(3));
        this.client = RestClient.builder().requestFactory(timeouts).build();
    }

    @Scheduled(fixedDelay = 1000)
    public void tick() {
        simulator.tick(Instant.now());
        if (!announced) {
            announced = true;
            simulator.announceScanner();
        }
        queue.addAll(simulator.drainEvents());
        push();
    }

    // 순서를 지키려고 첫 실패에서 멈춘다. 그래서 수신 측이 계속 4xx로 거절하는 이벤트가 있으면 뒤 이벤트까지 밀린다.
    // 응답 유실로 재전송돼도 수신 측이 이벤트 ID로 거른다.
    private void push() {
        String target = eventTarget();
        if (target == null) {
            return;
        }
        try {
            while (!queue.isEmpty()) {
                client.post()
                        .uri(target + "/api/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(queue.peek())
                        .retrieve()
                        .toBodilessEntity();
                queue.remove();
            }
        } catch (RuntimeException e) {
            log.log(Level.DEBUG, "event push failed, retrying next tick");
        }
    }

    // 임의 포트는 서버가 뜬 뒤에야 정해지므로 호출할 때마다 읽는다.
    private String eventTarget() {
        String configured = env.getProperty("controltower.simulator.event-target");
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        String port = env.getProperty("local.server.port");
        return port == null ? null : "http://127.0.0.1:" + port;
    }
}
