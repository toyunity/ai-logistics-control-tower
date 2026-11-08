package com.example.controltower.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.controltower.domain.ChuteStatus;
import com.example.controltower.domain.CommandSpec;
import com.example.controltower.domain.RunState;
import com.example.controltower.domain.SorterStatus;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StateDigestTest {

    private static final CommandSpec.Divert DIVERT = new CommandSpec.Divert("CH-03", "CH-04");

    private static SorterStatus sorter(boolean blocked, boolean fromFull, boolean toFull) {
        return new SorterStatus("SR-1", RunState.RUNNING, blocked, List.of(
                        new ChuteStatus("CH-03", "A", RunState.RUNNING, fromFull ? 100 : 40, 30, 60, fromFull),
                        new ChuteStatus("CH-04", "A", RunState.RUNNING, toFull ? 100 : 20, 20, 60, toFull)),
                Map.of());
    }

    // 막힘과 만재는 RUNNING 상태 그대로 보고된다. 요약에 없으면 막힘 중에 만든 우회 카드가 리셋 뒤에도 그대로 실행된다.
    @Test
    void divert_blockedOrFullChange_changesDigest() {
        String jammed = StateDigest.of(DIVERT, sorter(true, true, false));

        assertThat(StateDigest.of(DIVERT, sorter(false, true, false))).isNotEqualTo(jammed);
        assertThat(StateDigest.of(DIVERT, sorter(true, false, false))).isNotEqualTo(jammed);
        assertThat(StateDigest.of(DIVERT, sorter(true, true, true))).isNotEqualTo(jammed);
    }

    @Test
    void divert_numbersAlone_doNotChangeDigest() {
        SorterStatus a = sorter(true, true, false);
        SorterStatus b = new SorterStatus("SR-1", RunState.RUNNING, true, List.of(
                        new ChuteStatus("CH-03", "A", RunState.RUNNING, 99, 35, 60, true),
                        new ChuteStatus("CH-04", "A", RunState.RUNNING, 25, 22, 60, false)),
                Map.of());

        assertThat(StateDigest.of(DIVERT, b)).isEqualTo(StateDigest.of(DIVERT, a));
    }
}
