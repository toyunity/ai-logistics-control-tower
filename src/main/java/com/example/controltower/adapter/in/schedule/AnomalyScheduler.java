package com.example.controltower.adapter.in.schedule;

import com.example.controltower.application.port.in.DetectAnomalies;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 5초 주기 감지. 시뮬레이터가 꺼져도 돌아야 하므로 스케줄링을 직접 켠다. AI를 부르지 않는다. */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "controltower.detection.enabled", havingValue = "true")
public class AnomalyScheduler {

    private static final Logger log = LoggerFactory.getLogger(AnomalyScheduler.class);

    private final DetectAnomalies detection;

    public AnomalyScheduler(DetectAnomalies detection) {
        this.detection = detection;
    }

    // 예외가 새면 이 주기만 빠지지만, 메시지에 저장소 값이 섞일 수 있어 고정 문구만 남기고 삼킨다.
    @Scheduled(fixedDelay = 5000)
    void run() {
        try {
            detection.detect();
        } catch (RuntimeException e) {
            log.error("anomaly detection cycle failed: {}", e.getClass().getName());
        }
    }
}
