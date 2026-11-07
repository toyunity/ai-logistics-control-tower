package com.example.controltower.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class LineTopologyTest {

    private static final LineTopology LINE = LineTopology.DEFAULT;
    private static final List<String> CHUTES = List.of("CH-01", "CH-02", "CH-03", "CH-04", "CH-05", "CH-06");
    private static final List<String> ROBOTS = List.of("AMR-01", "AMR-02", "AMR-03", "AMR-04");

    @Test
    void upstreamIsNearestFirst() {
        assertThat(LINE.upstreamOf("CV-1")).isEmpty();
        assertThat(LINE.upstreamOf("CV-3")).containsExactly("CV-2", "CV-1");
        assertThat(LINE.upstreamOf("CH-03")).containsExactly("SR-1", "SC-1", "CV-3", "CV-2", "CV-1");
    }

    @Test
    void downstreamIsNearestFirstAndEndsAtChutes() {
        assertThat(LINE.downstreamOf("CV-3"))
                .startsWith("SC-1", "SR-1")
                .containsSequence(CHUTES)
                .hasSize(2 + CHUTES.size());
        assertThat(LINE.downstreamOf("SR-1")).containsExactlyElementsOf(CHUTES);
    }

    @Test
    void chuteSiblingsAreNotRelated() {
        assertThat(LINE.upstreamOf("CH-03")).noneMatch(id -> id.startsWith("CH-"));
        assertThat(LINE.downstreamOf("CH-03")).noneMatch(id -> id.startsWith("CH-"));
    }

    @Test
    void robotsAreDownstreamOfChutesOnly() {
        assertThat(LINE.downstreamOf("CH-03")).containsExactlyElementsOf(ROBOTS);
        assertThat(LINE.upstreamOf("AMR-02")).containsExactlyElementsOf(CHUTES);
        assertThat(LINE.downstreamOf("AMR-02")).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
        "CV-1, Z1", "CV-2, Z1", "CV-3, Z1",
        "SC-1, Z2", "SR-1, Z2", "CH-01, Z2", "CH-06, Z2",
        "AMR-01, Z3", "AMR-04, Z3"
    })
    void knownEquipmentHasZone(String id, String zone) {
        assertThat(LINE.contains(id)).isTrue();
        assertThat(LINE.zoneOf(id)).contains(zone);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CV-4", "CH-07", "AMR-05", "cv-1", ""})
    void unknownEquipmentIsEmpty(String id) {
        assertUnknown(id);
    }

    @Test
    void nullIdIsUnknown() {
        assertUnknown(null);
    }

    private static void assertUnknown(String id) {
        assertThat(LINE.contains(id)).isFalse();
        assertThat(LINE.zoneOf(id)).isEmpty();
        assertThat(LINE.upstreamOf(id)).isEmpty();
        assertThat(LINE.downstreamOf(id)).isEmpty();
    }
}
