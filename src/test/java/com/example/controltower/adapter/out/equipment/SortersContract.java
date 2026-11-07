package com.example.controltower.adapter.out.equipment;

import static com.example.controltower.adapter.out.equipment.EquipmentStub.assertPlainGatewayFailure;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.controltower.application.port.out.Sorters;
import com.example.controltower.domain.ChuteStatus;
import com.example.controltower.domain.CommandOutcomeUnknownException;
import com.example.controltower.domain.RunState;
import com.example.controltower.domain.SorterStatus;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/** 구현은 정상 가동 중인 SR-1과 {@link #CH_03}, {@link #CH_04}, 빈 우회 설정으로 시작한다. */
abstract class SortersContract {

    static final ChuteStatus CH_03 = new ChuteStatus("CH-03", "A", RunState.RUNNING, 60, 20, 40, false);
    static final ChuteStatus CH_04 = new ChuteStatus("CH-04", "A", RunState.RUNNING, 30, 10, 40, false);

    abstract Sorters subject();

    abstract void goDown();

    abstract void loseSendResponses();

    abstract List<UUID> sentCommandIds();

    @Test
    void get_readsTheSorter() {
        assertThat(subject().get())
                .isEqualTo(new SorterStatus("SR-1", RunState.RUNNING, false, List.of(CH_03, CH_04), Map.of()));
    }

    @Test
    void divert_showsUpInTheNextRead() {
        subject().divert(UUID.randomUUID(), "CH-03", "CH-04");

        assertThat(subject().get().diversions()).isEqualTo(Map.of("CH-03", "CH-04"));
    }

    @Test
    void commandIdIsSentOnWire() {
        UUID commandId = UUID.randomUUID();

        subject().divert(commandId, "CH-03", "CH-04");

        assertThat(sentCommandIds()).containsExactly(commandId);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "CH-3", "CH-003", "CH-0a", "SR-1", "CH-03/x"})
    void chuteIdOutOfFormat_isAGatewayFailure_andNothingIsSent(String chute) {
        assertPlainGatewayFailure(() -> subject().divert(UUID.randomUUID(), chute, "CH-04"));
        assertPlainGatewayFailure(() -> subject().divert(UUID.randomUUID(), "CH-03", chute));

        assertThat(sentCommandIds()).isEmpty();
    }

    @Test
    void down_readsAndSendsAreGatewayFailures() {
        goDown();

        assertPlainGatewayFailure(() -> subject().get());
        assertPlainGatewayFailure(() -> subject().divert(UUID.randomUUID(), "CH-03", "CH-04"));
    }

    @Test
    void lostSendResponse_isOutcomeUnknown_thoughTheCommandApplied() {
        loseSendResponses();

        assertThatThrownBy(() -> subject().divert(UUID.randomUUID(), "CH-03", "CH-04"))
                .isExactlyInstanceOf(CommandOutcomeUnknownException.class);

        assertThat(subject().get().diversions()).containsEntry("CH-03", "CH-04");
    }
}
