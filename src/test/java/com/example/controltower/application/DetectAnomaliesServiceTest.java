package com.example.controltower.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.example.controltower.application.service.DetectAnomaliesService;
import com.example.controltower.domain.ChuteStatus;
import com.example.controltower.domain.ConveyorStatus;
import com.example.controltower.domain.EquipmentEvent;
import com.example.controltower.domain.FaultClass;
import com.example.controltower.domain.Incident;
import com.example.controltower.domain.RobotStatus;
import com.example.controltower.domain.RunState;
import com.example.controltower.domain.Severity;
import com.example.controltower.domain.SorterStatus;
import com.example.controltower.support.InMemoryConveyors;
import com.example.controltower.support.InMemoryEventLog;
import com.example.controltower.support.InMemoryIncidents;
import com.example.controltower.support.InMemoryRobotFleet;
import com.example.controltower.support.InMemorySorters;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class DetectAnomaliesServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-07T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final LocalTime FAR_CUTOFF = LocalTime.of(23, 0);

    private final InMemoryConveyors conveyors = new InMemoryConveyors(running("CV-1"), running("CV-3"));
    private final InMemorySorters sorters = new InMemorySorters(sorter(chute("CH-03", false, 0)));
    private final InMemoryRobotFleet fleet =
            new InMemoryRobotFleet(robot("AMR-01", 80), robot("AMR-02", 80), robot("AMR-03", 80));
    private final InMemoryEventLog log = new InMemoryEventLog();
    private final InMemoryIncidents incidents = new InMemoryIncidents();
    private final DetectAnomaliesService service = service(CLOCK, FAR_CUTOFF);

    private DetectAnomaliesService service(Clock clock, LocalTime cutoff) {
        return new DetectAnomaliesService(conveyors, sorters, fleet, log, incidents, clock, cutoff);
    }

    private static ConveyorStatus running(String id) {
        return conveyor(id, RunState.RUNNING);
    }

    private static ConveyorStatus conveyor(String id, RunState state) {
        return new ConveyorStatus(id, "Z1", state, FaultClass.NONE);
    }

    private static ChuteStatus chute(String id, boolean full, int rate) {
        return chute(id, RunState.RUNNING, full, rate);
    }

    private static ChuteStatus chute(String id, RunState state, boolean full, int rate) {
        return new ChuteStatus(id, "A", state, full ? 100 : 10, rate, 500, full);
    }

    private static SorterStatus sorter(ChuteStatus... chutes) {
        return new SorterStatus("SR-1", RunState.RUNNING, false, List.of(chutes), Map.of());
    }

    private static RobotStatus robot(String id, int battery) {
        return new RobotStatus(id, RunState.RUNNING, battery, 0, 5);
    }

    private void event(String id, String equipment, String code, Integer value, Duration ago) {
        log.append(new EquipmentEvent(id, equipment, code, value, NOW.minus(ago)));
    }

    private void stopCv3(Duration ago) {
        conveyors.set(conveyor("CV-3", RunState.STOPPED));
        event(UUID.randomUUID().toString(), "CV-3", "STOPPED", null, ago);
    }

    private Incident onlyOpen() {
        assertThat(incidents.open()).hasSize(1);
        return incidents.open().getFirst();
    }

    private void openAllFamilies() {
        conveyors.set(conveyor("CV-3", RunState.STOPPED));
        sorters.set(sorter(chute("CH-03", true, 0)));
        fleet.set(robot("AMR-01", 10));
        fleet.set(robot("AMR-02", 10));
        event("s-1", "SC-1", "READ_DEGRADED", 12, Duration.ofMinutes(1));
        service.detect();
        assertThat(incidents.open()).hasSize(4);
    }

    private void clearAllFamilies() {
        conveyors.set(running("CV-3"));
        sorters.set(sorter(chute("CH-03", false, 0)));
        fleet.set(robot("AMR-01", 80));
        fleet.set(robot("AMR-02", 80));
        event("s-2", "SC-1", "READ_NORMAL", 1, Duration.ZERO);
    }

    private List<String> openKinds() {
        return incidents.open().stream().map(Incident::kind).toList();
    }

    @Test
    void nothingAbnormal_opensNothing() {
        service.detect();

        assertThat(incidents.all()).isEmpty();
    }

    @Test
    void opensIncidentOnce() {
        conveyors.set(conveyor("CV-3", RunState.STOPPED));

        service.detect();
        service.detect();

        Incident incident = onlyOpen();
        assertThat(incidents.opens()).isEqualTo(1);
        assertThat(incident.equipmentId()).isEqualTo("CV-3");
        assertThat(incident.kind()).isEqualTo("CONVEYOR_STOPPED");
        assertThat(incident.openedAt()).isEqualTo(NOW);
        assertThat(incident.closedAt()).isNull();
    }

    @Test
    void conveyorFault_opensAndRoundTripsThroughClose() {
        conveyors.set(conveyor("CV-3", RunState.FAULT));
        service.detect();
        assertThat(onlyOpen().kind()).isEqualTo("CONVEYOR_STOPPED");

        conveyors.set(running("CV-3"));
        service.detect();
        assertThat(incidents.open()).isEmpty();

        conveyors.set(conveyor("CV-3", RunState.FAULT));
        service.detect();
        assertThat(incidents.all()).hasSize(2);
        assertThat(incidents.open()).hasSize(1);
    }

    @Test
    void closesWhenConditionClears() {
        openAllFamilies();

        clearAllFamilies();
        service.detect();

        assertThat(incidents.open()).isEmpty();
        assertThat(incidents.all()).hasSize(4).allSatisfy(i -> assertThat(i.closedAt()).isEqualTo(NOW));
    }

    @Test
    void chuteFull_opensOnChute() {
        sorters.set(sorter(chute("CH-03", true, 40)));

        service.detect();

        assertThat(onlyOpen().equipmentId()).isEqualTo("CH-03");
        assertThat(onlyOpen().kind()).isEqualTo("CHUTE_FULL");
        assertThat(onlyOpen().severity()).isEqualTo(Severity.SEV3);
    }

    @Test
    void chuteFull_latestCodeNotAStop_gradesWithZeroStopped() {
        sorters.set(sorter(chute("CH-03", true, 400)));
        event("c-1", "CH-03", "CHUTE_FULL", null, Duration.ofMinutes(30));

        service.detect();

        assertThat(onlyOpen().severity()).isEqualTo(Severity.SEV3);
    }

    @Test
    void chuteFull_alsoStopped_gradesFromItsStop() {
        sorters.set(sorter(chute("CH-03", RunState.STOPPED, true, 0)));
        event("c-1", "CH-03", "STOPPED", null, Duration.ofMinutes(30));

        service.detect();

        assertThat(onlyOpen().severity()).isEqualTo(Severity.SEV1);
    }

    @Test
    void scannerDegraded_opensOnScannerAndClosesOnNormal() {
        event("s-1", "SC-1", "READ_DEGRADED", 12, Duration.ofMinutes(30));

        service.detect();

        assertThat(onlyOpen().equipmentId()).isEqualTo("SC-1");
        assertThat(onlyOpen().kind()).isEqualTo("SCANNER_DEGRADED");
        assertThat(onlyOpen().severity()).isEqualTo(Severity.SEV3);

        event("s-2", "SC-1", "READ_NORMAL", 1, Duration.ZERO);
        service.detect();

        assertThat(incidents.open()).isEmpty();
    }

    @Test
    void scannerNoHistory_opensNothingAndClosesNothing() {
        incidents.open(new Incident(UUID.randomUUID(), "SC-1", "SCANNER_DEGRADED", Severity.SEV3, NOW, null));

        service.detect();

        assertThat(onlyOpen().kind()).isEqualTo("SCANNER_DEGRADED");
    }

    @Test
    void scannerUnknownCode_opensNothingAndClosesNothing() {
        openAllFamilies();
        event("s-x", "SC-1", "VENDOR_X", null, Duration.ZERO);

        clearAllFamiliesExceptScanner();
        service.detect();

        assertThat(openKinds()).containsExactly("SCANNER_DEGRADED");
    }

    private void clearAllFamiliesExceptScanner() {
        conveyors.set(running("CV-3"));
        sorters.set(sorter(chute("CH-03", false, 0)));
        fleet.set(robot("AMR-01", 80));
        fleet.set(robot("AMR-02", 80));
    }

    @Test
    void robotBattery_oneLowIsNotEnough() {
        fleet.set(robot("AMR-01", 10));
        fleet.set(robot("AMR-02", 30));

        service.detect();

        assertThat(incidents.all()).isEmpty();
    }

    @Test
    void robotBattery_twoLowOpenFleetIncident() {
        fleet.set(robot("AMR-01", 29));
        fleet.set(robot("AMR-02", 10));

        service.detect();

        assertThat(onlyOpen().equipmentId()).isEqualTo("AMR-FLEET");
        assertThat(onlyOpen().kind()).isEqualTo("ROBOT_BATTERY");
        assertThat(onlyOpen().severity()).isEqualTo(Severity.SEV3);
    }

    @Test
    void robotBattery_robotMissingFromRead_couldStillBeLowSoStaysOpen() {
        fleet.set(robot("AMR-01", 10));
        fleet.set(robot("AMR-02", 10));
        service.detect();

        fleet.set(robot("AMR-01", 80));
        service.detect();

        assertThat(onlyOpen().kind()).isEqualTo("ROBOT_BATTERY");
    }

    @Test
    void robotBattery_oneUnknownRobotCannotReachThreshold_closes() {
        fleet.set(robot("AMR-01", 10));
        fleet.set(robot("AMR-02", 10));
        fleet.set(robot("AMR-04", 80));
        service.detect();

        fleet.set(robot("AMR-01", 80));
        fleet.set(robot("AMR-02", 80));
        fleet.set(new RobotStatus("AMR-04", RunState.OFFLINE, 80, 0, 5));
        service.detect();

        assertThat(incidents.open()).isEmpty();
    }

    @Test
    void gatewayDown_doesNotCloseOrOpen() {
        openAllFamilies();

        conveyors.setFailing(true);
        sorters.setFailing(true);
        fleet.setFailing(true);
        log.setFailing(true);
        service.detect();

        assertThat(incidents.open()).hasSize(4);
    }

    @Test
    void gatewayDown_otherFamiliesStillClose() {
        openAllFamilies();
        clearAllFamilies();

        conveyors.setFailing(true);
        service.detect();

        assertThat(openKinds()).containsExactly("CONVEYOR_STOPPED");
    }

    @Test
    void sorterDown_otherFamiliesStillClose() {
        openAllFamilies();
        clearAllFamilies();

        sorters.setFailing(true);
        service.detect();

        assertThat(openKinds()).containsExactly("CHUTE_FULL");
    }

    @Test
    void robotsDown_otherFamiliesStillClose() {
        openAllFamilies();
        clearAllFamilies();

        fleet.setFailing(true);
        service.detect();

        assertThat(openKinds()).containsExactly("ROBOT_BATTERY");
    }

    @Test
    void eventLogDown_scannerFrozenAndStoppedConveyorFrozen_othersClose() {
        openAllFamilies();
        clearAllFamilies();
        conveyors.set(conveyor("CV-3", RunState.STOPPED));
        conveyors.set(conveyor("CV-1", RunState.STOPPED));

        log.setFailing(true);
        service.detect();

        assertThat(openKinds()).containsExactlyInAnyOrder("CONVEYOR_STOPPED", "SCANNER_DEGRADED");
    }

    @Test
    void gatewayDown_doesNotOpen() {
        conveyors.setFailing(true);
        sorters.setFailing(true);
        fleet.setFailing(true);

        service.detect();

        assertThat(incidents.all()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = RunState.class, names = {"UNKNOWN", "OFFLINE"})
    void equipmentStateUnknown_doesNotClose(RunState state) {
        openAllFamilies();
        clearAllFamilies();
        conveyors.set(conveyor("CV-3", state));
        sorters.set(sorter(chute("CH-03", state, false, 0)));
        fleet.set(robot("AMR-01", 80));
        fleet.set(new RobotStatus("AMR-02", state, 80, 0, 5));

        service.detect();

        assertThat(openKinds()).containsExactlyInAnyOrder("CONVEYOR_STOPPED", "CHUTE_FULL", "ROBOT_BATTERY");
    }

    @Test
    void equipmentMissingFromRead_doesNotClose() {
        incidents.open(new Incident(UUID.randomUUID(), "CV-2", "CONVEYOR_STOPPED", Severity.SEV3, NOW, null));

        service.detect();

        assertThat(onlyOpen().equipmentId()).isEqualTo("CV-2");
    }

    @Test
    void unknownStateRobotBattery_notCountedAsLow() {
        fleet.set(robot("AMR-01", 10));
        fleet.set(new RobotStatus("AMR-02", RunState.UNKNOWN, 0, 0, 5));

        service.detect();

        assertThat(incidents.all()).isEmpty();
    }

    @Test
    void regrades_openIncidentAsStopGrows() {
        conveyors.set(conveyor("CV-3", RunState.STOPPED));
        service.detect();
        UUID id = onlyOpen().id();
        assertThat(onlyOpen().severity()).isEqualTo(Severity.SEV3);

        event("c-1", "CV-3", "STOPPED", null, Duration.ofMinutes(4));
        service.detect();
        assertThat(onlyOpen().severity()).isEqualTo(Severity.SEV2);

        service(Clock.fixed(NOW.plus(Duration.ofMinutes(7)), ZoneOffset.UTC), FAR_CUTOFF).detect();
        assertThat(onlyOpen().severity()).isEqualTo(Severity.SEV1);
        assertThat(onlyOpen().id()).isEqualTo(id);
        assertThat(incidents.all()).hasSize(1);
    }

    @Test
    void stopped_latestEventNotAbnormal_countsAsZero() {
        conveyors.set(conveyor("CV-3", RunState.STOPPED));
        event("c-1", "CV-3", "STOPPED", null, Duration.ofMinutes(30));
        event("c-2", "CV-3", "RUNNING", null, Duration.ofMinutes(1));

        service.detect();

        assertThat(onlyOpen().severity()).isEqualTo(Severity.SEV3);
    }

    @Test
    void stopped_gatewayClockAhead_clampsAndDoesNotAbortCycle() {
        stopCv3(Duration.ofMinutes(-5));
        sorters.set(sorter(chute("CH-03", true, 40)));

        service.detect();

        assertThat(incidents.open())
                .extracting(Incident::kind, Incident::severity)
                .containsExactly(
                        tuple("CONVEYOR_STOPPED", Severity.SEV3),
                        tuple("CHUTE_FULL", Severity.SEV3));
    }

    @Test
    void parcels_useWholeStoppedMinutes() {
        sorters.set(sorter(chute("CH-01", false, 30), chute("CH-02", false, 10)));
        stopCv3(Duration.ofSeconds(150));

        service.detect();

        // 2분 30초를 그대로 곱하면 100건(SEV2)이지만 온전한 2분만 세면 80건이다.
        assertThat(onlyOpen().severity()).isEqualTo(Severity.SEV3);
    }

    @Test
    void parcels_sumAcrossChutes() {
        sorters.set(sorter(chute("CH-01", false, 30), chute("CH-02", false, 20)));
        stopCv3(Duration.ofMinutes(2));

        service.detect();

        assertThat(onlyOpen().severity()).isEqualTo(Severity.SEV2);
    }

    @Test
    void parcels_hugeStopDoesNotOverflow() {
        sorters.set(sorter(chute("CH-01", false, Integer.MAX_VALUE)));
        stopCv3(Duration.ofDays(3650));

        service.detect();

        assertThat(onlyOpen().severity()).isEqualTo(Severity.SEV1);
    }

    @Test
    void sorterDown_conveyorOpensWithZeroParcels() {
        sorters.set(sorter(chute("CH-01", false, 300)));
        stopCv3(Duration.ofMinutes(2));
        sorters.setFailing(true);

        service.detect();

        assertThat(onlyOpen().kind()).isEqualTo("CONVEYOR_STOPPED");
        assertThat(onlyOpen().severity()).isEqualTo(Severity.SEV3);

        sorters.setFailing(false);
        service.detect();

        assertThat(onlyOpen().severity()).isEqualTo(Severity.SEV1);
    }

    @Test
    void cutoff_nearAndStopped_isSev1() {
        stopCv3(Duration.ofMinutes(1));

        service(CLOCK, LocalTime.of(0, 30)).detect();

        assertThat(onlyOpen().severity()).isEqualTo(Severity.SEV1);
    }

    @Test
    void cutoff_alreadyPassedToday_isSev1NotTomorrow() {
        Instant lateNow = Instant.parse("2026-11-07T23:30:00Z");
        conveyors.set(conveyor("CV-3", RunState.STOPPED));
        log.append(new EquipmentEvent("c-1", "CV-3", "STOPPED", null, lateNow.minusSeconds(60)));

        service(Clock.fixed(lateNow, ZoneOffset.UTC), LocalTime.of(23, 0)).detect();

        assertThat(onlyOpen().severity()).isEqualTo(Severity.SEV1);
    }

    @Test
    void cutoff_usesClockZone() {
        stopCv3(Duration.ofMinutes(1));

        // NOW는 서울 시각 09:00이다. UTC로 계산하면 마감까지 9시간이 넘어 SEV3가 된다.
        service(Clock.fixed(NOW, ZoneId.of("Asia/Seoul")), LocalTime.of(9, 20)).detect();

        assertThat(onlyOpen().severity()).isEqualTo(Severity.SEV1);
    }

    @Test
    void cutoff_nearButNotStopped_staysSev3() {
        sorters.set(sorter(chute("CH-03", true, 40)));

        service(CLOCK, LocalTime.of(0, 10)).detect();

        assertThat(onlyOpen().severity()).isEqualTo(Severity.SEV3);
    }
}
