package com.example.controltower.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.controltower.application.port.in.DetectAnomalies;
import com.example.controltower.application.service.IngestEventService;
import com.example.controltower.domain.EquipmentEvent;
import com.example.controltower.support.InMemoryEventLog;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class IngestEventServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-07T00:00:00Z");

    private final InMemoryEventLog log = new InMemoryEventLog();
    private final AtomicInteger detections = new AtomicInteger();
    private final DetectAnomalies counting = detections::incrementAndGet;

    private static EquipmentEvent event(String id, Instant at) {
        return new EquipmentEvent(id, "CV-3", "STOPPED", null, at);
    }

    @Test
    void duplicateEvent_ignored() {
        IngestEventService service = new IngestEventService(log, counting);

        service.ingest(event("e-1", NOW));
        service.ingest(event("e-1", NOW));

        assertThat(log.since(NOW)).hasSize(1);
        assertThat(detections).hasValue(1);
    }

    @Test
    void outOfOrderEvent_storedButLatestStaysNewest() {
        IngestEventService service = new IngestEventService(log, counting);

        service.ingest(event("newer", NOW));
        service.ingest(event("older", NOW.minusSeconds(60)));

        assertThat(log.since(NOW.minusSeconds(60))).extracting(EquipmentEvent::eventId).containsExactly("older", "newer");
        assertThat(log.latest("CV-3").orElseThrow().eventId()).isEqualTo("newer");
    }

    @Test
    void detectionFailure_doesNotFailIngest() {
        IngestEventService service = new IngestEventService(log, () -> {
            throw new IllegalStateException("incident store down");
        });

        service.ingest(event("e-1", NOW));

        assertThat(log.latest("CV-3")).isPresent();
    }

    @Test
    void storeFailure_reachesCaller() {
        log.setFailing(true);
        IngestEventService service = new IngestEventService(log, counting);

        assertThatThrownBy(() -> service.ingest(event("e-1", NOW))).isInstanceOf(IllegalStateException.class);
        assertThat(detections).hasValue(0);
    }
}
