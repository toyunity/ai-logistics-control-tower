package com.example.controltower.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.controltower.application.port.out.PendingCommands;
import com.example.controltower.domain.CommandSpec;
import com.example.controltower.domain.CommandStatus;
import com.example.controltower.domain.PendingCommand;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

abstract class PendingCommandsContract {

    static final Instant T0 = Instant.parse("2026-11-07T00:00:00Z");
    static final Instant EXPIRES = T0.plusSeconds(120);
    static final CommandSpec RESTART = new CommandSpec.Restart("CV-3");

    abstract PendingCommands subject();

    abstract List<CommandStatus> auditedResults(UUID commandId);

    static PendingCommand card(String account, CommandSpec spec, Instant createdAt, Instant expiresAt) {
        return new PendingCommand(
                UUID.randomUUID(), account, spec, "digest", "영향 범위", CommandStatus.PENDING, createdAt, expiresAt);
    }

    static PendingCommand card(String account, CommandSpec spec) {
        return card(account, spec, T0, EXPIRES);
    }

    private PendingCommand saved(String account, CommandSpec spec) {
        PendingCommand c = card(account, spec);
        subject().save(c);
        return c;
    }

    private CommandStatus statusOf(PendingCommand c) {
        return subject().find(c.id()).orElseThrow().status();
    }

    private static Stream<CommandSpec> allKinds() {
        return Stream.of(
                new CommandSpec.Restart("CV-3"),
                new CommandSpec.Divert("CH-03", "CH-04"),
                new CommandSpec.Reassign("AMR-01", "AMR-02"),
                new CommandSpec.Maintenance("CV-3", "벨트 소음, \"점검\" 요청"));
    }

    @ParameterizedTest
    @MethodSource("allKinds")
    void specRoundTrip(CommandSpec spec) {
        PendingCommand c = saved("op-1", spec);

        assertThat(subject().find(c.id())).contains(c);
    }

    @Test
    void find_missingIsEmpty() {
        assertThat(subject().find(UUID.randomUUID())).isEmpty();
    }

    @Test
    void findLive_matchesAccountKindAndTargetOnly() {
        PendingCommand c = saved("op-1", RESTART);

        assertThat(subject().findLive("op-1", CommandSpec.Kind.RESTART, "CV-3", T0)).contains(c);
        assertThat(subject().findLive("op-2", CommandSpec.Kind.RESTART, "CV-3", T0)).isEmpty();
        assertThat(subject().findLive("op-1", CommandSpec.Kind.MAINTENANCE, "CV-3", T0)).isEmpty();
        assertThat(subject().findLive("op-1", CommandSpec.Kind.RESTART, "CV-2", T0)).isEmpty();
    }

    @Test
    void findLive_stopsAtExpiry() {
        saved("op-1", RESTART);

        assertThat(subject().findLive("op-1", CommandSpec.Kind.RESTART, "CV-3", EXPIRES.minusNanos(1000))).isPresent();
        assertThat(subject().findLive("op-1", CommandSpec.Kind.RESTART, "CV-3", EXPIRES)).isEmpty();
    }

    @Test
    void findLive_ignoresDecidedCards() {
        PendingCommand c = saved("op-1", RESTART);
        subject().transition(c.id(), CommandStatus.PENDING, CommandStatus.REJECTED, T0);

        assertThat(subject().findLive("op-1", CommandSpec.Kind.RESTART, "CV-3", T0)).isEmpty();
    }

    @Test
    void transition_movesOnlyFromTheGivenStatus() {
        PendingCommand c = saved("op-1", RESTART);

        assertThat(subject().transition(c.id(), CommandStatus.EXECUTING, CommandStatus.SUCCEEDED, T0)).isFalse();
        assertThat(statusOf(c)).isEqualTo(CommandStatus.PENDING);

        assertThat(subject().transition(c.id(), CommandStatus.PENDING, CommandStatus.REJECTED, T0)).isTrue();
        assertThat(statusOf(c)).isEqualTo(CommandStatus.REJECTED);
    }

    @Test
    void transition_unknownIdIsFalse() {
        assertThat(subject().transition(UUID.randomUUID(), CommandStatus.PENDING, CommandStatus.EXECUTING, T0))
                .isFalse();
    }

    @Test
    void expiredNotClaimed() {
        PendingCommand c = saved("op-1", RESTART);

        assertThat(subject().transition(c.id(), CommandStatus.PENDING, CommandStatus.EXECUTING, EXPIRES)).isFalse();
        assertThat(statusOf(c)).isEqualTo(CommandStatus.PENDING);
        assertThat(subject().transition(c.id(), CommandStatus.PENDING, CommandStatus.EXECUTING, EXPIRES.minusNanos(1000)))
                .isTrue();
    }

    @Test
    void expiryAppliesOnlyToPending_aResultIsRecordedAfterTheCardExpired() {
        PendingCommand c = saved("op-1", RESTART);
        subject().transition(c.id(), CommandStatus.PENDING, CommandStatus.EXECUTING, T0);

        assertThat(subject().transition(c.id(), CommandStatus.EXECUTING, CommandStatus.SUCCEEDED, EXPIRES.plusSeconds(60)))
                .isTrue();
    }

