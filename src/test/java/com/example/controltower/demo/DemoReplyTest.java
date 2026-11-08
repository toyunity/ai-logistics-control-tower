package com.example.controltower.demo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DemoReplyTest {

    private static final String PREFIX = "[데모 응답 · 실제 AI 아님] ";

    @Test
    void diagnosisNamesCandidateAsCandidate() {
        String reply = DemoReply.render("diagnose",
                "{\"status\":\"OK\",\"data\":{\"candidateId\":\"CH-03\",\"evidence\":[]}}");

        assertThat(reply).startsWith(PREFIX).contains("CH-03").contains("후보");
    }

    @Test
    void deniedProposalPassesFixedReason() {
        String reply = DemoReply.render("proposeRestart",
                "{\"status\":\"DENIED\",\"code\":\"SORTER_BLOCKED\",\"message\":\"분류기가 막혀 있습니다.\"}");

        assertThat(reply).startsWith(PREFIX).contains("분류기가 막혀 있습니다.");
    }

    @Test
    void proposalPointsToCardAndSaysNotExecuted() {
        String reply = DemoReply.render("proposeDivert",
                "{\"status\":\"OK\",\"data\":{\"kind\":\"DIVERT\",\"targetId\":\"CH-03\"}}");

        assertThat(reply).startsWith(PREFIX).contains("카드").contains("실행되지 않았");
    }

    @Test
    void plantStatusListsAbnormalEquipment() {
        String reply = DemoReply.render("getPlantStatus", """
                {"status":"OK","data":{"equipment":[{"id":"CV-1","state":"RUNNING"},{"id":"CV-3","state":"STOPPED"}],
                "incidents":[{"id":"x"}],"maintenance":[]}}""");

        assertThat(reply).startsWith(PREFIX).contains("CV-3").doesNotContain("CV-1").contains("1건");
    }

    @Test
    void malformedResultGivesFixedTextWithPrefix() {
        assertThat(DemoReply.render("diagnose", "not json")).startsWith(PREFIX);
    }
}
