package com.example.controltower.application.service;

import com.example.controltower.application.port.in.DetectAnomalies;
import com.example.controltower.application.port.in.IngestEvent;
import com.example.controltower.application.port.out.EventLog;
import com.example.controltower.domain.EquipmentEvent;
import com.example.controltower.domain.LineTopology;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.regex.Pattern;

public final class IngestEventService implements IngestEvent {

    private static final Logger log = System.getLogger(IngestEventService.class.getName());

    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,39}");

    private final EventLog events;
    private final LineTopology topology;
    private final DetectAnomalies detection;
    private final Executor detectionExecutor;

    /** @param detectionExecutor 감지는 게이트웨이를 읽으므로 수신 스레드에서 돌리지 않는다. 느린 설비가 수신 응답을 붙잡으면 송신 측 큐가 밀린다. */
    public IngestEventService(
            EventLog events, LineTopology topology, DetectAnomalies detection, Executor detectionExecutor) {
        this.events = events;
        this.topology = topology;
        this.detection = detection;
        this.detectionExecutor = detectionExecutor;
    }

    @Override
    public void ingest(EquipmentEvent event) {
        // 저장된 이벤트는 AI 도구까지 전달되므로 현장 원문이 그대로 들어오지 못하게 막는다.
        if (!topology.contains(event.equipmentId()) || !CODE.matcher(event.code()).matches()) {
            throw new IllegalArgumentException("unknown equipment or malformed event code");
        }
        if (!events.append(event)) {
            return;
        }
        // 이벤트는 이미 저장됐으므로 감지가 밀리거나 실패해도 다음 스케줄러 주기가 다시 감지한다.
        try {
            detectionExecutor.execute(this::detectQuietly);
        } catch (RejectedExecutionException e) {
            log.log(Level.DEBUG, "anomaly detection after event ingest skipped");
        }
    }

    // 예외 메시지에 이벤트 내용이 섞일 수 있어 고정 문구만 남긴다.
    private void detectQuietly() {
        try {
            detection.detect();
        } catch (RuntimeException e) {
            log.log(Level.ERROR, "anomaly detection failed after event ingest");
        }
    }
}