    @Test
    void oneExecutingPerTarget_evenAcrossAccountsAndKinds() {
        PendingCommand first = saved("op-1", RESTART);
        PendingCommand otherAccount = saved("op-2", RESTART);
        PendingCommand otherKind = saved("op-1", new CommandSpec.Maintenance("CV-3", "점검"));
        PendingCommand otherTarget = saved("op-1", new CommandSpec.Restart("CV-2"));

        assertThat(subject().transition(first.id(), CommandStatus.PENDING, CommandStatus.EXECUTING, T0)).isTrue();

        assertThat(subject().transition(otherAccount.id(), CommandStatus.PENDING, CommandStatus.EXECUTING, T0)).isFalse();
        assertThat(subject().transition(otherKind.id(), CommandStatus.PENDING, CommandStatus.EXECUTING, T0)).isFalse();
        assertThat(statusOf(otherAccount)).isEqualTo(CommandStatus.PENDING);
        assertThat(subject().transition(otherTarget.id(), CommandStatus.PENDING, CommandStatus.EXECUTING, T0)).isTrue();

        subject().transition(first.id(), CommandStatus.EXECUTING, CommandStatus.SUCCEEDED, T0);

        assertThat(subject().transition(otherAccount.id(), CommandStatus.PENDING, CommandStatus.EXECUTING, T0)).isTrue();
    }

    @Test
    void expiredCardDoesNotBlockANewProposal() {
        PendingCommand old = saved("op-1", RESTART);
        PendingCommand fresh = card("op-1", RESTART, EXPIRES, EXPIRES.plusSeconds(120));

        subject().save(fresh);

        assertThat(statusOf(old)).isEqualTo(CommandStatus.EXPIRED);
        assertThat(subject().findLive("op-1", CommandSpec.Kind.RESTART, "CV-3", EXPIRES)).contains(fresh);
    }

    @Test
    void save_leavesAnotherKeysExpiredCardAlone() {
        PendingCommand other = saved("op-2", RESTART);

        subject().save(card("op-1", RESTART, EXPIRES, EXPIRES.plusSeconds(120)));

        assertThat(statusOf(other)).isEqualTo(CommandStatus.PENDING);
    }

    @Test
    void save_liveCardOfTheSameKeyIsRefused() {
        PendingCommand live = saved("op-1", RESTART);
        PendingCommand second = card("op-1", RESTART);

        assertThatThrownBy(() -> subject().save(second)).isInstanceOf(RuntimeException.class);

        assertThat(statusOf(live)).isEqualTo(CommandStatus.PENDING);
        assertThat(subject().find(second.id())).isEmpty();
    }

    @Test
    void superseding_freesTheKey() {
        PendingCommand old = saved("op-1", RESTART);
        subject().transition(old.id(), CommandStatus.PENDING, CommandStatus.SUPERSEDED, T0);

        PendingCommand fresh = saved("op-1", RESTART);

        assertThat(statusOf(old)).isEqualTo(CommandStatus.SUPERSEDED);
        assertThat(statusOf(fresh)).isEqualTo(CommandStatus.PENDING);
    }

    @Test
    void markStale_closesEveryExecutingRowWithAnAuditEntry() {
        PendingCommand a = saved("op-1", RESTART);
        PendingCommand b = saved("op-1", new CommandSpec.Divert("CH-03", "CH-04"));
        PendingCommand waiting = saved("op-1", new CommandSpec.Restart("CV-2"));
        PendingCommand done = saved("op-1", new CommandSpec.Restart("CV-1"));
        subject().transition(a.id(), CommandStatus.PENDING, CommandStatus.EXECUTING, T0);
        subject().transition(b.id(), CommandStatus.PENDING, CommandStatus.EXECUTING, T0);
        subject().transition(done.id(), CommandStatus.PENDING, CommandStatus.REJECTED, T0);

        subject().markStaleExecutingUnconfirmed();

        assertThat(statusOf(a)).isEqualTo(CommandStatus.UNCONFIRMED);
        assertThat(statusOf(b)).isEqualTo(CommandStatus.UNCONFIRMED);
        assertThat(statusOf(waiting)).isEqualTo(CommandStatus.PENDING);
        assertThat(statusOf(done)).isEqualTo(CommandStatus.REJECTED);
        assertThat(auditedResults(a.id())).containsExactly(CommandStatus.UNCONFIRMED);
        assertThat(auditedResults(b.id())).containsExactly(CommandStatus.UNCONFIRMED);
        assertThat(auditedResults(waiting.id())).isEmpty();
    }

    @Test
    void markStale_secondRunAddsNoAudit() {
        PendingCommand c = saved("op-1", RESTART);
        subject().transition(c.id(), CommandStatus.PENDING, CommandStatus.EXECUTING, T0);

        subject().markStaleExecutingUnconfirmed();
        subject().markStaleExecutingUnconfirmed();

        assertThat(auditedResults(c.id())).hasSize(1);
    }
}
