package com.example.controltower.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.controltower.application.port.out.Incidents;
import com.example.controltower.domain.Incident;
import com.example.controltower.domain.Severity;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

abstract class IncidentsContract {

    private static final Instant T0 = Instant.parse("2026-11-07T00:00:00Z");

    abstract Incidents subject();

    private static Incident incident(String equipment, String kind, Severity severity) {
        return new Incident(UUID.randomUUID(), equipment, kind, severity, T0, null);
    }

    @Test
    void open_isListedUntilClosed() {
        Incident i = incident("CV-1", "CONVEYOR_STOPPED", Severity.SEV3);

        subject().open(i);
        assertThat(subject().open()).containsExactly(i);

        subject().close(i.id(), T0.plusSeconds(1));
        assertThat(subject().open()).isEmpty();
    }

    @Test
    void open_isIdempotentForTheSameEquipmentAndKind_theFirstStays() {
        Incident first = incident("CV-1", "CONVEYOR_STOPPED", Severity.SEV3);
        subject().open(first);

        subject().open(incident("CV-1", "CONVEYOR_STOPPED", Severity.SEV1));

        assertThat(subject().open()).containsExactly(first);
    }

    @Test
    void open_otherKindOrEquipmentIsSeparate() {
        subject().open(incident("CV-1", "CONVEYOR_STOPPED", Severity.SEV3));
        subject().open(incident("CV-1", "CHUTE_FULL", Severity.SEV3));
        subject().open(incident("CV-2", "CONVEYOR_STOPPED", Severity.SEV3));

        assertThat(subject().open()).hasSize(3);
    }

    @Test
    void open_afterCloseStartsANewIncident() {
        Incident first = incident("CV-1", "CONVEYOR_STOPPED", Severity.SEV3);
        Incident second = incident("CV-1", "CONVEYOR_STOPPED", Severity.SEV2);
        subject().open(first);
        subject().close(first.id(), T0.plusSeconds(1));

        subject().open(second);

        assertThat(subject().open()).containsExactly(second);
    }

    @Test
    void close_missingOrAlreadyClosedIsHarmless() {
        Incident i = incident("CV-1", "CONVEYOR_STOPPED", Severity.SEV3);
        subject().open(i);
        subject().close(i.id(), T0.plusSeconds(1));

        subject().close(i.id(), T0.plusSeconds(2));
        subject().close(UUID.randomUUID(), T0.plusSeconds(2));

        assertThat(subject().open()).isEmpty();
    }

    @Test
    void regrade_updatesSeverityOnly() {
        Incident i = incident("CV-1", "CONVEYOR_STOPPED", Severity.SEV3);
        subject().open(i);

        subject().regrade(i.id(), Severity.SEV1);

        assertThat(subject().open())
                .containsExactly(new Incident(i.id(), "CV-1", "CONVEYOR_STOPPED", Severity.SEV1, T0, null));
    }

    @Test
    void regrade_missingIsHarmless() {
        subject().regrade(UUID.randomUUID(), Severity.SEV1);

        assertThat(subject().open()).isEmpty();
    }
}
