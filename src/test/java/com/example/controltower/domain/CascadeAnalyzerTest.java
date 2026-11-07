package com.example.controltower.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CascadeAnalyzerTest {

    private static final EquipmentEvent CHUTE_FULL = event("e1", "CH-03", "CHUTE_FULL", "10:00:00");
    private static final EquipmentEvent LANE_BLOCKED = event("e2", "SR-1", "LANE_BLOCKED", "10:00:05");
    private static final EquipmentEvent CV3_STOPPED = event("e3", "CV-3", "STOPPED", "10:00:09");

    private static EquipmentEvent event(String eventId, String equipmentId, String code, String time) {
        return new EquipmentEvent(eventId, equipmentId, code, null, Instant.parse("2026-11-07T" + time + "Z"));
    }

    @Test
    void chuteJamTracesToChute() {
        var candidate = CascadeAnalyzer.analyze("CV-3", List.of(CV3_STOPPED, CHUTE_FULL, LANE_BLOCKED));

        assertThat(candidate).hasValueSatisfying(c -> {
            assertThat(c.equipmentId()).isEqualTo("CH-03");
            assertThat(c.evidence()).containsExactly(CHUTE_FULL, LANE_BLOCKED, CV3_STOPPED);
        });
    }

    @Test
    void ignoresEventsOutsideCausalPath() {
        // 모두 더 이르고 이상 코드라서, 경로 필터가 없으면 후보를 가로챈다.
        var robot = event("e4", "AMR-02", "BATTERY_LOW", "09:00:00");
        var upstream = event("e5", "CV-1", "STOPPED", "09:00:01");

        var candidate = CascadeAnalyzer.analyze("CV-3", List.of(robot, upstream, CHUTE_FULL, CV3_STOPPED));

        assertThat(candidate).hasValueSatisfying(c -> {
            assertThat(c.equipmentId()).isEqualTo("CH-03");
            assertThat(c.evidence()).containsExactly(CHUTE_FULL, CV3_STOPPED);
        });
    }

    @Test
    void chuteAnalysisIgnoresSiblingChutes() {
        var sibling = event("e4", "CH-01", "CHUTE_FULL", "09:00:00");

        var candidate = CascadeAnalyzer.analyze("CH-03", List.of(sibling, CHUTE_FULL));

        assertThat(candidate).hasValueSatisfying(c -> {
            assertThat(c.equipmentId()).isEqualTo("CH-03");
            assertThat(c.evidence()).containsExactly(CHUTE_FULL);
        });
    }

    @Test
    void chuteAnalysisCanTraceToRobot() {
        var robot = event("e4", "AMR-02", "BATTERY_LOW", "09:00:00");

        var candidate = CascadeAnalyzer.analyze("CH-03", List.of(CHUTE_FULL, robot));

        assertThat(candidate.map(RootCauseCandidate::equipmentId)).contains("AMR-02");
    }

    @Test
    void conveyorBackedUpFromFarUpstreamStillTracesToChute() {
        var candidate = CascadeAnalyzer.analyze("CV-1", List.of(CV3_STOPPED, CHUTE_FULL));

        assertThat(candidate.map(RootCauseCandidate::equipmentId)).contains("CH-03");
    }

    @Test
    void timestampTieBreaksTowardMostDownstream() {
        var blockedSameTime = event("e2", "SR-1", "LANE_BLOCKED", "10:00:00");
        var stoppedSameTime = event("e3", "CV-3", "STOPPED", "10:00:00");

        var candidate = CascadeAnalyzer.analyze("CV-3", List.of(stoppedSameTime, blockedSameTime, CHUTE_FULL));

        assertThat(candidate).hasValueSatisfying(c -> {
            assertThat(c.equipmentId()).isEqualTo("CH-03");
            assertThat(c.evidence()).containsExactly(CHUTE_FULL, blockedSameTime, stoppedSameTime);
        });
    }

    @Test
    void everyAbnormalCodeCounts() {
        for (var code : List.of("CHUTE_FULL", "LANE_BLOCKED", "STOPPED", "FAULT", "READ_DEGRADED", "BATTERY_LOW")) {
            var candidate = CascadeAnalyzer.analyze("SC-1", List.of(event("e1", "SC-1", code, "10:00:00")));

            assertThat(candidate.map(RootCauseCandidate::equipmentId)).as(code).contains("SC-1");
        }
    }

    @Test
    void noAbnormalEvents_empty() {
        var normal = List.of(event("e1", "SC-1", "READ_NORMAL", "10:00:00"), event("e2", "CV-3", "RESTARTED", "10:00:01"));

        assertThat(CascadeAnalyzer.analyze("CV-3", normal)).isEmpty();
        assertThat(CascadeAnalyzer.analyze("CV-3", List.of())).isEmpty();
    }

    private static void assertChuteJamWithExtra(EquipmentEvent... extra) {
        var events = new ArrayList<>(List.of(CHUTE_FULL, LANE_BLOCKED, CV3_STOPPED));
        events.addAll(List.of(extra));

        assertThat(CascadeAnalyzer.analyze("CV-3", events)).hasValueSatisfying(c -> {
            assertThat(c.equipmentId()).isEqualTo("CH-03");
            assertThat(c.evidence()).containsExactly(CHUTE_FULL, LANE_BLOCKED, CV3_STOPPED);
        });
    }

    @Test
    void recoveredEventIsNotRoot_scanner() {
        assertChuteJamWithExtra(
                event("e4", "SC-1", "READ_DEGRADED", "09:45:00"), event("e5", "SC-1", "READ_NORMAL", "09:50:00"));
    }

    @Test
    void recoveredEventIsNotRoot_targetItself() {
        assertChuteJamWithExtra(
                event("e4", "CV-3", "STOPPED", "09:40:00"), event("e5", "CV-3", "RESTARTED", "09:41:00"));
    }

    @Test
    void recoveredEventIsNotRoot_otherChute() {
        assertChuteJamWithExtra(
                event("e4", "CH-05", "CHUTE_FULL", "09:50:00"), event("e5", "CH-05", "CHUTE_CLEARED", "09:52:00"));
    }

    @Test
    void recoveryOnlyDropsEventsBeforeIt() {
        assertChuteJamWithExtra(
                event("e4", "CH-03", "CHUTE_FULL", "09:00:00"), event("e5", "CH-03", "CHUTE_CLEARED", "09:01:00"));
    }

    @Test
    void chuteClearsAfterConveyorStopped_stillChute() {
        assertChuteJamWithExtra(event("e4", "CH-03", "CHUTE_CLEARED", "10:00:10"));
    }

    @Test
    void recoveryAtAnchorTimeCounts() {
        var cleared = event("e4", "CH-03", "CHUTE_CLEARED", "10:00:09");

        var candidate = CascadeAnalyzer.analyze("CV-3", List.of(CHUTE_FULL, LANE_BLOCKED, CV3_STOPPED, cleared));

        assertThat(candidate.map(RootCauseCandidate::equipmentId)).contains("SR-1");
    }

    @Test
    void eventAfterAnchorNotEvidence() {
        assertChuteJamWithExtra(event("e4", "CH-02", "CHUTE_FULL", "10:00:10"));
    }

    @Test
    void targetWithoutAbnormalEvent_anchorsOnLatestOnPath() {
        var cleared = event("e4", "CH-03", "CHUTE_CLEARED", "10:00:02");

        var candidate = CascadeAnalyzer.analyze("CV-1", List.of(CHUTE_FULL, cleared, CV3_STOPPED));

        assertThat(candidate).hasValueSatisfying(c -> {
            assertThat(c.equipmentId()).isEqualTo("CV-3");
            assertThat(c.evidence()).containsExactly(CV3_STOPPED);
        });
    }

    @Test
    void normalEventsAreNotEvidence() {
        var normal = event("e4", "SC-1", "READ_NORMAL", "09:00:00");

        var candidate = CascadeAnalyzer.analyze("CV-3", List.of(normal, CV3_STOPPED));

        assertThat(candidate).hasValueSatisfying(c -> assertThat(c.evidence()).containsExactly(CV3_STOPPED));
    }

    @Test
    void unknownEquipment_empty() {
        var unknownTarget = event("e4", "CV-9", "STOPPED", "10:00:00");

        assertThat(CascadeAnalyzer.analyze("CV-9", List.of(unknownTarget, CHUTE_FULL))).isEmpty();
        assertThat(CascadeAnalyzer.analyze(null, List.of(CHUTE_FULL))).isEmpty();
    }

    @Test
    void eventRequiresAllFieldsExceptValue() {
        var at = Instant.parse("2026-11-07T10:00:00Z");

        assertThatNullPointerException().isThrownBy(() -> new EquipmentEvent(null, "CV-3", "STOPPED", 1, at));
        assertThatNullPointerException().isThrownBy(() -> new EquipmentEvent("e", null, "STOPPED", 1, at));
        assertThatNullPointerException().isThrownBy(() -> new EquipmentEvent("e", "CV-3", null, 1, at));
        assertThatNullPointerException().isThrownBy(() -> new EquipmentEvent("e", "CV-3", "STOPPED", 1, null));
        assertThatCode(() -> new EquipmentEvent("e", "CV-3", "STOPPED", null, at)).doesNotThrowAnyException();
    }
}
