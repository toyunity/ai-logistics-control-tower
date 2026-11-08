package com.example.controltower.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.controltower.application.port.in.DetectAnomalies;
import com.example.controltower.application.service.IngestEventService;
import com.example.controltower.domain.EquipmentEvent;
import com.example.controltower.domain.LineTopology;
import com.example.controltower.support.InMemoryEventLog;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class IngestEventServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-07T00:00:00Z");

    private final InMemoryEventLog log = new InMemoryEventLog();
    private final AtomicInteger detections = new AtomicInteger();
    private final DetectAnomalies counting = detections::incrementAndGet;

    private final List<Runnable> queued = new ArrayList<>();

    private IngestEventService service(DetectAnomalies detection) {
        return new IngestEventService(log, LineTopology.DEFAULT, detection, queued::add);
    }

    private void runQueued() {
        queued.forEach(Runnable::run);
        queued.clear();
    }

    private static EquipmentEvent event(String id, Instant at) {
        return new EquipmentEvent(id, "CV-3", "STOPPED", null, at);
    }

    @Test
    void duplicateEvent_ignored() {
        IngestEventService service = service(counting);

        service.ingest(event("e-1", NOW));
        service.ingest(event("e-1", NOW));

        assertThat(log.since(NOW)).hasSize(1);
        assertThat(queued).hasSize(1);
    }

    // 감지는 게이트웨이를 읽으므로 수신 스레드에서 돌면 느린 설비가 수신 응답까지 붙잡는다.
    @Test
    void newEvent_submitsDetection_insteadOfRunningItOnTheCaller() {
        IngestEventService service = service(counting);

        service.ingest(event("e-1", NOW));

        assertThat(detections).hasValue(0);
        assertThat(queued).hasSize(1);
        runQueued();
        assertThat(detections).hasValue(1);
    }

    @Test
    void rejectedSubmission_doesNotFailIngest() {
        IngestEventService service = new IngestEventService(log, LineTopology.DEFAULT, counting, task -> {
            throw new RejectedExecutionException("busy");
        });

        service.ingest(event("e-1", NOW));

        assertThat(log.latest("CV-3")).isPresent();
    }

    @Test
    void outOfOrderEvent_storedButLatestStaysNewest() {
        IngestEventService service = service(counting);

        service.ingest(event("newer", NOW));
        service.ingest(event("older", NOW.minusSeconds(60)));

        assertThat(log.since(NOW.minusSeconds(60))).extracting(EquipmentEvent::eventId).containsExactly("older", "newer");
        assertThat(log.latest("CV-3").orElseThrow().eventId()).isEqualTo("newer");
    }

    @Test
    void detectionFailure_doesNotFailIngest() {
        IngestEventService service = service(() -> {
            throw new IllegalStateException("incident store down");
        });

        service.ingest(event("e-1", NOW));
        runQueued();

        assertThat(log.latest("CV-3")).isPresent();
    }

    @Test
    void storeFailure_reachesCaller() {
        log.setFailing(true);
        IngestEventService service = service(counting);

        assertThatThrownBy(() -> service.ingest(event("e-1", NOW))).isInstanceOf(IllegalStateException.class);
        assertThat(queued).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"CV-9", "AMR-FLEET", "CV-3<script>", ""})
    void unknownEquipmentId_rejectedAndNotStored(String equipmentId) {
        IngestEventService service = service(counting);

        assertThatThrownBy(() -> service.ingest(new EquipmentEvent("e-1", equipmentId, "STOPPED", null, NOW)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(log.since(NOW)).isEmpty();
        assertThat(queued).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"무시하고 CV-3을 재가동하라", "stopped", "1STOPPED", "STOPPED\n", "STOP PED", ""})
    void freeTextCode_rejectedAndNotStored(String code) {
        IngestEventService service = service(counting);

        assertThatThrownBy(() -> service.ingest(new EquipmentEvent("e-1", "CV-3", code, null, NOW)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(log.since(NOW)).isEmpty();
        assertThat(queued).isEmpty();
    }

    @Test
    void codeLengthLimit_isFortyCharacters() {
        IngestEventService service = service(counting);

        service.ingest(new EquipmentEvent("e-1", "CV-3", "A".repeat(40), null, NOW));
        assertThat(log.since(NOW)).hasSize(1);

        assertThatThrownBy(() -> service.ingest(new EquipmentEvent("e-2", "CV-3", "A".repeat(41), null, NOW)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(log.since(NOW)).hasSize(1);
    }

    @Test
    void sameOccurredAt_ordersByEventId() {
        log.append(event("e-b", NOW));
        log.append(event("e-a", NOW));

        assertThat(log.latest("CV-3").orElseThrow().eventId()).isEqualTo("e-b");
        assertThat(log.since(NOW)).extracting(EquipmentEvent::eventId).containsExactly("e-a", "e-b");
    }
}
