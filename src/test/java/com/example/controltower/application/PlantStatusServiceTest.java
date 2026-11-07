package com.example.controltower.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.controltower.application.port.in.EquipmentView;
import com.example.controltower.application.port.in.PlantSnapshot;
import com.example.controltower.application.service.PlantStatusService;
import com.example.controltower.domain.ChuteStatus;
import com.example.controltower.domain.ConveyorStatus;
import com.example.controltower.domain.EquipmentEvent;
import com.example.controltower.domain.FaultClass;
import com.example.controltower.domain.Incident;
import com.example.controltower.domain.LineTopology;
import com.example.controltower.domain.RobotStatus;
import com.example.controltower.domain.RootCauseCandidate;
import com.example.controltower.domain.RunState;
import com.example.controltower.domain.Severity;
import com.example.controltower.domain.SorterStatus;
import com.example.controltower.support.InMemoryConveyors;
import com.example.controltower.support.InMemoryEventLog;
import com.example.controltower.support.InMemoryIncidents;
import com.example.controltower.support.InMemoryMaintenanceDesk;
import com.example.controltower.support.InMemoryRobotFleet;
import com.example.controltower.support.InMemorySorters;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PlantStatusServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-07T00:00:00Z");

    private final InMemoryConveyors conveyors = new InMemoryConveyors(
            new ConveyorStatus("CV-1", "Z1", RunState.RUNNING, FaultClass.NONE),
            new ConveyorStatus("CV-2", "Z1", RunState.RUNNING, FaultClass.NONE),
            new ConveyorStatus("CV-3", "Z1", RunState.FAULT, FaultClass.MECHANICAL));
    private final InMemorySorters sorters = new InMemorySorters(new SorterStatus(
            "SR-1",
            RunState.RUNNING,
            true,
            List.of(new ChuteStatus("CH-03", "A", RunState.RUNNING, 100, 40, 60, true)),
            Map.of("CH-03", "CH-04", "CH-03<script>", "free text")));
    private final InMemoryRobotFleet fleet =
            new InMemoryRobotFleet(new RobotStatus("AMR-01", RunState.RUNNING, 55, 2, 5));
    private final InMemoryEventLog log = new InMemoryEventLog();
    private final InMemoryIncidents incidents = new InMemoryIncidents();
    private final InMemoryMaintenanceDesk desk = new InMemoryMaintenanceDesk();
    private final PlantStatusService service = new PlantStatusService(
            conveyors, sorters, fleet, log, incidents, desk, LineTopology.DEFAULT, Clock.fixed(NOW, ZoneOffset.UTC));

    private static EquipmentEvent event(String id, String equipment, String code, Integer value, Instant at) {
        return new EquipmentEvent(id, equipment, code, value, at);
    }

    private Map<String, EquipmentView> views() {
        return service.snapshot().equipment().stream()
                .collect(Collectors.toMap(EquipmentView::id, Function.identity()));
    }

    private EquipmentView scanner() {
        return service.equipment("SC-1").orElseThrow();
    }

    @Test
    void snapshot_listsAllTopologyEquipmentWithFactsAndRecords() {
        Incident incident = new Incident(UUID.randomUUID(), "CV-3", "CONVEYOR_STOPPED", Severity.SEV2, NOW, null);
        incidents.open(incident);
        desk.open("SR-1", "벨트 소음 점검", NOW);

        PlantSnapshot snapshot = service.snapshot();

        assertThat(snapshot.equipment())
                .extracting(EquipmentView::id)
                .containsExactlyElementsOf(LineTopology.DEFAULT.all());
        assertThat(snapshot.incidents()).containsExactly(incident);
        assertThat(snapshot.maintenance()).hasSize(1);
        assertThat(snapshot.at()).isEqualTo(NOW);

        Map<String, EquipmentView> views = views();
        assertThat(views.get("CV-3").kind()).isEqualTo("CONVEYOR");
        assertThat(views.get("CV-3").state()).isEqualTo(RunState.FAULT);
        assertThat(views.get("CV-3").facts()).containsEntry("fault", "MECHANICAL");
        assertThat(views.get("SR-1").kind()).isEqualTo("SORTER");
        assertThat(views.get("SR-1").facts()).containsEntry("blocked", true);
        assertThat(views.get("CH-03").kind()).isEqualTo("CHUTE");
        assertThat(views.get("CH-03").facts()).containsEntry("fillPercent", 100).containsEntry("full", true);
        assertThat(views.get("AMR-01").kind()).isEqualTo("ROBOT");
        assertThat(views.get("AMR-01").facts()).containsEntry("batteryPercent", 55);
    }

    @Test
    void facts_carryNoFreeTextFromDevices() {
        assertThat(views().get("SR-1").facts()).containsEntry("diversions", Map.of("CH-03", "CH-04"));
        assertThat(views().get("CH-03").facts()).doesNotContainKey("group");
    }

    @Test
    void snapshot_equipmentMissingFromRead_isUnknown() {
        EquipmentView missing = views().get("CH-01");

        assertThat(missing.state()).isEqualTo(RunState.UNKNOWN);
        assertThat(missing.facts()).isEmpty();
    }

    @Test
    void snapshot_gatewayDown_marksFamilyUnknown() {
        conveyors.setFailing(true);

        Map<String, EquipmentView> views = views();

        assertThat(List.of("CV-1", "CV-2", "CV-3")).allSatisfy(id -> {
            assertThat(views.get(id).state()).isEqualTo(RunState.UNKNOWN);
            assertThat(views.get(id).facts()).isEmpty();
        });
        assertThat(views.get("SR-1").state()).isEqualTo(RunState.RUNNING);
        assertThat(views.get("CH-03").state()).isEqualTo(RunState.RUNNING);
        assertThat(views.get("AMR-01").state()).isEqualTo(RunState.RUNNING);
    }

    @Test
    void snapshot_eachOtherFamilyDown_marksOnlyThatFamily() {
        sorters.setFailing(true);
        fleet.setFailing(true);
        log.append(event("e-1", "SC-1", "READ_NORMAL", 1, NOW));

        Map<String, EquipmentView> views = views();

        assertThat(views.get("SR-1").state()).isEqualTo(RunState.UNKNOWN);
        assertThat(views.get("CH-03").state()).isEqualTo(RunState.UNKNOWN);
        assertThat(views.get("AMR-01").state()).isEqualTo(RunState.UNKNOWN);
        assertThat(views.get("CV-1").state()).isEqualTo(RunState.RUNNING);
        assertThat(views.get("SC-1").state()).isEqualTo(RunState.RUNNING);
    }

    @Test
    void scanner_noHistory_isUnknown() {
        assertThat(scanner().state()).isEqualTo(RunState.UNKNOWN);
        assertThat(scanner().kind()).isEqualTo("SCANNER");
        assertThat(scanner().facts()).isEmpty();
    }

    @Test
    void scanner_logDown_isUnknownAndOthersUnaffected() {
        log.append(event("e-1", "SC-1", "READ_DEGRADED", 12, NOW));
        log.setFailing(true);

        assertThat(views().get("SC-1").state()).isEqualTo(RunState.UNKNOWN);
        assertThat(views().get("CV-1").state()).isEqualTo(RunState.RUNNING);
    }

    @Test
    void scanner_degradedAndNormal_translateWithFailRate() {
        log.append(event("e-1", "SC-1", "READ_DEGRADED", 12, NOW.minusSeconds(60)));
        assertThat(scanner().state()).isEqualTo(RunState.DEGRADED);
        assertThat(scanner().facts()).containsEntry("failRatePercent", 12);

        log.append(event("e-2", "SC-1", "READ_NORMAL", 1, NOW));
        assertThat(scanner().state()).isEqualTo(RunState.RUNNING);
        assertThat(scanner().facts()).containsEntry("failRatePercent", 1);
    }

    @Test
    void scanner_unknownCode_isUnknown() {
        log.append(event("e-1", "SC-1", "VENDOR_X", 12, NOW));

        assertThat(scanner().state()).isEqualTo(RunState.UNKNOWN);
        assertThat(scanner().facts()).isEmpty();
    }

    @Test
    void scanner_nullValue_omitsFailRate() {
        log.append(event("e-1", "SC-1", "READ_DEGRADED", null, NOW));

        assertThat(scanner().state()).isEqualTo(RunState.DEGRADED);
        assertThat(scanner().facts()).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"-5,0", "150,100"})
    void scanner_failRate_staysInBounds(int raw, int expected) {
        log.append(event("e-1", "SC-1", "READ_DEGRADED", raw, NOW));

        assertThat(scanner().facts()).containsEntry("failRatePercent", expected);
    }

    @Test
    void equipment_unknownId_isEmpty() {
        assertThat(service.equipment("CV-9")).isEmpty();
        assertThat(service.equipment("AMR-FLEET")).isEmpty();
    }

    @Test
    void equipment_readsOnlyItsFamily() {
        assertThat(service.equipment("AMR-01").orElseThrow().state()).isEqualTo(RunState.RUNNING);

        assertThat(conveyors.reads()).isZero();
        assertThat(sorters.reads()).isZero();
    }

    @Test
    void equipment_familyDown_isUnknownView() {
        fleet.setFailing(true);

        assertThat(service.equipment("AMR-01").orElseThrow().state()).isEqualTo(RunState.UNKNOWN);
    }

    @Test
    void timeline_clampsMinutesToOneThroughHundredTwenty() {
        log.append(event("before-120", "CV-3", "STOPPED", null, NOW.minus(Duration.ofMinutes(121))));
        log.append(event("at-120", "CV-3", "STOPPED", null, NOW.minus(Duration.ofMinutes(120))));
        log.append(event("at-30s", "CV-3", "STOPPED", null, NOW.minusSeconds(30)));
        log.append(event("before-1", "CV-3", "STOPPED", null, NOW.minus(Duration.ofMinutes(2))));

        assertThat(service.timeline("CV-3", 9999)).extracting(EquipmentEvent::eventId)
                .containsExactly("at-120", "before-1", "at-30s");
        assertThat(service.timeline("CV-3", 0)).extracting(EquipmentEvent::eventId).containsExactly("at-30s");
        assertThat(service.timeline("CV-3", -7)).extracting(EquipmentEvent::eventId).containsExactly("at-30s");
    }

    @Test
    void timeline_unknownId_isEmpty() {
        log.append(event("e-1", "AMR-FLEET", "STOPPED", null, NOW));

        assertThat(service.timeline("AMR-FLEET", 10)).isEmpty();
    }

    @Test
    void diagnose_usesWindowOf30Minutes() {
        log.append(event("old", "CH-03", "CHUTE_FULL", null, NOW.minus(Duration.ofMinutes(31))));
        log.append(event("recent", "SR-1", "LANE_BLOCKED", null, NOW.minus(Duration.ofMinutes(29))));
        log.append(event("stop", "CV-3", "STOPPED", null, NOW.minus(Duration.ofMinutes(28))));

        RootCauseCandidate candidate = service.diagnose("CV-3").orElseThrow();

        assertThat(candidate.equipmentId()).isEqualTo("SR-1");
        assertThat(candidate.evidence()).extracting(EquipmentEvent::eventId).containsExactly("recent", "stop");
    }

    @Test
    void diagnose_seesDownstreamEquipmentInsideWindow() {
        log.append(event("full", "CH-03", "CHUTE_FULL", null, NOW.minus(Duration.ofMinutes(29))));
        log.append(event("stop", "CV-3", "STOPPED", null, NOW.minus(Duration.ofMinutes(28))));

        assertThat(service.diagnose("CV-3").orElseThrow().equipmentId()).isEqualTo("CH-03");
    }

    @Test
    void diagnose_nothingInWindowOrUnknownId_isEmpty() {
        log.append(event("old", "CV-3", "STOPPED", null, NOW.minus(Duration.ofMinutes(31))));

        assertThat(service.diagnose("CV-3")).isEmpty();
        assertThat(service.diagnose("CV-9")).isEmpty();
    }
}
